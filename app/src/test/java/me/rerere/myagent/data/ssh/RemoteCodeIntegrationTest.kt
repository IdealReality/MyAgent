package me.rerere.myagent.data.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.myagent.data.dap.DapAdapterSpec
import me.rerere.myagent.data.dap.DapRunOutcome
import me.rerere.myagent.data.dap.RemoteDapClient
import me.rerere.myagent.data.lsp.LspEdits
import me.rerere.myagent.data.lsp.LspPosition
import me.rerere.myagent.data.lsp.LspSpecs
import me.rerere.myagent.data.lsp.RemoteLspClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * End-to-end verification of the SSH code-intelligence path against a **real host**.
 *
 * Unit tests can pin framing and edit arithmetic, but they cannot prove that a real clangd or
 * debugpy accepts our `initialize` payload, our document sync, or our launch handshake. This test
 * runs the exact production clients (`RemoteLspClient`, `RemoteDapClient`) over a real JSch
 * session, so a protocol-shaped mistake fails here instead of on the phone.
 *
 * Skipped unless configured, either in `local.properties`:
 * ```
 * myagent.it.host=100.x.y.z
 * myagent.it.user=baka
 * myagent.it.key=C:/Users/me/.ssh/id_ed25519
 * myagent.it.port=22                                   (optional)
 * myagent.it.debugpy=/tmp/venv/bin/python               (optional, for the DAP test)
 * ```
 * or through the equivalent `MYAGENT_IT_*` environment variables.
 * Everything written here lands in `/tmp/myagent-it-*` and is removed afterwards.
 */
class RemoteCodeIntegrationTest {

    private data class ItConfig(
        val host: String,
        val user: String,
        val key: String,
        val port: Int,
        val debugpyPython: String?,
    )

    /**
     * Credentials come from `local.properties` (already gitignored) with env vars as a fallback:
     * Gradle's daemon caches its environment, so a property file edited between runs is the only
     * reliable way to enable the test without restarting the daemon.
     */
    private fun config(): ItConfig? {
        val props = java.util.Properties()
        val candidates = listOf(java.io.File("../local.properties"), java.io.File("local.properties"))
        for (file in candidates) {
            if (file.exists()) runCatching { file.inputStream().use { props.load(it) } }
        }
        fun value(prop: String, env: String): String? =
            props.getProperty(prop)?.takeIf { it.isNotBlank() } ?: System.getenv(env)?.takeIf { it.isNotBlank() }

        val host = value("myagent.it.host", "MYAGENT_IT_HOST") ?: return null
        val user = value("myagent.it.user", "MYAGENT_IT_USER") ?: return null
        val key = value("myagent.it.key", "MYAGENT_IT_KEY") ?: return null
        return ItConfig(
            host = host,
            user = user,
            key = key,
            port = value("myagent.it.port", "MYAGENT_IT_PORT")?.toIntOrNull() ?: 22,
            debugpyPython = value("myagent.it.debugpy", "MYAGENT_IT_DEBUGPY"),
        )
    }

    /**
     * Connect with one retry: the first socket from a cold JVM can lose the race against a
     * tailnet/VPN path coming up, which shows up as a bare connect timeout on an otherwise
     * reachable host.
     */
    private fun connect(cfg: ItConfig): Session {
        var last: Throwable? = null
        repeat(2) { attempt ->
            try {
                val jsch = JSch()
                jsch.addIdentity(cfg.key)
                val session = jsch.getSession(cfg.user, cfg.host, cfg.port)
                session.setConfig("StrictHostKeyChecking", "no")
                session.setConfig("PreferredAuthentications", "publickey")
                session.serverAliveInterval = 30_000
                session.connect(if (attempt == 0) 25_000 else 45_000)
                return session
            } catch (t: Throwable) {
                last = t
                Thread.sleep(1_000)
            }
        }
        throw IllegalStateException("SSH connect to ${cfg.host}:${cfg.port} failed twice", last)
    }

