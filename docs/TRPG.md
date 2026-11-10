# 用 MyAgent 跑团（TRPG）

手机上跑团缺的从来不是「会讲故事的模型」，而是三件模型自己保证不了的事：**骰子不能编、状态不能忘、模组不能吃书**。
这份文档记录 MyAgent 为此做的改造，以及怎么用。

## 一、现有能力盘点（改造前就有）

| 能力 | 位置 | 对跑团的意义 |
|---|---|---|
| 助手人设（systemPrompt） | `data/model/Assistant.kt:39` | KP 的规则与口径 |
| 世界书 Lorebook（关键词触发注入） | `Assistant.kt:367`（`PromptInjection.RegexInjection`） | 模组设定、NPC 卡、规则速查，提到才注入 |
| 正则注射/替换（前后处理） | `Assistant.kt:238`（`AssistantRegex`） | 统一骰子与台词的渲染格式 |
| 记忆引擎四层 + L1 常驻 | `data/memory/` | 跨会话记住「上次团灭在哪」 |
| 冷记忆（工作区 Markdown + 三工具） | `data/ai/tools/ColdMemoryTools.kt` | 塞整本规则书/模组，按需检索 |
| 上下文压缩（手动/工具/自动） | `ContextCompactionPlanner` | 长局不炸上下文 |
| 插件内核（QuickJS，可写工具） | `plugin/`（manifest + main.js） | 跑团运行时真正的落地处 |
| 角色卡导入（SillyTavern v2/v3、PNG 卡） | `ui/pages/assistant/detail/AssistantImporter.kt` | 直接吃现成角色卡 |
| 子代理 | `subagent/` | 可选：让独立 agent 扮演某个 NPC |

**改造前的缺口**：没有骰子工具（模型会自己编点数）、没有跨轮角色卡、没有场景/线索/时钟，也没有「多角色群聊」（源码里确实不存在 group chat，`Conversation` 只有一个 `assistantId`）。

## 二、本轮交付（已随 APK 内置）

### 1. `trpg` 插件（`app/src/main/assets/plugins/trpg/`）

四个工具，全部 `needs_approval=false`（本地纯计算，跑团时不该每次点确认）：

| 工具 | 作用 |
|---|---|
| `trpg_dice` | 设备 PRNG 掷骰。CoC 7e：目标值 + 奖励/惩罚骰 + 成功等级（大成功/极难/困难/普通/失败/大失败，含 96–100 与 100 的失败带差异）；DnD 5e：d20 + 修正 vs DC、优势/劣势、自然 20/1；通用：`NdM±K`、`NdMkhX`、`N#1d6` |
| `trpg_sheet` | 角色卡就地读写。`delta` 做数值增减并 clamp 到 `[0,max]`（`{"hp":-3}` 是 `hp.cur` 的简写），`patch` 做深合并 |
| `trpg_scene` | campaign/act/location/time、在场 NPC、事实、线索、钟（进度/威胁）。事实保留最近 200 条 |
| `trpg_save` | 命名存档 = 角色卡 + 场景快照；`load` 一次性恢复两者 |

状态落在 `files/plugins/.kv/trpg.json`（插件自带的 `storage` 权限），每个工具调用都是全新 QuickJS 上下文，所以**所有状态都只在 KV 里**，不靠 JS 全局变量。

随机数是自带的 sfc32，播种混入 `ctx.now()` 与持久化的 `rng.counter`（`Math.random()` 在每个新上下文会重新播种，同毫秒内可能相关），取模用拒绝采样消除偏置。

插件自带一段 prompt（`manifest.json` 的 `prompt.text`），写明五条纪律：随机性只能来自工具、状态改动必须落地、场景用 `trpg_scene` 维护、长局用 `trpg_save`、主持不替玩家做决定。它只在跑团语境生效，日常对话不受影响。

### 2. 内置插件补齐机制（`plugin/PluginSamples.kt`）

旧规则是「插件目录为空时才 seed」，意味着**以后往 APK 里加的任何插件都永远装不到已装机的设备上**（目录早被 sample-clock 占住了）。现在改成按插件判断：目录存在就是用户/模型的状态，绝不覆盖；缺失的内置插件视为未安装的应用能力，启动时补上。

副作用要知道：删除内置插件会在下次启动时被装回来。不想用就**禁用**（插件页有开关，`PluginStore.setEnabled` 持久化）。

### 3. 每轮状态快照注入（原生）

`data/ai/transformers/TrpgStateReminderTransformer.kt` + `Assistant.trpgStateInjection` + 助手设置页开关（高级/实验卡片）。

开启后，每轮请求在**最后一条用户消息之前**插一条合成消息：

