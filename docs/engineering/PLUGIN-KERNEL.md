# 插件内核（PLUGIN-KERNEL）

> 立项：2026-11-07 ｜ 目标：让应用的能力边界从「编译期」搬到「运行期」，让内部 LLM 通道可以自己扩展自己。

---

## 0. 决策：为什么不重写

用户最初的诉求是「重构整个 MyAgent，换个更好的内核」。评估结论是**不重写，而是切出一个内核边界**：

- 现有工程是 RikkaHub / Agent-Pure 的深度 fork：`app` 1239 个 Kotlin/Java 源文件、10 个 Gradle 子模块、2290+ 单测、60+ 内置工具、工作流/子代理/浏览器/MCP/Telegram 等一整套能力。
- 「换内核」在 Android 上只有两条路：①换 UI/运行时框架重写（数月，功能全丢）；②把 Compose 换成 WebView 壳（性能与系统集成全丢）。
- 真正缺的**不是运行时框架，而是扩展点**：任何新工具、新提示词、新面板都必须改 Kotlin、重编 APK、重装，模型无法参与。这才是「自己修改自己」被卡住的地方。
- 所以本次交付的是**内核 + 插件层**：内核（Kotlin）只保留「聊天循环 + Provider + 存储 + 权限 + 插件装载」，其余能力逐步以插件形式接入；插件是磁盘上的数据 + JS，不需要重新编译。

一句话：**内核不变，边界改。**

---

## 1. 分层

```
┌───────────────────────────── kernel (APK, Kotlin) ──────────────────────────────┐
│  ChatService / GenerationLoop / Providers / Room / Approval / ToolSurface       │
│  PluginRegistry  ── 解析、热重载、健康度、自动停用                              │
│  PluginJsRuntime ── QuickJS 沙箱 + 声明式能力桥（http / kv / host_tools）        │
└───────────────┬───────────────────────────────────────────┬─────────────────────┘
                │ manifest.json + main.js                    │ 同一份 SPI
        ┌───────▼────────┐                          ┌────────▼────────┐
        │ 插件（磁盘）    │  sample-clock, ...        │ 内置 Kotlin 工具 │
        │ 模型可读写      │                          │ 逐步迁入插件层   │
        └────────────────┘                          └─────────────────┘
```

插件 ≠ 可执行 APK。插件是**数据 + JS**：manifest 声明它提供什么，JS 提供实现。因此它天然可热更新、可回滚、可被模型写入。

---

## 2. 磁盘布局

```
files/plugins/
  <id>/
    manifest.json          声明：id / version / permissions / tools / prompt / ui
    main.js                入口：load 时同步注册工具
    …任意资源文件…
  .state.json              每个插件的 enabled / failures / lastError
  .history/<id>/<epoch>/   写入前快照（保留 5 份，用于回滚）
  .kv/<id>.json            插件私有 KV（storage 权限）
  .seeded                  示例插件只投放一次
```

所有写入都是「快照 → 临时文件 → 原子 rename」。`plugin_rollback` 恢复最近快照。

---

## 3. manifest 契约

```json
{
  "schema_version": 1,
  "id": "weather",
  "name": "Weather",
  "version": "1.0.0",
  "description": "…",
  "entry": "main.js",
  "permissions": ["http", "storage"],
  "prompt": { "text": "…" } | { "file": "PROMPT.md" },
  "tools": [
    {
      "name": "weather_now",
      "description": "…",
      "parameters": { "type": "object", "properties": { "city": {"type": "string"} }, "required": ["city"] },
      "needs_approval": true
    }
  ],
  "ui": { "panel": "panel.html", "title": "Weather" }
}
```

校验规则（`PluginValidationRules`）：

- `id`：`[a-z0-9][a-z0-9._-]{0,63}`，且**必须等于目录名**；`builtin/core/system/host/kernel` 保留。
- `version`：`x.y.z` 形式。
- 工具名：`[a-z][a-z0-9_]{1,63}`，单插件内唯一；单插件 ≤ 40 个工具。
- 路径字段（entry / prompt.file / ui.panel）：禁止绝对路径、盘符、反斜杠、`..`，且必须落在插件目录内。
- 未知权限：**warning** 并丢弃（不授予）。未知字段忽略。
- 任一 error → 该插件不加载，但**不影响其他插件**（错误进入 `PluginSnapshot.errors` 与插件页）。

---

## 4. JS 契约（同步）

