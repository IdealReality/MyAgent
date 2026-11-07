package me.rerere.rikkahub.ui.pages.setting

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.ai.tools.local.SshHostProbe
import me.rerere.rikkahub.data.ai.tools.local.forgetHostKey
import me.rerere.rikkahub.data.ai.tools.local.knownHostsFile
import me.rerere.rikkahub.data.db.entity.SshHostEntity
import me.rerere.rikkahub.data.repository.SshHostRepository
import me.rerere.rikkahub.data.ssh.KnownHostEntry
import me.rerere.rikkahub.data.ssh.SshHostDraft
import me.rerere.rikkahub.data.ssh.parseKnownHosts

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
 * [me.rerere.rikkahub.data.ai.tools.local.execOneShot] — a test opens no channel and runs no
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
            val entity = SshHostEntity(
                name = draft.name.trim(),
                host = draft.host.trim(),
                port = draft.portOrNull() ?: 22,
                user = draft.user.trim(),
                password = draft.password.takeIf { it.isNotBlank() },
                privateKey = draft.privateKey.takeIf { it.isNotBlank() },
                passphrase = draft.passphrase.takeIf { it.isNotBlank() },
                webTerminalUrl = draft.webTerminalUrl.trim().takeIf { it.isNotBlank() },
                createdAtMs = System.currentTimeMillis(),
            )
            withContext(Dispatchers.IO) {
                val original = draft.originalName?.trim()
                // `name` is the primary key, so a rename is a delete + insert.
                if (original != null && original != entity.name) repository.deleteByName(original)
                repository.upsert(entity)
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
