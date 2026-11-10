package me.rerere.myagent.data.ssh

/**
 * P2-32: pure model + validation behind the SSH host settings page.
 *
 * Deliberately free of Room / Android / Compose imports: the editor rules are identical no
 * matter who opened the page, and they can be unit-tested by the kotlinc harness on the VPS
 * (`sh /workspace/ktest.sh ...`) without an Android SDK.
 */

/** Which secret the editor collects. Mirrors the two nullable secret columns of `SshHostEntity`. */
enum class SshAuthMethod { PASSWORD, PRIVATE_KEY }

/** Which editor row an error belongs to, so the sheet can highlight the offending field. */
enum class SshHostField { NAME, HOST, PORT, USER, CREDENTIAL, WEB_TERMINAL }

/** What is wrong with a [SshHostField]. */
enum class SshHostError {
    NAME_BLANK,
    NAME_TAKEN,
    HOST_BLANK,
    PORT_INVALID,
    USER_BLANK,
    CREDENTIAL_MISSING,
    WEB_TERMINAL_INVALID,
}

/**
 * Editable snapshot of one saved SSH host.
 *
 * [originalName] is the name the row had when the editor opened (null for a new host); it is
 * what lets a rename be told apart from a collision with an existing name.
 */
data class SshHostDraft(
    val originalName: String? = null,
    val name: String = "",
    val host: String = "",
    val port: String = "22",
    val user: String = "",
    val authMethod: SshAuthMethod = SshAuthMethod.PASSWORD,
    val password: String = "",
    val privateKey: String = "",
    val passphrase: String = "",
    /** Optional web terminal URL; blank means the host has no browser terminal. */
    val webTerminalUrl: String = "",
) {
    val isNew: Boolean get() = originalName == null

    /** Parsed port, or null when the text is not an integer in 1..65535. */
    fun portOrNull(): Int? = port.trim().toIntOrNull()?.takeIf { it in 1..65535 }

    /** Whether the secret field for the currently selected method has content. */
    fun hasSecret(): Boolean = when (authMethod) {
        SshAuthMethod.PASSWORD -> password.isNotBlank()
        SshAuthMethod.PRIVATE_KEY -> privateKey.isNotBlank()
    }
}

object SshHostDraftValidator {
    /**
     * @param existingNames names currently in the database. A rename onto one of them is
     *   rejected; editing a host without touching its name is not a collision.
     */
    fun validate(draft: SshHostDraft, existingNames: Set<String>): Map<SshHostField, SshHostError> {
        val errors = LinkedHashMap<SshHostField, SshHostError>()
        val name = draft.name.trim()
        if (name.isEmpty()) {
            errors[SshHostField.NAME] = SshHostError.NAME_BLANK
        } else if (name != draft.originalName?.trim() && name in existingNames) {
            errors[SshHostField.NAME] = SshHostError.NAME_TAKEN
        }
        if (draft.host.isBlank()) errors[SshHostField.HOST] = SshHostError.HOST_BLANK
        if (draft.portOrNull() == null) errors[SshHostField.PORT] = SshHostError.PORT_INVALID
        if (draft.user.isBlank()) errors[SshHostField.USER] = SshHostError.USER_BLANK
        if (!draft.hasSecret()) errors[SshHostField.CREDENTIAL] = SshHostError.CREDENTIAL_MISSING
        val terminalUrl = draft.webTerminalUrl.trim()
        if (terminalUrl.isNotEmpty() && !isAllowedTerminalUrl(terminalUrl)) {
            errors[SshHostField.WEB_TERMINAL] = SshHostError.WEB_TERMINAL_INVALID
        }
        return errors
    }

    fun isValid(draft: SshHostDraft, existingNames: Set<String>): Boolean =
        validate(draft, existingNames).isEmpty()
}

