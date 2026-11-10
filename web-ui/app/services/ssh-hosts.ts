import { ApiError } from "~/services/api";

/**
 * Client for `/api/ssh/hosts/ws` — the SSH host management socket.
 *
 * Protocol notes that shape this file:
 *  - Every request carries an `id` and is answered by exactly one frame with that id.
 *  - `test` is answered late (the server runs the handshake off the frame loop), so its
 *    request keeps waiting for the frame that actually carries a result.
 *  - Mutations broadcast `hosts_changed` to the *other* connections only; the origin already
 *    has the response and refreshes once.
 *  - Change frames are full `SshHostResponse` objects, not bare event names.
 */

export const SSH_HOST_EVENT_CHANGED = "hosts_changed";
export const SSH_HOST_EVENT_KNOWN_HOSTS_CHANGED = "known_hosts_changed";

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

export type SshHostOp =
  | "list"
  | "get"
  | "save"
  | "delete"
  | "test"
  | "known_hosts"
  | "forget_host_key"
  | "ping";

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

/**
 * A server-side failure (`frame.ok === false`). Carries the machine code so the UI can show a
 * localised message instead of the server's English detail, while keeping [message] as a
 * fallback for codes this build does not know yet.
 */
export class SshHostRequestError extends ApiError {
  readonly errorCode: string;

  constructor(code: string, message?: string | null) {
    super(message ?? code, 0);
    this.name = "SshHostRequestError";
    this.errorCode = code;
  }
}

interface PendingRequest {
  resolve: (frame: SshHostFrame) => void;
  reject: (error: unknown) => void;
  timer: ReturnType<typeof setTimeout>;
  /** When set, frames that fail it are dropped and the request keeps waiting. */
  accept?: (frame: SshHostFrame) => boolean;
}

interface RequestOptions {
  timeoutMs?: number;
  accept?: (frame: SshHostFrame) => boolean;
}

/** Timing knobs; injectable so the retry and heartbeat logic can be exercised without waiting. */
export interface SshSocketTiming {
  /** Local Room round-trips; the production value is already generous for a slow phone. */
  requestTimeoutMs: number;
  /** `test` is a real SSH handshake: the server caps it at probe timeout + 5s. */
  testTimeoutMs: number;
  pingTimeoutMs: number;
  heartbeatIntervalMs: number;
  /** Unanswered pings before the connection is treated as half-open, not merely slow. */
  heartbeatFailures: number;
  reconnectBaseMs: number;
  reconnectMaxMs: number;
  /** After this many failures, stop hammering and let the user ask for a retry. */
  maxReconnectAttempts: number;
  /**
   * A connection must survive this long before the retry budget resets. Without it, a server
   * that accepts the upgrade and drops it immediately would be retried forever, because every
   * successful handshake looked like progress.
   */
  stableConnectionMs: number;
}

