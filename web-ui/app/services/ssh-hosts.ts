import { ApiError } from "~/services/api";

/**
 * Client for `/api/ssh/hosts/ws` — the SSH host management socket.
 *
 * Requests are one-shot ops, each answered by exactly one frame carrying the same `id`, so
 * callers can pipeline. `hosts_changed` frames arrive unsolicited whenever any client (another
 * tab, or the phone's own settings page) mutates a host.
 *
 * Auth reuses the JWT the REST client stores: browsers cannot set a header on a WebSocket
 * handshake, so the token rides in the query string, which is the same mechanism the API
 * already accepts for SSE-driven image URLs (`access_token`).
 *
 * @see app/src/main/java/me/rerere/rikkahub/web/SshHostSocket.kt
 */

export const SSH_HOST_EVENT_CHANGED = "hosts_changed";

export type SshAuthMethod = "password" | "private_key";

export interface SshHostDto {
  name: string;
  host: string;
  port: number;
  user: string;
  authMethod: SshAuthMethod;
  hasPassword: boolean;
  hasPrivateKey: boolean;
  hasPassphrase: boolean;
  webTerminalUrl?: string | null;
  createdAtMs: number;
}

export interface SshHostTestResult {
  ok: boolean;
  errorCode?: string | null;
  detail?: string | null;
}

export interface SshKnownHostEntry {
  pattern: string;
  keyType: string;
}

export interface SshHostSecretInput {
  password?: string | null;
  privateKey?: string | null;
  passphrase?: string | null;
}

export interface SshHostUpsert {
  originalName?: string | null;
  name: string;
  host: string;
  port: number;
  user: string;
  authMethod: SshAuthMethod;
  webTerminalUrl?: string | null;
  secret: SshHostSecretInput;
}

export type SshHostOp = "list" | "get" | "save" | "delete" | "test" | "known_hosts" | "forget_host_key";

export interface SshHostFrame {
  id?: string | null;
  op?: string | null;
  ok: boolean;
  error?: string | null;
  message?: string | null;
  errors?: Record<string, string> | null;
  hosts?: SshHostDto[] | null;
  host?: SshHostDto | null;
  knownHosts?: SshKnownHostEntry[] | null;
  test?: SshHostTestResult | null;
  event?: string | null;
}

interface PendingRequest {
  resolve: (frame: SshHostFrame) => void;
  reject: (error: unknown) => void;
  timer: ReturnType<typeof setTimeout>;
}

const REQUEST_TIMEOUT_MS = 45_000;
const RECONNECT_DELAY_MS = 2000;

function webSocketUrl(): string {
  const scheme = window.location.protocol === "https:" ? "wss:" : "ws:";
  const auth = readAccessToken();
  const query = auth ? `?access_token=${encodeURIComponent(auth)}` : "";
  return `${scheme}//${window.location.host}/api/ssh/hosts/ws${query}`;
}

/**
 * The token lives in the same localStorage entry the REST client writes, and that entry is
 * private to it. Re-reading it here rather than exporting a getter keeps the auth storage in
 * one module; the shape is the contract (`{ token, expiresAt }`).
 */
function readAccessToken(): string | null {
  try {
    const raw = window.localStorage.getItem("rikkahub:web-auth");
    if (!raw) return null;
    const parsed = JSON.parse(raw) as { token?: unknown; expiresAt?: unknown };
    if (typeof parsed.token !== "string" || typeof parsed.expiresAt !== "number") return null;
    // Treat a token expiring within 10s as gone, matching the REST client's skew.
    if (Date.now() >= parsed.expiresAt - 10_000) return null;
    return parsed.token;
  } catch {
    return null;
  }
}

export type SshSocketStatus = "connecting" | "open" | "closed";

export class SshHostSocket {
  private socket: WebSocket | null = null;
  private pending = new Map<string, PendingRequest>();
  private seq = 0;
  private closedByCaller = false;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private changeListeners = new Set<() => void>();
  private statusListeners = new Set<(status: SshSocketStatus) => void>();
  private status: SshSocketStatus = "closed";

  open(): void {
    // A caller that closed us may legitimately want the connection back (dialog reopened,
    // route remounted); only an already-open or in-flight connect makes this a no-op.
    this.closedByCaller = false;
    if (this.socket) return;
    this.setStatus("connecting");

    const socket = new WebSocket(webSocketUrl());
    this.socket = socket;

    socket.onopen = () => this.setStatus("open");
    socket.onmessage = (event) => this.handleMessage(event.data);
    socket.onerror = () => {
      // A failed handshake (e.g. an expired token → 401 before upgrade) surfaces here with
      // no detail; onclose follows and schedules the retry.
    };
    socket.onclose = () => {
      this.socket = null;
      this.failAllPending(new ApiError("WebSocket closed", 0));
      this.setStatus("closed");
      if (!this.closedByCaller) this.scheduleReconnect();
    };
  }

