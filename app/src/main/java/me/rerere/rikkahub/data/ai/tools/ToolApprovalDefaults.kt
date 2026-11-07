package me.rerere.rikkahub.data.ai.tools

/**
 * Static knowledge the runtime approval decision is built on.
 *
 * **Blacklist era.** Approval is not a fixed "these tools always ask" white-list baked into
 * the build. Every tool is routed through the runtime decision
 * ([ToolApprovalPolicy.shouldAutoApprove] via `ChatService.isToolAutoApproved`) and the user
 * says which tools must ask by putting them on a **blacklist** (Settings → Tool approvals).
 * By default that blacklist is empty: nothing asks.
 *
 * My Agent 决策（2026-10-30）：上游还有两条不可豁免的规则（[NO_ALWAYS_ALLOW] 硬地板、
 * 所有 `mcp__*` 一律要问），本构建把两者**整体移除**。理由是这个 App 的定位是「能自己干活
 * 的手机助手」：定时任务 / 子代理 / workflow 没有可点确认的人，任何一条强制审核都会变成
 * 永久卡死。危险命令仍由 [HardlineCommandGuard] 直接拒绝执行——那是拒绝，不是审核。
 *
 * [DANGEROUS_TOOLS] is the curated list of side-effecting / privacy-reading tools. It no
 * longer gates anything; it is what the Settings screen offers as a one-tap "add the usual
 * suspects to the blacklist" starting point, so the safe behaviour is one tap away without
 * being forced on anyone.
 *
 * The command-level [HardlineCommandGuard] floor is separate and still unconditional: even a
 * blacklist miss and YOLO cannot push `rm -rf /` through.
 */
object ToolApprovalDefaults {

    /**
     * The `mcp__` namespace prefix `buildMcpToolName` puts on every relayed MCP tool.
     *
     * 上游用它把 MCP 工具整体推到「每次都要问」的位置；本构建不再这么做，前缀仍保留供命名
     * 与过滤（`HardlineCommandGuard` 等）使用。
     */
    const val MCP_TOOL_PREFIX: String = "mcp__"

