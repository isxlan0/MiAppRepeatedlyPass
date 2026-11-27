"""Exercise installer failures in an isolated directory, with no root/device writes."""
from pathlib import Path
import os
import shlex
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
BASH = shutil.which('bash') if os.name != 'nt' else r'C:\Program Files\Git\bin\bash.exe'


@unittest.skipUnless(BASH and Path(BASH).is_file(), 'POSIX shell unavailable')
class InstallDiagnosticsTests(unittest.TestCase):
    def run_installer(self, *, api='37', arch='arm64', boot='true', lsp='enabled',
                      root='ksu', scan_fail=False):
        with tempfile.TemporaryDirectory(prefix='miapp-installer-') as folder:
            base = Path(folder).resolve()
            adb = base / 'adb'
            stage = adb / 'modules_update/miapp_test'
            stage.mkdir(parents=True)
            (stage / 'module.prop').write_text(
                'id=miapp_test\nname=MiApp test\nversion=1.0.4\nversionCode=10004\n', encoding='utf-8')
            installed = adb / 'modules/miapp_test'
            installed.mkdir(parents=True)
            (installed / 'module.prop').write_text(
                'id=miapp_test\nname=Previous\nversion=1.0.3\nversionCode=10003\n', encoding='utf-8')
            if lsp != 'missing':
                lsposed = adb / 'modules/zygisk_lsposed'
                lsposed.mkdir(parents=True)
                (lsposed / 'module.prop').write_text(
                    'name=LSPosed\r\nversion=v2.2.1 (7912)\r\nversionCode=7912\r\n', encoding='utf-8')
                if lsp in ('disable', 'remove'):
                    (lsposed / lsp).touch()
            zygisk = adb / 'modules/zygisksu'
            zygisk.mkdir(parents=True)
            (zygisk / 'module.prop').write_text(
                'name=Zygisk Next\nversion=1.5.3\nversionCode=900\n', encoding='utf-8')
            text = (ROOT / 'module/install-diagnostics.sh').read_text(encoding='utf-8')
            (stage / 'install-diagnostics.sh').write_text(
                text.replace('/data/adb', adb.as_posix()), encoding='utf-8', newline='\n')
            customize = (ROOT / 'module/customize.sh').read_text(encoding='utf-8')
            if scan_fail:
                customize = customize.split('# The private namespace')[0]
                customize = customize.replace('/system/bin/unshare', 'unshare_probe')
                libs = stage / 'system/system_ext/lib64'
                libs.mkdir(parents=True)
                (libs / 'test.so').write_bytes(b'fixture')
                (stage / 'bin').mkdir()
                (stage / 'bin/prepare-system').touch()
                (stage / 'upgrade.sh').write_text(
                    'miapp_check_legacy_module() { return 0; }\n'
                    'miapp_registry_dir() { printf "/unused-test-registry\\n"; }\n', encoding='utf-8')
            else:
                customize = customize.split('. "$MODPATH/upgrade.sh"')[0] + '\nmiapp_print "PROBE_COMPLETE"\n'
            customize = customize.replace('/data/adb', adb.as_posix())
            (stage / 'customize.sh').write_text(customize, encoding='utf-8', newline='\n')
            environment = (
                'KSU=true; KSU_VER=v3.0.0; KSU_VER_CODE=30000; KSU_KERNEL_VER_CODE=30001\n'
                'su() { printf "3.0.0:SukiSU\\n"; }\n' if root == 'ksu' else
                'MAGISK_VER=30.6; MAGISK_VER_CODE=30600\nsu() { printf "30.6:MAGISKSU\\n"; }\n'
                if root == 'magisk' else 'su() { return 1; }\n')
            harness = (
                'unset KSU KSU_VER KSU_VER_CODE KSU_KERNEL_VER_CODE MAGISK_VER MAGISK_VER_CODE\n'
                'ui_print() { printf "%s\\n" "$*"; }\n'
                'abort() { printf "ABORT: %s\\n" "$*"; exit 1; }\n'
                'unshare_probe() { return 23; }\n'
                'getprop() { case "$1" in\n'
                '  ro.product.model) printf "Xiaomi 15 Ultra\\n";;\n'
                '  ro.product.device) printf "xuanyuan\\n";;\n'
                '  ro.build.version.incremental) printf "OS4.0.4.0.XOACNXM\\n";;\n'
                '  ro.build.fingerprint) printf "%s\\n" \'test/$(touch UNEXPECTED_EXECUTION)/fingerprint\';;\n'
                'esac; }\n' + environment +
                f'MODPATH={shlex.quote(stage.as_posix())}\n'
                f'API={api}; ARCH={arch}; BOOTMODE={boot}\n'
                '. "$MODPATH/customize.sh"\n')
            script = base / 'run.sh'
            script.write_text(harness, encoding='utf-8', newline='\n')
            result = subprocess.run([BASH, str(script)], cwd=base, capture_output=True, text=True,
                                    encoding='utf-8', errors='replace', timeout=30)
            self.assertEqual(result.stderr, '', result.stderr)
            log = (stage / 'install.log').read_text(encoding='utf-8')
            self.assertIn('ZIP module: name=MiApp test; version=1.0.4; versionCode=10004', log)
            self.assertIn('Installed module: name=Previous; version=1.0.3', log)
            self.assertIn('ro.product.model=Xiaomi 15 Ultra', log)
            self.assertIn('ro.product.device=xuanyuan', log)
            self.assertIn('ro.build.version.incremental=OS4.0.4.0.XOACNXM', log)
            self.assertIn('Zygisk Next (installed): name=Zygisk Next; version=1.5.3', log)
            self.assertIn('ro.product.marketname=unknown', log)
            self.assertIn('Xposed runtime API: see MiAppXSpace runtime environment', log)
            self.assertNotIn('Xposed API=37', log)
            self.assertFalse((base / 'UNEXPECTED_EXECUTION').exists())
            for line in log.splitlines():
                self.assertIn(line, result.stdout)
            saved = adb / 'miapp_test-prepare.log'
            if result.returncode:
                # Simulate manager cleanup and ensure the full diagnostic survives.
                self.assertEqual(stage.resolve().parent, (adb / 'modules_update').resolve())
                shutil.rmtree(stage)
                self.assertEqual(saved.read_text(encoding='utf-8'), log)
                self.assertIn(str(saved.as_posix()), result.stdout)
            else:
                self.assertFalse(saved.exists())
            return result.returncode, log

    def test_sukisu_and_module_versions(self):
        code, log = self.run_installer()
        self.assertEqual(code, 0)
        self.assertIn('Root su: 3.0.0:SukiSU', log)
        self.assertIn('KSU_KERNEL_VER_CODE=30001', log)
        self.assertIn('LSPosed (installed): name=LSPosed; version=v2.2.1 (7912); versionCode=7912; state=enabled', log)

    def test_magisk(self):
        code, log = self.run_installer(root='magisk')
        self.assertEqual(code, 0)
        self.assertIn('Root installer: Magisk; MAGISK_VER=30.6; MAGISK_VER_CODE=30600', log)

    def test_unknown_root_is_not_guessed(self):
        code, log = self.run_installer(root='unknown')
        self.assertEqual(code, 0)
        self.assertIn('Root installer: unknown', log)
        self.assertIn('Root su: unknown', log)

    def test_every_early_abort_preserves_environment(self):
        for config, error in [({'api': '36'}, '仅适配 SDK 37'),
                              ({'arch': 'x64'}, '仅支持 arm64'),
                              ({'boot': 'false'}, '请在 KernelSU 或 Magisk'),
                              ({'lsp': 'disable'}, 'LSPosed 处于禁用状态'),
                              ({'lsp': 'remove'}, 'LSPosed 已安排卸载'),
                              ({'lsp': 'missing'}, '请先安装并启用 LSPosed')]:
            with self.subTest(config=config):
                code, log = self.run_installer(**config)
                self.assertEqual(code, 1)
                self.assertIn('FAIL: ' + error, log)
                self.assertNotIn('PROBE_COMPLETE', log)

    def test_silent_scan_failure_has_exit_code_and_environment(self):
        code, log = self.run_installer(scan_fail=True)
        self.assertEqual(code, 1)
        self.assertIn('FAIL: preparation process exited with code 23', log)


if __name__ == '__main__':
    unittest.main()
