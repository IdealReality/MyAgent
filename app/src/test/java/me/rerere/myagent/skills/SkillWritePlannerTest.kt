package me.rerere.myagent.skills

import me.rerere.myagent.data.files.SkillFrontmatterParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [SkillWritePlanner] — the pure half of `skill_write`.
 *
 * These pin the four edit verbs, the ambiguity guard behind PATCH, and the frontmatter
 * preservation rule that keeps an agent from stripping `license` / `metadata` / `auto_load`
 * off its own skill while editing the body.
 */
class SkillWritePlannerTest {

    private fun create(
        name: String = "demo-skill",
        description: String? = "Does one thing",
        content: String? = "## Steps\n\n1. Do the thing.",
        files: Map<String, String> = emptyMap(),
    ) = SkillWritePlanner.plan(
        SkillWritePlanner.Request(
            name = name,
            mode = SkillWritePlanner.Mode.CREATE,
            description = description,
            content = content,
            extraFiles = files,
        ),
        existingSkillMd = null,
    )

    private fun ok(outcome: SkillWritePlanner.Outcome): SkillWritePlanner.Outcome.Ok {
        assertTrue("expected Ok, got $outcome", outcome is SkillWritePlanner.Outcome.Ok)
        return outcome as SkillWritePlanner.Outcome.Ok
    }

    private fun err(outcome: SkillWritePlanner.Outcome): SkillWritePlanner.Outcome.Err {
        assertTrue("expected Err, got $outcome", outcome is SkillWritePlanner.Outcome.Err)
        return outcome as SkillWritePlanner.Outcome.Err
    }

    private val existing = """
        ---
        name: demo-skill
        description: "Does one thing"
        license: MIT
        auto_load: true
        metadata:
          author: someone
        ---

        ## Steps

        1. Do the thing.
    """.trimIndent()

    // ------------------------------------------------------------------ create

    @Test
    fun `create writes a spec-shaped frontmatter and the body`() {
        val result = ok(create())

        assertTrue(result.created)
        assertEquals("demo-skill", result.name)
        assertEquals(SkillWritePlanner.Mode.CREATE, result.mode)
        assertTrue(result.skillMd.startsWith("---\n"))
        assertTrue(result.skillMd.contains("name: demo-skill"))
        assertTrue(result.skillMd.contains("description: \"Does one thing\""))
        assertTrue(result.skillMd.trimEnd().endsWith("1. Do the thing."))

        val parsed = SkillFrontmatterParser.parse(result.skillMd)
        assertEquals("demo-skill", parsed["name"])
        assertEquals("Does one thing", parsed["description"])
        assertEquals("## Steps\n\n1. Do the thing.", SkillFrontmatterParser.extractBody(result.skillMd))
    }

    @Test
    fun `create refuses an existing skill and points at the edit verbs`() {
        val collision = err(
            SkillWritePlanner.plan(
                SkillWritePlanner.Request("demo-skill", SkillWritePlanner.Mode.CREATE, "x", "y"),
                existingSkillMd = existing,
            )
        )
        assertEquals("skill_exists", collision.code)
        assertTrue(collision.hint!!.contains("append"))
    }

    @Test
    fun `create normalises a messy description onto one line`() {
        val result = ok(create(description = "  Does\n  several   things  "))
        assertEquals("Does several things", result.description)
    }

    @Test
    fun `create refuses a description over the cap instead of truncating it`() {
        val failure = err(create(description = "d".repeat(SkillWritePlanner.MAX_DESCRIPTION_CHARS + 1)))
        assertEquals("description_too_long", failure.code)
    }

    @Test
    fun `create refuses an invalid name and suggests the normalised one`() {
        val failure = err(create(name = "Demo_Skill"))
        assertEquals("invalid_name", failure.code)
        assertTrue(failure.hint!!.contains("demo-skill"))
    }

    @Test
    fun `create requires content`() {
        assertEquals("content_required", err(create(content = "   ")).code)
    }

    @Test
    fun `create requires a description when there is no existing one`() {
        assertEquals("description_required", err(create(description = null)).code)
    }

    // ----------------------------------------------------------------- replace

