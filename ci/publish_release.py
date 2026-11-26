"""Publish a verified ZIP from main; drafts are resumable, public releases immutable."""
from pathlib import Path
import base64
import hashlib
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from release_metadata import VERSION_PATTERN, parse_changelog, read_releases


class GitHub:
    def __init__(self, repository, token):
        self.base = f'https://api.github.com/repos/{repository}'
        self.token = token

    def request(self, method, path, data=None, missing_ok=False, binary=False):
        url = path if path.startswith('https://') else self.base + path
        if urllib.parse.urlparse(url).hostname not in ('api.github.com', 'uploads.github.com'):
            raise RuntimeError('Unexpected GitHub API host')
        headers = {'Authorization': f'Bearer {self.token}', 'Accept': 'application/vnd.github+json',
                   'X-GitHub-Api-Version': '2022-11-28', 'User-Agent': 'MiAppXSpace-release'}
        if data is not None:
            headers['Content-Type'] = 'application/zip' if binary else 'application/json'
            if not binary:
                data = json.dumps(data).encode('utf-8')
        request = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                body = response.read()
                return json.loads(body) if body else None
        except urllib.error.HTTPError as error:
            if missing_ok and error.code == 404:
                return None
            # Do not turn auth, network, or rate-limit failures into "not released".
            raise RuntimeError(f'GitHub {method} {urllib.parse.urlparse(url).path}: HTTP {error.code}') from None

    def releases(self):
        result = []
        page = 1
        while True:
            items = self.request('GET', f'/releases?per_page=100&page={page}')
            result.extend(items)
            if len(items) < 100:
                return result
            page += 1

    def tag_commit(self, tag):
        ref = self.request('GET', '/git/ref/tags/' + tag, missing_ok=True)
        if ref is None:
            return None
        obj = ref['object']
        for _ in range(10):
            if obj['type'] == 'commit':
                return obj['sha']
            if obj['type'] != 'tag':
                break
            obj = self.request('GET', '/git/tags/' + obj['sha'])['object']
        raise RuntimeError(f'{tag} does not resolve to a commit')

    def release_metadata(self, tag):
        content = self.request('GET', '/contents/CHANGELOG.md?ref=' + urllib.parse.quote(tag, safe=''))
        if content.get('encoding') != 'base64':
            raise RuntimeError(f'Cannot read versionCode for published release {tag}')
        return parse_changelog(base64.b64decode(content['content']).decode('utf-8-sig'))[0]


def publish(api, release, archive, sha, event, ref):
    if event != 'push' or ref != 'refs/heads/main':
        print('Release skipped: only main branch pushes can publish.')
        return 'skipped'
    if not re.fullmatch(r'[0-9a-f]{40}', sha):
        raise ValueError('Release target must be a full commit SHA')
    records = api.releases()
    current = next((item for item in records if item['tag_name'] == release.tag), None)
    if current and not current['draft']:
        print(f'{release.tag} is already published; keeping its notes and assets unchanged.')
        return 'existing'
    published = [item for item in records if not item['draft']
                 and re.fullmatch('v' + VERSION_PATTERN, item['tag_name'])]
    if published:
        previous = max(published, key=lambda item: tuple(map(int, item['tag_name'][1:].split('.'))))
        previous_version = tuple(map(int, previous['tag_name'][1:].split('.')))
        if release.version_tuple <= previous_version:
            raise ValueError(f'New version must be greater than published {previous["tag_name"]}')
        previous_metadata = api.release_metadata(previous['tag_name'])
        if previous_metadata.tag != previous['tag_name']:
            raise ValueError('Published tag and its CHANGELOG.md version disagree')
        if release.version_code <= previous_metadata.version_code:
            raise ValueError('New versionCode must be greater than the published versionCode')

    # Check conflicts before any mutation, including recovery of an interrupted run.
    target = api.tag_commit(release.tag)
    if target is not None and target != sha:
        raise ValueError(f'{release.tag} already points to a different commit')
    if current and current['target_commitish'] != sha:
        raise ValueError(f'Draft {release.tag} targets a different commit')
    archive = Path(archive)
    if archive.name != release.archive_name:
        raise ValueError('Release ZIP filename does not match CHANGELOG.md')
    payload = archive.read_bytes()
    digest = hashlib.sha256(payload).hexdigest()
    if target is None:
        api.request('POST', '/git/refs', {'ref': 'refs/tags/' + release.tag, 'sha': sha})
    metadata = {'tag_name': release.tag, 'target_commitish': sha,
                'name': 'MiApp XSpace ' + release.tag, 'body': release.notes,
                'draft': True, 'prerelease': False}
    if current is None:
        current = api.request('POST', '/releases', metadata)
    else:
        current = api.request('PATCH', f'/releases/{current["id"]}', metadata)
    for asset in current.get('assets', []):
        if asset['name'] == archive.name:
            api.request('DELETE', f'/releases/assets/{asset["id"]}')
    upload_url = current['upload_url'].split('{', 1)[0] + '?name=' + urllib.parse.quote(archive.name)
    asset = api.request('POST', upload_url, payload, binary=True)
    if asset.get('state') != 'uploaded' or asset.get('size') != len(payload):
        raise RuntimeError('Release asset did not finish uploading; leaving a recoverable draft')
    if asset.get('digest') and asset['digest'] != 'sha256:' + digest:
        raise RuntimeError('Uploaded asset checksum mismatch; leaving a recoverable draft')
    if api.tag_commit(release.tag) != sha:
        raise RuntimeError('Release tag changed during upload; leaving a draft')
    result = api.request('PATCH', f'/releases/{current["id"]}', {'draft': False, 'make_latest': 'true'})
    print('Published ' + result['html_url'])
    return 'published'


def main():
    release = read_releases()[0]
    api = GitHub(os.environ['GITHUB_REPOSITORY'], os.environ['GITHUB_TOKEN'])
    publish(api, release, Path('dist') / release.archive_name, os.environ['GITHUB_SHA'],
            os.environ['GITHUB_EVENT_NAME'], os.environ['GITHUB_REF'])


if __name__ == '__main__':
    main()