    @Test(timeout = 240_000)
    fun `clangd over ssh answers diagnostics definition references and applies a rename`() = runBlocking {
        val cfg = config()
        Assume.assumeTrue("set MYAGENT_IT_HOST/USER/KEY to run this integration test", cfg != null)
        val session = connect(cfg!!)
        val root = "/tmp/myagent-it-lsp-${System.nanoTime()}"
        val files = RemoteFileClient(session)
        try {
            RemoteExec.run(session, "mkdir -p ${posixShellQuote(root)}")
            val main = "$root/main.c"
            val header = "$root/util.h"
            files.writeText(header, "int add(int a, int b);\n")
            files.writeText(main, "#include \"util.h\"\n\nint main(void) {\n    return add(1, 2);\n}\n")

            val spec = LspSpecs.builtIn.first { it.name == "clangd" }
            val probe = LspSpecs.parseProbeOutput(probeRemote(session, LspSpecs.probeScript(root, listOf(spec))))
            val command = probe["clangd"]
            Assume.assumeTrue("clangd is not installed on the test host", command != null)

            val client = RemoteLspClient(session, root, spec, command!!, files)
            try {
                val init = client.initialize()
                assertTrue("initialize must report capabilities", init.containsKey("capabilities"))

                client.syncDocument(main)
                val diagnostics = client.waitForDiagnostics(main, 25_000)
                assertNotNull("clangd must publish diagnostics for an opened file", diagnostics)

                // definition: `add` on line 4 (1-based) must resolve into util.h
                val mainText = files.readText(main)
                val callLine = mainText.split('\n')[3]
                val callColumn = LspEdits.resolveSymbolColumn(callLine, "add")
                assertNotNull("symbol 'add' must be on the call line: $callLine", callColumn)
                val definitions = client.definition(main, LspPosition(3, callColumn!!), 25_000)
                assertTrue(
                    "definition should point into util.h, got ${definitions.map { it["uri"] }}",
                    definitions.any { it["uri"]?.jsonPrimitive?.contentOrNull?.endsWith("util.h") == true },
                )

                client.syncDocument(header)
                val headerText = files.readText(header)
                val declLine = headerText.split('\n')[0]
                val declColumn = LspEdits.resolveSymbolColumn(declLine, "add")!!
                val references = client.references(header, LspPosition(0, declColumn), 25_000)
                assertTrue(
                    "references should include the call site in main.c, got ${references.map { it["uri"] }}",
                    references.any { it["uri"]?.jsonPrimitive?.contentOrNull?.endsWith("main.c") == true },
                )

                val edit = client.rename(header, LspPosition(0, declColumn), "add2", 25_000)
                val applied = client.applyWorkspaceEdit(edit)
                assertTrue("rename must report applied files, got $applied", applied.isNotEmpty())
                assertTrue("header must be renamed", files.readText(header).contains("add2"))
                assertTrue("call site must be renamed", files.readText(main).contains("add2"))
            } finally {
                client.close()
            }
        } finally {
            runCatching { files.close() }
            runCatching { RemoteExec.run(session, "rm -rf ${posixShellQuote(root)}") }
            runCatching { session.disconnect() }
        }
    }

    @Test(timeout = 240_000)
    fun `debugpy over ssh stops on a breakpoint and evaluates a variable`() = runBlocking {
        val cfg = config()
        Assume.assumeTrue("set MYAGENT_IT_HOST/USER/KEY to run this integration test", cfg != null)
        Assume.assumeTrue(
            "set MYAGENT_IT_DEBUGPY to a python interpreter that has debugpy installed",
            cfg?.debugpyPython != null,
        )
        val session = connect(cfg!!)
        val root = "/tmp/myagent-it-dap-${System.nanoTime()}"
        val files = RemoteFileClient(session)
        val adapter = DapAdapterSpec(
            name = "debugpy",
            command = "${posixShellQuote(cfg.debugpyPython!!)} -m debugpy.adapter",
            fileTypes = listOf(".py"),
        )
        try {
            RemoteExec.run(session, "mkdir -p ${posixShellQuote(root)}")
            val program = "$root/app.py"
            files.writeText(
                program,
                """
                def greet(name):
                    message = "hello " + name
                    return message


                print(greet("world"))
                """.trimIndent() + "\n",
            )

            val client = RemoteDapClient.start(session, root, adapter)
            try {
                client.initialize("debugpy", 25_000)
                // Stop at entry so the breakpoint can be installed before the body runs.
                client.launch(
                    program = program,
                    args = emptyList(),
                    cwd = root,
                    stopOnEntry = true,
                    timeoutMs = 60_000,
                )
                delay(200)
                val breakpoints = client.setBreakpoint(
                    path = program,
                    line = 2,
                    function = null,
                    condition = null,
                    timeoutMs = 20_000,
                )
                val verified = (breakpoints["breakpoints"] as? JsonArray)?.isNotEmpty() == true
                assertTrue("adapter must accept the breakpoint: $breakpoints", verified)

                val outcome = client.resume("continue", client.currentThreadId(), 45_000)
                assertTrue("expected a stop at line 2, got $outcome", outcome is DapRunOutcome.Stopped)

                val threadId = client.lastStop?.get("threadId")?.jsonPrimitive?.intOrNull
                assertNotNull("stopped event must carry a threadId", threadId)
                val frames = client.stackTrace(threadId!!, 5, 20_000)["stackFrames"] as JsonArray
                val top = frames.firstOrNull() as? JsonObject
                assertNotNull("stack_trace must return frames", top)
                assertEquals(2, top!!["line"]?.jsonPrimitive?.intOrNull)
                val frameId = top["id"]?.jsonPrimitive?.intOrNull!!

                val evaluated = client.evaluate("name", frameId, "repl", 20_000)
                // Python's repr quotes strings; accept either quote style.
                val result = evaluated["result"]?.jsonPrimitive?.contentOrNull?.trim('"', '\'')
                assertEquals("world", result)
            } finally {
                runCatching { client.disconnect(terminateDebuggee = true, timeoutMs = 5_000) }
                client.close()
            }
        } finally {
            runCatching { files.close() }
            runCatching { RemoteExec.run(session, "rm -rf ${posixShellQuote(root)}") }
            runCatching { session.disconnect() }
        }
    }
}
