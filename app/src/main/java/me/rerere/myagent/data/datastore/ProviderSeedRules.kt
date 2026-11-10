package me.rerere.myagent.data.datastore

import me.rerere.ai.provider.ProviderSetting
import kotlin.uuid.Uuid

/**
 * 升级清理规则：哪些**旧预置**服务商行还停在出厂状态，可以一次性删掉。
 *
 * 现在的构建不预置任何服务商（见 [DEFAULT_PROVIDERS]），所以这段只在从旧版本升上来时跑一次。
 * 抽成对象是为了能在没有 DataStore 的单元测试里直接验证判据。
 *
 * ## 判据不能是「没启用 / 没模型」
 *
 * 这是实测踩出来的：出厂预置里 月之暗面 的 `enabled` 就是 `true`，My Agent 自带一个
 * `auto` 占位模型。按「没启用、没模型」判断，这两行都会被当成「用户配过」而永远留在列表里——
 * 删空预置的第一版实现正是这样，装到手机上列表里仍有 2 行。
 *
 * 所以按每种类型的**用户配置证据**判断。宁可漏删一行（用户自己删得掉），也不误删一行
 * 带走别人的 API Key。
 */
object ProviderSeedRules {

    fun isUnconfiguredBundledRow(provider: ProviderSetting): Boolean = when (provider) {
        // key 型：只有 API Key 是用户填的。模型清单不是证据——出厂行自带占位模型，
        // 而 key 为空的行本来也用不了。
        is ProviderSetting.OpenAI -> provider.apiKey.isBlank()
        is ProviderSetting.Google -> provider.apiKey.isBlank()
        is ProviderSetting.Claude -> provider.apiKey.isBlank()
        // OAuth 型：登录成功后远端模型目录会被写进 models，空 = 从没登录过。
        is ProviderSetting.Codex,
        is ProviderSetting.Grok,
        is ProviderSetting.GeminiOAuth,
        -> provider.models.isEmpty()
        // AICore 的 models 是随行附带的合成条目，不能当证据，只看用户有没有启用它。
        is ProviderSetting.AICore -> !provider.enabled
        is ProviderSetting.LiteRtLocal,
        is ProviderSetting.LlamaCppLocal,
        -> !provider.enabled && provider.models.isEmpty()
    }

    /**
     * 一次性清理：丢掉「旧预置 id」并且「没被用户配过」的行。
     *
     * 顺序与其余行原样保留；被用户配置过的旧预置行留在原位（他可能一直在用）。
     */
    fun sweepUnconfiguredBundled(
        stored: List<ProviderSetting>,
        legacyIds: Set<Uuid> = LEGACY_BUNDLED_PROVIDER_IDS,
        sponsoredIds: Set<Uuid> = REMOVED_SPONSORED_PROVIDER_IDS,
    ): List<ProviderSetting> = stored.filterNot { provider ->
        (provider.id in legacyIds || provider.id in sponsoredIds) &&
            isUnconfiguredBundledRow(provider)
    }
}