    @Test
    fun `replace keeps every frontmatter field it was not asked to change`() {
        val result = ok(
            SkillWritePlanner.plan(
                SkillWritePlanner.Request(
                    name = "demo-skill",
                    mode = SkillWritePlanner.Mode.REPLACE,
                    content = "## Steps\n\n1. Do it differently.",
                ),
                existingSkillMd = existing,
            )
        )

        val parsed = SkillFrontmatterParser.parse(result.skillMd)
        assertEquals("Does one thing", parsed["description"])
        assertEquals("MIT", parsed["license"])
        assertEquals(true, parsed.raw("auto_load"))
        assertEquals("someone", parsed.map("metadata")["author"])
        assertEquals("## Steps\n\n1. Do it differently.", SkillFrontmatterParser.extractBody(result.skillMd))
    }

    @Test
    fun `replace rewrites the description line only when one is supplied`() {
        val result = ok(
            SkillWritePlanner.plan(
                SkillWritePlanner.Request(
                    name = "demo-skill",
                    mode = SkillWritePlanner.Mode.REPLACE,
                    description = "Now does two things",
                    content = "body",
                ),
                existingSkillMd = existing,
            )
        )

        val parsed = SkillFrontmatterParser.parse(result.skillMd)
        assertEquals("Now does two things", parsed["description"])
        assertEquals("MIT", parsed["license"])
        assertEquals(1, Regex("""(?m)^description:""").findAll(result.skillMd).count())
    }

    // ------------------------------------------------------------------ append

    @Test
    fun `append adds a section and leaves the original body intact`() {
        val result = ok(
            SkillWritePlanner.plan(
                SkillWritePlanner.Request(
                    name = "demo-skill",
                    mode = SkillWritePlanner.Mode.APPEND,
                    content = "## Pitfalls\n\nDon't skip step 2.",
                ),
                existingSkillMd = existing,
            )
        )

        val body = SkillFrontmatterParser.extractBody(result.skillMd)
        assertTrue(body.startsWith("## Steps"))
        assertTrue(body.endsWith("## Pitfalls\n\nDon't skip step 2."))
        assertTrue(body.contains("1. Do the thing."))
        assertFalse(result.created)
    }

    // ------------------------------------------------------------------- patch

    @Test
    fun `patch replaces the single matching passage`() {
        val result = ok(
            SkillWritePlanner.plan(
                SkillWritePlanner.Request(
                    name = "demo-skill",
                    mode = SkillWritePlanner.Mode.PATCH,
                    oldText = "1. Do the thing.",
                    newText = "1. Do the thing twice.",
                ),
                existingSkillMd = existing,
            )
        )

        val body = SkillFrontmatterParser.extractBody(result.skillMd)
        assertEquals("## Steps\n\n1. Do the thing twice.", body)
    }

    @Test
    fun `patch with an empty new_text deletes the passage`() {
        val result = ok(
            SkillWritePlanner.plan(
                SkillWritePlanner.Request(
                    name = "demo-skill",
                    mode = SkillWritePlanner.Mode.PATCH,
                    oldText = "\n\n1. Do the thing.",
                ),
                existingSkillMd = existing,
            )
        )

        assertEquals("## Steps", SkillFrontmatterParser.extractBody(result.skillMd))
    }

    @Test
    fun `patch refuses a passage that is not there`() {
        val failure = err(
            SkillWritePlanner.plan(
                SkillWritePlanner.Request(
                    name = "demo-skill",
                    mode = SkillWritePlanner.Mode.PATCH,
                    oldText = "no such line",
                    newText = "x",
                ),
                existingSkillMd = existing,
            )
        )
        assertEquals("patch_target_missing", failure.code)
        assertTrue(failure.hint!!.contains("use_skill"))
    }

    @Test
    fun `patch refuses an ambiguous passage instead of editing the first hit`() {
        val duplicated = existing.replace("1. Do the thing.", "1. Do the thing.\n2. Do the thing.")
        val failure = err(
            SkillWritePlanner.plan(
                SkillWritePlanner.Request(
                    name = "demo-skill",
                    mode = SkillWritePlanner.Mode.PATCH,
                    oldText = "Do the thing.",
                    newText = "Do the work.",
                ),
                existingSkillMd = duplicated,
            )
        )
        assertEquals("patch_target_ambiguous", failure.code)
        assertTrue(failure.detail.contains("2 times"))
    }

