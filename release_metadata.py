"""Read the shared build/release version from CHANGELOG.md (standard library only)."""
from dataclasses import dataclass
from pathlib import Path
import argparse
import json
import os
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
ANDROID_NS = 'http://schemas.android.com/apk/res/android'
VERSION_PATTERN = r'(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)'


@dataclass(frozen=True)
class Release:
    version: str
    version_code: int
    notes: str

    @property
    def version_tuple(self):
        return tuple(map(int, self.version.split('.')))

    @property
    def tag(self):
        return 'v' + self.version

    @property
    def archive_name(self):
        return f'MiAppXSpace-{self.version}.zip'


def parse_changelog(text):
    """Level-two headings delimit versions; fenced Markdown is kept verbatim."""
    sections = []
    current = None
    fence = None
    for line in text.splitlines():
        marker = re.match(r'^ {0,3}(`{3,}|~{3,})', line)
        was_fenced = fence is not None
        if marker:
            token = marker[1]
            if fence is None:
                fence = token
            elif token[0] == fence[0] and len(token) >= len(fence) and not line[marker.end():].strip():
                fence = None
        heading = re.fullmatch(r'##\s+(.+?)\s*', line) if not was_fenced and not marker else None
        if heading:
            if not re.fullmatch(VERSION_PATTERN, heading[1]):
                raise ValueError(f'Invalid release heading: {line}; use ## major.minor.patch')
            current = (heading[1], [])
            sections.append(current)
        elif current is not None:
            current[1].append(line)
    if not sections:
        raise ValueError('CHANGELOG.md must contain at least one ## major.minor.patch release')
    releases = []
    for version, lines in sections:
        content = '\n'.join(lines).strip()
        first, _, notes = content.partition('\n')
        code = re.fullmatch(r'versionCode: ([1-9][0-9]*)', first)
        if not code or int(code[1]) > 2147483647:
            raise ValueError(f'{version}: the first line must be versionCode: <positive Android integer>')
        if not notes.strip():
            raise ValueError(f'{version}: release notes must not be empty')
        release = Release(version, int(code[1]), notes.strip() + '\n')
        if releases and (release.version_tuple >= releases[-1].version_tuple
                         or release.version_code >= releases[-1].version_code):
            raise ValueError('Versions and versionCode must strictly decrease from newest to oldest; duplicates are forbidden')
        releases.append(release)
    return releases


def read_releases(path=ROOT / 'CHANGELOG.md'):
    return parse_changelog(Path(path).read_text(encoding='utf-8-sig'))


def render_manifest(source, release):
    manifest = ET.fromstring(source)
    manifest.set(f'{{{ANDROID_NS}}}versionName', release.version)
    manifest.set(f'{{{ANDROID_NS}}}versionCode', str(release.version_code))
    ET.register_namespace('android', ANDROID_NS)
    return ET.tostring(manifest, encoding='unicode') + '\n'


def render_module_prop(source, release):
    for key, value in [('version', release.version), ('versionCode', str(release.version_code))]:
        source, count = re.subn(r'^' + key + r'=.*$', key + '=' + value, source, flags=re.MULTILINE)
        if count != 1:
            raise ValueError(f'module.prop must contain exactly one {key} field')
    return source.replace('\r\n', '\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--changelog', type=Path, default=ROOT / 'CHANGELOG.md')
    parser.add_argument('--github-output', type=Path)
    parser.add_argument('--notes', type=Path)
    args = parser.parse_args()
    release = read_releases(args.changelog)[0]
    metadata = dict(version=release.version, version_code=release.version_code,
                    tag=release.tag, archive=release.archive_name)
    if args.notes:
        args.notes.parent.mkdir(parents=True, exist_ok=True)
        args.notes.write_text(release.notes, encoding='utf-8', newline='\n')
    if args.github_output:
        sha = os.environ['GITHUB_SHA']
        run_id = os.environ['GITHUB_RUN_ID']
        attempt = os.environ['GITHUB_RUN_ATTEMPT']
        metadata['artifact'] = f'MiAppXSpace-{release.version}-{sha[:12]}-{run_id}-{attempt}'
        with args.github_output.open('a', encoding='utf-8', newline='\n') as output:
            for key, value in metadata.items():
                output.write(f'{key}={value}\n')
    print(json.dumps(metadata, ensure_ascii=False))


if __name__ == '__main__':
    main()
