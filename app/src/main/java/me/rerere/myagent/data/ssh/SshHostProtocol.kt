package me.rerere.myagent.data.ssh

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.myagent.data.db.entity.SshHostEntity

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
    @Serializable(with = SshIdSerializer::class) val id: String? = null,
    val op: String = "",
    val name: String? = null,
    val pattern: String? = null,
    val host: SshHostUpsert? = null,
)

/** A server → client frame. Either a response ([id] set) or an event ([event] set). */
@Serializable
data class SshHostResponse(
    @Serializable(with = SshIdSerializer::class) val id: String? = null,
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

/**
 * The request id, accepted as any JSON scalar and re-emitted as a string.
 *
 * `id` is opaque and only has to round-trip, but a hand-written client (or a language whose
 * integer type does not serialise as a string) sends `{"id": 7, ...}`. With a plain `String` field
 * kotlinx rejected the whole frame, the client got `bad_request` with no id, and it waited out its
 * request timeout for an answer it could not have matched anyway. Numbers and booleans are
 * therefore coerced to their JSON text, and everything else to its raw JSON.
 */
@OptIn(ExperimentalSerializationApi::class)
object SshIdSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("SshHostId", PrimitiveKind.STRING).nullable

    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value)
    }

    override fun deserialize(decoder: Decoder): String? {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeString()
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonNull -> null
            is JsonPrimitive -> element.content
            else -> element.toString()
        }
    }
}

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

    /**
     * Liveness probe. Answered from the frame loop, never touching the backend, so a client
     * can tell a half-open TCP connection from a server that is merely busy.
     */
    const val PING = "ping"
}

object SshHostEvent {
    const val HOSTS_CHANGED = "hosts_changed"

    /** Emitted after `forget_host_key`: the trusted-key list changed, the host list did not. */
    const val KNOWN_HOSTS_CHANGED = "known_hosts_changed"
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
    const val WEB_TERMINAL = "webTerminalUrl"
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
    //
    // Only the chosen method's credential is kept. Both columns used to be preserved "so the user
    // can switch back", but the connection tries publickey first: after switching a host to a
    // password the old key still won, so the password the user had just entered was never used —
    // and if the stale key was broken (usually why they switched), the test failed while the
    // password was correct. One credential per row makes the badge, the editor and the handshake
    // agree.
    val password = if (authMethod == AUTH_PASSWORD) {
        input.secret.password?.takeIf { it.isNotBlank() } ?: existing?.password
    } else {
        null
    }
    val privateKey = if (authMethod == AUTH_PRIVATE_KEY) {
        input.secret.privateKey?.takeIf { it.isNotBlank() } ?: existing?.privateKey
    } else {
        null
    }
    val passphrase = if (authMethod == AUTH_PRIVATE_KEY) {
        input.secret.passphrase?.takeIf { it.isNotBlank() } ?: existing?.passphrase
    } else {
        null
    }

    val webTerminalUrl = input.webTerminalUrl?.trim()?.takeIf { it.isNotEmpty() }
    if (webTerminalUrl != null && !isAllowedTerminalUrl(webTerminalUrl)) {
        errors[SshHostErrorField.WEB_TERMINAL] = "web_terminal_invalid"
    }

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
            password = password,
            privateKey = privateKey,
            passphrase = passphrase,
            webTerminalUrl = webTerminalUrl,
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
