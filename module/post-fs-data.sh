#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/status-description.sh"
miapp_description "✓ 正常运行中"
miapp_native_status starting "正在检查当前 ROM 系统库"
# Disable mounting until the current ROM generation has been verified. This also
# protects the next boot when a previous refresh was interrupted.
touch "$MODDIR/skip_mount" || exit 1
if /system/bin/timeout -k 2 20 /system/bin/unshare -m /system/bin/sh \
    "$MODDIR/prepare-native.sh" "$MODDIR" > "$MODDIR/system-prepare.log" 2>&1; then
  rm -f "$MODDIR/skip_mount"
  miapp_native_status ready "系统库准备完成"
else
  result=$?
  echo "FAIL: pre-mount system preparation exited with code $result; native overlay skipped" >> "$MODDIR/system-prepare.log"
  miapp_native_status error "系统库检查失败（退出码 $result），查看 system-prepare.log"
  miapp_description "系统库适配失败：退出码 $result，查看 system-prepare.log"
fi