export const DEFAULT_SSH_SOCKET_TIMING: SshSocketTiming = {
  requestTimeoutMs: 20_000,
  testTimeoutMs: 90_000,
  pingTimeoutMs: 8_000,
  heartbeatIntervalMs: 20_000,
  heartbeatFailures: 2,
  reconnectBaseMs: 1_000,
  reconnectMaxMs: 15_000,
  maxReconnectAttempts: 6,
  stableConnectionMs: 5_000,
};

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
  private readonly timing: SshSocketTiming;
  private socket: WebSocket | null = null;
  private pending = new Map<string, PendingRequest>();
  private seq = 0;
  private closedByCaller = false;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  /** Requests that arrived while the socket was still upgrading; resolved on `open`. */
  private openWaiters = new Set<{
    resolve: () => void;
    reject: (error: unknown) => void;
    timer: ReturnType<typeof setTimeout>;
  }>();
  private reconnectAttempt = 0;
  private openedAt = 0;
  private heartbeatTimer: ReturnType<typeof setInterval> | null = null;
  private heartbeatFailures = 0;
  private changeListeners = new Set<() => void>();
  private knownHostsListeners = new Set<() => void>();
  private statusListeners = new Set<(status: SshSocketStatus) => void>();
  private status: SshSocketStatus = "closed";

  constructor(timing: SshSocketTiming = DEFAULT_SSH_SOCKET_TIMING) {
    this.timing = timing;
  }

  open(): void {
    // A caller that closed us may legitimately want the connection back (dialog reopened,
    // route remounted); only an already-open or in-flight connect makes this a no-op.
    this.closedByCaller = false;
    if (this.socket) return;
    // An explicit open is a fresh start: forget the failures that led to the backoff.
    this.reconnectAttempt = 0;
    this.connect();
  }

  /**
   * Reconnect after the automatic retries gave up. The UI exposes this as a button instead of
   * retrying forever: at that point something needs the user (expired token, wrong address,
   * server down).
   */
  retry(): void {
    if (this.socket) return;
    this.open();
  }

  close(): void {
    this.closedByCaller = true;
    this.clearReconnectTimer();
    this.stopHeartbeat();
    this.socket?.close();
    this.socket = null;
    const error = new ApiError("WebSocket closed by caller", 0);
    this.failAllPending(error);
    this.failOpenWaiters(error);
    this.setStatus("closed");
  }

  onHostsChanged(listener: () => void): () => void {
    this.changeListeners.add(listener);
    return () => {
      this.changeListeners.delete(listener);
    };
  }

  onKnownHostsChanged(listener: () => void): () => void {
    this.knownHostsListeners.add(listener);
    return () => {
      this.knownHostsListeners.delete(listener);
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
    return this.request({ op: "list" }).then((frame) => {
      throwIfFailed(frame);
      return frame.hosts ?? [];
    });
  }

  getHost(name: string): Promise<SshHostDto | null> {
    return this.request({ op: "get", name }).then((frame) => {
      throwIfFailed(frame);
      return frame.host ?? null;
    });
  }

  /** Resolves with the raw frame: a rejected save carries per-field `errors` for the editor. */
  save(input: SshHostUpsert): Promise<SshHostFrame> {
    return this.request({ op: "save", host: input });
  }

  deleteHost(name: string): Promise<SshHostFrame> {
    return this.request({ op: "delete", name });
  }

  test(name: string): Promise<SshHostTestResult> {
    return this.request(
      { op: "test", name },
      {
        timeoutMs: this.timing.testTimeoutMs,
        // The reply to a started test arrives later and is the only frame with `test` set.
        // An immediate rejection (missing/unknown host) is an answer too, so it must not be
        // dropped while we wait for a result that will never come.
        accept: (frame) => frame.test != null || frame.ok === false,
      },
    ).then((frame) => {
      throwIfFailed(frame);
      return frame.test ?? { ok: false, errorCode: "no_result" };
    });
  }

  knownHosts(): Promise<SshKnownHostEntry[]> {
    return this.request({ op: "known_hosts" }).then((frame) => {
      throwIfFailed(frame);
      return frame.knownHosts ?? [];
    });
  }

  forgetHostKey(pattern: string): Promise<SshHostFrame> {
    return this.request({ op: "forget_host_key", pattern });
  }

  private connect(): void {
    this.setStatus("connecting");

    const socket = new WebSocket(webSocketUrl());
    this.socket = socket;

    socket.onopen = () => {
      this.openedAt = Date.now();
      this.heartbeatFailures = 0;
      this.setStatus("open");
      this.startHeartbeat();
      // Anything that asked while we were dialling goes out now, oldest first.
      this.flushOpenWaiters();
    };
    socket.onmessage = (event) => this.handleMessage(event.data);
    socket.onerror = () => {
      // A failed handshake (e.g. an expired token → 401 before upgrade) surfaces here with
      // no detail; onclose follows and schedules the retry.
    };
    socket.onclose = () => {
      if (this.socket !== socket) return;
      this.socket = null;
      this.stopHeartbeat();
      // Only a connection that stayed up counts as progress; a handshake followed by an
      // immediate drop keeps burning the retry budget.
      if (Date.now() - this.openedAt >= this.timing.stableConnectionMs) this.reconnectAttempt = 0;
      this.failAllPending(new ApiError("WebSocket closed", 0));
      this.setStatus("closed");
      if (!this.closedByCaller) this.scheduleReconnect();
    };
  }

  private request(
    payload: Record<string, unknown>,
    options: RequestOptions = {},
  ): Promise<SshHostFrame> {
    const timeoutMs = options.timeoutMs ?? this.timing.requestTimeoutMs;
    // One budget for waiting + sending + answering, so a request that races a reconnect does not
    // take twice its timeout to fail.
    const deadline = Date.now() + timeoutMs;
    return this.waitForOpen(deadline).then(
      () =>
        // `new Promise` rather than `Promise.withResolvers()`: the shipped WebView is not
        // guaranteed to be new enough, and this is the only place that would need it.
        new Promise<SshHostFrame>((resolve, reject) => {
          const socket = this.socket;
          if (!socket || socket.readyState !== WebSocket.OPEN) {
            reject(new ApiError("SSH host socket is not connected", 0));
            return;
          }

          const id = `req-${++this.seq}`;
          const timer = setTimeout(() => {
            this.pending.delete(id);
            reject(new ApiError("SSH host request timed out", 0));
          }, Math.max(deadline - Date.now(), 1));

          this.pending.set(id, { resolve, reject, timer, accept: options.accept });
          try {
            socket.send(JSON.stringify({ id, ...payload }));
          } catch (error) {
            // A send on a socket that just started closing throws synchronously; without this
            // the pending entry (and its timer) would outlive the promise that already rejected.
            clearTimeout(timer);
            this.pending.delete(id);
            reject(new ApiError(`SSH host socket could not send: ${String(error)}`, 0));
          }
        }),
    );
  }

  /**
   * Resolve once the socket is open, or reject when [deadline] passes.
   *
   * A caller that reconnects is retried by [scheduleReconnect] underneath this wait, so a save
   * issued during a one-second blip succeeds instead of surfacing "not connected" for something
   * the user really did.
   */
  private waitForOpen(deadline: number): Promise<void> {
    if (this.socket && this.socket.readyState === WebSocket.OPEN) return Promise.resolve();
    if (this.closedByCaller) {
      return Promise.reject(new ApiError("SSH host socket is closed", 0));
    }
    return new Promise<void>((resolve, reject) => {
      const waiter = {
        resolve,
        reject: (error: unknown) => reject(error),
        timer: setTimeout(() => {
          this.openWaiters.delete(waiter);
          reject(new ApiError("SSH host socket did not open in time", 0));
        }, Math.max(deadline - Date.now(), 1)),
      };
      this.openWaiters.add(waiter);
    });
  }

  private flushOpenWaiters(): void {
    const waiters = [...this.openWaiters];
    this.openWaiters.clear();
    for (const waiter of waiters) {
      clearTimeout(waiter.timer);
      waiter.resolve();
    }
  }

  private failOpenWaiters(error: unknown): void {
    const waiters = [...this.openWaiters];
    this.openWaiters.clear();
    for (const waiter of waiters) {
      clearTimeout(waiter.timer);
      waiter.reject(error);
    }
  }

  private handleMessage(raw: unknown): void {
    if (typeof raw !== "string") return;

    let frame: SshHostFrame;
    try {
      frame = JSON.parse(raw) as SshHostFrame;
    } catch {
      return;
    }

    if (frame.event) {
      // Events carry no id; they are notifications, never replies.
      const listeners =
        frame.event === SSH_HOST_EVENT_KNOWN_HOSTS_CHANGED
          ? this.knownHostsListeners
          : frame.event === SSH_HOST_EVENT_CHANGED
            ? this.changeListeners
            : null;
      if (!listeners) return;
      for (const listener of listeners) {
        try {
          listener();
        } catch (error) {
          console.error(`SSH hosts listener failed for ${frame.event}:`, error);
        }
      }
      return;
    }

    if (!frame.id) return;
    const pending = this.pending.get(frame.id);
    if (!pending) return;
    if (pending.accept && !pending.accept(frame)) return;
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
    if (this.status === status) return;
    this.status = status;
    for (const listener of this.statusListeners) {
      try {
        listener(status);
      } catch (error) {
        console.error("SSH socket status listener failed:", error);
      }
    }
  }

  private startHeartbeat(): void {
    this.stopHeartbeat();
    this.heartbeatFailures = 0;
    this.heartbeatTimer = setInterval(() => {
      void this.beat();
    }, this.timing.heartbeatIntervalMs);
  }

  private stopHeartbeat(): void {
    if (this.heartbeatTimer !== null) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
  }

  /**
   * Application-level liveness check. The server's protocol pings keep the peer honest, but
   * a phone that changed networks leaves a socket the browser still believes is open; only a
   * request/response round trip proves the path works.
   */
  private async beat(): Promise<void> {
    if (!this.socket || this.socket.readyState !== WebSocket.OPEN) return;
    try {
      await this.request({ op: "ping" }, { timeoutMs: this.timing.pingTimeoutMs });
      this.heartbeatFailures = 0;
    } catch {
      this.heartbeatFailures += 1;
      if (this.heartbeatFailures >= this.timing.heartbeatFailures) {
        // Dropping the socket triggers onclose → failAllPending → reconnect with backoff.
        this.socket?.close();
      }
    }
  }

  private clearReconnectTimer(): void {
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
  }

  private scheduleReconnect(): void {
    if (this.reconnectTimer !== null) return;
    if (this.reconnectAttempt >= this.timing.maxReconnectAttempts) {
      // Stay closed; the panel offers an explicit retry. Waiting callers must not sit on their
      // own timeout for a connection that will not be attempted again.
      this.failOpenWaiters(new ApiError("SSH host socket gave up reconnecting", 0));
      return;
    }
    const delay = Math.min(
      this.timing.reconnectBaseMs * 2 ** this.reconnectAttempt,
      this.timing.reconnectMaxMs,
    );
    this.reconnectAttempt += 1;
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      if (!this.socket && !this.closedByCaller) this.connect();
    }, delay);
  }
}

function throwIfFailed(frame: SshHostFrame): void {
  if (!frame.ok) throw new SshHostRequestError(frame.error ?? "request_failed", frame.message);
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
