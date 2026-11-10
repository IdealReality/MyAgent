package me.rerere.myagent.ui.pages.setting

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.myagent.data.ai.tools.local.SshHostProbe
import me.rerere.myagent.data.ai.tools.local.forgetHostKey
import me.rerere.myagent.data.ai.tools.local.knownHostsFile
import me.rerere.myagent.data.db.entity.SshHostEntity
import me.rerere.myagent.data.repository.SshHostRepository
import me.rerere.myagent.data.ssh.KnownHostEntry
import me.rerere.myagent.data.ssh.SshAuthMethod
import me.rerere.myagent.data.ssh.SshHostDraft
import me.rerere.myagent.data.ssh.isAllowedTerminalUrl
import me.rerere.myagent.data.ssh.parseKnownHosts

/** Outcome of the "test connection" button: a real JSch handshake, never a bare TCP probe. */
data class SshTestResult(
    val ok: Boolean,
    val errorCode: String? = null,
    val detail: String? = null,
)

/**
 * P2-32: backs the SSH host settings page.
 *
 * Hosts live in the `ssh_hosts` Room table and were previously only reachable through the LLM
 * tools (`save_ssh_host` / `delete_ssh_host` / `list_ssh_hosts`). This is the first
 * human-facing surface for that data.
 *
 * The connection test uses [openSshSession] rather than
 * [me.rerere.myagent.data.ai.tools.local.execOneShot] — a test opens no channel and runs no
 * command — but it deliberately walks the same candidate transports, because otherwise the
 * page can report a failure for a host the tools reach without trouble (the tools spend their
 * first candidate on the default route too, then fall back to a physical network; a test
 * pinned to the default route alone cannot).
 */
class SettingSshViewModel(
    private val context: Context,
    private val repository: SshHostRepository,
) : ViewModel() {

    private val _hosts = MutableStateFlow<List<SshHostEntity>>(emptyList())
    val hosts: StateFlow<List<SshHostEntity>> = _hosts.asStateFlow()

    private val _knownHosts = MutableStateFlow<List<KnownHostEntry>>(emptyList())
    val knownHosts: StateFlow<List<KnownHostEntry>> = _knownHosts.asStateFlow()

    private val _testingName = MutableStateFlow<String?>(null)
    val testingName: StateFlow<String?> = _testingName.asStateFlow()

    private val _testResult = MutableStateFlow<Pair<String, SshTestResult>?>(null)
    val testResult: StateFlow<Pair<String, SshTestResult>?> = _testResult.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _hosts.value = withContext(Dispatchers.IO) { repository.getAll() }
            reloadKnownHosts()
        }
    }

    private suspend fun reloadKnownHosts() {
        _knownHosts.value = withContext(Dispatchers.IO) {
            val file = knownHostsFile(context)
            if (file.exists()) parseKnownHosts(file.readText()) else emptyList()
        }
    }

    fun save(draft: SshHostDraft) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val original = draft.originalName?.trim()
                // An edit keeps the row's age; only a create stamps "now". The web socket has
                // always behaved this way (see resolveSshHostUpsert) and the two surfaces must
                // not disagree about the same host's createdAtMs.
                val existing = original?.let { repository.getByName(it) }
                val entity = SshHostEntity(
                    name = draft.name.trim(),
                    host = draft.host.trim(),
                    port = draft.portOrNull() ?: 22,
                    user = draft.user.trim(),
                    // This editor prefills the stored secrets, so a blank field really means the
                    // user cleared it — unlike the socket, where blank means "keep". Only the
                    // selected method's credential is stored, same rule as the socket: a stale
                    // key would otherwise win the handshake after switching to a password.
                    password = draft.password.takeIf {
                        draft.authMethod == SshAuthMethod.PASSWORD && it.isNotBlank()
                    },
                    privateKey = draft.privateKey.takeIf {
                        draft.authMethod == SshAuthMethod.PRIVATE_KEY && it.isNotBlank()
                    },
                    passphrase = draft.passphrase.takeIf {
                        draft.authMethod == SshAuthMethod.PRIVATE_KEY && it.isNotBlank()
                    },
                    // Never persist a scheme the WebView must not navigate to, even if the
                    // dialog's own validation was bypassed.
                    webTerminalUrl = draft.webTerminalUrl.trim()
                        .takeIf { it.isNotBlank() && isAllowedTerminalUrl(it) },
                    createdAtMs = existing?.createdAtMs ?: System.currentTimeMillis(),
                )
                // `name` is the primary key, so a rename is a delete + insert — one transaction,
                // or a crash in between loses the host.
                if (original != null && original != entity.name) repository.rename(original, entity)
                else repository.upsert(entity)
            }
            refresh()
        }
    }

    fun delete(host: SshHostEntity) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.deleteByName(host.name) }
            refresh()
        }
    }

    /** Forget every stored host key for [entry]'s pattern. Hashed entries cannot be mapped. */
    fun forgetKey(entry: KnownHostEntry) {
        if (entry.isHashed) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { forgetHostKey(context, entry.pattern) }
            reloadKnownHosts()
        }
    }

    fun test(host: SshHostEntity) {
        if (_testingName.value != null) return
        _testingName.value = host.name
        _testResult.value = null
        viewModelScope.launch {
            // `finally`, not a trailing assignment: if the scope is cancelled (the user leaves
            // the page mid-test) the spinner must not be left running forever — the guard above
            // would then refuse every later test on every host.
            try {
                val result = runCatching { performHandshake(host) }
                    .getOrElse { SshTestResult(false, "connect_failed", it.message) }
                _testResult.value = host.name to result
            } finally {
                _testingName.value = null
            }
        }
    }

    private suspend fun performHandshake(host: SshHostEntity): SshTestResult {
        val result = SshHostProbe.probe(context, host)
        return SshTestResult(ok = result.ok, errorCode = result.errorCode, detail = result.detail)
    }
}
