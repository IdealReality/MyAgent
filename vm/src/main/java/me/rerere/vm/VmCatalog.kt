package me.rerere.vm

import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 镜像库：框架内置的是「怎么用镜像」，镜像本身由这里描述的地址下载。
 *
 * 内置条目只保证一件事——不需要用户填任何 URL 就能点一次装好。用户也可以贴一个
 * 自己的 catalog JSON（远程或本地）整体替换，格式就是 [VmCatalog]。
 */
object VmCatalogDefaults {

    /** 内置目录。条目顺序就是 UI 里的展示顺序。 */
    val json: String = """
    {
      "version": 1,
      "images": [
        {
          "id": "android-x86-9.0-r2",
          "name": "Android-x86 9.0-r2",
          "description": "Android 9 (Pie)，x86_64，userdebug 镜像：adb root 直接可用，适合 root shell / 装 APK / 自动化测试。冷启动在 TCG 下以十分钟计。",
          "kind": "android",
          "arch": "x86_64",
          "sizeBytes": 1106100224,
          "files": [
            {
              "url": "https://downloads.sourceforge.net/project/android-x86/Release%209.0/android-x86_64-9.0-r2.iso",
              "fileName": "android-x86_64-9.0-r2.iso",
              "role": "cdrom",
              "mirrors": [
                "https://downloads.sourceforge.net/project/android-x86/Release%209.0/android-x86_64-9.0-r2.iso?use_mirror=jaist",
                "https://downloads.sourceforge.net/project/android-x86/Release%209.0/android-x86_64-9.0-r2.iso?use_mirror=tenet"
              ]
            },
            {
              "url": "",
              "fileName": "kernel",
              "role": "kernel",
              "fromArchive": "android-x86_64-9.0-r2.iso",
              "archivePath": "kernel"
            },
            {
              "url": "",
              "fileName": "initrd.img",
              "role": "initrd",
              "fromArchive": "android-x86_64-9.0-r2.iso",
              "archivePath": "initrd.img"
            }
          ],
          "boot": {
            "machine": "pc",
            "memoryMb": 2048,
            "cpus": 4,
            "cpu": "max",
            "vga": "std",
            "graphics": "vnc",
            "dataDiskMb": 4096,
            "kernelArgs": [
              "root=/dev/ram0",
              "androidboot.hardware=android_x86_64",
              "console=ttyS0",
              "androidboot.console=ttyS0"
            ]
          },
          "guest": {
            "adbPort": 5555,
            "vncPort": 5900,
            "rootMode": "adb_root",
            "consolePrompt": "console:",
            "bootstrap": [
              "ip addr add 10.0.2.15/24 dev wifi_eth",
              "ip route add default via 10.0.2.2 dev wifi_eth",
              "ip rule add pref 20000 lookup main",
              "setprop service.adb.tcp.port 5555",
              "stop adbd; start adbd"
            ],
            "postBoot": [
              "input keyevent KEYCODE_WAKEUP",
              "svc power stayon true"
            ]
          }
        },
        {
          "id": "alpine-virt",
          "name": "Alpine Linux 3.20 (virt)",
          "description": "轻量 Linux，root 免密直登；用来验证 QEMU/VNC/串口整条链路，也适合当通用小工具机。",
          "kind": "linux",
          "arch": "x86_64",
          "files": [
            {
              "url": "https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.20/releases/x86_64/alpine-virt-3.20.3-x86_64.iso",
              "fileName": "alpine-virt-3.20.3-x86_64.iso",
              "role": "cdrom",
              "mirrors": [
                "https://mirrors.ustc.edu.cn/alpine/v3.20/releases/x86_64/alpine-virt-3.20.3-x86_64.iso"
              ]
            }
          ],
          "boot": {
            "machine": "pc",
            "memoryMb": 512,
            "cpus": 2,
            "cpu": "max",
            "vga": "std",
            "graphics": "vnc",
            "dataDiskMb": 0
          },
          "guest": {
            "adbPort": 5555,
            "vncPort": 5900,
            "rootMode": "direct"
          }
        }
      ]
    }
    """.trimIndent()

    private val jsonFormat = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun catalog(): VmCatalog = parse(json)

    fun parse(text: String): VmCatalog = jsonFormat.decodeFromString(VmCatalog.serializer(), text)

    fun encode(catalog: VmCatalog): String = jsonFormat.encodeToString(VmCatalog.serializer(), catalog)
}

/**
 * 文件来源：https(s) 直接下载；file:// 或裸路径读取本地文件（便于从剪贴板/文件导入自定义目录）。
 */
object VmCatalogLoader {
    fun load(location: String): VmCatalog {
        val text = when {
            location.startsWith("http://") || location.startsWith("https://") -> fetch(location)
            location.startsWith("file://") -> File(URL(location).toURI()).readText()
            else -> File(location).readText()
        }
        return VmCatalogDefaults.parse(text)
    }

    private fun fetch(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        try {
            val code = connection.responseCode
            require(code in 200..299) { "Catalog HTTP $code for $url" }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
