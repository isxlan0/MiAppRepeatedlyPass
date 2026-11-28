"""Focused negative and execution regressions for reviewed OTA ABI variants.

Uses an isolated adb temporary directory; executes synthetic trampoline code,
never the supplied ROM functions, and leaves installed modules unchanged.
"""
import argparse
from pathlib import Path
import subprocess
import sys
import uuid

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import build_native


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--fixtures', type=Path, required=True)
    args = parser.parse_args()
    out = ROOT / 'build/ota-adaptation/regressions'
    out.mkdir(parents=True, exist_ok=True)
    logs = []

    def run(command):
        p = subprocess.run(list(map(str, command)), capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=240)
        logs.append(p.stdout + p.stderr)
        (out / 'results.log').write_text('\n'.join(logs), encoding='utf-8')
        if p.returncode:
            raise RuntimeError(logs[-1])
        return p.stdout.strip()

    classes = args.fixtures / 'scanner-classes'
    run(['javac', '-encoding', 'UTF-8', '-cp', classes, '-d', classes, ROOT/'ci/OtaAdaptationTest.java', ROOT/'ci/OtaNativeTest.java', ROOT/'ci/OtaJavaRegressionTest.java', ROOT/'ci/FrameworkDiagnosticsTest.java'])
    apk = args.fixtures/'18fold/files/product/app/SecurityCoreAdd/SecurityCoreAdd.apk'
    print(run(['java', '-Xmx768m', '-cp', classes, 'io.github.isxlan0.miappxspace.OtaAdaptationTest', apk]), flush=True)
    for label in ('小米18pm', '18fold'):
        files = args.fixtures/label/'files'
        print(label, run(['java', '-Xmx768m', '-cp', classes, 'io.github.isxlan0.miappxspace.OtaNativeTest', files/'product/priv-app/MiuiHome/MiuiHome.apk', files/'system_ext/lib64', out/label]).splitlines()[-1], flush=True)
    for version in ('4.0.0.44', '4.0.4.0', '4.0.6.0'):
        files = ROOT/'build/rom-audit'/version/'files'
        print(version,run(['java','-Xmx768m','-cp',classes,'io.github.isxlan0.miappxspace.OtaCompatibility','security',out/(version+'.properties'),files/'product_a/app/SecurityCoreAdd/SecurityCoreAdd.apk']),flush=True)
        print(version,run(['java','-Xmx768m','-cp',classes,'io.github.isxlan0.miappxspace.OtaCompatibility','native',out/(version+'.native.properties'),files/'product_a/priv-app/MiuiHome/MiuiHome.apk',files/'system_ext_a/lib64']).splitlines()[-1],flush=True)
    files=ROOT/'build/rom-audit/4.0.6.0/files'
    print(run(['java','-Xmx768m','-cp',classes,'io.github.isxlan0.miappxspace.OtaJavaRegressionTest',files/'product_a/app/SecurityCoreAdd/SecurityCoreAdd.apk',out/'java-negative',files/'system_ext_a/framework/miui-framework.jar']),flush=True)
    print(run(['java','-Xmx768m','-cp',classes,'io.github.isxlan0.miappxspace.FrameworkDiagnosticsTest',ROOT/'build/rom-audit',out/'framework']),flush=True)
    compiler = build_native.BIN/'aarch64-linux-android30-clang++.cmd'
    common = [compiler, '-std=c++17', '-O2', '-I'+str(args.fixtures/'native-tests'), '-I'+str(ROOT/'src/native'), '-static-libstdc++']
    run(common+[ROOT/'ci/native_rust_entry_test.cpp', '-llog', '-ldl', '-o', out/'rust-test'])
    run(common+[ROOT/'ci/native_system_test.cpp', '-o', out/'system-test'])
    adb = ['adb', '-s', args.serial]
    remote = '/data/local/tmp/miapp-abi-'+uuid.uuid4().hex
    run(adb+['shell','mkdir',remote])
    try:
        for name in ('rust-test','system-test'):
            run(adb+['push',out/name,remote+'/'+name])
            run(adb+['shell','chmod','0755',remote+'/'+name])
        print(run(adb+['shell',remote+'/rust-test']), flush=True)
        run(adb+['shell','mkdir',remote+'/libs'])
        def check_libraries(label,libs):
            for name in ('libhyper_os_security_public.so','libhyper_os_bundle_public.so','libhyper_os_bundle_private.dylib.so'):
                run(adb+['push',libs/name,remote+'/libs/'+name])
            print(label,run(adb+['shell',remote+'/system-test',remote+'/libs']),flush=True)
        for label in ('小米18pm','18fold','17tpro'):
            libs = args.fixtures/label/'files/system_ext/lib64'
            check_libraries(label,libs)
        for version in ('4.0.0.44','4.0.4.0','4.0.6.0'):
            check_libraries(version,ROOT/'build/rom-audit'/version/'files/system_ext_a/lib64')
    finally:
        run(adb+['shell','rm','-rf',remote])


if __name__ == '__main__':
    main()
