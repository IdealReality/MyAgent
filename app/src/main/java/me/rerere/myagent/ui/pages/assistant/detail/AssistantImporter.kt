package me.rerere.myagent.ui.pages.assistant.detail

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.dokar.sonner.ToastType
import com.dokar.sonner.ToasterState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.myagent.data.datastore.SettingsStore
import me.rerere.myagent.data.model.Assistant
import me.rerere.myagent.data.model.InjectionPosition
import me.rerere.myagent.data.model.Lorebook
import me.rerere.myagent.data.model.PromptInjection
import me.rerere.myagent.data.files.FilesManager
import me.rerere.myagent.ui.components.ui.AutoAIIcon
import me.rerere.myagent.ui.context.LocalToaster
import me.rerere.myagent.utils.ImageUtils
import me.rerere.myagent.utils.jsonPrimitiveOrNull
import me.rerere.myagent.R
import org.koin.compose.koinInject

@Composable
fun AssistantImporter(
    modifier: Modifier = Modifier,
    onUpdate: (Assistant) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        SillyTavernImporter(onImport = onUpdate)
    }
}

@Composable
private fun SillyTavernImporter(
    onImport: (Assistant) -> Unit
) {
    val context = LocalContext.current
    val filesManager: FilesManager = koinInject()
    val settingsStore: SettingsStore = koinInject()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var isLoading by remember { mutableStateOf(false) }

    val jsonPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            isLoading = true
            scope.launch {
                try {
                    runCatching {
                        importAssistantFromUri(
                            context = context,
                            uri = uri,
                            onImport = onImport,
                            toaster = toaster,
                            filesManager = filesManager,
                            settingsStore = settingsStore,
                        )
                    }.onFailure { exception ->
                        exception.printStackTrace()
                        toaster.show(exception.message ?: context.getString(R.string.assistant_importer_import_failed))
                    }
                } finally {
                    isLoading = false
                }
            }
        }
    }

    val pngPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            isLoading = true
            scope.launch {
                try {
                    runCatching {
                        importAssistantFromUri(
                            context = context,
                            uri = uri,
                            onImport = onImport,
                            toaster = toaster,
                            filesManager = filesManager,
                            settingsStore = settingsStore,
                        )
                    }.onFailure { exception ->
                        exception.printStackTrace()
                        toaster.show(exception.message ?: context.getString(R.string.assistant_importer_import_failed))
                    }
                } finally {
                    isLoading = false
                }
            }
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = {
                pngPickerLauncher.launch(arrayOf("image/png"))
            },
            enabled = !isLoading
        ) {
            AutoAIIcon(name = "tavern", modifier = Modifier.padding(end = 8.dp))
            Text(text = if (isLoading) stringResource(R.string.assistant_importer_importing) else stringResource(R.string.assistant_importer_import_tavern_png))
        }

        OutlinedButton(
            onClick = {
                jsonPickerLauncher.launch(arrayOf("application/json"))
            },
            enabled = !isLoading
        ) {
            AutoAIIcon(name = "tavern", modifier = Modifier.padding(end = 8.dp))
            Text(text = if (isLoading) stringResource(R.string.assistant_importer_importing) else stringResource(R.string.assistant_importer_import_tavern_json))
        }
    }
}

// region Parsing Strategy

/**
 * 一张酒馆角色卡解析出来的全部内容。
 *
 * [lorebook] 不能挂在 Assistant 里一起落地：世界书必须先写进全局清单拿到稳定 ID，
 * 再把 ID 关联回助手（PreferencesStore 的保存管道会过滤掉悬空引用）。
 */
private data class ImportedCard(
    val assistant: Assistant,
    val lorebook: Lorebook?,
)

private interface TavernCardParser {
    val specName: String
    fun parse(context: Context, json: JsonObject, background: String?): ImportedCard
}

private fun JsonObject.strOrNull(field: String): String? =
    this[field]?.jsonPrimitiveOrNull?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.strList(field: String): List<String> =
    (this[field] as? JsonArray)
        ?.mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull?.takeIf(String::isNotBlank) }
        .orEmpty()

private fun JsonObject.boolOrNull(field: String): Boolean? =
    this[field]?.jsonPrimitiveOrNull?.booleanOrNull

private fun JsonObject.intOrNull(field: String): Int? =
    this[field]?.jsonPrimitiveOrNull?.intOrNull

/**
 * v2 / v3 的字段完全一致（v3 只是多了一组可选扩展字段），共用一套映射。
 *
 * 映射取舍：SillyTavern 的 alternate_greetings 变成多条 presetMessages —— 用户可以在
 * 聊天里换开场白；mes_example 与 post_history_instructions 是「提示词级」内容，拼进
 * systemPrompt 的独立小节，而不是当聊天记录插进去。
 */
