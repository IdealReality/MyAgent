package me.rerere.myagent.skills

import me.rerere.myagent.data.files.SkillFrontmatterParser
import me.rerere.myagent.data.files.SkillNameRules

/**
 * Hermes-style closed learning loop, step 2 — the pure half of `skill_write`.
 *
 * The upstream harnesses that inspired this (Hermes Agent's "skills self-improve during
 * use", omp's `skill://` authoring) all converge on one contract: the agent may persist a
 * *reusable procedure* after a task taught it something, and may revise an existing one
 * later. What they disagree on is the edit vocabulary. This file settles on four verbs
 * that can be expressed without a diff engine:
 *
 *  - [Mode.CREATE]  — a brand-new skill (fails if the directory already has a SKILL.md);
 *  - [Mode.REPLACE] — swap the whole body, keeping the frontmatter block intact;
 *  - [Mode.APPEND]  — add a section at the end of an existing body;
 *  - [Mode.PATCH]   — replace one *uniquely occurring* run of text inside the body.
 *
 * **Why PATCH counts occurrences instead of taking the first hit.** A skill body is
 * prose; "Step 1" appears in every skill that has numbered steps. Silently patching the
 * first match is how a self-editing agent corrupts its own playbook. Ambiguity is a
 * refusal with a recovery hint, not a coin flip.
 *
 * **Why the frontmatter block is preserved byte-for-byte on edits.** A skill may carry
 * `compatibility`, `license`, `metadata`, `allowed-tools`, `auto_load` and the importer's
 * `source-url`. Re-serializing those through a parsed map would drop YAML types (a
 * boolean becomes the string "true") and reorder keys. Only the one `description:` line
 * is ever rewritten, and only when the caller explicitly supplies a new description.
 *
 * Everything here is pure: no IO, no Android, no coroutines. The caller (the
 * `skill_write` tool) reads the current SKILL.md, applies [plan], then writes atomically
 * through [me.rerere.myagent.data.files.SkillManager.saveSkillFilesAtomically].
 */
object SkillWritePlanner {

    /** Same cap SkillManager enforces on reads, applied at write time so we never create one. */
    const val MAX_SKILL_MD_BYTES: Int = 512 * 1024

    /** A one-line frontmatter description longer than this is a paragraph, not a description. */
    const val MAX_DESCRIPTION_CHARS: Int = 200

    /** Per ancillary file cap. A skill may ship scripts/references; it may not ship a dataset. */
    const val MAX_EXTRA_FILE_BYTES: Int = 256 * 1024

    /** Number of ancillary files one call may write. */
    const val MAX_EXTRA_FILES: Int = 20

    const val MAX_EXTRA_PATH_CHARS: Int = 200

    enum class Mode {
        CREATE,
        REPLACE,
        APPEND,
        PATCH;

        companion object {
            /** Case-insensitive parse; returns null for an unknown verb rather than guessing. */
            fun parse(raw: String?): Mode? = when (raw?.trim()?.lowercase()) {
                "create" -> CREATE
                "replace" -> REPLACE
                "append" -> APPEND
                "patch" -> PATCH
                else -> null
            }
        }
    }

    data class Request(
        val name: String,
        val mode: Mode,
        val description: String? = null,
        val content: String? = null,
        val oldText: String? = null,
        val newText: String? = null,
        val extraFiles: Map<String, String> = emptyMap(),
    )

    sealed class Outcome {
        data class Ok(
            /** Directory name == skill name; the caller writes under `skillsDir/<name>/`. */
            val name: String,
            val mode: Mode,
            /** True when this call created the skill (no SKILL.md existed before). */
            val created: Boolean,
            /** Complete SKILL.md text, frontmatter included. */
            val skillMd: String,
            /** Ancillary files to write alongside SKILL.md, validated and ready. */
            val extraFiles: Map<String, String>,
            /** Description the resulting frontmatter carries. */
            val description: String,
            /** Body-only length, for the tool's audit envelope. */
            val bodyChars: Int,
        ) : Outcome()

