#!/bin/bash
# 从源码编出 4 个 Android ABI 的 liblsusb.so，放进 app/lib/<abi>/
#
#   liblsusb.so 实际就是静态 lsusb 可执行文件（usbutils 017 + libusb 1.0.27 + 内嵌 usb.ids），
#   改扩展名 .so 是为了让 Android 把它当 native lib 释放到 nativeLibraryDir，
#   应用再用绝对路径 exec 它（无需 root 安装、无需落盘到 /data）。
#
# 用法:
#   ./build-so.sh                 # 4 个 ABI 全编
#   ONLY=arm64-v8a ./build-so.sh  # 只编一个
#   NDK=/opt/android-ndk ./build-so.sh
#
# 依赖: NDK（自动探测）、tar/xz/bzip2/make/patch/python3；不需要 zig、不联网。
set -u

ROOT=$(cd "$(dirname "$0")" && pwd)
SO=$ROOT/so-src
LIB=$ROOT/app/lib
JOBS=${JOBS:-$(nproc 2>/dev/null || echo 4)}
ONLY=${ONLY:-}

fail() { echo "!! $*" >&2; exit 1; }

# bin 名 -> ABI 目录
MAP="android_arm64:arm64-v8a android_arm:armeabi-v7a android_x86:x86 android_x64:x86_64"

mkdir -p "$LIB"

ok=0
for pair in $MAP; do
  t=${pair%%:*}
  abi=${pair##*:}
  outname=${t/android_/android-}     # android_arm64 -> android-arm64
  if [ -n "$ONLY" ] && [ "$ONLY" != "$abi" ] && [ "$ONLY" != "$t" ]; then continue; fi

  echo "========== $abi =========="
  ONLY=$t JOBS=$JOBS "$SO/build-lsusb-8.sh" || fail "[$abi] 编译失败"

  mkdir -p "$LIB/$abi"
  cp "$SO/bins/lsusb-$outname" "$LIB/$abi/liblsusb.so" || fail "[$abi] 拷贝失败"
  chmod 755 "$LIB/$abi/liblsusb.so"
  ok=$((ok+1))
done

[ "$ok" -gt 0 ] || fail "没有目标被编译（ONLY=$ONLY 不匹配）"

echo
echo "==== app/lib 下的产物 ===="
for f in "$LIB"/*/liblsusb.so; do
  printf '%-40s %9d B\n' "$f" "$(stat -c%s "$f")"
done

echo
echo "提示: 之后执行 ./build.sh 重新打包 APK（会以 -0 方式把这 4 个 so 打进 lib/）"
