package me.rerere.myagent.skills

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.myagent.data.ai.tools.AssistantConfigTools
import me.rerere.myagent.data.datastore.SettingsStore
import me.rerere.myagent.data.datastore.getCurrentAssistant
import me.rerere.myagent.data.files.SkillManager
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File

/**
 * On-device proof for the `skill_write` wiring — the half the JVM planner tests cannot see.
 *
 * What this pins down:
 *  - the tool is actually emitted by the `assistant_config` group (no silent registration gap);
 *  - `create` lands a real SKILL.md under `filesDir/skills/<name>/` through
 *    [SkillManager.saveSkillFilesAtomically] (staging + rename), not merely in memory;
 *  - `patch` edits the body on disk while keeping the frontmatter block;
 *  - a second `create` is refused with `skill_exists` instead of clobbering the file.
 *
 * The test writes under a reserved name and deletes it in [cleanup], so it never touches a
 * real skill. `enable` is left false on purpose: the run must not mutate the user's assistant.
 */
@RunWith(AndroidJUnit4::class)
class SkillWriteInstrumentedTest {

    private val skillName = "zz-skill-write-instrumented"

    private val skillsRoot: File
        get() = File(
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "skills",
        )

    private val skillDir: File
        get() = File(skillsRoot, skillName)

    private val skillMd: File
        get() = File(skillDir, "SKILL.md")

    @After
    fun cleanup() {
        skillDir.deleteRecursively()
    }

    private fun runTool(tool: Tool, args: String): String = runBlocking {
        val parts = tool.execute(Json.parseToJsonElement(args).jsonObject)
        parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
    }

    @Test
    fun createThenPatchRoundTripsOnDevice() = runBlocking {
        val koin = GlobalContext.get()
        val skillManager = koin.get<SkillManager>()
        val settingsStore = koin.get<SettingsStore>()
        val settings = settingsStore.settingsFlow.first()
        val assistantId = settings.getCurrentAssistant().id.toString()

        val tools = AssistantConfigTools.createTools(settingsStore, skillManager, assistantId)
        val write = tools.firstOrNull { it.name == "skill_write" }
        assertNotNull(
            "skill_write must be exposed by its tool group; got ${tools.map { it.name }}",
            write,
        )

        // ---- create -------------------------------------------------------
        val created = runTool(
            write!!,
            """{"name":"$skillName","mode":"create","description":"instrumented round trip","content":"## Steps\n\n1. Original."}""",
        )
        assertTrue("create failed: $created", created.contains("\"ok\":true"))
        assertTrue("SKILL.md missing on disk: $created", skillMd.isFile)
        val afterCreate = skillMd.readText()
        assertTrue(afterCreate.contains("description: \"instrumented round trip\""))
        assertTrue(afterCreate.contains("1. Original."))

        // ---- patch --------------------------------------------------------
        val patched = runTool(
            write,
            """{"name":"$skillName","mode":"patch","old_text":"1. Original.","new_text":"1. Patched."}""",
        )
        assertTrue("patch failed: $patched", patched.contains("\"ok\":true"))
        val afterPatch = skillMd.readText()
        assertTrue("patch did not land: $afterPatch", afterPatch.contains("1. Patched."))
        assertTrue(
            "patch dropped the frontmatter description: $afterPatch",
            afterPatch.contains("description: \"instrumented round trip\""),
        )

        // ---- duplicate create is refused ----------------------------------
        val duplicate = runTool(
            write,
            """{"name":"$skillName","mode":"create","description":"dup","content":"x"}""",
        )
        assertTrue("expected skill_exists, got: $duplicate", duplicate.contains("\"skill_exists\""))
        assertTrue(skillMd.readText().contains("1. Patched."))
    }
}