```js
plugin.tool('weather_now', function (args, ctx) {
  var r = ctx.http('https://api.example.com/now?city=' + encodeURIComponent(args.city));
  return { text: r.body };            // 或 { text, image_base64 } / { error }
});
```

- 加载时**同步**注册；handler 必须同步（宿主桥是同步的）。返回 Promise 会得到明确错误。
- `ctx`：`log(msg)`、`now()`、`kv.get/set`（storage）、`http(url, {method, headers, body})`（http）、`tool(name, args)`（host_tools）。
- 每次调用一个全新的 QuickJS 上下文：32 MiB 堆 / 512 KiB 栈 / 30s 墙钟；超时只影响本次调用。
- 宿主能力在 **Java 侧**校验 manifest 权限，未声明即结构化报错。

---

## 5. 生命周期与热重载

| 动作 | 何时生效 |
|---|---|
| `plugin_write` / `plugin_create` | **下一轮对话**（registry 每轮比对磁盘签名并重解析） |
| UI「重新加载」/ `plugin_reload` | 立即 |
| 开新会话 | 无需额外动作（每轮已重载） |
| 插件连续失败 3 次 | 自动停用（`failures` 计数），插件页可见原因 |
| 回滚 | `plugin_rollback` 或插件页「回滚」 |

**新鲜度判定**是廉价 token：插件目录内文件的 `(name, mtime, size)` + 状态文件。无变化时不重解析、不重建提示词，保证 per-turn 成本是一次目录遍历。

提示词贡献（`prompt`）进入系统提示词的 **stable 段**（见 `SystemPromptBuilder`），同一插件逐字节稳定，因此不破坏 prompt caching。

---

## 6. 模型自修改闭环

新增 9 个工具（`PluginAdminTools`，恒在工具面）：

| 工具 | 用途 |
|---|---|
| `plugin_list` | 列出插件、启用状态、声明工具、错误/失败次数 |
| `plugin_read` | 读插件内文件 / 列文件 |
| `plugin_write` | 写插件内文件（先快照；写 manifest 自动重校验） |
| `plugin_create` | 新建插件（manifest + entry） |
| `plugin_check` | 空跑：加载入口，报告「声明 vs 实际注册」 |
| `plugin_set_enabled` | 启停（重新启用会清零失败计数） |
| `plugin_delete` | 删除（保留快照） |
| `plugin_rollback` | 恢复最近快照 |
| `plugin_reload` | 强制重扫 |

典型自修改链路：

```
用户：“你能查天气吗”
 → 模型 plugin_list → plugin_create(weather, manifest, main.js)
 → plugin_check → 报「registered: weather_now」
 → 下一轮 plugin_write 修正 → 工具面出现 weather_now → 直接调用
```

内核代码不可写：所有写路径都被 `resolveFile` 限制在 `files/plugins/<id>/` 内。这是刻意的边界——**可扩展 ≠ 可自我提权**。

---

## 7. 迁移路线（内核边界之后）

已经就位：工具面、提示词、热重载、自修改工具、管理页。

下一步按价值排序：

1. **内置工具插件化**：把 `LocalTools` 的 60+ 工具分组迁到「内置 Provider 实现同一 SPI」，让内置与第三方走同一注册路径（行为不变，只是来源统一）。
2. **UI 面板**：`ui.panel` 已在 manifest 里；渲染器用 WebView + 现有 `evaluateJavascriptAsync` 桥加载插件目录内的 HTML。
3. **生命周期钩子**：`hooks.beforeTurn / afterTool` 注入 GenerationLoop，实现上下文改写、结果后处理。
4. **触发器**：复用 `workflow/trigger`，让插件声明 cron/broadcast 触发自己的工具。

---

## 8. 验证

- JVM 单测（29 条）：manifest 校验、路径逃逸、store 快照/回滚/自动停用/KV、registry 装载/缓存/健康度/管理工具契约。
- 真机：插件页「自检」走真实 QuickJS 加载入口并报告注册结果；示例插件 `sample-clock` 首次启动投放一次。
- 回归基线：`testPureUnitTest` 既有失败集合不变（Windows 环境既有 74 条，与本改动无关）。

## 9. 已知限制

- 插件 handler 必须同步：宿主桥同步、QuickJS 无事件循环，异步会显式报错而不是静默挂起。
- 单次调用 30s、HTTP 20s、响应体 512 KiB、KV 单文件、插件目录写入 4 MiB/文件。
- `host_tools` 只能调用**本轮工具面中的内置工具**（插件工具不进入 host 池，避免插件间递归）。
- 面板渲染尚未接入（manifest 字段已预留）。