    /**
     * Curated set of tools worth blacklisting: everything side-effecting (writes to disk,
     * runs shell, controls hardware, posts to a remote service, manipulates UI) plus the
     * privacy-sensitive READS (contacts, sms, call log) — reading PII off the device into an
     * LLM context deserves the same friction as a write. NOT a gate any more; the Settings
     * screen uses it as its "recommended" one-tap selection.
     *
     * If you add a new LLM-callable tool, decide: side-effecting or PII-reading → add it here
     * so users can find it in the picker. A pure read (battery, brightness) does not need an
     * entry — nothing has to be configured for it to run.
     */
    val DANGEROUS_TOOLS: Set<String> = setOf(
        // Shell / arbitrary code execution
        "transcribe_audio_file",  // decodes + sends arbitrary audio files to the recognizer
        "eval_javascript",

        // Shell via Shizuku (shell UID, no root)
        "shizuku_exec",

        // Virtual display: launches apps + injects input via Shizuku shell
        "virtual_display_create",
        "virtual_display_launch",
        "virtual_display_tap",
        "virtual_display_swipe",
        "virtual_display_key",
        "virtual_display_text",

        // Remote shell (SSH)
        "ssh_exec",
        "ssh_exec_saved",
        "ssh_upload",
        "ssh_download",
        "ssh_forget_host_key",
        "save_ssh_host",
        "delete_ssh_host",
        // Agent vault mutations (request_secret is a human-input pause, not listed here)
        "secret_delete",

        // Accounting writes
        "usage_set_prices",  // replaces the whole model price table; every later cost depends on it

        // Filesystem / network writes
        "write_text_file",
        "download_file",
        "scan_media",

        // Cron mutations (read is free; mutate asks)
        "schedule_job",
        "delete_job",
        "pause_job",
        "resume_job",
        "trigger_job_now",
        "get_job_history",

        // UI manipulation through the AccessibilityService
        "tap",
        "long_press",
        "swipe",
        "scroll",
        "set_text",
        "click_node",
        "global_action",
        "launch_app",
        "launch_activity",   // launches a specific activity, same trust footprint as launch_app
        "open_url",          // can dial tel:, draft mailto:, hand a URI to any app
        "open_file",         // ACTION_VIEW on a user-supplied path — same surface as launch_app
        "wake_screen",       // acquires wake lock, turns screen on at night

        // Privacy / hardware actuation
        "take_photo",
        "record_audio",
        "speech_to_text",    // activates the microphone + uploads audio to recognizer
        "verify_fingerprint",
        "share",
        "set_torch",
        "vibrate",
        "set_brightness",
        "set_volume",
        "play_media",
        "stop_media",
        "pause_media",
        "resume_media",
        "seek_media",
        // get_media_status is read-only — no approval needed
        "post_notification",

        // Privacy reads — PII leaves the device into the model's context
        "list_call_log",
        "list_contacts",
        "search_contacts",
        "list_sms_inbox",
        "search_sms",

        // Notification listener side effects (read-only listing is free, mutating is not)
        "dismiss_notification",
        "notification_action_click",
        "notification_reply",       // fires a notification's direct-reply RemoteInput action

        // File manager — all ops are side-effecting or read PII from arbitrary paths
        "list_files",
        "read_file",
        "write_binary_file",
        "delete_file",
        "move_file",
        "copy_file",
        "create_directory",
        "file_info",
        "find_files",
        // Batch file ops (item 5.5) — list-or-glob copy / move / delete. Side-effecting
        // over potentially many paths in one call, so each gets the same approval gate as
        // the single-path file tools.
        "batch_copy",
        "batch_move",
        "batch_delete",

        // Telegram outbound — the bot can DM other chats / change its own config
        "telegram_send_message",
        "telegram_send_photo",
        "telegram_send_document",
        "telegram_set_token",
        "telegram_enable",
        "telegram_disable",
        "telegram_add_whitelist",
        "telegram_remove_whitelist",
        "telegram_set_default_chat",
        "telegram_set_assistant",
        "telegram_set_commands",
        "telegram_delete_commands",

        // MCP control — all side-effecting MCP tools require approval.
        // mcp_add and mcp_update are also flagged with "no always-allow" below because a
        // hostile MCP server can exfiltrate everything the LLM has access to and we want
        // a per-call confirmation each time, not a one-shot blanket grant.
        "mcp_add",
        "mcp_update",
        "mcp_delete",
        "mcp_set_enabled",
        "mcp_set_tool_approval",

        // External Automation Intent API — flipping any of these changes who can fire
        // the assistant from outside the app. Privilege-escalation surface, requires
        // approval. Reads (`external_automation_status`) are free.
        "external_automation_set_enabled",
        "external_automation_add_trusted_package",
        "external_automation_remove_trusted_package",

        // Reliability bundle — generate_bug_report writes a file to disk that includes
        // a redacted logcat dump. The redactor catches the common token shapes; we still
        // gate so the user can review what's being created and decide whether to share.
        "generate_bug_report",

        // Sub-agents — subagent_dispatch spawns an autonomous LLM run with the parent's
        // tool surface. Approval-required so the user sees the task + tools before
        // delegation happens. list / get / cancel are read-only or user-controlling and
        // have no entry here.
        "subagent_dispatch",

        // P2-06b — the roster WRITE tools. Approval-required for the same reason as
        // subagent_dispatch above: a stored expert becomes dispatchable immediately, and
        // deleting one changes what every later `agent` name resolves to, so the user reviews
        // the change before it lands. subagent_list stays read-only and has no entry here.
        "subagent_create",
        "subagent_update",
        "subagent_delete",

        // P2-37 — the model-roster WRITE tools (`model_*`). Approval-required for the same
        // reason as the expert writes above: the roster is what every assistant's model
        // picker and every later generation resolves against, so the user reviews an add,
        // rename or delete before it lands. model_list stays read-only and has no entry here.
        "model_add",
        "model_update",
        "model_delete",

        // Workflows (Phase 12) — every mutator goes through the existing approval flow
        // with a human-readable summary rendered by WorkflowApprovalRenderer. workflow_run
        // fires immediately on approve, with HARDLINE still applied to every action.
        // workflow_list / workflow_get are read-only and have no entry here.
        "workflow_create",
        "workflow_update",
        "workflow_delete",
        "workflow_set_enabled",
        "workflow_run",

        // Skill import (Phase 16) — pulls a markdown / JSON skill from an arbitrary URL
        // (or from raw text the LLM already fetched via ssh / curl / MCP) and installs
        // it. Whatever is in the skill rides along with the assistant's tool surface,
        // so this is privilege-escalation-adjacent. NO_ALWAYS_ALLOW below.
        "skill_install_from_url",
        "skill_install_from_text",

        // JS skills (Phase 18) — run a skill's JavaScript inside a hidden WebView.
        // The script can issue arbitrary network requests on behalf of the user, so
        // every invocation gets per-call approval. Eligible for "Always allow" once a
        // particular skill is trusted (NOT in NO_ALWAYS_ALLOW — the skill's body has
        // already been reviewed at install time).
        "run_js",

        // Native intent tools (Phase 18) — open the system Calendar / Contacts / SMS /
        // Email composer / WiFi settings / Maps app. Each fires a system intent the user
        // finalises in the destination app. Approval gate ensures the user reviews the
        // pre-filled fields before the LLM hands the action off.
        "create_calendar_event",
        "create_contact",
        "send_email_intent",
        "send_sms_intent",
        "open_wifi_settings",
        "show_location_on_map",

        // In-app browser write tools (Phase 21 / Pass 2). The browser can carry auth tokens
        // in cookies, so anything that mutates page state OR runs JS is approval-gated. Read
        // tools (open, current_url, screenshot, get_text, get_dom, get_links, back, forward,
        // wait_for) are NOT in this set — reading text out of a page is the same trust level
        // as taking a screenshot or reading any other LLM context. browser_done is the
        // loop-control sentinel and never side-effects.
        "browser_click",
        "browser_type",
        "browser_scroll",
        "browser_submit",
        "browser_select",
        "browser_press_key",
        "browser_eval_js",
        // Composite click+read added in the token-cost optimisation pass — its click
        // side carries the same trust footprint as plain browser_click, so it
        // inherits the same approval gate.
        "browser_click_and_read",

        // web_fetch (item 1.2) — network egress. A bare HTTP GET/POST can exfiltrate
        // anything the LLM puts in the URL / body / headers, so it gets the same
        // approval gate as every other outbound call.
        "web_fetch",
        // web_extract performs the same outbound GET as web_fetch, so it carries the same
        // trust footprint and inherits the same approval gate.
        "web_extract",

        // Phase 25 — Phase 3 second cut. Every mutating tool is approval-gated; the
        // read-only tools (keystore_verify, keystore_list_keys, list_storage_volumes,
        // list_granted_directories, list_zip_contents) are deliberately NOT in this set.
        "send_sms",                 // sends a real SMS — costs money / leaves the device
        "set_wallpaper",            // changes a visible device setting
        "keystore_generate_key",    // creates a hardware key (NO_ALWAYS_ALLOW below)
        "keystore_sign",            // signs arbitrary data with the user's key
        "keystore_encrypt",         // encrypts with the user's key
        "keystore_decrypt",         // decrypts ciphertext (NO_ALWAYS_ALLOW below)
        "keystore_delete_key",      // destroys a key
        "nfc_read_tag",             // opens a foreground reader session
        "nfc_write_tag",            // writes NDEF to a physical tag (NO_ALWAYS_ALLOW below)
        "grant_directory_access",   // persistent read+write to a whole tree (NO_ALWAYS_ALLOW below)
        "zip_files",                // writes an archive to disk / a granted tree
        "unzip_file",               // writes extracted files to disk / a granted tree

        // agent-keyboard control — every tool that mutates the focused text field or cursor
        // is side-effecting and approval-gated. The two read tools (keyboard_read_field,
        // keyboard_editor_info) are deliberately NOT in this set. No HARDLINE arm is needed:
        // typing a string into a focused field is not shell execution, and agent-keyboard
        // itself refuses password / sensitive fields at the IME boundary.
        "keyboard_type",
        "keyboard_press_key",
        "keyboard_delete",
        "keyboard_clear",
        "keyboard_set_cursor",
        "keyboard_select_range",
    )

