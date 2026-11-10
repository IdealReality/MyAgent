package me.rerere.myagent.web.routes

import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlinx.coroutines.CancellationException
import me.rerere.myagent.web.SshHostSocketHandler

/**
 * `/api/ssh/hosts/ws` — the SSH host management socket.
 *
 * Auth is inherited from the enclosing route: [me.rerere.myagent.web.configureWebApi] mounts
 * this inside the same `route("/api")` (and the same `authenticate("auth-jwt")` block when a
 * web password is set) as every REST endpoint, so a socket is exactly as protected as the
 * REST API next to it. The JWT plugin validates during the upgrade handshake, which is why
 * this must be a real route rather than something installed outside the routing tree.
 */
fun Route.sshHostSocketRoutes(handler: SshHostSocketHandler) {
    // Frame size is capped once for the whole server, in `Entry.kt`'s `install(WebSockets)`.
    webSocket("/ssh/hosts/ws") {
        try {
            handler.serve(this)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // A socket that dies mid-frame must not take the server down with it; the peer
            // sees a closed connection and reconnects.
            val reason = (e.message ?: e::class.simpleName ?: "internal error").take(100)
            try {
                close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, reason))
            } catch (_: Throwable) {
                // Already closed — nothing left to do.
            }
        }
    }
}