```
<trpg_state>
场景: 暗夜之门 · 第2幕 · @地下室 · 🕒 03:20 | 在场: 管家莫里斯 | 线索: … | 最近事实: …
钟: 仪式进度 3/6
凯尔: HP 9/12 SAN 42/60 | 状态: 轻伤 | 物品: 手电筒、左轮
最近掷骰: 侦查 目标 55 → 37 困难成功
</trpg_state>
以上是设备侧的只读状态快照……不要在回复里复述本块。
```

- 进的是**易变段**（`injectContextReminder`），stable 前缀照旧可缓存。
- 插件没写过状态就**一个字都不注入**，没开团的会话零成本。
- 同一个开关也决定**工具面**（`data/ai/tools/PluginToolVisibility.kt`）：关闭时 `trpg_*`
  四个工具不进入请求，日常对话不为跑团付费；打开才提供。插件页里禁用插件仍然是最高优先级，
  这里不会把注册表没产出的工具塞回去。
- 之所以需要它：状态只有模型主动调 `trpg_sheet` 才看得见，而长局里它经常忘记调，然后按满血描述一个 3/12 的角色。

## 三、怎么用（三步）

1. **新建一个助手**（比如叫「KP」），提示词粘贴 `docs/trpg-kp-prompt.md` 的全文。
2. 助手设置 → 高级/实验 → 打开 **跑团状态快照**。
3. 开聊：「我要跑《暗夜之门》，角色叫凯尔，恐怖为主，别太硬核」。
   模型应该先 `trpg_scene begin` + `trpg_sheet new`，然后开场。要检定时会调 `trpg_dice` 并给出引用行 `> 🎲 侦查 55 → 63 · 失败`。

想换规则系统就改提示词第一行；想加模组设定，在「世界书」里建条目（关键词触发），或者干脆让助手自己用 `upsert_lorebook` 建。

## 四、还没做的（想更进一步时的改动点）

按价值排序：

1. **骰子消息卡片化**。现在骰子是一段文本。要卡片就识别工具结果里的 `[RNG:trpg_dice]`，在 chat 消息组件里换成带成功等级配色的块。改 `ui/pages/chat/` 的消息渲染，不改数据层。
2. **角色卡面板页**。现在只能靠模型调 `trpg_sheet` 看。加一个读 `.kv/trpg.json` 的只读页面（或底部抽屉），`ui/pages/chat/` 挂入口即可。
3. **多 NPC 分角色渲染**。目前靠 `**名字**：台词` 约定。要做到头像/左右分栏，得在消息模型里带角色标记（`UIMessagePart` 或节点级），属于数据结构改动，影响面比前两项大。
4. **多助手群聊 / 玩家同桌**。`Conversation` 是单 `assistantId`，要支持多个参与者需要新表与新编排，是目前最大的一块。
5. **骰子防伪校验**。模型偶尔会在叙述里写一个没经过工具的点数。可以在出站 transformer 里检测「本轮没有 `trpg_dice` 结果却出现 `d100=` 样式数字」，命中就整段重写一次——但这种闸门会误伤，先靠提示词约束。

## 五、验证

插件逻辑（在开发机上用 Node + 假宿主跑同一份 `main.js`，一次性脚本，未入库）：

- 工具注册齐全；`1d6` 400 次全在 1..6 且六面分布均衡；`3d6+2` 算术自洽；`2d20kh1` 200 次都取较大值；`4#1d6` 分行合计正确；非法表达式（`abc`、`0d6`）返回 error 而非抛异常。
- CoC：600 次 d100 覆盖 1..100，`p(≤10)≈0.093`（期望 0.10）；目标 55 时阈值逐点命中（1 大成功 / 2 极难 / 12 困难 / 28 普通 / 56 失败 / 96 失败 / 100 大失败）；目标 40 时 96 变「大失败」（失败带随目标值移动）；奖励骰与惩罚骰的十位候选数与取值方向正确。
- DnD：`d20+4` 总数恒等；优势输出两骰。
- 角色卡：delta 双向 clamp（`hp -99` → 0，`san +999` → max）、patch 深合并、重名/不存在/坏 JSON 各自报错。
- 场景：begin 写入、NPC/事实/线索/钟；钟加到 max 后标记「已满」；事实超 200 条被截到 200。
- 存档：save → 改卡改场景 → load 后两者都回到存档时刻；list 含备注；delete 生效。
- 跨上下文：把同一份 KV 交给全新上下文，角色卡与场景仍可读（证明状态只依赖 KV）；每次掷骰 `rng.counter` 递增。

原生注入：`app/src/test/java/me/rerere/rikkahub/data/ai/transformers/TrpgStateReminderTransformerTest.kt`（无文件/空状态不注入、坏 JSON 静默失败、满钟标记、超长截断仍闭合）。

真机：装机后插件页出现 `trpg`，聊天里触发掷骰能看到工具结果。
