#!/system/bin/sh
# Executed in a private mount namespace by customize.sh. Only fresh, read-only
# views of ROM partitions are used; installed module overlays are never inputs.
set -eu
MODPATH=$1
WORK="$MODPATH/.native-prepare"
stage="initialize scan workspace"
mounted_ext=0
mounted_product=0
mounted_system=0
finish() {
  result=$?
  trap - EXIT
  if [ "$result" -ne 0 ]; then
    echo "FAIL: $stage (exit=$result)" >&2
  fi
  for partition in system product system_ext; do
    case "$partition" in
      system) mounted=$mounted_system ;;
      product) mounted=$mounted_product ;;
      system_ext) mounted=$mounted_ext ;;
    esac
    if [ "$mounted" = 1 ]; then
      /system/bin/umount "$WORK/$partition" || {
        echo "FAIL: unmount scan workspace: $partition" >&2
        result=1
      }
    fi
  done
  exit "$result"
}
trap finish EXIT
scan_stage() {
  stage=$1
  echo "- [prepare] $stage"
}
scan_stage "$stage"
mkdir -p "$WORK/system_ext" "$WORK/product" "$WORK/system"
scan_stage "mount original ROM partitions read-only"
/system/bin/mount -o rprivate none /
rom_mount() {
  mountpoint=$1
  output=$2
  source=$(awk -v target="$mountpoint" '$2 == target && ($3 == "erofs" || $3 == "ext4") {print $1; exit}' /proc/mounts)
  fstype=$(awk -v target="$mountpoint" '$2 == target && ($3 == "erofs" || $3 == "ext4") {print $3; exit}' /proc/mounts)
  [ -b "$source" ] || { echo "Cannot locate original partition for $mountpoint"; return 1; }
  case "$fstype" in
    erofs) /system/bin/mount -t erofs -o ro "$source" "$output" ;;
    ext4) /system/bin/mount -t ext4 -o ro,noload "$source" "$output" ;;
    *) return 1 ;;
  esac
}
rom_mount /system_ext "$WORK/system_ext"
mounted_ext=1
rom_mount /product "$WORK/product"
mounted_product=1
if awk '$2 == "/system" && ($3 == "erofs" || $3 == "ext4") {found=1} END {exit !found}' /proc/mounts; then
  rom_mount /system "$WORK/system"
  SYSTEM_ROOT="$WORK/system"
else
  rom_mount / "$WORK/system"
  SYSTEM_ROOT="$WORK/system/system"
fi
mounted_system=1

# Resolve Java members from DEX code, references and resource identities. Prefer
# an installed SecurityCore update when it is the APK the next process will load.
SECURITY_APK="$WORK/product/app/SecurityCoreAdd/SecurityCoreAdd.apk"
scan_stage "query active SecurityCore APK"
# cmd/pm passes stdout AND stderr to system_server through Binder. The parent
# redirects this script to a module log labelled system_file, which system_server
# cannot write. Capture both descriptors in the pipe, and print errors ourselves.
if active_security=$(/system/bin/pm path --user 0 com.miui.securitycore 2>&1); then
  :
else
  query_result=$?
  echo "Cannot query active SecurityCore APK (exit=$query_result): $active_security" >&2
  exit "$query_result"
fi
case "$active_security" in
  package:/product/app/SecurityCoreAdd/SecurityCoreAdd.apk) ;;
  package:/data/app/*) SECURITY_APK=${active_security#package:} ;;
  *) echo "Unsupported active SecurityCore location: $active_security"; exit 1 ;;
esac
scan_stage "scan Java methods, references and resources"
CLASSPATH="$MODPATH/bridge.apk" /system/bin/app_process /system/bin \
  io.github.isxlan0.miappxspace.JavaPrepare \
  "$SYSTEM_ROOT" "$WORK/system_ext" "$SECURITY_APK" "$MODPATH/java-layout.properties"

# app_process supplies the installed Android Java runtime; no Python dependency.
# A new module ZIP always contains unconfigured wrappers (ready=0).
scan_stage "scan native functions and extract original libraries"
CLASSPATH="$MODPATH/bridge.apk" /system/bin/app_process /system/bin \
  io.github.isxlan0.miappxspace.NativePrepare \
  "$WORK/product/priv-app/MiuiHome/MiuiHome.apk" "$WORK/system_ext/lib64" \
  "$MODPATH/native-layout.properties" "$MODPATH"
scan_stage "Java and native adaptation complete"
