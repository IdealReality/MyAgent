package me.rerere.myagent.data.datastore

import me.rerere.ai.provider.AICORE_PROVIDER_ID
import me.rerere.ai.provider.LLAMACPP_PROVIDER_ID
import me.rerere.ai.provider.LITERT_PROVIDER_ID
import me.rerere.ai.provider.ProviderSetting
import kotlin.uuid.Uuid

/**
 * 随应用内置的服务商。
 *
 * **故意为空**：这个构建不预置任何服务商。全新安装的列表是空的，用户只添加自己真正在用的
 * 那几个——没有厂商货架、没有推广链接、没有永远填不上 key 的死行。
 *
 * 应用能凭空创建的类型全在 [ProviderSetting.creatableTypes]（「添加服务商」对话框遍历它），
 * 所以清空种子列表不损失任何能力：OAuth 类（Codex / Grok / Gemini）与端侧运行时
 * （AICore / LiteRT / llama.cpp）都能在那里创建。
 *
 * 往这里重新加条目之前要知道的后果：
 * - `PreferencesStore` 的补种流程遍历这个列表，所以以后加进来的默认项会在下一次读取设置时
 *   出现在**每一个**已有安装上。
 * - 仍带着旧预置行的老安装，由 [LEGACY_BUNDLED_PROVIDER_IDS] 做一次性清理——只删用户没动过的行。
 */
val DEFAULT_PROVIDERS: List<ProviderSetting> = emptyList()

/**
 * Codex / Gemini OAuth 回跳要落到固定的一行上：授权回调只知道「登录成功了」，靠这个稳定 id
 * 找回（或在缺失时补建）对应的服务商行。见 `RouteActivity.handleIntent`。
 *
 * 它们不是预置行——用户从没创建过时列表里没有它们；回跳会就地补一行。
 */
val DEFAULT_CODEX_PROVIDER_ID = Uuid.parse("7ce7e322-b995-4b0c-9d48-42e08dcfcdda")
val DEFAULT_GEMINI_OAUTH_PROVIDER_ID = Uuid.parse("2b6c1f84-73ad-4e35-b0c7-1a9e4d5f8c21")

/** 本构建曾预置过的服务商 id。只用于升级时丢掉没被动过的残留行，不再据此创建任何东西。 */
val LEGACY_BUNDLED_PROVIDER_IDS: Set<Uuid> = setOf(
    Uuid.parse("a8d2d463-e8c0-41f2-b89e-f5eb8e716cce"), // My Agent
    Uuid.parse("1eeea727-9ee5-4cae-93e6-6fb01a4d051e"), // OpenAI
    DEFAULT_CODEX_PROVIDER_ID, // Codex
    Uuid.parse("8f3e1d20-4b6a-4c9e-a1f2-9d5c7e0b3a44"), // Grok
    DEFAULT_GEMINI_OAUTH_PROVIDER_ID, // Gemini OAuth
    Uuid.parse("6ab18148-c138-4394-a46f-1cd8c8ceaa6d"), // Gemini
    Uuid.parse("56a94d29-c88b-41c5-8e09-38a7612d6cf8"), // 硅基流动
    Uuid.parse("f099ad5b-ef03-446d-8e78-7e36787f780b"), // DeepSeek
    Uuid.parse("d6c4d8c6-3f62-4ca9-a6f3-7ade6b15ecc3"), // 月之暗面
    Uuid.parse("d5734028-d39b-4d41-9841-fd648d65440e"), // OpenRouter
    Uuid.parse("386e0f29-8228-4512-affe-8fd8add82d88"), // Vercel AI Gateway
    Uuid.parse("f76cae46-069a-4334-ab8e-224e4979e58c"), // 阿里云百炼
    Uuid.parse("3dfd6f9b-f9d9-417f-80c1-ff8d77184191"), // 火山引擎
    Uuid.parse("3bc40dc1-b11a-46fa-863b-6306971223be"), // 智谱AI开放平台
    Uuid.parse("f4f8870e-82d3-495b-9b64-d58e508b3b2c"), // 阶跃星辰
    Uuid.parse("da93779f-3956-48cc-82ef-67bb482eaaf7"), // 302.AI
    Uuid.parse("ef5d149b-8e34-404b-818c-6ec242e5c3c5"), // 腾讯Hunyuan
    Uuid.parse("ff3cde7e-0f65-43d7-8fb2-6475c99f5990"), // xAI
    Uuid.parse("b4deabea-20fb-4101-a74c-65679c7e4754"), // MiniMax (minimaxi)
    Uuid.parse("a2bafe83-eaf8-47bf-a8c7-3dd82d89f637"), // MIMO
    Uuid.parse("b55716c7-e465-4666-a788-4243aab13fb3"), // MiniMax (minimax.io)
    AICORE_PROVIDER_ID,
    LITERT_PROVIDER_ID,
    LLAMACPP_PROVIDER_ID,
)

/** 端侧运行时是单例：界面与本地模型管理都按固定 id 找行，创建时不能换 id。 */
val LOCAL_RUNTIME_PROVIDER_IDS: Set<Uuid> = setOf(
    AICORE_PROVIDER_ID,
    LITERT_PROVIDER_ID,
    LLAMACPP_PROVIDER_ID,
)

/**
 * 已从应用里彻底移除的上游赞助预置（条目里带返利/推广链接）。
 * 老用户由 `PreferencesStore` 一次性清掉，只删没被动过的行。
 */
val REMOVED_SPONSORED_PROVIDER_IDS: Set<Uuid> = setOf(
    Uuid.parse("1b1395ed-b702-4aeb-8bc1-b681c4456953"), // AiHubMix
    Uuid.parse("2a05506f-3a59-450a-a493-33a82bc85a81"), // APIMart
    Uuid.parse("da020a90-f7b3-4c29-b90e-c511a0630630"), // 小马算力
    Uuid.parse("aecf04fd-cb5c-4582-aed2-e8bf393923fd"), // 随想AI网关
    Uuid.parse("afbc54ad-807e-4455-9594-7d7a546356ad"), // MaruCode
    Uuid.parse("53027b08-1b58-43d5-90ed-29173203e3d8"), // AckAI
    Uuid.parse("4da09554-8844-4cc8-a4a9-fe1b2515e91b"), // UnifyLLM
)
