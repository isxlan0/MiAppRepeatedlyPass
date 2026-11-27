#!/system/bin/sh
MODDIR=${0%/*}
exec 9> "$MODDIR/service.lock"
/system/bin/flock -n 9 9>&9 || exit 0
. "$MODDIR/status-description.sh"
until [ "$(/system/bin/getprop sys.boot_completed)" = 1 ]; do sleep 2; done
"$MODDIR/bin/miapp" status > "$MODDIR/status.log" 2>&1
"$MODDIR/bin/miapp" boot-spaces > "$MODDIR/boot-spaces.log" 2>&1
# Regenerate only after package/locale changes. The owner-label map is published
# atomically and survives boots; native Runtime reloads it on the next DTO encode.
last_labels=
last_state=
while [ -d "$MODDIR" ] && [ ! -f "$MODDIR/disable" ] && [ ! -f "$MODDIR/remove" ]; do
  if ! /system/bin/timeout -k 2 12 "$MODDIR/bin/miapp" health "$MODDIR" > "$MODDIR/health.log" 2>&1; then
    miapp_description "状态检查失败或超时：查看 health.log"
  fi
  current_labels="$(stat -c '%Y:%i:%s' /data/system/packages.xml 2>/dev/null):$(getprop persist.sys.locale)"
  if [ "$current_labels" != "$last_labels" ]; then
    if "$MODDIR/bin/miapp" refresh-titles > "$MODDIR/titles.log" 2>&1; then
      last_labels="$current_labels"
    fi
  fi
  # Per-user package state changes even when the shared APK is still installed.
  current_state=$(stat -c '%n:%Y:%i:%s' /data/system/users/userlist.xml /data/system/users/*.xml /data/system/users/*/package-restrictions.xml /data/system/packages.xml 2>/dev/null)
  if [ "$current_state" != "$last_state" ]; then
    if "$MODDIR/bin/miapp" repair-launcher > "$MODDIR/launcher-repair.log" 2>&1; then
      last_state="$current_state"
    fi
  fi
  sleep 15
done
if [ -d "$MODDIR" ]; then miapp_description "已禁用/待卸载：重启后生效"; fi
