package me.rerere.myagent.ui.pages.extensions.vm

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.myagent.R
import me.rerere.myagent.data.repository.VmRepository
import me.rerere.myagent.data.vnc.RfbClient
import me.rerere.myagent.ui.components.nav.BackButton
import me.rerere.vm.VmState
import org.koin.compose.koinInject
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * VM 屏幕页：RFB 拉帧 + 指针注入。
 *
 * 坐标换算在这里做（fit 居中缩放），协议层只接收 guest 像素坐标——两条链路各自单纯，
 * 出错时能立刻判断是画面还是输入。
 */
@Composable
fun VmScreenPage(workspaceId: String, instanceId: String) {
    val repository = koinInject<VmRepository>()
    val scope = rememberCoroutineScope()
    var instance by remember { mutableStateOf<me.rerere.myagent.data.db.entity.VmInstanceEntity?>(null) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var connected by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var text by remember { mutableStateOf("") }
    var generation by remember { mutableStateOf(0) }
    val client = remember { mutableStateOf<RfbClient?>(null) }

    LaunchedEffect(instanceId) {
        instance = repository.getInstance(instanceId)
    }

    LaunchedEffect(instance?.vncPort, generation) {
        val port = instance?.vncPort ?: return@LaunchedEffect
        val rfb = RfbClient(
            host = "127.0.0.1",
            port = port,
            onFrame = { frame, _, _ -> bitmap = frame },
            onClosed = { throwable ->
                connected = false
                if (throwable != null) error = throwable.message ?: throwable::class.java.simpleName
            },
        )
        client.value = rfb
        runCatching { rfb.connect() }
            .onSuccess { connected = true; error = null }
            .onFailure { error = it.message; connected = false }
    }

    DisposableEffect(Unit) {
        onDispose { client.value?.close() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(instance?.name ?: "VM screen") },
                navigationIcon = { BackButton() },
                actions = {
                    TextButton(onClick = { generation += 1 }) { Text("Reconnect") }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.Black)
                    .onSizeChanged { canvasSize = it }
                    .pointerInput(bitmap, canvasSize) {
                        val bmp = bitmap ?: return@pointerInput
                        fun toGuest(offset: Offset): Offset {
                            val scale = min(canvasSize.width.toFloat() / bmp.width, canvasSize.height.toFloat() / bmp.height)
                            if (scale <= 0f) return Offset.Zero
                            val drawnW = bmp.width * scale
                            val drawnH = bmp.height * scale
                            val left = (canvasSize.width - drawnW) / 2f
                            val top = (canvasSize.height - drawnH) / 2f
                            val gx = ((offset.x - left) / scale).coerceIn(0f, bmp.width - 1f)
                            val gy = ((offset.y - top) / scale).coerceIn(0f, bmp.height - 1f)
                            return Offset(gx, gy)
                        }
                        detectTapGestures(
                            onTap = { offset ->
                                val p = toGuest(offset)
                                client.value?.pointer(p.x.roundToInt(), p.y.roundToInt(), 1)
                                client.value?.pointer(p.x.roundToInt(), p.y.roundToInt(), 0)
                            },
                        )
                    }
                    .pointerInput(bitmap, canvasSize) {
                        val bmp = bitmap ?: return@pointerInput
                        var last = Offset.Zero
                        var buttonMask = 0
                        fun toGuest(offset: Offset): Offset {
                            val scale = min(canvasSize.width.toFloat() / bmp.width, canvasSize.height.toFloat() / bmp.height)
                            if (scale <= 0f) return Offset.Zero
                            val left = (canvasSize.width - bmp.width * scale) / 2f
                            val top = (canvasSize.height - bmp.height * scale) / 2f
                            return Offset(
                                ((offset.x - left) / scale).coerceIn(0f, bmp.width - 1f),
                                ((offset.y - top) / scale).coerceIn(0f, bmp.height - 1f),
                            )
                        }
                        detectDragGestures(
                            onDragStart = { offset ->
                                last = offset
                                buttonMask = 1
                                val p = toGuest(offset)
                                client.value?.pointer(p.x.roundToInt(), p.y.roundToInt(), buttonMask)
                            },
                            onDrag = { change, _ ->
                                last = change.position
                                val p = toGuest(last)
                                client.value?.pointer(p.x.roundToInt(), p.y.roundToInt(), buttonMask)
                                change.consume()
                            },
                            onDragEnd = {
                                val p = toGuest(last)
                                buttonMask = 0
                                client.value?.pointer(p.x.roundToInt(), p.y.roundToInt(), buttonMask)
                            },
                            onDragCancel = {
                                buttonMask = 0
                                val p = toGuest(last)
                                client.value?.pointer(p.x.roundToInt(), p.y.roundToInt(), buttonMask)
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                val frame = bitmap
                if (frame != null) {
                    Image(
                        bitmap = frame.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator()
                        Text(
                            if (connected) "Waiting for the first frame…" else "Connecting to VNC…",
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                fun key(command: String) {
                    scope.launch { repository.shell(workspaceId, instanceId, command, 30_000) }
                }
                TextButton(onClick = { key("input keyevent 4") }) { Text("Back") }
                TextButton(onClick = { key("input keyevent 3") }) { Text("Home") }
                TextButton(onClick = { key("input keyevent 187") }) { Text("Recents") }
                OutlinedTextField(
                    value = text,
                    onValueChange = { value ->
                        text = value
                        // 逐字符注入：RFB keysym 对 ASCII 就是字符本身
                        value.lastOrNull()?.let { ch ->
                            client.value?.key(ch.code, true)
                            client.value?.key(ch.code, false)
                        }
                    },
                    singleLine = true,
                    label = { Text("Type") },
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        val rfb = client.value
                        if (rfb != null) {
                            rfb.key(0xFF0D, true)
                            rfb.key(0xFF0D, false)
                        }
                    },
                ) { Text("Enter") }
            }
        }
    }
}
