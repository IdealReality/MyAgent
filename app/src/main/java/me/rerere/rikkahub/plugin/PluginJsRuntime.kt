package me.rerere.rikkahub.plugin

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.JSObject
import com.whl.quickjs.wrapper.QuickJSContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

private const val TAG = "PluginJsRuntime"

/** Seam between the registry and the sandbox: tests provide a fake executor. */
interface PluginExecutor {
    suspend fun inspect(dir: File, manifest: PluginManifest): PluginInvocation
    suspend fun invoke(
        dir: File,
        manifest: PluginManifest,
        toolName: String,
        argsJson: String,
    ): PluginInvocation
}

data class PluginInvocation(
    val ok: Boolean,
    val text: String? = null,
    val imageBase64: String? = null,
    val logs: String? = null,
    val error: String? = null,
    val registeredTools: List<String> = emptyList(),
    val durationMs: Long = 0L,
)

/**
 * P2-40 — plugin kernel, part 3: the sandbox.
 *
 * Runs a plugin's JS in QuickJS on a throw-away native context per call. Three rules make
 * this safe enough to hand to a model:
 *
 *  1. **Thread affinity.** QuickJS contexts are native and thread-affine; every context is
 *     created, used and destroyed on one short-lived daemon thread (same discipline as
 *     `eval_javascript`). A runaway script therefore cannot wedge the app's dispatchers —
 *     the caller just times out.
 *  2. **Declared capabilities only.** Host functions (`__omp_http`, `__omp_kv_*`,
 *     `__omp_tool`) check the manifest permission set on the Java side before doing any
 *     work. A plugin that did not declare `http` gets a structured error, not a socket.
 *  3. **Synchronous host bridge.** The wrapper's Java→JS bridge is synchronous, so the
 *     plugin-facing API is synchronous too: `ctx.http(...)` blocks the JS thread (with its
 *     own timeout) instead of returning a promise. Plugins are written against that
 *     contract; `async` handlers are rejected with an explicit message rather than being
 *     silently awaited forever.
 */
