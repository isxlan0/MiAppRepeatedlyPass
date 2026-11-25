#!/usr/bin/env bash
set -eu
cd "$(dirname "$0")/.."
. module/upgrade.sh

mkdir -p build
workspace_dir=$(pwd -P)
test_root=$(mktemp -d "$workspace_dir/build/upgrade-test.XXXXXX")
test_root=$(cd "$test_root" && pwd -P)
case "$test_root" in "$workspace_dir"/build/upgrade-test.*) ;; *) exit 1 ;; esac
trap 'case "$test_root" in "$workspace_dir"/build/upgrade-test.*) rm -rf -- "$test_root" ;; esac' EXIT
data_dir="$test_root/system"
legacy_module="$test_root/modules/miapp_instances"
mkdir -p "$data_dir" "$legacy_module"
test "$(miapp_registry_dir "$data_dir")" = "$data_dir/miapp_xspace"
mkdir "$data_dir/miapp_instances"
printf '%s\n' 'space.998=1001' > "$data_dir/miapp_instances/clones.properties"
test "$(miapp_registry_dir "$data_dir")" = "$data_dir/miapp_instances"
mkdir "$data_dir/miapp_xspace"
test "$(miapp_registry_dir "$data_dir")" = "$data_dir/miapp_instances"

printf '%s\n' '#!/system/bin/sh' 'exit 71' > "$legacy_module/uninstall.sh"
cp "$legacy_module/uninstall.sh" "$test_root/original-uninstall.sh"
touch "$legacy_module/remove"
if miapp_check_legacy_module "$legacy_module"; then
  echo 'FAIL: accepted a pending legacy removal' >&2
  exit 1
fi
if miapp_retire_legacy_module "$legacy_module"; then
  echo 'FAIL: migrated a module scheduled for removal' >&2
  exit 1
fi
cmp "$legacy_module/uninstall.sh" "$test_root/original-uninstall.sh"
test ! -e "$legacy_module/disable"
rm "$legacy_module/remove"

miapp_check_legacy_module "$legacy_module"
miapp_retire_legacy_module "$legacy_module"
test -f "$legacy_module/disable"
cmp "$legacy_module/uninstall.before-xspace-migration.sh" "$test_root/original-uninstall.sh"
sh "$legacy_module/uninstall.sh"
test "$(cat "$data_dir/miapp_instances/clones.properties")" = 'space.998=1001'
miapp_retire_legacy_module "$legacy_module"
cmp "$legacy_module/uninstall.before-xspace-migration.sh" "$test_root/original-uninstall.sh"
miapp_retire_legacy_module "$test_root/no-legacy-module"
echo 'PASS: pending-removal guard, old module disabled, harmless uninstall, registry retention, idempotent migration'
