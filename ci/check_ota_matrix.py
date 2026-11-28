"""Run current production recognition rules against audit_ota_matrix inputs."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'ci'))
from audit_ota_matrix import ALIASES, write_json


def run_rom(extraction, classes):
    work = extraction.parent
    inventory = json.loads(extraction.read_text(encoding='utf-8'))
    files = {}
    records = {}
    verified = set()
    for record in inventory['files']:
        name = Path(record['path']).name
        name = ALIASES.get(name, name)
        path = work / record['output']
        files.setdefault(name, []).append(path)
        records[path] = record
    result = {'label': inventory['label'], 'manifest_sha256': inventory.get('manifest_sha256'), 'checks': {}}

    def file(name):
        candidates = files.get(name, [])
        if len(candidates) != 1:
            raise ValueError(f'Expected exactly one {name}, found {len(candidates)}')
        path = candidates[0]
        if path not in verified:
            record = records[path]
            if (record.get('manifest_sha256') != inventory.get('manifest_sha256') or
                    hashlib.sha256(path.read_bytes()).hexdigest() != record['sha256']):
                raise ValueError(f'Extracted source identity/hash mismatch: {name}')
            verified.add(path)
        return path

    def check(name, mode, inputs):
        log = work / (name + '.log')
        report = work / (name + '.properties')
        report.unlink(missing_ok=True)
        if mode == 'native':
            Path(str(report) + '.layout').unlink(missing_ok=True)
        try:
            arguments = inputs()
            command = ['java', '-Xmx1024m', '-cp', str(classes),
                       'io.github.isxlan0.miappxspace.OtaCompatibility', mode,
                       str(report), *map(str, arguments)]
            completed = subprocess.run(command, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=240)
            log.write_text(completed.stdout + completed.stderr, encoding='utf-8')
            result['checks'][name] = {'status': 'PASS' if completed.returncode == 0 else 'FAIL',
                                      'exit_code': completed.returncode, 'log': log.name}
        except Exception as error:
            log.write_text(str(error), encoding='utf-8')
            result['checks'][name] = {'status': 'ERROR', 'log': log.name, 'error': str(error)}
        print(inventory['label'], name, result['checks'][name]['status'], flush=True)
        write_json(work / 'compatibility.json', result)

    def framework_inputs(archive):
        file(archive)
        return file('framework.jar').parent.parent, file('miui-framework.jar').parent.parent, archive
    for archive in ('framework.jar', 'miui-framework.jar', 'miui-services.jar', 'services.jar'):
        check(archive, 'framework', lambda archive=archive: framework_inputs(archive))
    for mode, name in (('security', 'SecurityCoreAdd.apk'), ('management', 'SecurityCenter.apk'), ('settings', 'Settings.apk'),
                       ('notification-helper', 'miui-framework.jar')):
        check(mode, mode, lambda name=name: (file(name),))
    def native_inputs():
        for name in ('libhyper_os_bundle_public.so', 'libhyper_os_bundle_private.dylib.so'):
            file(name)
        return file('MiuiHome.apk'), file('libhyper_os_security_public.so').parent
    check('native', 'native', native_inputs)
    props = {}
    for record in inventory['files']:
        if Path(record['path']).name == 'build.prop':
            for line in (work / record['output']).read_text(encoding='utf-8').splitlines():
                if '=' in line and not line.startswith('#'):
                    key, value = line.split('=', 1)
                    if any(word in key for word in ('version.sdk', 'version.release', 'version.incremental', 'fingerprint', 'cpu.abi', 'product.device', 'product.model')):
                        props[key] = value
    result['system_properties'] = props
    abis = props.get('ro.product.cpu.abilist', props.get('ro.system.product.cpu.abilist', ''))
    result['checks']['platform'] = {
        'status': 'PASS' if props.get('ro.build.version.sdk') == '37' and 'arm64-v8a' in abis.split(',') else 'UNVERIFIED',
        'sdk': props.get('ro.build.version.sdk'), 'abis': abis}
    write_json(work / 'compatibility.json', result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixtures', type=Path, default=ROOT / 'build/ota-matrix-20261127')
    parser.add_argument('--workers', type=int, choices=range(1, 5), default=3)
    parser.add_argument('--labels', nargs='*')
    args = parser.parse_args()
    classes = args.fixtures / 'scanner-classes'
    (classes / 'assets').mkdir(parents=True, exist_ok=True)
    sources = ROOT / 'src/main/java/io/github/isxlan0/miappxspace'
    names = ('DexModel.java', 'ResourceTable.java', 'JavaPrepare.java', 'NativePrepare.java', 'NativePatterns.java')
    inputs = [sources / name for name in names] + [ROOT / 'ci/OtaCompatibility.java']
    subprocess.run(['javac', '--release', '8', '-Xlint:-options', '-encoding', 'UTF-8', '-d', str(classes), *map(str, inputs)], check=True)
    for name in ('java-patterns.properties', 'management-patterns.properties'):
        source = ROOT / 'src/main/assets' / name
        (classes / 'assets' / name).write_bytes(source.read_bytes())
        inputs.append(source)
    write_json(args.fixtures / 'scanner-sources.json', {
        str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in inputs})
    inventories = sorted(args.fixtures.glob('*/extraction.json'))
    if args.labels:
        unknown = set(args.labels) - {path.parent.name for path in inventories}
        if unknown:
            parser.error('Unknown ROM labels: ' + ', '.join(sorted(unknown)))
        inventories = [path for path in inventories if path.parent.name in args.labels]
    if not inventories:
        parser.error('No extracted ROM inventories found')
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        results = list(pool.map(lambda path: run_rom(path, classes), inventories))
    write_json(args.fixtures / 'compatibility.json', [
        json.loads(path.read_text(encoding='utf-8')) for path in sorted(args.fixtures.glob('*/compatibility.json'))])
    return int(any(check['status'] != 'PASS' for result in results for check in result['checks'].values()))


if __name__ == '__main__':
    sys.exit(main())
