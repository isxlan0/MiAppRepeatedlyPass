"""Extract compatibility inputs from a list of full OTA URLs, without flashing.

Reuses extract_ota_file's verified Range/payload/EROFS reader. Each ROM keeps its
own cache, source manifest, original files and failure inventory. Query tokens
are not copied into reports. No pattern generation or allowlist changes occur.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import sys
import time
import urllib.parse
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from extract_ota_file import Erofs, HttpRanges, Partition, Payload, ZipReader, extract, sha

TARGETS = {
    'framework.jar', 'services.jar', 'miui-framework.jar', 'miui-services.jar',
    'libhyper_os_security_public.so', 'libhyper_os_bundle_public.so',
    'libhyper_os_bundle_private.dylib.so', 'SecurityCoreAdd.apk',
    'SecurityCenter.apk', 'MiuiHome.apk', 'Settings.apk',
}
ALIASES = {'MIUISecurityCenter.apk': 'SecurityCenter.apk'}


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + '.tmp')
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    temporary.replace(path)


class RetryingRanges(HttpRanges):
    def _fetch(self, offset, length):
        for attempt in range(3):
            try:
                return super()._fetch(offset, length)
            except ValueError as error:
                # Integrity, budget and source-generation failures must not be retried.
                if 'Range download failed:' not in str(error) or attempt == 2:
                    raise
                time.sleep(attempt + 1)


def directory_index(erofs):
    found = {}

    def walk(nid, path, depth):
        inode = erofs.inode(nid)
        if stat.S_ISREG(inode['mode']):
            found[path] = inode['size']
        elif stat.S_ISDIR(inode['mode']) and depth <= 4:
            for name, child in sorted(erofs.directory(inode).items()):
                if name in ('.', '..'):
                    continue
                if '/' in name or '\\' in name or name in ('', '..'):
                    raise ValueError('Unsafe EROFS filename')
                child_path = path.rstrip('/') + '/' + name
                # Inspect only relevant directories, not the entire product tree.
                if depth == 0 and name not in ('system', 'app', 'priv-app', 'framework', 'lib64', 'etc', 'build.prop'):
                    continue
                if path == '/system' and name not in ('app', 'priv-app', 'framework', 'lib64', 'etc', 'build.prop'):
                    continue
                walk(child, child_path, depth + 1)

    walk(erofs.root, '/', 0)
    return found


def audit_source(line, args):
    label, url = line.split(maxsplit=1)
    if not re.fullmatch(r'[\w.-]+', label) or label in ('.', '..'):
        raise ValueError('Invalid ROM label')
    work = args.output / label
    work.mkdir(parents=True, exist_ok=True)
    result = {'label': label, 'files': [], 'errors': []}
    source = None
    try:
        source = RetryingRanges(url, work / 'http', args.max_download_mib * 1024**2,
                                curl='curl.exe' if os.name == 'nt' else 'curl',
                                resolve=next((value for value in args.resolve
                                              if value.split(':')[0] == urllib.parse.urlsplit(url).hostname), None))
        payload = Payload(source)
        result.update(source=source.origin, source_size=source.size, etag=source.etag,
                      manifest_sha256=payload.manifest_hash, partitions=list(payload.partitions))
        with zipfile.ZipFile(ZipReader(source)) as archive:
            metadata = archive.getinfo('META-INF/com/android/metadata')
            if metadata.file_size > 1024 * 1024:
                raise ValueError('Oversized OTA metadata')
            result['ota_metadata'] = archive.read(metadata).decode('utf-8')
            (work / 'ota-metadata.txt').write_text(result['ota_metadata'], encoding='utf-8')
        print(f'{label}: manifest verified, inspecting partitions', flush=True)
        for name in ('system', 'system_ext', 'product', 'mi_product', 'mi_ext'):
            if name not in payload.partitions:
                continue
            try:
                partition = Partition(payload, name, work / (name + '.INCOMPLETE.img'))
                index = directory_index(Erofs(partition))
                write_json(work / (name + '-index.json'), index)
                selected = [path for path in index if ALIASES.get(PurePosixPath(path).name, PurePosixPath(path).name) in TARGETS
                            or path in ('/build.prop', '/system/build.prop', '/etc/build.prop',
                                        '/etc/permissions/android.software.managed_users.xml')]
                for path in selected:
                    output = work / 'files' / name / path.lstrip('/')
                    record = {'partition': name, 'path': path, 'output': str(output.relative_to(work))}
                    report_path = output.with_suffix(output.suffix + '.json')
                    try:
                        cached = json.loads(report_path.read_text(encoding='utf-8')) if report_path.exists() else {}
                        if (cached.get('manifest_sha256') == payload.manifest_hash and output.is_file()
                                and sha(output.read_bytes()) == cached.get('sha256')):
                            record.update(cached)
                        else:
                            record.update(extract(partition, path, args.dump_erofs, output))
                            record.update(manifest_sha256=payload.manifest_hash, source=source.origin,
                                          verification='Payload operation SHA-256; JAR/APK ZIP CRC; not full OTA signature')
                            write_json(report_path, record)
                        result['files'].append(record)
                        print(f'{label}: {name}{path} {record["file_size"]:,} bytes OK', flush=True)
                    except Exception as error:
                        record['error'] = str(error)
                        result['errors'].append(record)
                        print(f'{label}: FAILED {name}{path}: {error}', flush=True)
                    write_json(work / 'extraction.json', result)
            except Exception as error:
                result['errors'].append({'partition': name, 'error': str(error)})
                print(f'{label}: FAILED {name}: {error}', flush=True)
        present = {ALIASES.get(PurePosixPath(item['path']).name, PurePosixPath(item['path']).name) for item in result['files']}
        result['missing_targets'] = sorted(TARGETS - present)
    except Exception as error:
        result['errors'].append({'error': str(error)})
        print(f'{label}: FAILED {error}', flush=True)
    finally:
        if source is not None:
            result['downloaded_bytes_this_run'] = source.downloaded
            result['selected_range_bytes'] = sum(length for _, length in source.used_ranges)
        write_json(work / 'extraction.json', result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--list', type=Path, default=ROOT / 'OS卡刷机包列表.md')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/ota-matrix-20261127')
    parser.add_argument('--dump-erofs', type=Path, default=ROOT / 'build/rom-tools/erofs/dump.erofs.exe')
    parser.add_argument('--workers', type=int, choices=range(1, 5), default=3)
    parser.add_argument('--max-download-mib', type=int, default=1024)
    parser.add_argument('--labels', nargs='*')
    parser.add_argument('--resolve', action='append', default=[], help='curl HOST:443:IP override; TLS still verified')
    args = parser.parse_args()
    if args.max_download_mib <= 0 or not args.dump_erofs.is_file():
        parser.error('A positive download budget and dump.erofs executable are required')
    lines = [line.strip() for line in args.list.read_text(encoding='utf-8-sig').splitlines() if line.strip()]
    labels = [line.split(maxsplit=1)[0] for line in lines]
    if len(labels) != len(set(labels)):
        parser.error('ROM labels must be unique')
    if args.labels:
        if set(args.labels) - set(labels):
            parser.error('Unknown ROM labels: ' + ', '.join(sorted(set(args.labels) - set(labels))))
        lines = [line for line in lines if line.split(maxsplit=1)[0] in args.labels]
    if not lines:
        parser.error('No OTA rows selected')
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        results = list(pool.map(lambda line: audit_source(line, args), lines))
    write_json(args.output / 'extractions.json', [
        json.loads(path.read_text(encoding='utf-8')) for path in sorted(args.output.glob('*/extraction.json'))])
    return int(any(item['errors'] or item.get('missing_targets') for item in results))


if __name__ == '__main__':
    sys.exit(main())
