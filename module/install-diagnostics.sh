#!/system/bin/sh
# Sourced by customize.sh. Read properties as data, never as shell commands.
miapp_prop() {
  [ -r "$1" ] || return 0
  awk -v key="$2" 'index($0, key "=") == 1 { sub(/^[^=]*=/, ""); sub(/\r$/, ""); print; exit }' "$1"
}

miapp_print() {
  ui_print "$*"
  printf '%s\n' "$*" >> "$MIAPP_INSTALL_LOG"
}

miapp_abort() {
  miapp_print "FAIL: $*"
  # Root managers delete MODPATH on abort; this copy survives that cleanup.
  miapp_saved_log="/data/adb/${MODPATH##*/}-prepare.log"
  if (umask 077; cat "$MIAPP_INSTALL_LOG" > "$miapp_saved_log" && chmod 0600 "$miapp_saved_log"); then
    ui_print "- 安装失败日志已保存：$miapp_saved_log"
  else
    ui_print "- 无法保存安装失败日志，请保存当前安装界面的输出。"
  fi
  abort "$*"
}

miapp_property() {
  miapp_value=$(getprop "$1" 2>/dev/null)
  miapp_print "  $1=${miapp_value:-unknown}"
}

miapp_module_info() {
  miapp_dir=$1
  miapp_label=$2
  if [ ! -r "$miapp_dir/module.prop" ]; then
    miapp_print "  $miapp_label: not installed / metadata unavailable"
    return
  fi
  miapp_state=enabled
  [ ! -f "$miapp_dir/disable" ] || miapp_state=disabled
  [ ! -f "$miapp_dir/remove" ] || miapp_state="$miapp_state, pending removal"
  miapp_name=$(miapp_prop "$miapp_dir/module.prop" name)
  miapp_version=$(miapp_prop "$miapp_dir/module.prop" version)
  miapp_code=$(miapp_prop "$miapp_dir/module.prop" versionCode)
  miapp_print "  $miapp_label: name=${miapp_name:-unknown}; version=${miapp_version:-unknown}; versionCode=${miapp_code:-unknown}; state=$miapp_state"
}

miapp_environment() {
  MIAPP_INSTALL_LOG="$MODPATH/install.log"
  (umask 077; : > "$MIAPP_INSTALL_LOG") || abort "无法创建安装诊断日志。"
  chmod 0600 "$MIAPP_INSTALL_LOG"
  miapp_print "- MiApp XSpace 安装环境（请反馈完整日志）"
  miapp_print "  time=$(date -u '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null)"
  miapp_module_info "$MODPATH" 'ZIP module'
  miapp_module_info "/data/adb/modules/$(miapp_prop "$MODPATH/module.prop" id)" 'Installed module'
  for miapp_key in ro.product.manufacturer ro.product.model ro.product.marketname ro.product.device \
    ro.product.name ro.build.version.incremental ro.mi.os.version.name ro.mi.os.version.incremental \
    ro.build.version.release ro.build.version.sdk ro.build.version.security_patch \
    ro.product.cpu.abilist ro.build.fingerprint; do
    miapp_property "$miapp_key"
  done
  miapp_print "  kernel=$(uname -r 2>/dev/null)"
  miapp_print "  Installer: BOOTMODE=${BOOTMODE:-unknown}; ARCH=${ARCH:-unknown}; API=${API:-unknown}"
  miapp_su=$(su -v 2>/dev/null)
  miapp_print "  Root su: ${miapp_su:-unknown}"
  if [ "${KSU:-}" = true ]; then
    miapp_print "  Root installer: KernelSU family (fork identity is reported by Root su)"
    miapp_print "  KSU_VER=${KSU_VER:-unknown}; KSU_VER_CODE=${KSU_VER_CODE:-unknown}; KSU_KERNEL_VER_CODE=${KSU_KERNEL_VER_CODE:-unknown}"
  elif [ -n "${MAGISK_VER:-}" ] || [ -n "${MAGISK_VER_CODE:-}" ]; then
    miapp_print "  Root installer: Magisk; MAGISK_VER=${MAGISK_VER:-unknown}; MAGISK_VER_CODE=${MAGISK_VER_CODE:-unknown}"
  else
    miapp_print "  Root installer: unknown"
  fi
  miapp_module_info /data/adb/modules/zygisk_lsposed 'LSPosed (installed)'
  miapp_module_info /data/adb/modules/zygisksu 'Zygisk Next (installed)'
  miapp_print "  Xposed runtime API: see MiAppXSpace runtime environment in LSPosed logs"
  miapp_print "- 安装环境记录结束"
}
