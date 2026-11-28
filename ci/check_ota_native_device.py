"""Run production C++ recognizers on OTA samples in an isolated Android temp dir.

Requires adb and the Android NDK. No root, package installation, module changes,
hooks in other processes, or reboot. The runtime test maps private APK copies
and patches only its own memory; it never calls the ROM's mapped functions.
This checks recognizer parity, not behavior on the sample's original device.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import sys
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import build_native
sys.path.insert(0, str(ROOT / 'ci'))
from audit_ota_matrix import write_json


def run(command):
    result = subprocess.run(list(map(str, command)), capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=240)
    if result.returncode:
        raise RuntimeError(f'exit={result.returncode}\n{result.stdout}{result.stderr}')
    return result.stdout + result.stderr


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--fixtures', type=Path, default=ROOT / 'build/ota-matrix-20261127')
    parser.add_argument('--labels', nargs='*')
    args = parser.parse_args()
    inventories = sorted(args.fixtures.glob('*/extraction.json'))
    if args.labels:
        unknown = set(args.labels) - {path.parent.name for path in inventories}
        if unknown:
            parser.error('Unknown ROM labels: ' + ', '.join(sorted(unknown)))
        inventories = [path for path in inventories if path.parent.name in args.labels]
    if not inventories:
        parser.error('No extracted ROM inventories found')
    output = args.fixtures / 'native-tests'
    output.mkdir(parents=True, exist_ok=True)
    java = ROOT / 'src/main/java/io/github/isxlan0/miappxspace'
    arrays = re.findall(r'static final int\[\] (\w+)\s*=\s*\{([^}]+)\}', (java / 'NativePatterns.java').read_text())
    badges = re.search(r'int\[\]\[\] BADGES=\{(.*?)\n    \};', (java / 'NativePrepare.java').read_text(), re.S)
    if not arrays or not badges:
        raise ValueError('Missing current recognition patterns')
    arrays += [('BADGE_' + str(i), body) for i, body in enumerate(re.findall(r'\{([^}]+)\}', badges[1]))]
    (output / 'native_patterns.h').write_text('#pragma once\n#include <cstdint>\nnamespace miapp {\n' +
        ''.join('inline constexpr uint32_t ' + name + '[]={' + body + '};\n' for name, body in arrays) + '}\n', encoding='utf-8')
    compiler = build_native.BIN / 'aarch64-linux-android30-clang++.cmd'
    common = [compiler, '-std=c++17', '-O2', '-I' + str(output), '-I' + str(ROOT / 'src/native'), '-static-libstdc++']
    run(common + [ROOT / 'src/native/prepare_system.cpp', '-o', output / 'prepare-system'])
    run(common + [ROOT / 'ci/native_system_probe.cpp', '-o', output / 'system-probe'])
    run(common + ['-DMIAPP_SCAN_DIAGNOSTICS', ROOT / 'ci/native_runtime_test.cpp', ROOT / 'src/native/runtime_scan.cpp',
                  ROOT / 'src/native/runtime_titles.cpp', ROOT / 'src/native/dart_badge.S', '-llog', '-o', output / 'runtime-test'])
    adb = ['adb', '-s', args.serial]
    remote = '/data/local/tmp/miapp-ota-' + uuid.uuid4().hex
    device = {key: run(adb + ['shell', 'getprop', key]).strip() for key in
              ('ro.product.device', 'ro.build.fingerprint', 'ro.build.version.sdk')}
    sources = list((ROOT / 'src/native').glob('*')) + [java / 'NativePatterns.java', java / 'NativePrepare.java',
                                                       ROOT / 'ci/native_runtime_test.cpp', ROOT / 'ci/native_system_probe.cpp']
    write_json(output / 'provenance.json', {'device': device, 'sources': {
        str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in sources if path.is_file()}})
    results = []
    run(adb + ['shell', 'mkdir', remote])
    try:
        for name in ('prepare-system', 'system-probe', 'runtime-test'):
            run(adb + ['push', output / name, remote + '/' + name])
            run(adb + ['shell', 'chmod', '0755', remote + '/' + name])
        for index, inventory_path in enumerate(inventories):
            work = inventory_path.parent
            inventory = json.loads(inventory_path.read_text(encoding='utf-8'))
            result = {'label': inventory['label'], 'manifest_sha256': inventory.get('manifest_sha256'), 'checks': {}}
            case = remote + '/' + str(index)
            run(adb + ['shell', 'mkdir', '-p', case + '/libs', case + '/prepared'])
            files = {Path(item['path']).name: work / item['output'] for item in inventory['files']}
            for stage in ('system-native', 'system-abi-details', 'runtime-native'):
                try:
                    if stage == 'system-native':
                        for name in ('libhyper_os_security_public.so', 'libhyper_os_bundle_public.so', 'libhyper_os_bundle_private.dylib.so'):
                            run(adb + ['push', files[name], case + '/libs/' + name])
                        log = run(adb + ['shell', remote + '/prepare-system', case + '/libs', case + '/prepared'])
                    elif stage == 'system-abi-details':
                        log = run(adb + ['shell', remote + '/system-probe', case + '/libs'])
                    else:
                        layout = work / 'native.properties.layout'
                        compatibility = json.loads((work / 'compatibility.json').read_text(encoding='utf-8'))
                        probe = (not layout.is_file() or compatibility.get('manifest_sha256') != inventory.get('manifest_sha256')
                                 or compatibility['checks']['native']['status'] != 'PASS')
                        if probe:
                            data = bytearray(240)
                            data[:16] = b'MIAPP_LAYOUT_V1!'
                            struct.pack_into('<QQ', data, 16, 1, 1)
                            with zipfile.ZipFile(files['MiuiHome.apk']) as archive, files['MiuiHome.apk'].open('rb') as apk:
                                for offset, name in ((32, 'libapp.so'), (72, 'libapp_launcher.so')):
                                    entry = archive.getinfo('lib/arm64-v8a/' + name)
                                    if entry.compress_type != zipfile.ZIP_STORED or entry.flag_bits & 1:
                                        raise ValueError('Unmappable launcher library')
                                    apk.seek(entry.header_offset)
                                    header = apk.read(30)
                                    if header[:4] != b'PK\x03\x04':
                                        raise ValueError('Invalid APK local header')
                                    names, extra = struct.unpack_from('<HH', header, 26)
                                    struct.pack_into('<Q', data, offset, entry.header_offset + 30 + names + extra)
                            layout = work / 'native-probe.layout'
                            layout.write_bytes(data)
                        run(adb + ['push', files['MiuiHome.apk'], case + '/MiuiHome.apk'])
                        run(adb + ['push', layout, case + '/layout'])
                        log = run(adb + ['shell', remote + '/runtime-test', case + '/layout', case + '/MiuiHome.apk'] + (['probe'] if probe else []))
                    result['checks'][stage] = {'status': 'PASS', 'log': stage + '.log'}
                except Exception as error:
                    log = str(error)
                    result['checks'][stage] = {'status': 'FAIL', 'log': stage + '.log'}
                (work / (stage + '.log')).write_text(log, encoding='utf-8')
                print(inventory['label'], stage, result['checks'][stage]['status'], flush=True)
            write_json(work / 'native-device.json', result)
            results.append(result)
            write_json(output / 'results.json', results)
    finally:
        # Only our freshly generated directory is ever removed.
        if re.fullmatch(r'/data/local/tmp/miapp-ota-[0-9a-f]{32}', remote):
            run(adb + ['shell', 'rm', '-rf', remote])
    write_json(output / 'results.json', [
        json.loads(path.read_text(encoding='utf-8')) for path in sorted(args.fixtures.glob('*/native-device.json'))])
    return int(any(check['status'] != 'PASS' for result in results for check in result['checks'].values()))


if __name__ == '__main__':
    sys.exit(main())
