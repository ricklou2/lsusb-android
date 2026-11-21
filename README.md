# LSUSB For Android

**Languages:** [English](#english) | [简体中文](#简体中文) | [繁體中文](#繁體中文)

---

## English

A graphical Android front-end for the static **lsusb** (usbutils 017 + libusb 1.0.27, with `usb.ids` embedded). The APK bundles the lsusb binaries for four ABIs and the system selects the matching one automatically. It runs on Android 7–16 (API 24–36).

**Upstream version:** usbutils 017 + libusb 1.0.27

### Features

- **Summary** → `lsusb`, **Verbose** → `lsusb -v`, **Tree** → `lsusb -t`, **Select** → list every device, then run `lsusb -v -D <node>` on the chosen one.
- Output is rendered as bordered monospace tables (with a raw-text mode); long-press a button to view the original lsusb output. Output can be copied to the clipboard and scrolled horizontally.
- In-app language switching: English, Simplified Chinese, Traditional Chinese (the choice is remembered).
- All commands run on background threads; the UI never blocks.

### Root handling

Reading `/dev/bus/usb/*` requires root. On startup the app probes the well-known `su` locations (PATH, `/system/bin/su`, `/debug_ramdisk/su`, Magisk, KernelSU, APatch, …), shows the working path in the status line, and prompts to retry if no root is available.

### How the binaries are packaged

The four static lsusb binaries are placed at `app/lib/<abi>/liblsusb.so`. The PackageManager extracts them to the read-only, executable `nativeLibraryDir` at install time, which satisfies the Android 10+ W^X restriction (executable files cannot live in the writable app data directory). The `.so` suffix is only used so they are treated as native libraries. Each `.so` is stored uncompressed and aligned to 16 KB.

### Build (no Gradle)

Requirements: JDK, Android SDK build-tools and platform android-36, the Kotlin compiler, and Python 3.

```bash
./build-so.sh          # 1. Build liblsusb.so for the four ABIs (offline; sources in so-src/)
./build.sh             # 2. Resources -> kotlinc -> d8 -> package -> zipalign -> apksigner
```

### Install

```bash
adb install -r lsusb-gui.apk
```

Grant root on first launch, then the app is ready to use.

### Project layout

```
app/                Android resources, manifest and Kotlin sources
so-src/             Source for rebuilding the bundled lsusb (libusb, usbutils, usb.ids, patches)
build.sh / build-so.sh   Build entry points (APK and native libraries)
gen_r_kotlin.py     Generates the Kotlin R class from R.txt
lsusb-gui.apk       Prebuilt APK
```

---

## 简体中文

静态 **lsusb**（usbutils 017 + libusb 1.0.27，`usb.ids` 已内嵌）的安卓图形界面前端。APK 内置四个 ABI 的 lsusb 二进制，系统按设备 ABI 自动选用，支持 Android 7–16（API 24–36）。

**上游版本：**usbutils 017 + libusb 1.0.27

### 功能

- **简要** → `lsusb`，**详细** → `lsusb -v`，**树形** → `lsusb -t`，**选择** → 先列出全部设备，再对选中的设备执行 `lsusb -v -D <节点>`。
- 输出整理为带框线的等宽表格（可切换原始文本模式）；长按按钮可查看 lsusb 原始输出；输出可复制到剪贴板，并可横向滑动。
- 应用内切换语言：English、简体中文、繁體中文（选择会被记住）。
- 所有命令在子线程执行，界面不卡顿。

### root 处理

读取 `/dev/bus/usb/*` 需要 root。启动时应用会探测 `su` 的常见位置（PATH、`/system/bin/su`、`/debug_ramdisk/su`、Magisk、KernelSU、APatch 等），在状态行显示可用路径；若没有 root 则提示重试。

### 二进制如何打包

四个静态 lsusb 二进制放在 `app/lib/<abi>/liblsusb.so`。安装时 PackageManager 将其解压到只读且可执行的 `nativeLibraryDir`，满足 Android 10+ 的 W^X 限制（可执行文件不能放在可写的应用数据目录）。`.so` 后缀只是为了让系统把它们当作 native 库；每个 `.so` 不压缩存储并按 16 KB 对齐。

### 构建（不使用 Gradle）

要求：JDK、Android SDK build-tools 与 android-36 平台、Kotlin 编译器、Python 3。

```bash
./build-so.sh          # 1. 编译四个 ABI 的 liblsusb.so（离线；源码在 so-src/）
./build.sh             # 2. 资源 -> kotlinc -> d8 -> 打包 -> zipalign -> apksigner
```

### 安装

```bash
adb install -r lsusb-gui.apk
```

首次打开时授予 root 权限，即可使用。

### 项目结构

```
app/                安卓资源、清单与 Kotlin 源码
so-src/             重编内置 lsusb 所需源码（libusb、usbutils、usb.ids、补丁）
build.sh / build-so.sh   构建入口（APK 与原生库）
gen_r_kotlin.py     从 R.txt 生成 Kotlin R 类
lsusb-gui.apk       预编译 APK
```

---

## 繁體中文

靜態 **lsusb**（usbutils 017 + libusb 1.0.27，`usb.ids` 已內嵌）的安卓圖形介面前端。APK 內建四個 ABI 的 lsusb 可執行檔，系統依裝置 ABI 自動選用，支援 Android 7–16（API 24–36）。

**上游版本：**usbutils 017 + libusb 1.0.27

### 功能

- **簡要** → `lsusb`，**詳細** → `lsusb -v`，**樹狀** → `lsusb -t`，**選擇** → 先列出全部裝置，再對選中的裝置執行 `lsusb -v -D <節點>`。
- 輸出整理為帶框線的等寬表格（可切換原始文字模式）；長按按鈕可檢視 lsusb 原始輸出；輸出可複製到剪貼簿，並可橫向捲動。
- 應用內切換語言：English、簡體中文、繁體中文（選擇會被記住）。
- 所有指令在背景執行緒執行，介面不卡頓。

### root 處理

讀取 `/dev/bus/usb/*` 需要 root。啟動時應用會探查 `su` 的常見位置（PATH、`/system/bin/su`、`/debug_ramdisk/su`、Magisk、KernelSU、APatch 等），在狀態列顯示可用路徑；若沒有 root 則提示重試。

### 可執行檔如何打包

四個靜態 lsusb 可執行檔放在 `app/lib/<abi>/liblsusb.so`。安裝時 PackageManager 將其解壓到唯讀且可執行的 `nativeLibraryDir`，滿足 Android 10+ 的 W^X 限制（可執行檔不能放在可寫的應用資料目錄）。`.so` 副檔名只是為了讓系統把它們當作 native 程式庫；每個 `.so` 不壓縮儲存並按 16 KB 對齊。

### 建構（不使用 Gradle）

需求：JDK、Android SDK build-tools 與 android-36 平台、Kotlin 編譯器、Python 3。

```bash
./build-so.sh          # 1. 編譯四個 ABI 的 liblsusb.so（離線；原始碼在 so-src/）
./build.sh             # 2. 資源 -> kotlinc -> d8 -> 打包 -> zipalign -> apksigner
```

### 安裝

```bash
adb install -r lsusb-gui.apk
```

首次開啟時授予 root 權限，即可使用。

### 專案結構

```
app/                安卓資源、清單與 Kotlin 原始碼
so-src/             重編內建 lsusb 所需原始碼（libusb、usbutils、usb.ids、修補程式）
build.sh / build-so.sh   建構入口（APK 與原生程式庫）
gen_r_kotlin.py     從 R.txt 產生 Kotlin R 類別
lsusb-gui.apk       預編譯 APK
```
