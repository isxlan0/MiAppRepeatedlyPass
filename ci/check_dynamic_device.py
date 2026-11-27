"""Opt-in isolated Android regression. Never installs, reboots or touches an active module.

Requires three locally extracted ROM fixtures, the NDK-built native test, and a
rooted device. All device writes stay in a unique /data/local/tmp directory.
"""
from pathlib import Path
import argparse
import hashlib
import shlex
import subprocess
import struct
import sys
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import build_native


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--fixtures', type=Path, required=True)
    parser.add_argument('--archive', type=Path, required=True)
    args = parser.parse_args()
    adb = ['adb', '-s', args.serial]
    remote = '/data/local/tmp/miapp-dynamic-' + uuid.uuid4().hex[:12]
    output = ROOT / 'build/dynamic-tests'
    output.mkdir(parents=True, exist_ok=True)

    def run(command, **kwargs):
        result = subprocess.run(command, capture_output=True, text=True, encoding='utf-8', errors='replace', **kwargs)
        if result.returncode:
            raise RuntimeError(f'{command}:\n{result.stdout}{result.stderr}')
        return result.stdout

    def shell(command):
        return run(adb + ['shell', 'su -c ' + shlex.quote(command)])

    def push(path, target):
        run(adb + ['push', str(path), target])

    classes = output / 'classes'
    classes.mkdir(exist_ok=True)
    run(['javac', '--release', '8', '-Xlint:-options', '-encoding', 'UTF-8', '-cp', str(ROOT / 'build/bridge-classes'),
         '-d', str(classes), str(ROOT / 'ci/BindingCacheTest.java'), str(ROOT / 'ci/NativeLayoutFixture.java')])
    classpath = ';'.join(str(p) for p in (ROOT / 'build/bridge-classes', classes, ROOT / 'src/main'))
    print(run(['java', '-Xmx1024m', '-cp', classpath, 'io.github.isxlan0.miappxspace.BindingCacheTest',
               str(args.fixtures / '4.0.6.0/files/product_a/app/SecurityCoreAdd/SecurityCoreAdd.apk'), str(output / 'cache')]).strip(), flush=True)
    run([str(build_native.BIN / 'aarch64-linux-android30-clang++.cmd'), '-std=c++17', '-O2', '-DMIAPP_SCAN_DIAGNOSTICS',
         '-I'+str(ROOT / 'build/native-generated'), '-I'+str(ROOT / 'src/native'), str(ROOT / 'ci/native_runtime_test.cpp'),
         str(ROOT / 'src/native/runtime_scan.cpp'), str(ROOT / 'src/native/runtime_titles.cpp'), str(ROOT / 'src/native/dart_badge.S'),
         '-llog', '-static-libstdc++', '-o', str(ROOT / 'build/native-runtime-dynamic-test')])
    for name in ('startup', 'titles'):
        run([str(build_native.BIN / 'aarch64-linux-android30-clang++.cmd'), '-std=c++17', '-O2',
             str(ROOT / f'ci/native_{name}_test.cpp'), '-llog', '-static-libstdc++', '-o', str(output / (name+'-test'))])
    shell('mkdir ' + remote)
    try:
        push(ROOT / 'build/native-runtime-dynamic-test', remote + '/runtime-test')
        push(ROOT / 'module/bin/prepare-system', remote + '/prepare-system')
        shell(f'chmod 0755 {remote}/runtime-test {remote}/prepare-system')
        for name in ('startup', 'titles'):
            push(output / (name+'-test'), remote+'/'+name+'-test')
            print(shell(f'chmod 0755 {remote}/{name}-test; {remote}/{name}-test {remote}/test-labels.tsv').strip(), flush=True)
        for version in ('4.0.0.44', '4.0.4.0', '4.0.6.0'):
            root = args.fixtures / version
            apk = root / 'files/product_a/priv-app/MiuiHome/MiuiHome.apk'
            libs = root / 'files/system_ext_a/lib64'
            layout = output / (version + '.layout')
            run(['java', '-cp', classpath,
                 'io.github.isxlan0.miappxspace.NativeLayoutFixture', str(apk), str(libs), str(layout)])
            # Deliberately use a different APK filename/location from the ROM.
            push(apk, remote + '/updated-base.apk')
            push(layout, remote + '/expected.layout')
            print(version, shell(f'{remote}/runtime-test {remote}/expected.layout {remote}/updated-base.apk').strip(), flush=True)
            print(version, shell(f'{remote}/runtime-test {remote}/expected.layout {remote}/updated-base.apk changed').strip(), flush=True)
            print(version, 'unrelated patch', shell(f'{remote}/runtime-test {remote}/expected.layout {remote}/updated-base.apk unrelated').strip(), flush=True)
            shell(f'mkdir -p {remote}/rom-libs {remote}/prepared')
            for name in ('libhyper_os_security_public.so', 'libhyper_os_bundle_public.so', 'libhyper_os_bundle_private.dylib.so'):
                push(libs / name, remote + '/rom-libs/' + name)
            print(version, shell(f'{remote}/prepare-system {remote}/rom-libs {remote}/prepared').strip(), flush=True)
            for name in ('libmiapp_security_original.so', 'libmiapp_bundle_original.so', 'libmiapp_bundle_private_orig.so'):
                expected = hashlib.sha256((root / 'prepared/system/system_ext/lib64' / name).read_bytes()).hexdigest()
                actual = shell(f'sha256sum {remote}/prepared/{name}').split()[0]
                assert actual == expected, (version, name, 'native/Java SONAME output differs')
        original = bytearray(apk.read_bytes())
        zip_offset, code_address, _, _, code_file = struct.unpack_from('<5Q', layout.read_bytes(), 32)
        badge = struct.unpack_from('<Q', layout.read_bytes(), 112)[0]
        site = zip_offset + code_file + badge - code_address
        for case in ('changed-pattern', 'duplicate-pattern'):
            changed = original.copy()
            if case == 'changed-pattern':
                changed[site + 4] ^= 1
            else:
                changed[site + 128:site + 148] = changed[site:site + 20]
            fixture = output / (case + '.apk')
            fixture.write_bytes(changed)
            push(fixture, remote + '/updated-base.apk')
            print(case, shell(f'{remote}/runtime-test {remote}/expected.layout {remote}/updated-base.apk refuse').strip(), flush=True)
        stage = output / 'module'
        stage.mkdir(exist_ok=True)
        with zipfile.ZipFile(args.archive) as archive:
            archive.extractall(stage)
        push(stage, remote + '/stage')
        shell(f'chmod 0755 {remote}/stage/bin/prepare-system; mkdir {remote}/stage/.native-templates; '
              f'cp {remote}/stage/system/system_ext/lib64/*.so {remote}/stage/.native-templates/')
        boot = f'/system/bin/sh {remote}/stage/post-fs-data.sh'
        shell(boot)
        log = shell(f'cat {remote}/stage/system-prepare.log; test ! -e {remote}/stage/skip_mount')
        assert 'system adaptation complete' in log, log
        assert 'state=ready' in shell(f'cat {remote}/stage/native-boot.status')
        assert '[✓ 正常运行中]' in shell(f'cat {remote}/stage/module.prop')
        shell(boot)
        log = shell(f'cat {remote}/stage/system-prepare.log; test ! -e {remote}/stage/skip_mount')
        assert 'cache unchanged' in log, log
        # Simulate an old-generation input fingerprint after OTA.
        shell(f'printf stale > {remote}/stage/system-inputs.sha256')
        shell(boot)
        log = shell(f'cat {remote}/stage/system-prepare.log; test ! -e {remote}/stage/skip_mount')
        assert 'system adaptation complete' in log, log
        # Damage one prepared library: cache validation must rebuild all outputs.
        shell(f'printf broken > {remote}/stage/system/system_ext/lib64/libmiapp_security_original.so')
        shell(boot)
        log = shell(f'cat {remote}/stage/system-prepare.log; test ! -e {remote}/stage/skip_mount')
        assert 'system adaptation complete' in log, log
        # A failed scanner must leave mounting disabled. It must recover next time.
        shell(f'mv {remote}/stage/bin/prepare-system {remote}/stage/bin/prepare-system.good; '
              f'printf {shlex.quote("#!/system/bin/sh" + chr(10) + "exit 23" + chr(10))} > {remote}/stage/bin/prepare-system; '
              f'chmod 0755 {remote}/stage/bin/prepare-system')
        shell(boot)
        log = shell(f'cat {remote}/stage/system-prepare.log; test -e {remote}/stage/skip_mount')
        assert 'code 23' in log, log
        assert 'state=error' in shell(f'cat {remote}/stage/native-boot.status')
        assert '系统库适配失败' in shell(f'cat {remote}/stage/module.prop')
        shell(f'mv {remote}/stage/bin/prepare-system.good {remote}/stage/bin/prepare-system')
        shell(boot)
        shell(f'test ! -e {remote}/stage/skip_mount')
        assert 'state=ready' in shell(f'cat {remote}/stage/native-boot.status')
        assert '[✓ 正常运行中]' in shell(f'cat {remote}/stage/module.prop')
        # Unsupported source library must be refused before creating any outputs.
        shell(f'mkdir {remote}/refused; printf broken > {remote}/rom-libs/libhyper_os_security_public.so')
        shell(f'if {remote}/prepare-system {remote}/rom-libs {remote}/refused; then exit 1; fi; '
              f'test -z "$(ls -A {remote}/refused)"')
        print('PASS: pre-mount refresh, cache hit, changed inputs, damaged output, failed scanner, recovery and unsupported ELF', flush=True)
    finally:
        # Fixed, generated test prefix; never an installed module or app data path.
        assert remote.startswith('/data/local/tmp/miapp-dynamic-') and len(remote.rsplit('/', 1)[1]) == 26
        shell('rm -rf ' + remote)


if __name__ == '__main__':
    main()
