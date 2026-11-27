"""Read-only Android verification of framework scanning and installer diagnostics.

Uses only a unique /data/local/tmp directory and its own failure log. Does not
install APKs/modules, create users, change active hooks or reboot the device.
"""
import argparse
from pathlib import Path
import shlex
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--fixture', type=Path, required=True)
    args = parser.parse_args()
    adb = ['adb', '-s', args.serial]
    name = 'miapp-framework-probe-' + uuid.uuid4().hex[:12]
    remote = '/data/local/tmp/' + name
    saved = '/data/adb/' + name + '-prepare.log'
    logdir = ROOT / 'build/framework-tests'
    logdir.mkdir(parents=True, exist_ok=True)

    def shell(command, check=True):
        result = subprocess.run(adb + ['shell', 'su -c ' + shlex.quote(command)], capture_output=True,
                                text=True, encoding='utf-8', errors='replace')
        if check and result.returncode:
            raise RuntimeError(result.stdout + result.stderr)
        return result

    def push(source, target):
        subprocess.run(adb + ['push', str(source), target], check=True, capture_output=True)

    shell(f'test ! -e {remote} && test ! -e {saved} && mkdir {remote}')
    try:
        push(ROOT / 'dist/bridge.apk', remote + '/bridge.apk')
        push(args.fixture / 'files/system/system', remote + '/system')
        push(args.fixture / 'files/system_ext', remote + '/system_ext')
        package = shell('pm path --user 0 com.miui.securitycore').stdout
        apk = next(line.removeprefix('package:').strip() for line in package.splitlines() if line.startswith('package:'))
        command = (f'CLASSPATH={remote}/bridge.apk app_process /system/bin '
                   'io.github.isxlan0.miappxspace.JavaPrepare '
                   f'{remote}/system {remote}/system_ext {shlex.quote(apk)} {remote}/framework.properties')
        result = shell(command)
        (logdir / 'android-framework.log').write_text(result.stdout + result.stderr, encoding='utf-8')
        assert 'PASS: services.jar 15 required method shapes' in result.stdout, result.stdout
        report = shell(f'cat {remote}/framework.properties').stdout
        assert 'Ljava/lang/Object;[Ljava/lang/String;' in report, report
        print('PASS: target ROM framework scanner on Android app_process (no hooks installed)', flush=True)

        # Convert source CRLF to LF because this bypasses the ZIP packaging step.
        diagnostics = logdir / 'install-diagnostics.sh'
        diagnostics.write_text((ROOT / 'module/install-diagnostics.sh').read_text(encoding='utf-8'), encoding='utf-8', newline='\n')
        push(diagnostics, remote + '/install-diagnostics.sh')
        props = logdir / 'probe-module.prop'
        props.write_text(f'id={name}\nname=MiApp diagnostics probe\nversion=1.0.4\nversionCode=10004\n', encoding='utf-8', newline='\n')
        push(props, remote + '/module.prop')
        harness = logdir / 'diagnostics-device.sh'
        harness.write_text(
            '#!/system/bin/sh\n'
            f'MODPATH={remote}\n'
            'BOOTMODE=true; ARCH=arm64; API=37; KSU=true\n'
            'ui_print() { printf "%s\\n" "$*"; }\n'
            'abort() { exit 29; }\n'
            '. "$MODPATH/install-diagnostics.sh"\n'
            'miapp_environment\n'
            'miapp_abort "PROBE_EXPECTED_FAILURE"\n', encoding='utf-8', newline='\n')
        push(harness, remote + '/diagnostics-device.sh')
        result = shell(f'sh {remote}/diagnostics-device.sh', check=False)
        assert result.returncode == 29, result.stdout + result.stderr
        persisted = shell(f'cat {saved}').stdout
        for label in ('version=1.0.4', 'ro.product.model=', 'ro.build.fingerprint=', 'Root su:',
                      'LSPosed (installed):', 'Zygisk Next (installed):', 'PROBE_EXPECTED_FAILURE'):
            assert label in persisted, label
        (logdir / 'android-install-diagnostics.log').write_text(persisted, encoding='utf-8')
        assert shell(f'stat -c %a {saved}').stdout.strip() == '600'
        print('PASS: real Android device/environment reporting and protected failure-log persistence', flush=True)
    finally:
        # These exact, unique paths belong to this probe; no active module is touched.
        shell(f'rm -rf {remote}; rm -f {saved}')


if __name__ == '__main__':
    main()
