package me.rerere.myagent.plugin

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 内置插件是「应用能力」，不是示例文字：它们随 APK 发出去、会被 seed 到用户设备上。
 * 一个写坏的 manifest 只会在真机上表现为「插件列表里一行红字」，所以在这里把每个
 * assets/plugins/<id>/manifest.json 解析 + 校验一遍（含 entry / ui.panel 文件存在性）。
 */
class BundledPluginManifestsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `every bundled plugin manifest parses and validates`() {
        val root = File("src/main/assets/plugins")
        assertTrue("assets/plugins not found at ${root.absolutePath}", root.isDirectory)
        val ids = root.listFiles { file -> file.isDirectory }?.map { it.name }.orEmpty()
        assertTrue("no bundled plugins found", ids.isNotEmpty())

        ids.forEach { id ->
            val dir = File(root, id)
            val raw = File(dir, "manifest.json").readText()
            val manifest = json.decodeFromString(PluginManifest.serializer(), raw)
            assertEquals("directory name must equal manifest id ($id)", id, manifest.id)

            val validation = PluginValidationRules.validate(manifest, dir)
            assertTrue("$id: ${validation.render()}", validation.ok)
            assertTrue(
                "$id declares an unknown permission",
                manifest.permissions.all { it in PluginPermissions.ALL },
            )
        }
    }
}