class PluginJsRuntime(
    private val store: PluginStore,
    private val json: Json,
    private val httpClientProvider: () -> OkHttpClient,
    private val hostToolInvoker: suspend (name: String, argsJson: String) -> String,
) : PluginExecutor {
    companion object {
    private const val PRELUDE = """
            var __omp_registry = Object.create(null);
            var plugin = {
              tool: function (name, fn) { __omp_registry[String(name)] = fn; },
              log: function (message) { __omp_log(String(message)); }
            };
            function __omp_unwrap(envelope) {
              var parsed = JSON.parse(envelope);
              if (!parsed.ok) throw new Error(parsed.error || 'host call failed');
              return parsed.result;
            }
            var __omp_ctx = {
              log: function (message) { __omp_log(String(message)); },
              now: function () { return Date.now(); },
              kv: {
                get: function (key) { return __omp_kv_get(String(key)); },
                set: function (key, value) { __omp_kv_set(String(key), String(value)); }
              },
              http: function (url, options) {
                options = options || {};
                var headers = options.headers ? JSON.stringify(options.headers) : null;
                var body = options.body === undefined || options.body === null ? null : String(options.body);
                return __omp_unwrap(__omp_http(String(options.method || 'GET'), String(url), headers, body));
              },
              tool: function (name, args) {
                return __omp_unwrap(__omp_tool(String(name), JSON.stringify(args || {})));
              }
            };
            function __omp_invoke(name) {
              var fn = __omp_registry[name];
              if (typeof fn !== 'function') throw new Error('tool not registered by plugin: ' + name);
              var args = JSON.parse(__omp_args || '{}');
              var out = fn(args, __omp_ctx);
              if (out && typeof out.then === 'function') {
                throw new Error('plugin tool returned a Promise: handlers must be synchronous');
              }
              return out;
            }
        """
        const val TIMEOUT_MS = 30_000L
        const val HOST_CALL_TIMEOUT_MS = 20_000L
        private const val MAX_HTTP_BODY = 512 * 1024
        private const val MAX_LOGS = 200
    }

    /** Loads the plugin entry and returns the tools it registered, executing nothing. */
    override suspend fun inspect(dir: File, manifest: PluginManifest): PluginInvocation =
        run(dir, manifest, toolName = null, argsJson = "{}")

    /** Invokes one tool declared by [manifest]. */
    override suspend fun invoke(
        dir: File,
        manifest: PluginManifest,
        toolName: String,
        argsJson: String,
    ): PluginInvocation = run(dir, manifest, toolName = toolName, argsJson = argsJson)

    private suspend fun run(
        dir: File,
        manifest: PluginManifest,
        toolName: String?,
        argsJson: String,
    ): PluginInvocation {
        val entry = File(dir, manifest.entry)
        if (!entry.isFile) {
            return PluginInvocation(ok = false, error = "entry '${manifest.entry}' missing")
        }
        val entrySource = runCatching { entry.readText() }.getOrElse { e ->
            return PluginInvocation(ok = false, error = "entry unreadable: ${e.message}")
        }
        val startedAt = System.currentTimeMillis()
        val done = CompletableDeferred<PluginInvocation>()

        Thread {
            val logs = ArrayList<String>()
            var context: QuickJSContext? = null
            try {
                context = QuickJSContext.create()
                context.setMemoryLimit(32 * 1024 * 1024)
                context.setMaxStackSize(512 * 1024)
                context.setEnableStackTrace(true)
                context.setConsole(object : QuickJSContext.Console {
                    override fun log(info: String?) { addLog(logs, "LOG", info) }
                    override fun info(info: String?) { addLog(logs, "INFO", info) }
                    override fun warn(info: String?) { addLog(logs, "WARN", info) }
                    override fun error(info: String?) { addLog(logs, "ERROR", info) }
                })
                installHostBridge(context, manifest, logs)
                context.evaluate(PRELUDE, "omp-plugin-prelude.js")
                context.evaluate(entrySource, manifest.entry)

                val registered = registeredToolNames(context)
                val outcome = if (toolName == null) {
                    PluginInvocation(ok = true, registeredTools = registered)
                } else {
                    invokeTool(context, manifest, toolName, argsJson, registered)
                }
                done.complete(
                    outcome.copy(
                        logs = logs.takeIf { it.isNotEmpty() }?.joinToString("\n"),
                        registeredTools = registered,
                        durationMs = System.currentTimeMillis() - startedAt,
                    )
                )
            } catch (t: Throwable) {
                done.complete(
                    PluginInvocation(
                        ok = false,
                        error = (t.message ?: t.toString()).take(4_000),
                        logs = logs.takeIf { it.isNotEmpty() }?.joinToString("\n"),
                        durationMs = System.currentTimeMillis() - startedAt,
                    )
                )
            } finally {
                runCatching { context?.destroy() }
            }
        }.apply {
            name = "plugin-js-${manifest.id}"
            isDaemon = true
            start()
        }

        return withTimeoutOrNull(TIMEOUT_MS) { done.await() }
            ?: PluginInvocation(
                ok = false,
                error = "plugin '${manifest.id}' exceeded ${TIMEOUT_MS / 1000}s wall clock",
                durationMs = System.currentTimeMillis() - startedAt,
            )
    }

    private fun invokeTool(
        context: QuickJSContext,
        manifest: PluginManifest,
        toolName: String,
        argsJson: String,
        registered: List<String>,
    ): PluginInvocation {
        if (toolName !in manifest.tools.map { it.name }) {
            return PluginInvocation(ok = false, error = "tool '$toolName' is not declared by the manifest")
        }
        if (toolName !in registered) {
            return PluginInvocation(
                ok = false,
                error = "tool '$toolName' is declared but not registered by ${manifest.entry}; " +
                    "call plugin.tool('$toolName', fn) at load time",
            )
        }
        val global = context.getGlobalObject()
        global.setProperty("__omp_args", argsJson)
        val raw = context.evaluate("__omp_invoke(${JsonPrimitive(toolName)})", "omp-plugin-call.js")
        return interpret(raw)
    }

    private fun registeredToolNames(context: QuickJSContext): List<String> = runCatching {
        val names = context.evaluate(
            "Object.keys(__omp_registry).join('\\u0000')"
        )?.toString().orEmpty()
        if (names.isBlank()) emptyList() else names.split('\u0000')
    }.getOrElse { emptyList() }

    private fun interpret(raw: Any?): PluginInvocation {
        if (raw == null) return PluginInvocation(ok = true, text = "(no result)")
        val text = when (raw) {
            is JSObject -> runCatching { raw.context.stringify(raw) }.getOrElse { raw.toString() }
            else -> raw.toString()
        }.toString()

        val parsed = runCatching { json.parseToJsonElement(text) }.getOrNull()
        val obj = parsed as? kotlinx.serialization.json.JsonObject
        if (obj == null) {
            // A handler may return a bare JSON scalar ("ok", 42) or a plain string; unwrap
            // the primitive so the model sees `ok`, not `"ok"`.
            val primitive = (parsed as? JsonPrimitive)?.contentOrNull
            return PluginInvocation(ok = true, text = (primitive ?: text).take(64_000))
        }
        val error = obj["error"]?.jsonPrimitive?.contentOrNull
        if (error != null) {
            return PluginInvocation(ok = false, error = error.take(4_000), text = obj["text"]?.jsonPrimitive?.contentOrNull)
        }
        return PluginInvocation(
            ok = true,
            text = obj["text"]?.jsonPrimitive?.contentOrNull ?: text.take(64_000),
            imageBase64 = obj["image_base64"]?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun installHostBridge(context: QuickJSContext, manifest: PluginManifest, logs: MutableList<String>) {
        val permissions = manifest.permissions.filter { it in PluginPermissions.ALL }.toSet()
        val global = context.getGlobalObject()

        global.setProperty("__omp_log", JSCallFunction { args ->
            addLog(logs, "PLUGIN", args.firstOrNull()?.toString())
            null
        })

        global.setProperty("__omp_kv_get", JSCallFunction { args ->
            if (PluginPermissions.STORAGE !in permissions) {
                hostError("plugin '${manifest.id}' did not declare the 'storage' permission")
            } else {
                store.kvGet(manifest.id, args.firstOrNull()?.toString().orEmpty())
            }
        })

        global.setProperty("__omp_kv_set", JSCallFunction { args ->
            if (PluginPermissions.STORAGE !in permissions) {
                hostError("plugin '${manifest.id}' did not declare the 'storage' permission")
            } else {
                store.kvSet(
                    manifest.id,
                    args.getOrNull(0)?.toString().orEmpty(),
                    args.getOrNull(1)?.toString().orEmpty(),
                )
                null
            }
        })

        global.setProperty("__omp_http", JSCallFunction { args ->
            if (PluginPermissions.HTTP !in permissions) {
                hostError("plugin '${manifest.id}' did not declare the 'http' permission")
            } else {
                httpBridge(
                    method = args.getOrNull(0)?.toString() ?: "GET",
                    url = args.getOrNull(1)?.toString().orEmpty(),
                    headersJson = args.getOrNull(2)?.toString(),
                    body = args.getOrNull(3)?.toString(),
                )
            }
        })

        global.setProperty("__omp_tool", JSCallFunction { args ->
            if (PluginPermissions.HOST_TOOLS !in permissions) {
                hostError("plugin '${manifest.id}' did not declare the 'host_tools' permission")
            } else {
                val name = args.getOrNull(0)?.toString().orEmpty()
                val payload = args.getOrNull(1)?.toString() ?: "{}"
                runCatching {
                    runBlocking {
                        withTimeoutOrNull(HOST_CALL_TIMEOUT_MS) {
                            hostToolInvoker(name, payload)
                        }
                    } ?: hostError("host tool '$name' timed out after ${HOST_CALL_TIMEOUT_MS}ms")
                }.getOrElse { e ->
                    hostError("host tool '$name' failed: ${e.message}")
                }
            }
        })
    }

    private fun httpBridge(method: String, url: String, headersJson: String?, body: String?): String {
        if (url.isBlank() || !(url.startsWith("http://") || url.startsWith("https://"))) {
            return hostError("http url must be absolute http(s)")
        }
        return runCatching {
            val builder = Request.Builder().url(url)
            headersJson?.let { raw ->
                runCatching { json.parseToJsonElement(raw).jsonObject }
                    .getOrNull()
                    ?.forEach { (key, value) -> builder.header(key, value.jsonPrimitive.contentOrNull ?: "") }
            }
            val requestBody = body?.toRequestBody("application/json".toMediaTypeOrNull())
            when (method.uppercase()) {
                "GET" -> builder.get()
                "POST" -> builder.post(requestBody ?: "".toRequestBody("text/plain".toMediaTypeOrNull()))
                "PUT" -> builder.put(requestBody ?: "".toRequestBody("text/plain".toMediaTypeOrNull()))
                "DELETE" -> builder.delete(requestBody)
                "PATCH" -> builder.patch(requestBody ?: "".toRequestBody("text/plain".toMediaTypeOrNull()))
                else -> return hostError("unsupported http method '$method'")
            }
            val call = httpClientProvider().newCall(builder.build())
            call.timeout().timeout(HOST_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            call.execute().use { response ->
                val text = response.body.string().take(MAX_HTTP_BODY)
                buildJsonObject {
                    put("ok", true)
                    put("result", buildJsonObject {
                        put("status", response.code)
                        put("body", text)
                    })
                }.toString()
            }
        }.getOrElse { e ->
            Log.w(TAG, "plugin http failed", e)
            hostError("http failed: ${e.message}")
        }
    }

    private fun hostError(message: String): String = buildJsonObject {
        put("ok", false)
        put("error", message.take(2_000))
    }.toString()

    private fun addLog(logs: MutableList<String>, level: String, message: String?) {
        if (logs.size < MAX_LOGS) logs.add("[$level] ${message.orEmpty().take(2_000)}")
    }

}
