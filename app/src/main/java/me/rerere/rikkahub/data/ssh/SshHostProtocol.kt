package me.rerere.rikkahub.data.ssh

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.db.entity.SshHostEntity

/**
 * Wire protocol for the SSH host management socket (`/api/ssh/hosts/ws`).
 *
 * Kept free of Android/Room/Compose imports for the same reason as [SshHostDraft]: the rules
 * an editor and the socket share must be identical, and they have to be unit-testable on a
 * plain JVM.
 *
 * Two invariants drive the shapes below:
 *
 *  1. **Secrets never leave the app.** A stored password / private key / passphrase is
 *     represented outbound only as a boolean. There is no response field that can carry the
 *     value, so an accidental `Json.encodeToString(host)` cannot leak one.
 *  2. **A blank secret means "keep".** The client never has the ciphertext to send back, so
 *     "field omitted" must be distinguishable from "field cleared". It is: null/blank keeps
 *     the stored value, which is also why clearing a passphrase is not expressible — an
 *     explicit operation would be needed, and the settings editor has the same rule today.
 */

/** One saved host as the socket reports it. */
@Serializable
data class SshHostDto(
    val name: String,
    val host: String,
    val port: Int,
    val user: String,
    /** `"password"` or `"private_key"`; whichever secret the row actually carries. */
    val authMethod: String,
    val hasPassword: Boolean,
    val hasPrivateKey: Boolean,
    val hasPassphrase: Boolean,
    val webTerminalUrl: String? = null,
    val createdAtMs: Long,
)

/** Secret-bearing part of an upsert. A null/blank value keeps whatever is stored. */
@Serializable
data class SshHostSecretInput(
    val password: String? = null,
    val privateKey: String? = null,
    val passphrase: String? = null,
)

/** Body of a `save` request. */
@Serializable
data class SshHostUpsert(
    /** Present when editing an existing row; lets a rename be told apart from a collision. */
    val originalName: String? = null,
    val name: String = "",
    val host: String = "",
    val port: Int = 22,
    val user: String = "",
    /** `"password"` (default) or `"private_key"`. */
    val authMethod: String = AUTH_PASSWORD,
    val webTerminalUrl: String? = null,
    val secret: SshHostSecretInput = SshHostSecretInput(),
)

/** A client → server frame. `id` is echoed on the matching response; it is opaque. */
@Serializable
data class SshHostRequest(
    val id: String? = null,
    val op: String = "",
    val name: String? = null,
    val pattern: String? = null,
    val host: SshHostUpsert? = null,
)

/** A server → client frame. Either a response ([id] set) or an event ([event] set). */
@Serializable
data class SshHostResponse(
    val id: String? = null,
    val op: String? = null,
    val ok: Boolean = false,
    /** Machine-readable failure code; null on success. */
    val error: String? = null,
    /** Human-readable detail for logs; never contains a secret. */
    val message: String? = null,
    /** Per-field validation codes, keyed by field name (`name`, `host`, `port`, `user`, `secret`). */
    val errors: Map<String, String>? = null,
    val hosts: List<SshHostDto>? = null,
    val host: SshHostDto? = null,
    /** `known_hosts` entries, pattern + key type only. */
    val knownHosts: List<KnownHostEntry>? = null,
    /** Result of a `test` op. */
    val test: SshHostTestResult? = null,
    /** Set on events: `hosts_changed`. */
    val event: String? = null,
)

/** Outcome of a real SSH handshake. Mirrors what the settings page shows. */
@Serializable
data class SshHostTestResult(
    val ok: Boolean,
    val errorCode: String? = null,
    val detail: String? = null,
)

const val AUTH_PASSWORD = "password"
const val AUTH_PRIVATE_KEY = "private_key"

/** Operation names. Strings, not an enum: an unknown op must fail loudly, not fail to parse. */
object SshHostOp {
    const val LIST = "list"
    const val GET = "get"
    const val SAVE = "save"
    const val DELETE = "delete"
    const val TEST = "test"
    const val KNOWN_HOSTS = "known_hosts"
    const val FORGET_HOST_KEY = "forget_host_key"
}

object SshHostEvent {
    const val HOSTS_CHANGED = "hosts_changed"
}