    /**
     * My Agent 决策（2026-10-30）——**没有常驻审核**。
     *
     * 上游把一小撮工具（`mcp_add` / `mcp_update` / `eval_javascript` / `skill_install_*` /
     * `browser_eval_js` / keystore / nfc / grant_directory_access）与所有 `mcp__*` 中继工具
     * 放在一个不可豁免的「硬地板」上，每次调用都弹确认。对「一个能自己干活的手机会话」来说
     * 这就是死锁：Mark 一个定时任务、跑一个 workflow、或让子代理自己决定下一步时没人能点确认，
     * 那条路就永远走不通。
     *
     * 因此这一层整体移除：没有任何工具被强制要求审核。用户仍然想管的话，黑名单
     * （[DANGEROUS_TOOLS] 一键加入）与 MANUAL 档位都还在，只是**默认不再拦任何人**。
     * 命令级红线 [HardlineCommandGuard] 不受影响 —— 那不是审核，是直接拒绝执行。
     */
    val NO_ALWAYS_ALLOW: Set<String> = emptySet()

    /**
     * 永远为 false，仅为调用点保留签名（设置页的「强制」徽标、策略函数的默认地板）。
     * 想恢复硬地板就把上面的集合填回去并改这里。
     */
    fun isHardFloor(toolName: String): Boolean = toolName in NO_ALWAYS_ALLOW
}
