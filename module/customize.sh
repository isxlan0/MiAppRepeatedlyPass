#!/system/bin/sh
[ "$BOOTMODE" = true ] || abort "请在 KernelSU 或 Magisk 管理器中安装。"
[ "$ARCH" = arm64 ] || abort "仅支持 arm64。"
[ "$API" = 37 ] || abort "仅适配 SDK 37。"
[ -d /data/adb/modules/zygisk_lsposed ] || abort "请先安装并启用 LSPosed。"
[ ! -f /data/adb/modules/zygisk_lsposed/disable ] || abort "LSPosed 处于禁用状态。"
. "$MODPATH/upgrade.sh"
for legacy_module in /data/adb/modules/miapp_instances /data/adb/modules_update/miapp_instances; do
  miapp_check_legacy_module "$legacy_module" || abort "旧版模块已安排卸载；请先在管理器取消卸载，再安装新版以保留分身。"
done
DATA_DIR=$(miapp_registry_dir /data/system)
ui_print "- 正在扫描 Java 方法、资源和原生函数特征，生成本机适配配置。"
prepare_result=0
/system/bin/unshare -m /system/bin/sh "$MODPATH/prepare-native.sh" "$MODPATH" > "$MODPATH/native-prepare.log" 2>&1 || prepare_result=$?
while IFS= read -r prepare_line || [ -n "$prepare_line" ]; do
  ui_print "$prepare_line"
done < "$MODPATH/native-prepare.log"
if [ "$prepare_result" -ne 0 ]; then
  # abort() removes MODPATH in KernelSU/Magisk, so keep the failure outside it.
  prepare_saved_log="/data/adb/${MODPATH##*/}-prepare.log"
  echo "FAIL: preparation process exited with code $prepare_result" >> "$MODPATH/native-prepare.log"
  if (umask 077; cat "$MODPATH/native-prepare.log" > "$prepare_saved_log" && chmod 0600 "$prepare_saved_log"); then
    ui_print "- 扫描失败日志已保存：$prepare_saved_log"
  else
    ui_print "- 无法保存扫描失败日志，请保存当前安装界面的输出。"
  fi
  abort "自动适配失败（退出码 $prepare_result）。请查看上方具体错误及失败阶段。"
fi
# The private namespace has exited, so no mount can remain on these empty dirs.
rmdir "$MODPATH/.native-prepare/system_ext" "$MODPATH/.native-prepare/product" "$MODPATH/.native-prepare/system" "$MODPATH/.native-prepare" || abort "无法清理临时目录。"
ui_print "- 原始库已从当前 ROM 提取，原生补丁地址已生成。"
ui_print "- 模块额外增加系统分身数量,使系统最多支持3个分身(系统自带一个+模块提供两个位置)。"
ui_print "- 模块开源地址: https://github.com/isxlan0/MiAppRepeatedlyPass"
if [ -f "$DATA_DIR/registry.properties" ]; then
  grep -q '^alias\.' "$DATA_DIR/registry.properties" && abort "请先使用旧版 remove-all 清理包别名实例。"
fi
set_perm_recursive "$MODPATH" 0 0 0755 0644
for script in customize.sh prepare-native.sh service.sh action.sh uninstall.sh bin/miapp; do
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
ui_print "- 在 LSPosed 启用 io.github.isxlan0.miappxspace，选择系统框架、系统桌面、安全核心组件、手机管家、系统界面、设置。"
ui_print "- 包名已更新，需在 LSPosed 重新启用新模块并重启；旧 ID 模块已禁用，可在管理器移除。"
ui_print "- 卸载之前请先在 应用设置 → 应用双开 → 设置 中选择删除双开账户，然后卸载 LSP 模块 APK，最后卸载 Magisk / KernelSU 模块，否则可能导致系统异常。"