private fun buildImportedCard(
    context: Context,
    data: JsonObject,
    background: String?,
): ImportedCard {
    val name = data.strOrNull("name")
        ?: error(context.getString(R.string.assistant_importer_missing_name_field))
    val system = data.strOrNull("system_prompt")
    val description = data.strOrNull("description")
    val personality = data.strOrNull("personality")
    val scenario = data.strOrNull("scenario")
    val example = data.strOrNull("mes_example")
    val postHistory = data.strOrNull("post_history_instructions")

    val prompt = buildString {
        appendLine("You are roleplaying as $name.")
        appendLine()
        if (!system.isNullOrBlank()) {
            appendLine(system)
            appendLine()
        }
        appendLine("## Description of the character")
        appendLine(description ?: "Empty")
        appendLine()
        appendLine("## Personality of the character")
        appendLine(personality ?: "Empty")
        appendLine()
        appendLine("## Scenario")
        appendLine(scenario ?: "Empty")
        if (!example.isNullOrBlank()) {
            appendLine()
            appendLine("## Example dialogue")
            appendLine(example)
        }
        if (!postHistory.isNullOrBlank()) {
            appendLine()
            appendLine("## Additional instructions")
            appendLine(postHistory)
        }
    }

    val greetings = buildList {
        data.strOrNull("first_mes")?.let(::add)
        addAll(data.strList("alternate_greetings"))
    }

    val lorebook = data["character_book"]?.jsonObjectOrNull()?.let { book ->
        val entries = (book["entries"] as? JsonArray).orEmpty().mapNotNull { element ->
            val entry = element.jsonObjectOrNull() ?: return@mapNotNull null
            val content = entry.strOrNull("content") ?: return@mapNotNull null
            val keys = entry.strList("keys") + entry.strList("secondary_keys")
            PromptInjection.RegexInjection(
                name = entry.strOrNull("name")
                    ?: entry.strOrNull("comment")
                    ?: keys.firstOrNull()
                    ?: "entry",
                enabled = entry.boolOrNull("enabled") ?: true,
                priority = entry.intOrNull("insertion_order") ?: 0,
                position = InjectionPosition.AFTER_SYSTEM_PROMPT,
                content = content,
                role = MessageRole.USER,
                keywords = keys.distinct(),
                useRegex = false,
                caseSensitive = entry.boolOrNull("case_sensitive") ?: false,
                scanDepth = 4,
                constantActive = entry.boolOrNull("constant") ?: false,
            )
        }
        if (entries.isEmpty()) null else Lorebook(
            name = "$name 的世界书",
            description = "从酒馆角色卡导入",
            entries = entries,
        )
    }

    return ImportedCard(
        assistant = Assistant(
            name = name,
            presetMessages = greetings.map { UIMessage.assistant(it) },
            systemPrompt = prompt,
            background = background,
        ),
        lorebook = lorebook,
    )
}

private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

private class CharaCardV2Parser : TavernCardParser {
    override val specName: String = "chara_card_v2"

    override fun parse(context: Context, json: JsonObject, background: String?): ImportedCard {
        val data = json["data"]?.jsonObjectOrNull()
            ?: error(context.getString(R.string.assistant_importer_missing_data_field))
        return buildImportedCard(context, data, background)
    }
}

private class CharaCardV3Parser : TavernCardParser {
    override val specName: String = "chara_card_v3"

    override fun parse(context: Context, json: JsonObject, background: String?): ImportedCard {
        val data = json["data"]?.jsonObjectOrNull()
            ?: error(context.getString(R.string.assistant_importer_missing_data_field))
        return buildImportedCard(context, data, background)
    }
}

private val TAVERN_PARSERS: Map<String, TavernCardParser> = listOf(
    CharaCardV2Parser(),
    CharaCardV3Parser()
).associateBy { it.specName }

private fun parseAssistantFromJson(
    context: Context,
    json: JsonObject,
    background: String?,
): ImportedCard {
    val spec = json["spec"]?.jsonPrimitive?.contentOrNull
        ?: error(context.getString(R.string.assistant_importer_missing_spec_field))
    val parser = TAVERN_PARSERS[spec] ?: error(context.getString(R.string.assistant_importer_unsupported_spec, spec))
    return parser.parse(context = context, json = json, background = background)
}

// endregion

private suspend fun importAssistantFromUri(
    context: Context,
    uri: Uri,
    onImport: (Assistant) -> Unit,
    toaster: ToasterState,
    filesManager: FilesManager,
    settingsStore: SettingsStore,
) {
    try {
        val mime = withContext(Dispatchers.IO) { filesManager.getFileMimeType(uri) }
        val (jsonString, backgroundStr) = withContext(Dispatchers.IO) {
            when (mime) {
                "image/png" -> {
                    val result = ImageUtils.getTavernCharacterMeta(context, uri)
                    result.map { base64Data ->
                        val json = String(Base64.decode(base64Data, Base64.DEFAULT))
                        val bg = filesManager.createChatFilesByContents(listOf(uri)).first().toString()
                        json to bg
                    }.getOrElse { throw it }
                }

                "application/json" -> {
                    val json = context.contentResolver.openInputStream(uri)?.bufferedReader()
                        .use { it?.readText() }
                        ?: error(context.getString(R.string.assistant_importer_read_json_failed))
                    json to null
                }

                else -> error(context.getString(R.string.assistant_importer_unsupported_file_type, mime ?: "unknown"))
            }
        }
        val json = Json.parseToJsonElement(jsonString).jsonObject
        val card = parseAssistantFromJson(context = context, json = json, background = backgroundStr)
        val lorebook = card.lorebook
        val assistant = if (lorebook == null) {
            card.assistant
        } else {
            // 铁律：世界书先进全局清单，再把 ID 关联回助手，否则保存管道会丢掉关联。
            settingsStore.update { settings ->
                settings.copy(lorebooks = settings.lorebooks + lorebook)
            }
            card.assistant.copy(lorebookIds = card.assistant.lorebookIds + lorebook.id)
        }
        onImport(assistant)
    } catch (exception: Exception) {
        exception.printStackTrace()
        toaster.show(
            message = exception.message ?: context.getString(R.string.assistant_importer_import_failed),
            type = ToastType.Error
        )
    }
}
