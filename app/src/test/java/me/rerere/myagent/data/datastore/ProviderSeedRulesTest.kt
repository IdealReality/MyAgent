package me.rerere.myagent.data.datastore

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 升级清理规则。每条用例都对应一个真实踩到过的坑，不是想当然的分支覆盖。
 */
class ProviderSeedRulesTest {

    private val myagentId = Uuid.parse("a8d2d463-e8c0-41f2-b89e-f5eb8e716cce")
    private val moonshotId = Uuid.parse("d6c4d8c6-3f62-4ca9-a6f3-7ade6b15ecc3")

    @Test
    fun `factory row that ships enabled is still swept`() {
        // 月之暗面 在旧 DEFAULT_PROVIDERS 里就是 enabled = true（见 git 历史）。按「没启用」
        // 判定会把它当成用户配过的行，实测升级后它确实残留了下来。
        val row = ProviderSetting.OpenAI(
            id = moonshotId,
            name = "月之暗面",
            enabled = true,
            apiKey = "",
        )

        assertTrue(ProviderSeedRules.sweepUnconfiguredBundled(listOf(row)).isEmpty())
    }

    @Test
    fun `factory row carrying its placeholder model is still swept`() {
        // My Agent 出厂自带一个 auto 占位模型。按「没模型」判定会把它判成已配置。
        val row = ProviderSetting.OpenAI(
            id = myagentId,
            name = "My Agent",
            enabled = false,
            apiKey = "",
            models = listOf(Model(modelId = "auto", displayName = "Auto")),
        )

        assertTrue(ProviderSeedRules.sweepUnconfiguredBundled(listOf(row)).isEmpty())
    }

    @Test
    fun `legacy row with a user API key survives`() {
        val row = ProviderSetting.OpenAI(id = moonshotId, name = "月之暗面", apiKey = "sk-real")

        assertEquals(listOf(row), ProviderSeedRules.sweepUnconfiguredBundled(listOf(row)))
    }

    @Test
    fun `provider the user created is never touched`() {
        // id 不在旧预置集合里：哪怕完全空白也保留，那是用户自己加的行。
        val row = ProviderSetting.OpenAI(id = Uuid.random(), name = "我的中转", apiKey = "")

        assertEquals(listOf(row), ProviderSeedRules.sweepUnconfiguredBundled(listOf(row)))
    }

    @Test
    fun `oauth row counts as configured only after a model listing was pulled`() {
        val fresh = ProviderSetting.GeminiOAuth(id = Uuid.random())
        val loggedIn = ProviderSetting.GeminiOAuth(
            id = Uuid.random(),
            models = listOf(Model(modelId = "gemini-3-pro", displayName = "Gemini 3 Pro")),
        )

        assertTrue(ProviderSeedRules.isUnconfiguredBundledRow(fresh))
        assertFalse(ProviderSeedRules.isUnconfiguredBundledRow(loggedIn))
    }

    @Test
    fun `sponsored shelf rows are swept alongside the legacy seeds`() {
        val sponsored = ProviderSetting.OpenAI(
            id = REMOVED_SPONSORED_PROVIDER_IDS.first(),
            name = "AiHubMix",
        )

        assertTrue(ProviderSeedRules.sweepUnconfiguredBundled(listOf(sponsored)).isEmpty())
    }

    @Test
    fun `order of surviving rows is preserved`() {
        val first = ProviderSetting.OpenAI(id = Uuid.random(), name = "first", apiKey = "k")
        val dead = ProviderSetting.OpenAI(id = moonshotId, name = "月之暗面")
        val last = ProviderSetting.OpenAI(id = Uuid.random(), name = "last", apiKey = "k")

        assertEquals(
            listOf(first, last),
            ProviderSeedRules.sweepUnconfiguredBundled(listOf(first, dead, last)),
        )
    }
}
