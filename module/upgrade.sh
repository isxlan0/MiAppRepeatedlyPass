#!/system/bin/sh
# Compatibility with installations made before the public module/package rename.
miapp_registry_dir() {
  if [ -d "$1/miapp_instances" ]; then
    printf '%s\n' "$1/miapp_instances"
  else
    printf '%s\n' "$1/miapp_xspace"
  fi
}

miapp_check_legacy_module() {
  [ ! -d "$1" ] || [ ! -f "$1/remove" ]
}

miapp_retire_legacy_module() {
  [ -d "$1" ] || return 0
  miapp_check_legacy_module "$1" || return 1
  # Preserve the original script, but prevent a later uninstall of the old ID
  # from deleting the profiles now managed by miapp_xspace.
  if [ -f "$1/uninstall.sh" ] && [ ! -f "$1/uninstall.before-xspace-migration.sh" ]; then
    cp "$1/uninstall.sh" "$1/uninstall.before-xspace-migration.sh" || return 1
  fi
  printf '%s\n' '#!/system/bin/sh' '# Profiles are now managed by miapp_xspace. Keep their data.' 'exit 0' > "$1/uninstall.sh.new" || return 1
  chmod 0755 "$1/uninstall.sh.new" || return 1
  mv -f "$1/uninstall.sh.new" "$1/uninstall.sh" || return 1
  touch "$1/disable" || return 1
}