        data class Err(
            val code: String,
            val detail: String,
            /** Actionable next step for the model; omitted when nothing useful can be said. */
            val hint: String? = null,
        ) : Outcome()
    }

    private val FrontmatterEnd = Regex("""\r?\n---(?:\r?\n|$)""")
    private val DescriptionLine = Regex("""(?m)^description:.*$""")

    /**
     * Decide what [request] would write, given the current SKILL.md text (or null when the
     * skill does not exist yet).
     */
    fun plan(request: Request, existingSkillMd: String?): Outcome {
        val name = request.name.trim()
        if (!SkillNameRules.isValid(name)) {
            val suggestion = SkillNameRules.normalize(name)
            return Outcome.Err(
                code = "invalid_name",
                detail = "'$name' is not a valid skill name (lowercase letters, digits, single hyphens, 1..64 chars, no leading/trailing hyphen).",
                hint = if (suggestion.isNotEmpty()) "Call skill_write again with name='$suggestion'." else null,
            )
        }

        val exists = existingSkillMd != null
        val split = existingSkillMd?.let(::splitFrontmatter)
        val oldFrontmatter = split?.first
        val oldBody = split?.second.orEmpty()

        if (request.mode == Mode.CREATE && exists) {
            return Outcome.Err(
                code = "skill_exists",
                detail = "Skill '$name' already exists (${existingSkillMd.length} chars).",
                hint = "Use mode=append to add a section, mode=replace to rewrite the body, or mode=patch to change one passage.",
            )
        }
        if (request.mode != Mode.CREATE && !exists) {
            return Outcome.Err(
                code = "skill_not_found",
                detail = "No skill named '$name' exists on disk.",
                hint = "Use mode=create to author it.",
            )
        }

        val rawDescription = request.description?.let { desc ->
            desc.trim().takeIf { it.isNotEmpty() }?.replace(Regex("""\s+"""), " ")
        }
        if (rawDescription != null && rawDescription.length > MAX_DESCRIPTION_CHARS) {
            return Outcome.Err(
                code = "description_too_long",
                detail = "description is ${rawDescription.length} chars; the cap is $MAX_DESCRIPTION_CHARS.",
                hint = "Summarize it to one line — the full detail belongs in the body.",
            )
        }

        val oldDescription = oldFrontmatter?.let { SkillFrontmatterParser.parse(it).get("description") }
        val descriptionResult = resolveDescription(rawDescription, oldDescription)
            ?: return Outcome.Err(
                code = "description_required",
                detail = "A new skill needs a one-line description (≤$MAX_DESCRIPTION_CHARS chars); the existing skill has none to carry over.",
            )

        val extraResult = validateExtraFiles(request.extraFiles)
        val extra = when (extraResult) {
            is FilesResult.Err -> return Outcome.Err(extraResult.code, extraResult.detail)
            is FilesResult.Ok -> extraResult.files
        }

        val newBody: String
        val newFrontmatter: String
        when (request.mode) {
            Mode.CREATE, Mode.REPLACE -> {
                val content = request.content?.trim()
                if (content.isNullOrEmpty()) {
                    return Outcome.Err(
                        code = "content_required",
                        detail = "mode=${request.mode.name.lowercase()} needs non-empty markdown `content` for the SKILL.md body.",
                    )
                }
                newBody = content
                // CREATE and REPLACE both build a fresh frontmatter block. REPLACE keeps the
                // old description unless a new one was supplied, but other fields (license,
                // metadata, auto_load…) are editor-owned and are re-emitted only when present.
                newFrontmatter = buildFrontmatter(
                    name = name,
                    description = descriptionResult,
                    carried = oldFrontmatter,
                )
            }

            Mode.APPEND -> {
                val content = request.content?.trim()
                if (content.isNullOrEmpty()) {
                    return Outcome.Err(
                        code = "content_required",
                        detail = "mode=append needs non-empty markdown `content` to add.",
                    )
                }
                newBody = if (oldBody.isBlank()) content else oldBody.trimEnd() + "\n\n" + content
                newFrontmatter = rewriteFrontmatter(oldFrontmatter, name, descriptionResult, request.description)
            }

            Mode.PATCH -> {
                val old = request.oldText
                val new = request.newText.orEmpty()
                if (old.isNullOrEmpty()) {
                    return Outcome.Err(
                        code = "patch_target_required",
                        detail = "mode=patch needs non-empty `old_text` — the exact passage to replace.",
                    )
                }
                if (old == new) {
                    return Outcome.Err(
                        code = "patch_noop",
                        detail = "old_text and new_text are identical; nothing to change.",
                    )
                }
                val occurrences = countOccurrences(oldBody, old)
                when {
                    occurrences == 0 -> return Outcome.Err(
                        code = "patch_target_missing",
                        detail = "old_text was not found in the body of '$name'.",
                        hint = "Read the skill first (use_skill or skill_get_content) and copy the passage exactly, including indentation.",
                    )

                    occurrences > 1 -> return Outcome.Err(
                        code = "patch_target_ambiguous",
                        detail = "old_text occurs $occurrences times in the body of '$name'.",
                        hint = "Include more surrounding context so the passage is unique.",
                    )
                }
                newBody = oldBody.replaceFirst(old, new).trimEnd()
                newFrontmatter = rewriteFrontmatter(oldFrontmatter, name, descriptionResult, request.description)
            }
        }

        val skillMd = if (newFrontmatter.isEmpty()) newBody else newFrontmatter.trimEnd() + "\n\n" + newBody
        val bytes = skillMd.toByteArray(Charsets.UTF_8).size
        if (bytes > MAX_SKILL_MD_BYTES) {
            return Outcome.Err(
                code = "skill_too_large",
                detail = "Resulting SKILL.md is $bytes bytes, over the $MAX_SKILL_MD_BYTES-byte cap.",
                hint = "Keep procedures in SKILL.md and move long reference material into an extra file via `files`.",
            )
        }

        return Outcome.Ok(
            name = name,
            mode = request.mode,
            created = !exists,
            skillMd = skillMd,
            extraFiles = extra,
            description = descriptionResult,
            bodyChars = newBody.length,
        )
    }

