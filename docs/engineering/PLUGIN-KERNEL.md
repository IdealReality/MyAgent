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
  "hooks": { "before_turn": "beforeTurn", "after_tool": "afterTool" },
  "ui": {
    "panel": "panel.html",
    "title": "Weather",
    "slots": [
      {
        "where": "composer",
        "type": "chips",
        "title": "天气",
        "items": [
          { "label": "打开面板", "panel": "" },
          { "label": "刷新", "action": "refresh", "icon": "↻" }
        ]
      },
      { "where": "message", "type": "html", "file": "card.html", "height": 92 }
    ]
  }
}
```

### 3.1 `ui.slots`（聊天内界面）

| 字段 | 取值 | 说明 |
|---|---|---|
| `where` | `composer` / `message` | 输入框上方 / 消息下方。其他值 → 校验错误 |
| `type` | `chips` / `html` | 原生按钮行 / 插件目录里的网页 |
| `title` | 文本 | chips 行首前缀 |
| `file` | 相对路径 | `type=html` 的入口，必须存在且不出插件目录 |
| `height` | 24–400 | `type=html` 的渲染高度（dp），越界只警告并 clamp |
| `all_messages` | bool | `where=message` 是否逐条挂；默认只挂最新一条（否则一段长对话同时存在几十个 WebView） |
| `items[]` | `{label, action?, panel?, icon?}` | chips 按钮；`action` 与 `panel` 二选一。`type=html` 也接受 `items[].action`（不渲染，只声明该网页允许调用的函数；`panel` 不允许） |

槽位动作由 `plugin.action(name, fn)` 注册，返回 `{insert?, send?, toast?, panel?}`：
`insert` 填进输入框、`send` 直接发出去、`toast` 弹提示、`panel` 打开某个插件面板（空串 =
本插件）。`type=html` 的页面拿到 `window.MyAgentHost`：

| 方法 | 作用 |
|---|---|
| `theme()` | 主题 JSON（与注入的 CSS 变量同源） |
| `args()` | 当前上下文：`conversation_id` / `message_id` / `role` / `text`（composer 槽位是 `draft`） |
| `call(action, argsJson)` | 跑一个 manifest 声明过的动作，同步返回结果 JSON |
| `insert(text)` / `send(text)` | 填输入框 / 直接发送 |
| `openPanel(id)` | 打开插件面板 |
| `toast(msg)` / `plugin()` | 提示 / 当前插件 id |

### 3.2 `hooks`

| 钩子 | 时机 | 返回值的用途 | 上限 |
|---|---|---|---|
| `before_turn` | 每轮生成前 | 追加进本轮系统提示（volatile 段） | 4 KB / 插件，1.2 s |
| `after_tool` | 每个工具执行完 | **替换**该工具交回模型的文本 | 32 KB，2 s |

没有 `before_tool`：那等于让插件伪造任意工具的入参，绕过工具自己的审批与权限边界。
`after_tool` 只处理「单个纯文本结果」，带图片/附件的多段结果原样通过。

校验规则补充（`PluginValidationRules.validateSlots`）：

- 槽位是**封闭集合**：`where`/`type` 拼错是错误而不是静默忽略（一个不显示的界面最难查）。
- `chips` 必须有 `items`，每项 `label` 非空、`action` 与 `panel` 恰有其一，`action` 必须是
  合法 JS 标识符，`panel` 必须是合法插件 id。
- `html` 必须有 `file` 且文件存在；`height` 越界只警告（会 clamp）；`items[].action` 可选，用于声明该网页允许调用的函数。
- 单插件 ≤ 8 个槽位、每行 ≤ 12 个按钮。


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
- `ctx`：`log(msg)`、`now()`、`kv.get/set`（storage）、`http(url, {method, headers, body})`（http）、`tool(name, args)`（host_tools）、`app.get/set`（app_read/app_write）。
- 除工具外还有两条注册通道：`plugin.hook(name, fn)`（宿主按轮次调用）与 `plugin.action(name, fn)`（用户点槽位按钮时调用）。两者返回 Promise 同样报错。
- 每次调用一个全新的 QuickJS 上下文：32 MiB 堆 / 512 KiB 栈 / 30s 墙钟；超时只影响本次调用。
- 宿主能力在 **Java 侧**校验 manifest 权限，未声明即结构化报错。

---

## 5. 生命周期与热重载

| 动作 | 何时生效 |
|---|---|
| `plugin_write` / `plugin_create` | **下一轮对话**（registry 每轮比对磁盘签名并重解析） |
| 改槽位网页（`card.html` 等） | 消息数变化时重新组合槽位；文件按 `(mtime,size)` 判定，改完下次查询即新内容 |
| 插件文件完全没变 | 槽位列表与卡片内容都命中缓存（同一 List 实例），消息发得再多也不重读磁盘 |
| 契约变更（`hooks` / `slots` 增删） | 下一轮；槽位列表在消息数变化时重查 |
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

已就位：工具面、提示词、热重载、自修改工具、管理页、`ui.panel`、`ui.slots`（composer
chips / message 卡片、含 HTML 槽位）、`hooks.before_turn`、`hooks.after_tool`。

下一步按价值排序：

1. **内置工具插件化**：把 `LocalTools` 的 60+ 工具分组迁到「内置 Provider 实现同一 SPI」，让内置与第三方走同一注册路径（行为不变，只是来源统一）。
2. **触发器**：复用 `workflow/trigger`，让插件声明 cron/broadcast / 通知点击触发自己的动作。
3. **槽位的更多位置**：抽屉、顶栏、设置页 —— 每加一处都要在内核里接一个渲染点，所以按需再加。

---

## 8. 验证

- JVM 单测：manifest 校验（含 `ui.slots` 的 where/type/file/items/height）、钩子名与 `after_tool`、路径逃逸、store 快照/回滚/自动停用/KV、registry 装载/缓存/健康度、槽位查询与动作（未声明即拒、失败不计数）、`after_tool` 替换/空返回/零声明零调用。
- 真机：插件页「自检」走真实 QuickJS 加载入口并报告注册结果；示例插件 `sample-clock` 首次启动投放一次。
- 回归基线：`testPureUnitTest` 既有失败集合不变（Windows 环境既有 74 条，与本改动无关）。

## 9. 已知限制

- 插件 handler 必须同步：宿主桥同步、QuickJS 无事件循环，异步会显式报错而不是静默挂起。
- 单次调用 30s、HTTP 20s、响应体 512 KiB、KV 单文件、插件目录写入 4 MiB/文件。
- `host_tools` 只能调用**本轮工具面中的内置工具**（插件工具不进入 host 池，避免插件间递归）。
- 槽位不参与权限体系：`chips`/`html` 只是界面，能做的只有 manifest 里声明的动作（填字、发送、提示、开面板）。
- 消息槽位默认只挂在**最新一条**消息下；`all_messages=true` 时每条都挂一个 WebView，长对话会明显变重。
- APK 内的 Kotlin/Compose 代码仍然不能热替换：插件只能改「有渲染点的地方」（工具、提示、面板、两种槽位、显示设置白名单）。
