#!/system/bin/sh
# Plain UTF-8 for manager display. Keep properties other than description intact.
miapp_description() {
  description="[$1] 额外增加系统分身数量,使系统最多支持3个分身(系统自带一个+模块提供两个位置) Github:isxlan0/MiAppRepeatedlyPass"
  { while IFS= read -r line || [ -n "$line" ]; do
      case "$line" in description=*) printf 'description=%s\n' "$description";; *) printf '%s\n' "$line";; esac
    done < "$MODDIR/module.prop"
  } > "$MODDIR/module.prop.health.tmp" && chmod 0644 "$MODDIR/module.prop.health.tmp" && mv -f "$MODDIR/module.prop.health.tmp" "$MODDIR/module.prop"
}
miapp_native_status() {
  { printf 'boot=%s\n' "$(cat /proc/sys/kernel/random/boot_id)"; printf 'state=%s\ndetail=%s\n' "$1" "$2";
  } > "$MODDIR/native-boot.status.tmp" && mv -f "$MODDIR/native-boot.status.tmp" "$MODDIR/native-boot.status"
}
