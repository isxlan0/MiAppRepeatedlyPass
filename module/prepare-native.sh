#!/system/bin/sh
# Private namespace: APKs are recognized in their own process, never here.
set -eu
MODPATH=$1
WORK="$MODPATH/.native-prepare"
stage="initialize system scan workspace"
mounted_ext=0
finish() {
  result=$?
  trap - EXIT
  [ "$result" -eq 0 ] || echo "FAIL: $stage (exit=$result)" >&2
  if [ "$mounted_ext" = 1 ]; then
    /system/bin/umount "$WORK/system_ext" || result=1
  fi
  exit "$result"
}
trap finish EXIT
scan_stage() { stage=$1; echo "- [prepare] $stage"; }
scan_stage "$stage"
[ "$(/system/bin/getprop ro.build.version.sdk)" = 37 ] || { echo "Unsupported Android API"; exit 1; }
mkdir -p "$WORK/system_ext"
scan_stage "mount original system_ext read-only"
/system/bin/mount -o rprivate none /
source=$(awk '$2 == "/system_ext" && ($3 == "erofs" || $3 == "ext4") {print $1; exit}' /proc/mounts)
fstype=$(awk '$2 == "/system_ext" && ($3 == "erofs" || $3 == "ext4") {print $3; exit}' /proc/mounts)
[ -b "$source" ] || { echo "Cannot locate original system_ext partition"; exit 1; }
case "$fstype" in
  erofs) /system/bin/mount -t erofs -o ro "$source" "$WORK/system_ext" ;;
  ext4) /system/bin/mount -t ext4 -o ro,noload "$source" "$WORK/system_ext" ;;
  *) exit 1 ;;
esac
mounted_ext=1
fingerprint() {
  for input in "$@"; do
    digest=$(/system/bin/sha256sum "$input") || return 1
    echo "${digest%% *}"
  done
}
system_inputs() {
  fingerprint "$WORK/system_ext/lib64/libhyper_os_security_public.so" \
    "$WORK/system_ext/lib64/libhyper_os_bundle_public.so" \
    "$WORK/system_ext/lib64/libhyper_os_bundle_private.dylib.so" \
    "$MODPATH/bin/prepare-system" "$MODPATH/.native-templates/"*.so
}
scan_stage "check current ROM and module library content"
inputs=$(system_inputs)
if [ -f "$MODPATH/system-inputs.sha256" ] && [ "$inputs" = "$(cat "$MODPATH/system-inputs.sha256")" ]; then
  if outputs=$(fingerprint "$MODPATH/system/system_ext/lib64/"*.so) && \
     [ -f "$MODPATH/system-outputs.sha256" ] && [ "$outputs" = "$(cat "$MODPATH/system-outputs.sha256")" ]; then
    echo "PASS: system library cache unchanged"
    exit 0
  fi
fi
scan_stage "recognize and stage current ROM system libraries"
# Fixed child paths are temporary, module-owned generations.
rm -rf "$MODPATH/.system-next"
mkdir -p "$MODPATH/.system-next/system_ext/lib64"
cp -p "$MODPATH/.native-templates/"*.so "$MODPATH/.system-next/system_ext/lib64/"
"$MODPATH/bin/prepare-system" "$WORK/system_ext/lib64" "$MODPATH/.system-next/system_ext/lib64"
outputs=$(fingerprint "$MODPATH/.system-next/system_ext/lib64/"*.so)
confirmed=$(system_inputs)
[ "$inputs" = "$confirmed" ] || { echo "System inputs changed during recognition"; exit 1; }
chmod 0755 "$MODPATH/.system-next" "$MODPATH/.system-next/system_ext" "$MODPATH/.system-next/system_ext/lib64"
chmod 0644 "$MODPATH/.system-next/system_ext/lib64/"*.so
/system/bin/chcon -R u:object_r:system_file:s0 "$MODPATH/.system-next"
scan_stage "publish verified system libraries before mounting"
rm -rf "$MODPATH/.system-previous"
if [ -d "$MODPATH/system" ]; then mv "$MODPATH/system" "$MODPATH/.system-previous"; fi
mv "$MODPATH/.system-next" "$MODPATH/system"
printf '%s\n' "$inputs" > "$MODPATH/system-inputs.sha256"
printf '%s\n' "$outputs" > "$MODPATH/system-outputs.sha256"
rm -rf "$MODPATH/.system-previous"
echo "PASS: system adaptation complete"
