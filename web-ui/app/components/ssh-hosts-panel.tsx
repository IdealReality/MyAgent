import * as React from "react";

import { toast } from "sonner";
import { useTranslation } from "react-i18next";
import {
  Check,
  CircleAlert,
  KeyRound,
  Loader2,
  Pencil,
  Play,
  Plus,
  RefreshCw,
  Server,
  Trash2,
  X,
} from "lucide-react";

import { Badge } from "~/components/ui/badge";
import { Button } from "~/components/ui/button";
import { Empty, EmptyDescription, EmptyHeader, EmptyTitle } from "~/components/ui/empty";
import { Input } from "~/components/ui/input";
import { ScrollArea } from "~/components/ui/scroll-area";
import { Separator } from "~/components/ui/separator";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "~/components/ui/dialog";
import { cn } from "~/lib/utils";
import {
  acquireSshHostSocket,
  releaseSshHostSocket,
  SshHostRequestError,
  type SshAuthMethod,
  type SshHostDto,
  type SshHostTestResult,
  type SshHostUpsert,
  type SshHostSocket,
  type SshKnownHostEntry,
  type SshSocketStatus,
} from "~/services/ssh-hosts";

/**
 * Acquires the shared socket for the lifetime of the calling component.
 *
 * Not `useMemo(() => acquire())`: React is free to re-run a `useMemo` factory, and a second
 * acquire without a matching release would pin the connection open for the whole page life.
 * The ref keeps acquisition to exactly one per mount, paired with one release.
 */
function useSshHostSocket(): SshHostSocket {
  const [socket] = React.useState(() => acquireSshHostSocket());
  React.useEffect(() => {
    return () => {
      releaseSshHostSocket();
    };
  }, []);
  return socket;
}

/**
 * A server failure carries a machine code; the UI prefers its own translation of that code and
 * only falls back to the server's English detail for codes this build does not know.
 */
interface DisplayError {
  code: string | null;
  text: string;
}

function toDisplayError(error: unknown): DisplayError {
  if (error instanceof SshHostRequestError) {
    return { code: error.errorCode, text: error.message };
  }
  return { code: null, text: error instanceof Error ? error.message : String(error) };
}

/**
 * SSH host manager backed by `/api/ssh/hosts/ws`.
 *
 * Two rules the layout encodes, both learned from the native settings page:
 *  - Secrets are write-only. Stored rows expose `hasPassword` / `hasPrivateKey` booleans, so
 *    the editor shows a "leave blank to keep" placeholder instead of a value it never had.
 *  - A failed `save` is field-scoped (`errors` map), so the editor keeps the draft open and
 *    highlights the offending inputs rather than closing and losing what was typed.
 */