fun SshHostEntity.toDto(): SshHostDto = SshHostDto(
    name = name,
    host = host,
    port = port,
    user = user,
    authMethod = if (!privateKey.isNullOrBlank()) AUTH_PRIVATE_KEY else AUTH_PASSWORD,
    hasPassword = !password.isNullOrBlank(),
    hasPrivateKey = !privateKey.isNullOrBlank(),
    hasPassphrase = !passphrase.isNullOrBlank(),
    webTerminalUrl = webTerminalUrl,
    createdAtMs = createdAtMs,
)

/** Field keys used in [SshHostResponse.errors]; the web editor highlights rows by these. */
object SshHostErrorField {
    const val NAME = "name"
    const val HOST = "host"
    const val PORT = "port"
    const val USER = "user"
    const val SECRET = "secret"
    const val AUTH_METHOD = "authMethod"
}

/** Result of folding an [SshHostUpsert] onto the row it would replace. */
sealed interface SshHostResolution {
    data class Saved(val entity: SshHostEntity, val renamedFrom: String?) : SshHostResolution
    data class Invalid(
        val errors: Map<String, String>,
        val message: String,
    ) : SshHostResolution
}

/**
 * Validate [input] and merge it with [existing] (null for a create) into the row to persist.
 *
 * @param nameTaken whether a row already exists under the *new* name — callers pass the
 *   answer for the trimmed name whenever it differs from [SshHostUpsert.originalName]. A
 *   rename onto an occupied name is refused; re-saving a row under its own name is not.
 * @param nowMs used for `createdAtMs` only when there is no existing row: an edit must not
 *   reset a host's age (the settings page currently does reset it, which the web socket
 *   would otherwise inherit).
 */
fun resolveSshHostUpsert(
    input: SshHostUpsert,
    existing: SshHostEntity?,
    nameTaken: Boolean,
    nowMs: Long,
): SshHostResolution {
    val errors = LinkedHashMap<String, String>()

    val name = input.name.trim()
    if (name.isEmpty()) {
        errors[SshHostErrorField.NAME] = "name_blank"
    } else if (nameTaken && name != input.originalName?.trim()) {
        errors[SshHostErrorField.NAME] = "name_taken"
    }

    val host = input.host.trim()
    if (host.isEmpty()) errors[SshHostErrorField.HOST] = "host_blank"

    if (input.port !in 1..65535) errors[SshHostErrorField.PORT] = "port_invalid"

    val user = input.user.trim()
    if (user.isEmpty()) errors[SshHostErrorField.USER] = "user_blank"

    val authMethod = normalizeAuthMethod(input.authMethod)
    if (authMethod == null) errors[SshHostErrorField.AUTH_METHOD] = "auth_method_invalid"

    // Blank means "keep the stored secret": the client cannot echo a value it never received.
    val password = input.secret.password?.takeIf { it.isNotBlank() } ?: existing?.password
    val privateKey = input.secret.privateKey?.takeIf { it.isNotBlank() } ?: existing?.privateKey
    val passphrase = input.secret.passphrase?.takeIf { it.isNotBlank() } ?: existing?.passphrase

    if (authMethod != null) {
        val hasCredential = when (authMethod) {
            AUTH_PRIVATE_KEY -> !privateKey.isNullOrBlank()
            else -> !password.isNullOrBlank()
        }
        if (!hasCredential) errors[SshHostErrorField.SECRET] = "credential_missing"
    }

    if (errors.isNotEmpty()) {
        return SshHostResolution.Invalid(errors, errors.entries.joinToString(", ") { "${it.key}=${it.value}" })
    }

    return SshHostResolution.Saved(
        entity = SshHostEntity(
            name = name,
            host = host,
            port = input.port,
            user = user,
            // Keep both columns as resolved: the auth method only decides which one is
            // required, and dropping the other would silently destroy a credential the user
            // may switch back to.
            password = password,
            privateKey = privateKey,
            passphrase = passphrase,
            webTerminalUrl = input.webTerminalUrl?.trim()?.takeIf { it.isNotEmpty() },
            createdAtMs = existing?.createdAtMs ?: nowMs,
        ),
        renamedFrom = input.originalName?.trim()?.takeIf { it != name && it.isNotEmpty() },
    )
}

/** Accept the shapes a hand-written client is likely to send; null when it is none of them. */
fun normalizeAuthMethod(raw: String): String? = when (raw.trim().lowercase()) {
    "", AUTH_PASSWORD, "passwd", "pwd" -> AUTH_PASSWORD
    AUTH_PRIVATE_KEY, "private-key", "key", "publickey" -> AUTH_PRIVATE_KEY
    else -> null
}
