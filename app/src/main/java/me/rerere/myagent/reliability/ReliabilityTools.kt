package me.rerere.myagent.reliability

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * Phase 14 — Reliability bundle, surfaced as an LLM tool so the assistant can drive the
 * bug-reporter from chat without a new Settings page (matches the project's existing
 * chat-driven config pattern).
 *
 * One tool: `generate_bug_report` — side-effecting (writes a file to cache). Builds a
 * redacted ZIP and returns its path. The user / assistant can then attach it to an email,
 * telegram_send_document, or share via intent. Gated by the per-assistant `Reliability`
 * Local Tools toggle.
 */

fun generateBugReportTool(context: Context, builder: BugReportBuilder): Tool = Tool(
    name = "generate_bug_report",
    description = "Build a bug-report ZIP: last ~5000 lines of secret-redacted logcat + app/device/Android version + a README listing what's excluded (conversations, tokens, hosts, memories all stay private). Returns absolute path + content:// URI for ACTION_SEND share. File lives in app cache.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {}, required = emptyList()) },
    needsApproval = { true },
    execute = {
        val zip = builder.build()
        val authority = "${context.packageName}.fileprovider"
        val uri = runCatching {
            FileProvider.getUriForFile(context, authority, zip)
        }.getOrNull()
        val payload = buildJsonObject {
            put("ok", true)
            put("path", zip.absolutePath)
            put("size_bytes", zip.length())
            if (uri != null) put("content_uri", uri.toString())
            put("share_intent_action", Intent.ACTION_SEND)
            put("mime_type", "application/zip")
        }
        listOf(UIMessagePart.Text(payload.toString()))
    },
)