export function SshHostsPanel({ className }: { className?: string }) {
  const { t } = useTranslation("page");
  const socket = useSshHostSocket();

  const [status, setStatus] = React.useState<SshSocketStatus>(socket.getStatus());
  const statusLabel =
    status === "open"
      ? t("ssh_hosts.status_connected")
      : status === "connecting"
        ? t("ssh_hosts.status_connecting")
        : t("ssh_hosts.status_offline");
  const [hosts, setHosts] = React.useState<SshHostDto[]>([]);
  const [knownHosts, setKnownHosts] = React.useState<SshKnownHostEntry[]>([]);
  const [loading, setLoading] = React.useState(true);
  const [error, setError] = React.useState<DisplayError | null>(null);
  const [editing, setEditing] = React.useState<SshHostDto | null>(null);
  const [creating, setCreating] = React.useState(false);
  const [pendingDelete, setPendingDelete] = React.useState<SshHostDto | null>(null);
  // Forgetting a key is destructive for the *next* connection (a changed key stops connecting
  // until it is trusted again), so it gets the same confirmation the native page has.
  const [pendingForget, setPendingForget] = React.useState<SshKnownHostEntry | null>(null);
  // A set, not a single name: the server now answers tests concurrently, so two rows may spin
  // at once and each must clear its own indicator.
  const [testingNames, setTestingNames] = React.useState<ReadonlySet<string>>(() => new Set());
  const [testResults, setTestResults] = React.useState<Record<string, SshHostTestResult>>({});

  /** Prefers the localised code; falls back to the server's detail for unknown codes. */
  const localize = React.useCallback(
    (code: string | null, fallback: string): string => {
      if (!code) return fallback;
      const key = `ssh_hosts.errors.${code}`;
      const translated = t(key, { defaultValue: fallback });
      return translated === key ? fallback : translated;
    },
    [t],
  );

  /** Turns a caught failure into text a user can act on. */
  const describe = React.useCallback(
    (failure: unknown): string => {
      const display = toDisplayError(failure);
      return localize(display.code, display.text);
    },
    [localize],
  );

  // Broadcast refreshes, manual refreshes and the reconnect refresh can overlap; only the
  // newest response may write state, or a slow `list` would resurrect a deleted host.
  const refreshSeq = React.useRef(0);
  const refresh = React.useCallback(async () => {
    const seq = ++refreshSeq.current;
    try {
      const [nextHosts, nextKnown] = await Promise.all([socket.list(), socket.knownHosts()]);
      if (seq !== refreshSeq.current) return;
      setHosts(nextHosts);
      setKnownHosts(nextKnown);
      setError(null);
    } catch (err) {
      if (seq !== refreshSeq.current) return;
      setError(toDisplayError(err));
    } finally {
      if (seq === refreshSeq.current) setLoading(false);
    }
  }, [socket]);

  React.useEffect(() => {
    const unsubscribeStatus = socket.onStatusChange(setStatus);
    const unsubscribeHosts = socket.onHostsChanged(() => {
      void refresh();
    });
    const unsubscribeKnownHosts = socket.onKnownHostsChanged(() => {
      void refresh();
    });
    return () => {
      unsubscribeStatus();
      unsubscribeHosts();
      unsubscribeKnownHosts();
    };
  }, [socket, refresh]);

  // One refresh trigger: the socket opened (initial mount lands here too, because
  // `getStatus()` already reports `open` for the shared connection). A request issued while
  // it is still reconnecting would reject and flash an error the user cannot act on.
  React.useEffect(() => {
    if (status === "open") {
      void refresh();
    }
  }, [status, refresh]);

  const handleTest = React.useCallback(
    async (name: string) => {
      setTestingNames((current) => new Set(current).add(name));
      try {
        const result = await socket.test(name);
        setTestResults((current) => ({ ...current, [name]: result }));
      } catch (err) {
        const display = toDisplayError(err);
        setTestResults((current) => ({
          ...current,
          [name]: { ok: false, errorCode: display.code ?? display.text },
        }));
      } finally {
        setTestingNames((current) => {
          const next = new Set(current);
          next.delete(name);
          return next;
        });
      }
    },
    [socket],
  );

  const handleDelete = React.useCallback(
    async (name: string) => {
      try {
        const frame = await socket.deleteHost(name);
        if (!frame.ok) {
          toast.error(
            describe(new SshHostRequestError(frame.error ?? "delete_failed", frame.message)),
          );
          return;
        }
        toast.success(t("ssh_hosts.deleted", { name }));
        setTestResults((current) => {
          const next = { ...current };
          delete next[name];
          return next;
        });
        await refresh();
      } catch (err) {
        toast.error(describe(err));
      }
    },
    [socket, refresh, t, describe],
  );

  const handleForgetKey = React.useCallback(
    async (pattern: string) => {
      try {
        const frame = await socket.forgetHostKey(pattern);
        if (!frame.ok) {
          toast.error(
            describe(new SshHostRequestError(frame.error ?? "forget_failed", frame.message)),
          );
          return;
        }
        toast.success(t("ssh_hosts.forgotten", { pattern }));
        await refresh();
      } catch (err) {
        toast.error(describe(err));
      }
    },
    [socket, refresh, t, describe],
  );

  return (
    <div className={cn("flex min-h-0 flex-1 flex-col", className)}>
      <div className="flex items-center gap-2 border-b px-4 py-3">
        <Server className="size-4 shrink-0 text-muted-foreground" />
        {/* The title block must be able to shrink to nothing: with a fixed basis the status
            chip and the add button got squeezed onto two lines at 640px. */}
        <div className="min-w-0 flex-1 basis-0">
          <div className="truncate text-sm font-medium">{t("ssh_hosts.title")}</div>
          <div className="truncate text-xs text-muted-foreground">{t("ssh_hosts.subtitle")}</div>
        </div>
        <Badge
          variant={status === "open" ? "secondary" : "outline"}
          className="shrink-0 whitespace-nowrap"
          title={statusLabel}
        >
          {statusLabel}
        </Badge>
        <Button
          variant="outline"
          size="icon-sm"
          type="button"
          aria-label={t("ssh_hosts.refresh")}
          title={t("ssh_hosts.refresh")}
          onClick={() => void refresh()}
          disabled={status !== "open"}
        >
          <RefreshCw className="size-4" />
        </Button>
        <Button
          size="sm"
          type="button"
          className="shrink-0 whitespace-nowrap"
          onClick={() => setCreating(true)}
          disabled={status !== "open"}
        >
          <Plus className="size-4" />
          {t("ssh_hosts.add")}
        </Button>
      </div>

      <ScrollArea className="min-h-0 flex-1">
        <div className="space-y-6 p-4">
          {error ? (
            <div className="flex items-start gap-2 rounded-lg border border-destructive/40 bg-destructive/5 p-3 text-sm text-destructive">
              <CircleAlert className="mt-0.5 size-4 shrink-0" />
              <span className="min-w-0 break-words">{localize(error.code, error.text)}</span>
            </div>
          ) : null}

          {loading && status !== "closed" ? (
            <div className="flex items-center justify-center gap-2 py-12 text-sm text-muted-foreground">
              <Loader2 className="size-4 animate-spin" />
              {t("ssh_hosts.loading")}
            </div>
          ) : null}

          {/* A socket that never opened leaves `loading` true forever; say so instead of
              showing a spinner the user cannot act on. After the automatic retries stop,
              reconnecting is a decision only the user can make. */}
          {status === "closed" ? (
            <div className="flex flex-col items-center justify-center gap-3 py-12 text-sm text-muted-foreground">
              <CircleAlert className="size-4" />
              <span>{t("ssh_hosts.status_offline")}</span>
              <Button size="sm" variant="outline" type="button" onClick={() => socket.retry()}>
                <RefreshCw className="size-4" />
                {t("ssh_hosts.reconnect")}
              </Button>
            </div>
          ) : null}

          {!loading && hosts.length === 0 && !error ? (
            <Empty className="border">
              <EmptyHeader>
                <EmptyTitle>{t("ssh_hosts.empty_title")}</EmptyTitle>
                <EmptyDescription>{t("ssh_hosts.empty_description")}</EmptyDescription>
              </EmptyHeader>
              <Button size="sm" type="button" onClick={() => setCreating(true)}>
                <Plus className="size-4" />
                {t("ssh_hosts.add")}
              </Button>
            </Empty>
          ) : null}

          {hosts.length > 0 ? (
            <div className="space-y-2">
              {hosts.map((host) => {
                const result = testResults[host.name];
                return (
                  <div
                    key={host.name}
                    className="rounded-lg border bg-card p-3 transition-colors"
                    data-testid="ssh-host-row"
                  >
                    <div className="flex items-start gap-3">
                      <div className="min-w-0 flex-1">
                        <div className="flex items-center gap-2">
                          <span className="truncate font-medium">{host.name}</span>
                          <Badge variant="outline" className="shrink-0">
                            {host.authMethod === "private_key"
                              ? t("ssh_hosts.auth_key")
                              : t("ssh_hosts.auth_password")}
                          </Badge>
                        </div>
                        <div className="truncate font-mono text-xs text-muted-foreground">
                          {`${host.user}@${host.host}:${host.port}`}
                        </div>
                        {result ? (
                          <div
                            className={cn(
                              "mt-1 text-xs",
                              result.ok ? "text-primary" : "text-destructive",
                            )}
                          >
                            <div>
                              {result.ok
                                ? t("ssh_hosts.test_ok")
                                : t("ssh_hosts.test_failed", {
                                    // Codes like tcp_unreachable / auth_failed are translated;
                                    // an unknown one falls back to itself.
                                    reason: localize(
                                      result.errorCode ?? null,
                                      result.errorCode ?? "error",
                                    ),
                                  })}
                            </div>
                            {!result.ok && result.detail ? (
                              <div className="break-words text-muted-foreground">
                                {result.detail}
                              </div>
                            ) : null}
                          </div>
                        ) : null}
                      </div>
                      <div className="flex shrink-0 items-center gap-1">
                        {testingNames.has(host.name) ? (
                          <Loader2 className="mx-2 size-4 animate-spin text-muted-foreground" />
                        ) : (
                          <Button
                            variant="ghost"
                            size="icon-sm"
                            type="button"
                            aria-label={t("ssh_hosts.test")}
                            title={t("ssh_hosts.test")}
                            onClick={() => void handleTest(host.name)}
                          >
                            <Play className="size-4" />
                          </Button>
                        )}
                        <Button
                          variant="ghost"
                          size="icon-sm"
                          type="button"
                          aria-label={t("ssh_hosts.edit")}
                          title={t("ssh_hosts.edit")}
                          onClick={() => setEditing(host)}
                        >
                          <Pencil className="size-4" />
                        </Button>
                        <Button
                          variant="ghost"
                          size="icon-sm"
                          type="button"
                          aria-label={t("ssh_hosts.delete")}
                          title={t("ssh_hosts.delete")}
                          className="text-destructive"
                          onClick={() => setPendingDelete(host)}
                        >
                          <Trash2 className="size-4" />
                        </Button>
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>
          ) : null}

          <div>
            <div className="mb-2 flex items-center gap-2 text-sm font-medium">
              <KeyRound className="size-4 text-muted-foreground" />
              {t("ssh_hosts.known_hosts_title")}
            </div>
            {knownHosts.length === 0 ? (
              <p className="text-xs text-muted-foreground">{t("ssh_hosts.known_hosts_empty")}</p>
            ) : (
              <div className="divide-y rounded-lg border">
                {knownHosts.map((entry) => (
                  <div
                    key={`${entry.pattern}:${entry.keyType}`}
                    className="flex items-center gap-2 px-3 py-2"
                  >
                    <div className="min-w-0 flex-1">
                      <div className="truncate font-mono text-xs">{entry.pattern}</div>
                      <div className="truncate text-xs text-muted-foreground">{entry.keyType}</div>
                    </div>
                    {/* Hashed entries start with `|` and cannot be mapped back to a hostname,
                        so the server would find nothing to remove. */}
                    {entry.pattern.startsWith("|") ? null : (
                      <Button
                        variant="ghost"
                        size="icon-sm"
                        type="button"
                        aria-label={t("ssh_hosts.forget_key")}
                        title={t("ssh_hosts.forget_key")}
                        className="text-destructive"
                        onClick={() => setPendingForget(entry)}
                      >
                        <X className="size-4" />
                      </Button>
                    )}
                  </div>
                ))}
              </div>
            )}
            <p className="mt-2 text-xs text-muted-foreground">{t("ssh_hosts.known_hosts_note")}</p>
          </div>

          <Separator />
          <p className="text-xs text-muted-foreground">{t("ssh_hosts.secrets_note")}</p>
        </div>
      </ScrollArea>

      <SshHostEditorDialog
        open={creating || editing !== null}
        host={editing}
        existingNames={hosts.map((host) => host.name)}
        onOpenChange={(open) => {
          if (!open) {
            setCreating(false);
            setEditing(null);
          }
        }}
        onSaved={async (name) => {
          setCreating(false);
          setEditing(null);
          toast.success(t("ssh_hosts.saved", { name }));
          await refresh();
        }}
      />

      <Dialog
        open={pendingForget !== null}
        onOpenChange={(open) => {
          if (!open) setPendingForget(null);
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t("ssh_hosts.forget_confirm_title")}</DialogTitle>
            <DialogDescription>
              {t("ssh_hosts.forget_confirm_description", {
                pattern: pendingForget?.pattern ?? "",
              })}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" type="button" onClick={() => setPendingForget(null)}>
              {t("ssh_hosts.cancel")}
            </Button>
            <Button
              variant="destructive"
              type="button"
              onClick={() => {
                const target = pendingForget;
                setPendingForget(null);
                if (target) void handleForgetKey(target.pattern);
              }}
            >
              {t("ssh_hosts.forget_key")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog
        open={pendingDelete !== null}
        onOpenChange={(open) => {
          if (!open) setPendingDelete(null);
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t("ssh_hosts.delete_confirm_title")}</DialogTitle>
            <DialogDescription>
              {t("ssh_hosts.delete_confirm_description", { name: pendingDelete?.name ?? "" })}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" type="button" onClick={() => setPendingDelete(null)}>
              {t("ssh_hosts.cancel")}
            </Button>
            <Button
              variant="destructive"
              type="button"
              onClick={() => {
                const target = pendingDelete;
                setPendingDelete(null);
                if (target) void handleDelete(target.name);
              }}
            >
              {t("ssh_hosts.delete")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

interface EditorErrors {
  name?: string;
  host?: string;
  port?: string;
  user?: string;
  secret?: string;
  authMethod?: string;
  webTerminalUrl?: string;
}

function SshHostEditorDialog({
  open,
  host,
  existingNames,
  onOpenChange,
  onSaved,
}: {
  open: boolean;
  /** null when creating. */
  host: SshHostDto | null;
  existingNames: string[];
  onOpenChange: (open: boolean) => void;
  onSaved: (name: string) => Promise<void> | void;
}) {
  const { t } = useTranslation("page");
  const socket = useSshHostSocket();

  const [name, setName] = React.useState("");
  const [hostName, setHostName] = React.useState("");
  const [port, setPort] = React.useState("22");
  const [user, setUser] = React.useState("");
  const [authMethod, setAuthMethod] = React.useState<SshAuthMethod>("password");
  const [password, setPassword] = React.useState("");
  const [privateKey, setPrivateKey] = React.useState("");
  const [passphrase, setPassphrase] = React.useState("");
  const [webTerminalUrl, setWebTerminalUrl] = React.useState("");
  const [errors, setErrors] = React.useState<EditorErrors>({});
  const [saving, setSaving] = React.useState(false);

  // Reset whenever the dialog opens for a different row, so a cancelled draft never leaks
  // into the next edit.
  React.useEffect(() => {
    if (!open) return;
    setName(host?.name ?? "");
    setHostName(host?.host ?? "");
    setPort(String(host?.port ?? 22));
    setUser(host?.user ?? "");
    setAuthMethod(host?.authMethod ?? "password");
    setPassword("");
    setPrivateKey("");
    setPassphrase("");
    setWebTerminalUrl(host?.webTerminalUrl ?? "");
    setErrors({});
    setSaving(false);
  }, [open, host]);

  const isEditing = host !== null;
  const keepsStoredPassword = host?.hasPassword === true;
  const keepsStoredKey = host?.hasPrivateKey === true;

  const handleSubmit = React.useCallback(async () => {
    const portNumber = Number(port.trim());
    const nextErrors: EditorErrors = {};
    if (name.trim().length === 0) {
      nextErrors.name = "name_blank";
    } else if (name.trim() !== host?.name && existingNames.includes(name.trim())) {
      nextErrors.name = "name_taken";
    }
    if (hostName.trim().length === 0) nextErrors.host = "host_blank";
    if (!Number.isInteger(portNumber) || portNumber < 1 || portNumber > 65535) {
      nextErrors.port = "port_invalid";
    }
    if (user.trim().length === 0) nextErrors.user = "user_blank";
    // A stored secret is kept when the field is left blank — only a new host must supply one.
    const suppliedSecret =
      authMethod === "password" ? password.trim().length > 0 : privateKey.trim().length > 0;
    const storedSecretExists = authMethod === "password" ? keepsStoredPassword : keepsStoredKey;
    if (!suppliedSecret && !storedSecretExists) nextErrors.secret = "credential_missing";

    if (Object.keys(nextErrors).length > 0) {
      setErrors(nextErrors);
      return;
    }

    const input: SshHostUpsert = {
      originalName: isEditing ? host.name : null,
      name: name.trim(),
      host: hostName.trim(),
      port: portNumber,
      user: user.trim(),
      authMethod,
      webTerminalUrl: webTerminalUrl.trim() || null,
      secret: {
        password: authMethod === "password" ? password || null : null,
        privateKey: authMethod === "private_key" ? privateKey || null : null,
        passphrase: passphrase || null,
      },
    };

    setSaving(true);
    setErrors({});
    try {
      const frame = await socket.save(input);
      if (!frame.ok) {
        setErrors((frame.errors ?? {}) as EditorErrors);
        // The field-level `errors` map drives the inline highlights; the toast explains the
        // failure as a whole, preferring our translation of the server code.
        const code = frame.error ?? "save_failed";
        const key = `ssh_hosts.errors.${code}`;
        const fallback = frame.message ?? t("ssh_hosts.save_failed");
        const translated = t(key, { defaultValue: fallback });
        toast.error(translated === key ? fallback : translated);
        return;
      }
      await onSaved(input.name);
    } catch (err) {
      toast.error(toDisplayError(err).text);
    } finally {
      setSaving(false);
    }
  }, [
    authMethod,
    existingNames,
    host,
    hostName,
    isEditing,
    keepsStoredKey,
    keepsStoredPassword,
    name,
    onSaved,
    passphrase,
    password,
    port,
    privateKey,
    socket,
    t,
    user,
    webTerminalUrl,
  ]);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[85svh] overflow-y-auto sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>
            {isEditing ? t("ssh_hosts.edit_title") : t("ssh_hosts.add_title")}
          </DialogTitle>
          <DialogDescription>{t("ssh_hosts.editor_description")}</DialogDescription>
        </DialogHeader>

        <div className="grid gap-3">
          <Field
            label={t("ssh_hosts.field_name")}
            error={errors.name && t(`ssh_hosts.errors.${errors.name}`)}
          >
            <Input
              value={name}
              onChange={(event) => setName(event.target.value)}
              placeholder="vps"
              aria-invalid={Boolean(errors.name)}
            />
          </Field>

          <div className="grid grid-cols-[1fr_6rem] gap-3">
            <Field
              label={t("ssh_hosts.field_host")}
              error={errors.host && t(`ssh_hosts.errors.${errors.host}`)}
            >
              <Input
                value={hostName}
                onChange={(event) => setHostName(event.target.value)}
                placeholder="192.168.0.10"
                aria-invalid={Boolean(errors.host)}
              />
            </Field>
            <Field
              label={t("ssh_hosts.field_port")}
              error={errors.port && t(`ssh_hosts.errors.${errors.port}`)}
            >
              <Input
                value={port}
                onChange={(event) => setPort(event.target.value)}
                inputMode="numeric"
                aria-invalid={Boolean(errors.port)}
              />
            </Field>
          </div>

          <Field
            label={t("ssh_hosts.field_user")}
            error={errors.user && t(`ssh_hosts.errors.${errors.user}`)}
          >
            <Input
              value={user}
              onChange={(event) => setUser(event.target.value)}
              placeholder="root"
              aria-invalid={Boolean(errors.user)}
            />
          </Field>

          <Field label={t("ssh_hosts.field_auth")}>
            <div className="flex gap-2">
              {(["password", "private_key"] as const).map((method) => (
                <Button
                  key={method}
                  type="button"
                  variant={authMethod === method ? "default" : "outline"}
                  size="sm"
                  onClick={() => setAuthMethod(method)}
                >
                  {method === "password" ? t("ssh_hosts.auth_password") : t("ssh_hosts.auth_key")}
                  {authMethod === method ? <Check className="size-3.5" /> : null}
                </Button>
              ))}
            </div>
          </Field>

          {authMethod === "password" ? (
            <Field
              label={t("ssh_hosts.field_password")}
              error={errors.secret && t(`ssh_hosts.errors.${errors.secret}`)}
              hint={keepsStoredPassword ? t("ssh_hosts.secret_keep_hint") : undefined}
            >
              <Input
                type="password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                placeholder={keepsStoredPassword ? "••••••••" : ""}
                aria-invalid={Boolean(errors.secret)}
                autoComplete="new-password"
              />
            </Field>
          ) : (
            <>
              <Field
                label={t("ssh_hosts.field_private_key")}
                error={errors.secret && t(`ssh_hosts.errors.${errors.secret}`)}
                hint={keepsStoredKey ? t("ssh_hosts.secret_keep_hint") : undefined}
              >
                <textarea
                  value={privateKey}
                  onChange={(event) => setPrivateKey(event.target.value)}
                  placeholder={"-----BEGIN OPENSSH PRIVATE KEY-----"}
                  aria-invalid={Boolean(errors.secret)}
                  className="border-input placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-ring/50 min-h-24 w-full rounded-md border bg-transparent px-3 py-2 font-mono text-xs shadow-xs outline-none focus-visible:ring-[3px]"
                />
              </Field>
              <Field label={t("ssh_hosts.field_passphrase")}>
                <Input
                  type="password"
                  value={passphrase}
                  onChange={(event) => setPassphrase(event.target.value)}
                  autoComplete="new-password"
                />
              </Field>
            </>
          )}

          <Field
            label={t("ssh_hosts.field_web_terminal")}
            hint={t("ssh_hosts.field_web_terminal_hint")}
            error={
              errors.webTerminalUrl && t(`ssh_hosts.errors.${errors.webTerminalUrl}`, {
                defaultValue: errors.webTerminalUrl,
              })
            }
          >
            <Input
              value={webTerminalUrl}
              onChange={(event) => setWebTerminalUrl(event.target.value)}
              placeholder="http://192.168.0.10:7681"
              aria-invalid={Boolean(errors.webTerminalUrl)}
            />
          </Field>
        </div>

        <DialogFooter>
          <Button variant="outline" type="button" onClick={() => onOpenChange(false)}>
            {t("ssh_hosts.cancel")}
          </Button>
          <Button type="button" onClick={() => void handleSubmit()} disabled={saving}>
            {saving ? <Loader2 className="size-4 animate-spin" /> : null}
            {t("ssh_hosts.save")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function Field({
  label,
  error,
  hint,
  children,
}: {
  label: string;
  error?: string;
  hint?: string;
  children: React.ReactNode;
}) {
  return (
    <label className="grid gap-1.5">
      <span className="text-xs font-medium text-muted-foreground">{label}</span>
      {children}
      {error ? <span className="text-xs text-destructive">{error}</span> : null}
      {!error && hint ? <span className="text-xs text-muted-foreground">{hint}</span> : null}
    </label>
  );
}

/** Dialog shell around [SshHostsPanel]; the sidebar opens this. */
export function SshHostsDialog({
  open,
  onOpenChange,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const { t } = useTranslation("page");
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        className="flex h-[80svh] max-h-[80svh] flex-col gap-0 overflow-hidden p-0 sm:max-w-2xl"
        // The panel draws its own header; the dialog's is required for a11y only.
        aria-describedby={undefined}
      >
        <DialogHeader className="sr-only">
          <DialogTitle>{t("ssh_hosts.title")}</DialogTitle>
        </DialogHeader>
        <SshHostsPanel />
      </DialogContent>
    </Dialog>
  );
}
