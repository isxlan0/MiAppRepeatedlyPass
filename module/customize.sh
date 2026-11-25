#!/system/bin/sh
[ "$BOOTMODE" = true ] || abort "请在 KernelSU 或 Magisk 管理器中安装。"
[ "$ARCH" = arm64 ] || abort "仅支持 arm64。"
[ "$API" = 37 ] || abort "仅适配 SDK 37。"
[ "$(/system/bin/sha256sum /product/priv-app/MiuiHome/MiuiHome.apk | cut -d ' ' -f 1)" = 38d681fa9683a005510f7961ea596fe46f964fc3446169025d8275135e6aa810 ] || abort "HyperOS Runtime 桌面版本与本次适配不一致。"
[ "$(/system/bin/sha256sum /system/framework/framework.jar | cut -d ' ' -f 1)" = bfe477943ca7c58ea151897b2498f705ec4e9470ae727b6aa7d18305d342f5b8 ] || abort "framework.jar 与本次分析版本不一致。"
[ "$(/system/bin/sha256sum /system_ext/framework/miui-framework.jar | cut -d ' ' -f 1)" = 4e2f281d7c218dc9451500597fd4573b87beeb5622d1bb73a19e33b6ab350d7f ] || abort "miui-framework.jar 与本次分析版本不一致。"
[ -d /data/adb/modules/zygisk_lsposed ] || abort "请先安装并启用 LSPosed。"
[ ! -f /data/adb/modules/zygisk_lsposed/disable ] || abort "LSPosed 处于禁用状态。"
[ -d /data/adb/modules/hybrid_mount ] || abort "此原生桌面适配需要现有 Hybrid Mount metamodule。"
. "$MODPATH/upgrade.sh"
for legacy_module in /data/adb/modules/miapp_instances /data/adb/modules_update/miapp_instances; do
  miapp_check_legacy_module "$legacy_module" || abort "旧版模块已安排卸载；请先在管理器取消卸载，再安装新版以保留分身。"
done
DATA_DIR=$(miapp_registry_dir /data/system)
matches_previous_library() {
  for previous_id in miapp_xspace miapp_instances; do
    previous_library="/data/adb/modules/$previous_id/system/system_ext/lib64/$1"
    if [ -f "$previous_library" ] && [ "$2" = "$(/system/bin/sha256sum "$previous_library" | cut -d ' ' -f 1)" ]; then
      return 0
    fi
  done
  return 1
}
native_hash=$(/system/bin/sha256sum /system_ext/lib64/libhyper_os_security_public.so | cut -d ' ' -f 1)
incoming_native_hash=$(/system/bin/sha256sum "$MODPATH/system/system_ext/lib64/libhyper_os_security_public.so" | cut -d ' ' -f 1)
if [ "$native_hash" != a3e3f75c43128d472454905a66e37276c77377af67b3383bfe2bb3d847947aa3 ] && [ "$native_hash" != "$incoming_native_hash" ]; then
  matches_previous_library libhyper_os_security_public.so "$native_hash" || abort "原生桌面库版本不匹配。"
fi
private_hash=$(/system/bin/sha256sum /system_ext/lib64/libhyper_os_bundle_private.dylib.so | cut -d ' ' -f 1)
if [ "$private_hash" != 9c7e33edbb11dae45950f0ea0a72d409d653d84b913a488cce2aa45a2975baf9 ]; then
  matches_previous_library libhyper_os_bundle_private.dylib.so "$private_hash" || abort "原生 Flutter 图标库版本不匹配。"
fi
bundle_hash=$(/system/bin/sha256sum /system_ext/lib64/libhyper_os_bundle_public.so | cut -d ' ' -f 1)
if [ "$bundle_hash" != 22b6fbaa7da9d09c7b8811b8d37bb3cbc35ca6685d503400e794f0e6d4459597 ]; then
  matches_previous_library libhyper_os_bundle_public.so "$bundle_hash" || abort "原生图标库版本不匹配。"
fi
ui_print "- 模块额外增加系统分身数量,使系统最多支持3个分身(系统自带一个+模块提供两个位置)。"
ui_print "- 模块开源地址: https://github.com/isxlan0/MiAppRepeatedlyPass"
if [ -f "$DATA_DIR/registry.properties" ]; then
  grep -q '^alias\.' "$DATA_DIR/registry.properties" && abort "请先使用旧版 remove-all 清理包别名实例。"
fi
set_perm_recursive "$MODPATH" 0 0 0755 0644
for script in customize.sh service.sh action.sh uninstall.sh bin/miapp; do
  set_perm "$MODPATH/$script" 0 0 0755
done
mkdir -p "$DATA_DIR"
chown 0:1000 "$DATA_DIR"
chmod 0770 "$DATA_DIR"
touch "$DATA_DIR/profile-control.lock"
chown 0:1000 "$DATA_DIR/profile-control.lock"
chmod 0660 "$DATA_DIR/profile-control.lock"
/system/bin/restorecon "$DATA_DIR"
ui_print "- 安装系统 Hook 组件。"
install_output=$(/system/bin/pm install -r --user 0 "$MODPATH/bridge.apk" 2>&1)
case "$install_output" in *Success*) ui_print "- Hook 组件安装成功。";; *) abort "$install_output";; esac
for legacy_module in /data/adb/modules/miapp_instances /data/adb/modules_update/miapp_instances; do
  miapp_retire_legacy_module "$legacy_module" || abort "旧模块兼容处理失败，请保留旧模块目录与分身数据并检查权限。"
done
if /system/bin/pm path dev.miapp.instances 2>/dev/null | grep -q '^package:'; then
  ui_print "- 检测到旧 Hook 包 dev.miapp.instances，请在 LSPosed 禁用并手动卸载旧 APK，避免同时启用。"
fi
"$MODPATH/bin/miapp" refresh-titles >> "$MODPATH/status.log" 2>&1 || ui_print "- 桌面名称缓存将在服务启动后初始化。"
ui_print "- 在 LSPosed 启用 io.github.isxlan0.miappxspace，选择系统框架、系统桌面、安全核心组件、手机管家、系统界面。"
ui_print "- 包名已更新，需在 LSPosed 重新启用新模块并重启；旧 ID 模块已禁用，可在管理器移除。"
ui_print "- 卸载之前请先在 应用设置 → 应用双开 → 设置 中选择删除双开账户，然后卸载 LSP 模块 APK，最后卸载 Magisk / KernelSU 模块，否则可能导致系统异常。"
