# Virtual machines in a workspace

A workspace is not limited to its PRoot Linux rootfs. It can additionally host whole
operating systems running under QEMU **inside the same PRoot**, and the agent can drive
them (`vm_shell`, `vm_push`, screenshots, APK installs, input injection).

Two layers, deliberately separated:

| Layer | Ships with the APK | Lives on disk |
|---|---|---|
| **Framework** (`:vm` + `VmRepository` + tools + UI) | yes | — |
| **Content** (OS images, kernel, initrd, data disk) | no | downloaded from a catalog |

Adding another OS is a catalog entry, not code.

## What the framework does

1. **Catalog** — `VmCatalogDefaults` ships two entries (Android-x86 9.0-r2, Alpine virt).
   A user can point the VM screen at any URL serving the same JSON shape (`VmCatalogLoader`).
2. **Install** — `VmInstaller` downloads each file with HTTP `Range` resume into
   `files/vm/<instanceId>/`, verifies `sha256` when declared, supports local
   `file://`/absolute-path sources, and creates the persistent `data.img`.
3. **Engine provisioning** — QEMU and `adb` are installed with `apt` inside the workspace
   rootfs on first start (`QemuProvisioner`, `VmRepository.ensureTool`).
4. **Boot** — `QemuEngine` builds the command line: TCG (no KVM for app processes),
   `-vnc` on loopback, `hostfwd` for adb, `-serial unix:...` for the console.
5. **Bootstrap** — adb only becomes reachable after the guest configures its own network.
   The catalog's `guest.bootstrap` commands are typed into the serial console until
   `guest.consolePrompt` shows up; then `adb root` and `guest.postBoot` run.
6. **Connect** — agent tools (`vm_shell`, `vm_push`, `vm_pull`, `vm_screencap`,
   `vm_install_apk`, `vm_input`, `vm_ui_dump`, `vm_logcat`) and the in-app screen page
   (a minimal RFB client: `data/vnc/RfbClient.kt`).

## Layout

```
files/workspaces/<workspaceRoot>/files/vm/<instanceId>/
  android-x86_64-9.0-r2.iso   downloaded image
  kernel / initrd.img         extracted from the ISO (fromArchive entries)
  data.img                    persistent /data
  serial.sock                 QEMU serial console (drives bootstrap)
  qemu.pid
```

## Catalog entry

```json
{
  "id": "android-x86-9.0-r2",
  "kind": "android",
  "arch": "x86_64",
  "files": [
    {"url": "https://…/android-x86_64-9.0-r2.iso", "fileName": "…iso", "role": "cdrom"},
    {"url": "", "fileName": "kernel", "role": "kernel",
     "fromArchive": "…iso", "archivePath": "kernel"},
    {"url": "", "fileName": "initrd.img", "role": "initrd",
     "fromArchive": "…iso", "archivePath": "initrd.img"}
  ],
  "boot": {"memoryMb": 2048, "cpus": 4, "dataDiskMb": 4096,
           "kernelArgs": ["root=/dev/ram0", "console=ttyS0", "androidboot.console=ttyS0"]},
  "guest": {"adbPort": 5555, "vncPort": 5900, "rootMode": "adb_root",
            "consolePrompt": "console:", "bootstrap": ["…"], "postBoot": ["…"]}
}
```

`role` decides how a file becomes QEMU arguments: `cdrom` → `-cdrom`, `disk` →
`-drive if=virtio`, `kernel`/`initrd` → `-kernel`/`-initrd` (+ `boot.kernelArgs` as
`-append`). `fromArchive` means "extract from another downloaded file with `bsdtar`"
(fetched on demand) — that is how Android-x86 gets a serial console without shipping a
second kernel binary.

## Hard-won facts (Android 17 device, 2026-11)

- **TCG works inside an app process.** QEMU's JIT executable memory is allowed in the
  `runas_app` SELinux domain; no KVM is available, so everything is software emulation.
- **apt needs `-o APT::Sandbox::User=root`.** Otherwise `dpkg` fails with
  `mkdtemp of /tmp/apt-dpkg-install-* failed` inside PRoot.
- **`5555` is usually taken on a phone** (wireless adb). Host ports are allocated with
  `pickPort()` and persisted with the instance.
- **`-cdrom` boot gives no serial console**; `-kernel`/`-initrd` boot does. Bootstrap
  therefore requires the extracted kernel path.
- **Android-x86's `wifi_eth` has no IPv4 until configured**: `ip addr add 10.0.2.15/24`,
  default route via `10.0.2.2`, and `ip rule add pref 20000 lookup main` (Android's
  netd routing tables otherwise end with `unreachable`).
- **Display starts powered off**; `input keyevent KEYCODE_WAKEUP` + `svc power stayon true`
  is required before `screencap`/VNC shows anything but white.
- TCG emulation of x86_64 on arm64 is slow: expect boot times in the tens of minutes and
  single-digit frame rates. This is hardware, not a bug.