/**
 * Whether [url] may be stored as a host's web-terminal link.
 *
 * The value is opened in the app's WebView, so it must be a plain http(s) address with a host:
 * `javascript:`, `file:`, `content:` and `intent:` URLs either do nothing or escape the browser
 * sandbox, and the socket accepts this field from any authenticated client. Checked on write
 * (draft editor and socket) and again when the link is rendered.
 */
fun isAllowedTerminalUrl(url: String): Boolean {
    val scheme = url.substringBefore(':', missingDelimiterValue = "").lowercase()
    if (scheme != "http" && scheme != "https") return false
    // "http://" alone has no host; the page would open blank and look broken.
    return url.length > scheme.length + 3
}

/** One entry of the app's `known_hosts` file, as shown on the settings page. */
@kotlinx.serialization.Serializable
data class KnownHostEntry(val pattern: String, val keyType: String) {
    /** OpenSSH hashed host entries start with `|1|`; those cannot be mapped back to a name. */
    val isHashed: Boolean get() = pattern.startsWith("|")
}

/**
 * Parse an OpenSSH known_hosts file.
 *
 * Each line is `[marker] pattern keytype base64key`. Only the pattern (host) and key type are
 * kept, since that is all the page shows. Blank / comment / malformed lines are skipped rather
 * than throwing — a user-edited known_hosts must never crash the settings page.
 */
fun parseKnownHosts(text: String): List<KnownHostEntry> =
    text.lineSequence().mapNotNull { rawLine ->
        val parts = knownHostsFields(rawLine) ?: return@mapNotNull null
        val pattern = parts.getOrNull(0) ?: return@mapNotNull null
        val keyType = parts.getOrNull(1) ?: return@mapNotNull null
        KnownHostEntry(pattern = pattern, keyType = keyType)
    }.toList()

/**
 * Tokenise one known_hosts line: `[marker] pattern keytype base64key`.
 *
 * Returns null for blank and comment lines. A marker (`@cert-authority`, `@revoked`) is dropped,
 * so index 0 is always the host pattern — the field every caller keys on.
 */
internal fun knownHostsFields(rawLine: String): List<String>? {
    val line = rawLine.trim()
    if (line.isEmpty() || line.startsWith("#")) return null
    val parts = line.split(' ', '\t').filter { it.isNotEmpty() }
    val start = if (parts.firstOrNull()?.startsWith("@") == true) 1 else 0
    return if (start == 0) parts else parts.drop(start)
}

/** Outcome of [removeKnownHostLines]: the rewritten file and how many entries were dropped. */
data class KnownHostsRemoval(val text: String, val removed: Int)

/**
 * Drop every entry whose host pattern is exactly [pattern], leaving every other line — comments,
 * unsupported key types, foreign markers, blank lines — byte-for-byte intact.
 *
 * This is deliberately done on the text rather than through JSch's in-memory repository: JSch's
 * `KnownHosts` only parses the key types it understands and re-serialises from that list, so a file
 * rewritten through it silently lost comments and any line it could not parse. Removing the raw
 * lines cannot lose data it did not understand.
 *
 * Matching is exact, case-sensitive and includes the port form (`[host]:2222`), the same way
 * OpenSSH looks a host up; hashed entries (`|1|…`) never match a plain host name, which is why the
 * settings page refuses to offer "forget" for them.
 */
fun removeKnownHostLines(text: String, pattern: String): KnownHostsRemoval {
    if (pattern.isEmpty()) return KnownHostsRemoval(text, 0)
    val hadTrailingNewline = text.endsWith("\n")
    val kept = ArrayList<String>()
    var removed = 0
    for (rawLine in text.split('\n')) {
        val host = knownHostsFields(rawLine)?.getOrNull(0)
        if (host == pattern) {
            removed += 1
        } else {
            // split() keeps the empty tail after a trailing newline; joining preserves it.
            kept += rawLine
        }
    }
    if (removed == 0) return KnownHostsRemoval(text, 0)
    var next = kept.joinToString("\n")
    if (hadTrailingNewline && !next.endsWith("\n")) next += "\n"
    return KnownHostsRemoval(next, removed)
}
