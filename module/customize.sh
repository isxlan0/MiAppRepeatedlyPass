#!/system/bin/sh
. "$MODPATH/install-diagnostics.sh"
miapp_environment
[ "$BOOTMODE" = true ] || miapp_abort "请在 KernelSU 或 Magisk 管理器中安装。"
[ "$ARCH" = arm64 ] || miapp_abort "仅支持 arm64。"
[ "$API" = 37 ] || miapp_abort "仅适配 SDK 37。"
[ -d /data/adb/modules/zygisk_lsposed ] || miapp_abort "请先安装并启用 LSPosed。"
[ ! -f /data/adb/modules/zygisk_lsposed/disable ] || miapp_abort "LSPosed 处于禁用状态。"
[ ! -f /data/adb/modules/zygisk_lsposed/remove ] || miapp_abort "LSPosed 已安排卸载，请先取消卸载。"
. "$MODPATH/upgrade.sh"
for legacy_module in /data/adb/modules/miapp_instances /data/adb/modules_update/miapp_instances; do
  miapp_check_legacy_module "$legacy_module" || miapp_abort "旧版模块已安排卸载；请先在管理器取消卸载，再安装新版以保留分身。"
done
DATA_DIR=$(miapp_registry_dir /data/system)
miapp_print "- 正在适配当前 ROM 系统库；应用 APK 由进程加载时动态识别。"
mkdir -p "$MODPATH/.native-templates" || miapp_abort "无法保存系统库模板。"
cp -p "$MODPATH/system/system_ext/lib64/"*.so "$MODPATH/.native-templates/" || miapp_abort "无法保存系统库模板。"
chmod 0755 "$MODPATH/bin/prepare-system"
prepare_result=0
/system/bin/unshare -m /system/bin/sh "$MODPATH/prepare-native.sh" "$MODPATH" > "$MODPATH/native-prepare.log" 2>&1 || prepare_result=$?
while IFS= read -r prepare_line || [ -n "$prepare_line" ]; do
  miapp_print "$prepare_line"
done < "$MODPATH/native-prepare.log"
if [ "$prepare_result" -ne 0 ]; then
  miapp_print "FAIL: preparation process exited with code $prepare_result"
  miapp_abort "自动适配失败（退出码 $prepare_result）。请查看上方具体错误及失败阶段。"
fi
# The private namespace has exited, so no mount can remain on these empty dirs.
rmdir "$MODPATH/.native-prepare/system_ext" "$MODPATH/.native-prepare" || miapp_abort "无法清理临时目录。"
miapp_print "- 系统原始库已提取；后续开机自动检查 OTA 变化。"
miapp_print "- 模块额外增加系统分身数量,使系统最多支持3个分身(系统自带一个+模块提供两个位置)。"
miapp_print "- 模块开源地址: https://github.com/isxlan0/MiAppRepeatedlyPass"
if [ -f "$DATA_DIR/registry.properties" ]; then
  grep -q '^alias\.' "$DATA_DIR/registry.properties" && miapp_abort "请先使用旧版 remove-all 清理包别名实例。"
fi
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MIAPP_INSTALL_LOG" 0 0 0600
for script in customize.sh install-diagnostics.sh prepare-native.sh post-fs-data.sh status-description.sh service.sh action.sh uninstall.sh bin/miapp bin/prepare-system; do
  set_perm "$MODPATH/$script" 0 0 0755
done
mkdir -p "$DATA_DIR"
chown 0:1000 "$DATA_DIR"
chmod 0770 "$DATA_DIR"
touch "$DATA_DIR/profile-control.lock"
chown 0:1000 "$DATA_DIR/profile-control.lock"
chmod 0660 "$DATA_DIR/profile-control.lock"
/system/bin/restorecon "$DATA_DIR"
miapp_print "- 安装系统 Hook 组件。"
install_output=$(/system/bin/pm install -r --user 0 "$MODPATH/bridge.apk" 2>&1)
case "$install_output" in *Success*) miapp_print "- Hook 组件安装成功。";; *) miapp_abort "$install_output";; esac
for legacy_module in /data/adb/modules/miapp_instances /data/adb/modules_update/miapp_instances; do
  miapp_retire_legacy_module "$legacy_module" || miapp_abort "旧模块兼容处理失败，请保留旧模块目录与分身数据并检查权限。"
done
if /system/bin/pm path dev.miapp.instances 2>/dev/null | grep -q '^package:'; then
  miapp_print "- 检测到旧 Hook 包 dev.miapp.instances，请在 LSPosed 禁用并手动卸载旧 APK，避免同时启用。"
fi
"$MODPATH/bin/miapp" refresh-titles >> "$MODPATH/status.log" 2>&1 || miapp_print "- 桌面名称缓存将在服务启动后初始化。"
miapp_print "- 需要支持 libxposed API 102 的框架，本版本不再提供 legacy 入口。"
miapp_print "- 在 LSPosed 启用 io.github.isxlan0.miappxspace，选择系统框架、系统桌面、安全核心组件、手机管家、系统界面、设置。"
miapp_print "- 包名已更新，需在 LSPosed 重新启用新模块并重启；旧 ID 模块已禁用，可在管理器移除。"
miapp_print "- 卸载之前请先在 应用设置 → 应用双开 → 设置 中选择删除双开账户，然后卸载 LSP 模块 APK，最后卸载 Magisk / KernelSU 模块，否则可能导致系统异常。"
miapp_print "- 本次安装日志：/data/adb/modules/${MODPATH##*/}/install.log（重启后）"