    @Test
    fun `patch requires a target`() {
        assertEquals(
            "patch_target_required",
            err(
                SkillWritePlanner.plan(
                    SkillWritePlanner.Request("demo-skill", SkillWritePlanner.Mode.PATCH, newText = "x"),
                    existingSkillMd = existing,
                )
            ).code
        )
    }

    @Test
    fun `patch of identical text is a no-op refusal`() {
        assertEquals(
            "patch_noop",
            err(
                SkillWritePlanner.plan(
                    SkillWritePlanner.Request(
                        "demo-skill",
                        SkillWritePlanner.Mode.PATCH,
                        oldText = "same",
                        newText = "same",
                    ),
                    existingSkillMd = existing,
                )
            ).code
        )
    }

    // ------------------------------------------------------------- existence

    @Test
    fun `edit verbs refuse a skill that does not exist`() {
        for (mode in listOf(
            SkillWritePlanner.Mode.REPLACE,
            SkillWritePlanner.Mode.APPEND,
            SkillWritePlanner.Mode.PATCH,
        )) {
            val failure = err(
                SkillWritePlanner.plan(
                    SkillWritePlanner.Request("ghost", mode, content = "x", oldText = "x", newText = "y"),
                    existingSkillMd = null,
                )
            )
            assertEquals("for $mode", "skill_not_found", failure.code)
        }
    }

    // ------------------------------------------------------------ extra files

    @Test
    fun `extra files are validated and passed through`() {
        val result = ok(create(files = mapOf("scripts/run.sh" to "echo hi")))
        assertEquals("echo hi", result.extraFiles["scripts/run.sh"])
    }

    @Test
    fun `extra files reject traversal and the reserved SKILL md`() {
        assertEquals(
            "invalid_extra_path",
            err(create(files = mapOf("../../etc/passwd" to "x"))).code
        )
        assertEquals(
            "invalid_extra_path",
            err(create(files = mapOf("/abs/path" to "x"))).code
        )
        assertEquals(
            "invalid_extra_path",
            err(create(files = mapOf("SKILL.md" to "x"))).code
        )
    }

    @Test
    fun `extra files reject an oversized file`() {
        val big = "x".repeat(SkillWritePlanner.MAX_EXTRA_FILE_BYTES + 1)
        assertEquals(
            "extra_file_too_large",
            err(create(files = mapOf("data.txt" to big))).code
        )
    }

    // ------------------------------------------------------------------ limits

    @Test
    fun `a body over the skill cap is refused with a pointer at extra files`() {
        // 513 KB of ASCII exceeds MAX_SKILL_MD_BYTES; the hint must offer the split.
        val failure = err(create(content = "x".repeat(SkillWritePlanner.MAX_SKILL_MD_BYTES + 1024)))
        assertEquals("skill_too_large", failure.code)
        assertTrue(failure.hint!!.contains("files"))
    }

    // ------------------------------------------------------------- helpers

    @Test
    fun `countOccurrences counts non-overlapping hits`() {
        assertEquals(2, SkillWritePlanner.countOccurrences("aaaa", "aa"))
        assertEquals(0, SkillWritePlanner.countOccurrences("abc", ""))
        assertEquals(1, SkillWritePlanner.countOccurrences("abc", "bc"))
    }

    @Test
    fun `splitFrontmatter keeps the block and returns the body`() {
        val (block, body) = SkillWritePlanner.splitFrontmatter(existing)
        assertTrue(block!!.startsWith("---"))
        assertTrue(block.trimEnd().endsWith("---"))
        assertTrue(body.startsWith("## Steps"))
    }

    @Test
    fun `a skill without frontmatter is all body`() {
        val (block, body) = SkillWritePlanner.splitFrontmatter("just text")
        assertNull(block)
        assertEquals("just text", body)
    }
}
