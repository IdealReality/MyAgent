package me.rerere.myagent.plugin

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

    /**
     * Runs a lifecycle hook registered by the plugin (`plugin.hook(name, fn)`).
     *
     * Default: report "unsupported" instead of throwing, so a fake executor in a test that
     * predates hooks keeps compiling and the registry can degrade quietly.
     */
    suspend fun invokeHook(
        dir: File,
        manifest: PluginManifest,
        hookName: String,
        argsJson: String,
    ): PluginInvocation = PluginInvocation(ok = false, error = "hooks are not supported by this executor")

    /**
     * 跑一个由界面触发的动作（`plugin.action(name, fn)`）。
     *
     * 与钩子的区别是「谁触发」：钩子是宿主按轮次自动跑，动作是用户点了槽位里的按钮。
     * 两条通道分开注册、分开调用，钩子因此永远拿不到界面专用的返回值。
     */
    suspend fun invokeAction(
        dir: File,
        manifest: PluginManifest,
        actionName: String,
        argsJson: String,
    ): PluginInvocation = PluginInvocation(ok = false, error = "actions are not supported by this executor")
}

data class PluginInvocation(
    val ok: Boolean,
    val text: String? = null,
    val imageBase64: String? = null,
    val logs: String? = null,
    val error: String? = null,
    val registeredTools: List<String> = emptyList(),
    /** 入口里 `plugin.action(name, fn)` 注册过的动作名（自检用）。 */
    val registeredActions: List<String> = emptyList(),
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
    /**
     * 应用状态读写面（`ctx.app`）。null = 当前构建没有接数据层，插件拿到明确的错误，
     * 而不是一个看起来能用、实际静默失败的 API。
     */
    private val appHost: PluginAppHost? = null,
) : PluginExecutor {
    companion object {
    private const val PRELUDE = """
            var __omp_registry = Object.create(null);
            var __omp_hooks = Object.create(null);
            var __omp_actions = Object.create(null);
            var plugin = {
              tool: function (name, fn) { __omp_registry[String(name)] = fn; },
              hook: function (name, fn) { __omp_hooks[String(name)] = fn; },
              action: function (name, fn) { __omp_actions[String(name)] = fn; },
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
              },
              app: {
                get: function (op, args) {
                  return __omp_unwrap(__omp_app_read(String(op), JSON.stringify(args || {})));
                },
                set: function (op, args) {
                  return __omp_unwrap(__omp_app_write(String(op), JSON.stringify(args || {})));
                }
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
            function __omp_invoke_hook(name) {
              var fn = __omp_hooks[name];
              if (typeof fn !== 'function') return '';
              var args = JSON.parse(__omp_hook_args || '{}');
              var out = fn(args, __omp_ctx);
              if (out && typeof out.then === 'function') {
                throw new Error('plugin hook returned a Promise: hooks must be synchronous');
              }
              return out === undefined || out === null ? '' : String(out);
            }
            function __omp_invoke_action(name) {
              var fn = __omp_actions[name];
              if (typeof fn !== 'function') throw new Error('action not registered by plugin: ' + name);
              var args = JSON.parse(__omp_action_args || '{}');
              var out = fn(args, __omp_ctx);
              if (out && typeof out.then === 'function') {
                throw new Error('plugin action returned a Promise: actions must be synchronous');
              }
              if (out === undefined || out === null) return '{}';
              return typeof out === 'string' ? JSON.stringify({ text: out }) : JSON.stringify(out);
            }
            function __omp_action_names() {
              return Object.keys(__omp_actions).join(String.fromCharCode(0));
            }
        """
        const val TIMEOUT_MS = 30_000L
        const val HOST_CALL_TIMEOUT_MS = 20_000L
        private const val MAX_HTTP_BODY = 512 * 1024
        private const val MAX_LOGS = 200

        /**
         * 钩子/动作返回值的硬上限。这里是「别让一个插件把内存吃光」的兜底；真正的业务
         * 上限按用途更小（`before_turn` 4 KB 进提示、`after_tool` 32 KB 进工具结果），
         * 由 [PluginRegistry] 现场截断 —— 运行时不知道钩子名对应哪个用途。
         */
        private const val MAX_HOOK_CHARS = 64_000
        private const val MAX_ACTION_CHARS = 8_000
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

    /**
     * Runs a hook the plugin registered with `plugin.hook(name, fn)`.
     *
     * The hook name comes from the manifest (`hooks.before_turn`), not from the model, so a
     * plugin can only call its own functions.
     */
    override suspend fun invokeHook(
        dir: File,
        manifest: PluginManifest,
        hookName: String,
        argsJson: String,
    ): PluginInvocation = run(dir, manifest, toolName = null, argsJson = argsJson, hookName = hookName)

    /**
     * Runs an action the plugin registered with `plugin.action(name, fn)`.
     *
     * Only the host calls this, and only for an action a manifest slot actually names —
     * the registry checks that before it gets here.
     */
    override suspend fun invokeAction(
        dir: File,
        manifest: PluginManifest,
        actionName: String,
        argsJson: String,
    ): PluginInvocation = run(dir, manifest, toolName = null, argsJson = argsJson, actionName = actionName)

    private suspend fun run(
        dir: File,
        manifest: PluginManifest,
        toolName: String?,
        argsJson: String,
        hookName: String? = null,
        actionName: String? = null,
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

                // 列注册表要额外跑两次 JS（拼 key 列表）。钩子和槽位动作都不需要它：
                // 它们由 manifest 点名调用，跑得越多越该省这一步。
                val inspecting = hookName == null && actionName == null
                val registered = if (inspecting) registeredToolNames(context) else emptyList()
                val registeredActions = if (inspecting && toolName == null) {
                    registeredActionNames(context)
                } else {
                    emptyList()
                }
                val outcome = when {
                    hookName != null -> runHook(context, hookName, argsJson)
                    actionName != null -> runAction(context, actionName, argsJson)
                    toolName == null -> PluginInvocation(ok = true, registeredTools = registered)
                    else -> invokeTool(context, manifest, toolName, argsJson, registered)
                }
                done.complete(
                    outcome.copy(
                        logs = logs.takeIf { it.isNotEmpty() }?.joinToString("\n"),
                        registeredTools = registered,
                        registeredActions = registeredActions,
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

    /**
     * 钩子的返回值就是它要追加进本轮提示的文本。
     *
     * 未注册 → 空字符串（不是错误）：插件声明了钩子但这次没注册，等价于「这轮没话说」，
     * 没必要把它记成故障。
     */
    private fun runHook(
        context: QuickJSContext,
        hookName: String,
        argsJson: String,
    ): PluginInvocation {
        val global = context.getGlobalObject()
        global.setProperty("__omp_hook_args", argsJson)
        val raw = context.evaluate(
            "__omp_invoke_hook(${JsonPrimitive(hookName)})",
            "omp-plugin-hook.js",
        )
        return PluginInvocation(ok = true, text = raw?.toString().orEmpty().take(MAX_HOOK_CHARS))
    }

    /**
     * 动作的返回值是一段 JSON（`{insert?, send?, toast?, panel?, text?}`）。
     *
     * 这里**不解析也不校验**：解析与白名单在 [PluginRegistry.runSlotAction] 里，因为那里
     * 才有插件目录与 manifest，能判断 `panel` 指向的插件是否真的存在。
     */
    private fun runAction(
        context: QuickJSContext,
        actionName: String,
        argsJson: String,
    ): PluginInvocation {
        val global = context.getGlobalObject()
        global.setProperty("__omp_action_args", argsJson)
        val raw = context.evaluate(
            "__omp_invoke_action(${JsonPrimitive(actionName)})",
            "omp-plugin-action.js",
        )
        return PluginInvocation(ok = true, text = raw?.toString().orEmpty().take(MAX_ACTION_CHARS))
    }

    private fun registeredActionNames(context: QuickJSContext): List<String> = runCatching {
        val names = context.evaluate("__omp_action_names()")?.toString().orEmpty()
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

        // 应用状态：读要 app_read，写要 app_write。两者都走 PluginAppHost 的白名单实现，
        // 插件拿不到任何未登记为「可写」的字段。
        global.setProperty("__omp_app_read", JSCallFunction { args ->
            when {
                PluginPermissions.APP_READ !in permissions ->
                    hostError("plugin '${manifest.id}' did not declare the 'app_read' permission")
                appHost == null -> hostError("app state is not available in this build")
                else -> appHost.read(
                    args.getOrNull(0)?.toString().orEmpty(),
                    args.getOrNull(1)?.toString() ?: "{}",
                )
            }
        })

        global.setProperty("__omp_app_write", JSCallFunction { args ->
            when {
                PluginPermissions.APP_WRITE !in permissions ->
                    hostError("plugin '${manifest.id}' did not declare the 'app_write' permission")
                appHost == null -> hostError("app state is not available in this build")
                else -> appHost.write(
                    args.getOrNull(0)?.toString().orEmpty(),
                    args.getOrNull(1)?.toString() ?: "{}",
                )
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