    /**
     * Normalizes and validates the description. Returns null when neither the request nor
     * the existing skill supplies one.
     */
    fun resolveDescription(raw: String?, fallback: String?): String? {
        val chosen = raw?.trim()?.takeIf { it.isNotEmpty() } ?: fallback?.trim()?.takeIf { it.isNotEmpty() }
        if (chosen == null) return null
        val singleLine = chosen.replace(Regex("""\s+"""), " ")
        if (singleLine.length > MAX_DESCRIPTION_CHARS) {
            // Refuse rather than truncate: a silently shortened description removes exactly
            // the words the model used to decide whether the skill applies.
            return null
        }
        return singleLine
    }

    /** Pure count of non-overlapping occurrences — the ambiguity check behind PATCH. */
    fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }

    /**
     * Splits `---\nfrontmatter\n---\n\nbody` into (frontmatter block including both fences,
     * body). A file without a leading `---` is all body.
     */
    fun splitFrontmatter(content: String): Pair<String?, String> {
        if (!content.startsWith("---")) return null to content
        val match = FrontmatterEnd.find(content, startIndex = 3) ?: return null to content
        val block = content.substring(0, match.range.last + 1)
        val body = content.substring(match.range.last + 1).trimStart('\r', '\n')
        return block to body
    }

    private fun buildFrontmatter(name: String, description: String, carried: String?): String {
        // Carry over every field the caller is not editing, in original order, by echoing the
        // original lines and only replacing name/description. A skill with no frontmatter gets
        // the minimal two-field block.
        val extraLines = carried?.let(::carriedFieldLines).orEmpty()
        return buildString {
            appendLine("---")
            appendLine("name: $name")
            appendLine("description: \"${escapeYaml(description)}\"")
            extraLines.forEach { appendLine(it) }
            appendLine("---")
        }
    }

    /**
     * Echoes the on-disk frontmatter, replacing the description line only when the caller
     * supplied a new one (and inserting it after `name:` when absent).
     */
    private fun rewriteFrontmatter(
        frontmatter: String?,
        name: String,
        description: String,
        requestedDescription: String?,
    ): String {
        if (frontmatter.isNullOrBlank()) return buildFrontmatter(name, description, carried = null)
        var body = frontmatter.trimEnd()

        // A skill whose frontmatter lost its `name:` line (hand-edited, or imported from a
        // format that only carried a title) would be invisible to name-keyed lookups. Repair
        // it here so a body edit can never leave an unnamed skill behind.
        val nameLine = Regex("""(?m)^name:.*$""")
        if (!nameLine.containsMatchIn(body)) {
            body = body.replaceFirst("---", "---\nname: $name")
        }

        // Description is only rewritten when the caller explicitly supplied one: an
        // append/patch that merely touches the body must not reformat the frontmatter.
        if (requestedDescription == null) return body

        val literal = "description: \"${escapeYaml(description)}\""
        body = if (DescriptionLine.containsMatchIn(body)) {
            // Lambda form: the replacement is data, not a regex template. The string overload
            // would interpret the backslashes escapeYaml just produced as group references.
            body.replace(DescriptionLine) { literal }
        } else {
            // No description line: insert immediately after the name line so the required
            // fields stay together at the top of the block.
            val match = nameLine.find(body) ?: return body
            body.substring(0, match.range.last + 1) +
                "\n" + literal +
                body.substring(match.range.last + 1)
        }
        return body
    }

    /** Frontmatter lines that survive an edit: everything except the top-level name/description. */
    private fun carriedFieldLines(frontmatter: String): List<String> {
        val inner = frontmatter.trim()
            .removePrefix("---").removeSuffix("---")
            .trim('\r', '\n')
        if (inner.isBlank()) return emptyList()
        val out = ArrayList<String>()
        var skippingNested = false
        for (line in inner.lines()) {
            val isTopLevel = line.isNotEmpty() && !line[0].isWhitespace()
            if (isTopLevel) {
                skippingNested = false
                val key = line.substringBefore(':').trim()
                if (key == "name" || key == "description") {
                    skippingNested = true
                    continue
                }
            } else if (skippingNested) {
                // Continuation of a name/description value (block scalar or wrapped text).
                continue
            }
            out.add(line)
        }
        return out
    }

    private fun escapeYaml(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private sealed class FilesResult {
        data class Ok(val files: Map<String, String>) : FilesResult()
        data class Err(val code: String, val detail: String) : FilesResult()
    }

    private fun validateExtraFiles(files: Map<String, String>): FilesResult {
        if (files.isEmpty()) return FilesResult.Ok(emptyMap())
        if (files.size > MAX_EXTRA_FILES) {
            return FilesResult.Err(
                "too_many_files",
                "${files.size} files exceed the $MAX_EXTRA_FILES-file limit per call.",
            )
        }
        val clean = LinkedHashMap<String, String>()
        for ((rawPath, content) in files) {
            val path = rawPath.trim().replace('\\', '/')
            val invalid = path.isEmpty() ||
                path.length > MAX_EXTRA_PATH_CHARS ||
                path.startsWith("/") ||
                path.split('/').any { it == ".." } ||
                path.endsWith("/") ||
                path.equals("SKILL.md", ignoreCase = true)
            if (invalid) {
                return FilesResult.Err(
                    "invalid_extra_path",
                    "'$rawPath' is not a safe relative path inside the skill directory (SKILL.md is reserved).",
                )
            }
            val bytes = content.toByteArray(Charsets.UTF_8).size
            if (bytes > MAX_EXTRA_FILE_BYTES) {
                return FilesResult.Err(
                    "extra_file_too_large",
                    "'$path' is $bytes bytes, over the $MAX_EXTRA_FILE_BYTES-byte per-file cap.",
                )
            }
            clean[path] = content
        }
        return FilesResult.Ok(clean)
    }
}