  close(): void {
    this.closedByCaller = true;
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.socket?.close();
    this.socket = null;
    this.failAllPending(new ApiError("WebSocket closed by caller", 0));
    this.setStatus("closed");
  }

  onHostsChanged(listener: () => void): () => void {
    this.changeListeners.add(listener);
    return () => {
      this.changeListeners.delete(listener);
    };
  }

  onStatusChange(listener: (status: SshSocketStatus) => void): () => void {
    this.statusListeners.add(listener);
    listener(this.status);
    return () => {
      this.statusListeners.delete(listener);
    };
  }

  getStatus(): SshSocketStatus {
    return this.status;
  }

  list(): Promise<SshHostDto[]> {
    return this.request({ op: "list" }).then((frame) => frame.hosts ?? []);
  }

  getHost(name: string): Promise<SshHostDto | null> {
    return this.request({ op: "get", name }).then((frame) => frame.host ?? null);
  }

  save(input: SshHostUpsert): Promise<SshHostFrame> {
    return this.request({ op: "save", host: input });
  }

  deleteHost(name: string): Promise<SshHostFrame> {
    return this.request({ op: "delete", name });
  }

  test(name: string): Promise<SshHostTestResult> {
    return this.request({ op: "test", name }).then(
      (frame) => frame.test ?? { ok: false, errorCode: "no_result" },
    );
  }

  knownHosts(): Promise<SshKnownHostEntry[]> {
    return this.request({ op: "known_hosts" }).then((frame) => frame.knownHosts ?? []);
  }

  forgetHostKey(pattern: string): Promise<SshHostFrame> {
    return this.request({ op: "forget_host_key", pattern });
  }

  private request(payload: Record<string, unknown>): Promise<SshHostFrame> {
    if (!this.socket || this.socket.readyState !== WebSocket.OPEN) {
      return Promise.reject(new ApiError("SSH host socket is not connected", 0));
    }

    const id = `req-${++this.seq}`;
    // `new Promise` rather than `Promise.withResolvers()`: the shipped WebView is not
    // guaranteed to be new enough, and this is the only place that would need it.
    return new Promise<SshHostFrame>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new ApiError("SSH host request timed out", 0));
      }, REQUEST_TIMEOUT_MS);

      this.pending.set(id, { resolve, reject, timer });
      this.socket?.send(JSON.stringify({ id, ...payload }));
    });
  }

  private handleMessage(raw: unknown): void {
    if (typeof raw !== "string") return;

    let frame: SshHostFrame;
    try {
      frame = JSON.parse(raw) as SshHostFrame;
    } catch {
      return;
    }

    if (frame.event === SSH_HOST_EVENT_CHANGED) {
      for (const listener of this.changeListeners) {
        try {
          listener();
        } catch (error) {
          console.error("SSH hosts listener failed:", error);
        }
      }
      return;
    }

    if (!frame.id) return;
    const pending = this.pending.get(frame.id);
    if (!pending) return;
    this.pending.delete(frame.id);
    clearTimeout(pending.timer);
    pending.resolve(frame);
  }

  private failAllPending(error: unknown): void {
    for (const [id, pending] of this.pending) {
      clearTimeout(pending.timer);
      pending.reject(error);
      this.pending.delete(id);
    }
  }

  private setStatus(status: SshSocketStatus): void {
    this.status = status;
    for (const listener of this.statusListeners) {
      try {
        listener(status);
      } catch (error) {
        console.error("SSH socket status listener failed:", error);
      }
    }
  }

  private scheduleReconnect(): void {
    if (this.reconnectTimer !== null) return;
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this.open();
    }, RECONNECT_DELAY_MS);
  }
}

let sharedSocket: SshHostSocket | null = null;
let refCount = 0;

/**
 * Process-wide socket: one connection serves every component on the page.
 *
 * Ref-counted rather than opened forever — the mobile client keeps this page mounted for
 * minutes at a time, and an idle socket holding a JWT-authenticated upgrade is not free.
 * Every [acquireSshHostSocket] must be paired with exactly one [releaseSshHostSocket].
 */
export function acquireSshHostSocket(): SshHostSocket {
  if (!sharedSocket) {
    sharedSocket = new SshHostSocket();
  }
  refCount += 1;
  sharedSocket.open();
  return sharedSocket;
}

export function releaseSshHostSocket(): void {
  refCount = Math.max(0, refCount - 1);
  if (refCount === 0) {
    sharedSocket?.close();
  }
}
