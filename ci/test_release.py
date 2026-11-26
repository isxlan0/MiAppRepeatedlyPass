"""Regression checks for version authority, signing, and safe release retries."""
from copy import deepcopy
from pathlib import Path
import hashlib
import os
import sys
import tempfile
import unittest
from unittest.mock import patch
import urllib.error
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import build
from release_metadata import ANDROID_NS, Release, parse_changelog, render_manifest, render_module_prop
from publish_release import GitHub, publish

SHA = 'a' * 40
OTHER_SHA = 'b' * 40
CURRENT = Release('1.0.2', 10002, '- 修复通知。\n')


def entry(version='1.0.2', code=10002, notes='- 更新。'):
    return f'## {version}\nversionCode: {code}\n\n{notes}\n'


class ChangelogTests(unittest.TestCase):
    def test_reads_latest_and_preserves_markdown(self):
        notes = '### 修复\n- **中文**与 `代码`。\n\n```text\n## example\n```'
        releases = parse_changelog('# 更新日志\n\n' + entry(notes=notes) + '\n' + entry('1.0.1', 10001))
        self.assertEqual(releases[0], Release('1.0.2', 10002, notes + '\n'))
        self.assertEqual(releases[0].archive_name, 'MiAppXSpace-1.0.2.zip')
        self.assertNotIn('versionCode:', releases[0].notes)

    def test_rejects_invalid_or_empty_versions(self):
        for text in ['', entry('v1.0.2'), entry('1.2'), entry('01.0.2'), entry('1.0.2-beta'),
                     entry(code=0), entry(code=-1), entry(code=2147483648), entry(notes=''),
                     '## 1.0.2\n- missing code']:
            with self.subTest(text=text), self.assertRaises(ValueError):
                parse_changelog(text)

    def test_rejects_duplicates_and_backwards_history(self):
        for older in [entry(), entry('1.0.3', 10001), entry('1.0.1', 10002), entry('1.0.1', 10003)]:
            with self.subTest(older=older), self.assertRaises(ValueError):
                parse_changelog(entry() + older)

    def test_compares_versions_numerically(self):
        releases = parse_changelog(entry('1.0.10', 10010) + entry('1.0.9', 10009))
        self.assertEqual(releases[0].version, '1.0.10')

    def test_generated_versions_override_stale_templates(self):
        release = Release('2.3.4', 20304, '- New release.\n')
        source = f'<manifest xmlns:android="{ANDROID_NS}" package="io.example" android:versionName="1.0.0" android:versionCode="1"><application android:label="Test" /></manifest>'
        generated = ET.fromstring(render_manifest(source, release))
        self.assertEqual(generated.get(f'{{{ANDROID_NS}}}versionName'), '2.3.4')
        self.assertEqual(generated.get(f'{{{ANDROID_NS}}}versionCode'), '20304')
        self.assertEqual(generated.find('application').get(f'{{{ANDROID_NS}}}label'), 'Test')
        self.assertEqual(render_module_prop('id=test\r\nversion=1.0.0\r\nversionCode=1\r\n', release),
                         'id=test\nversion=2.3.4\nversionCode=20304\n')
        with self.assertRaises(ValueError):
            render_module_prop('version=1\nversion=2\nversionCode=1\n', release)

    def test_build_reads_changelog_instead_of_template_versions(self):
        with patch.object(build, 'read_releases', return_value=[Release('2.0.0', 20000, '- New.\n')]):
            self.assertEqual(build.release_metadata()[:2], ('2.0.0', '20000'))


class SigningTests(unittest.TestCase):
    def test_ci_missing_secrets_never_generates_a_key(self):
        for env in [{'CI': 'true'}, {'GITHUB_ACTIONS': 'true', 'MIAPP_SIGNING_PASSWORD': 'example'}]:
            with self.subTest(env=env), patch.dict(os.environ, env, clear=True), patch.object(build, 'run') as run:
                with self.assertRaises(RuntimeError):
                    build.signing_key()
                run.assert_not_called()

    def test_explicit_key_requires_file_and_password(self):
        with tempfile.TemporaryDirectory() as folder:
            key = Path(folder) / 'signing.p12'
            env = {'CI': 'true', 'MIAPP_SIGNING_KEYSTORE': str(key), 'MIAPP_SIGNING_PASSWORD': 'example'}
            with patch.dict(os.environ, env, clear=True):
                with self.assertRaises(RuntimeError):
                    build.signing_key()
                key.write_bytes(b'test fixture')
                self.assertEqual(build.signing_key(), key)
                del os.environ['MIAPP_SIGNING_PASSWORD']
                with self.assertRaises(RuntimeError):
                    build.signing_key()

    def test_local_build_reuses_existing_key(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / '.local').mkdir()
            key = root / '.local/signing.p12'
            key.write_bytes(b'existing fixture')
            with patch.dict(os.environ, {}, clear=True), patch.object(build, 'ROOT', root), patch.object(build, 'run') as run:
                self.assertEqual(build.signing_key(), key)
                self.assertEqual(key.read_bytes(), b'existing fixture')
                run.assert_not_called()


class FakeGitHub:
    """In-memory GitHub API with observable writes and recoverable upload failure."""
    def __init__(self):
        self.records = []
        self.tags = {}
        self.metadata = {}
        self.writes = []
        self.fail_upload = False
        self.bad_digest = False

    def releases(self):
        return deepcopy(self.records)

    def tag_commit(self, tag):
        return self.tags.get(tag)

    def release_metadata(self, tag):
        return self.metadata[tag]

    def add_release(self, release=CURRENT, draft=False, sha=SHA):
        record = {'id': len(self.records) + 1, 'tag_name': release.tag, 'draft': draft,
                  'target_commitish': sha, 'assets': [], 'html_url': 'https://example.invalid/release',
                  'upload_url': 'https://uploads.github.com/example/assets{?name,label}'}
        self.records.append(record)
        self.metadata[release.tag] = release
        self.tags[release.tag] = sha
        return record

    def request(self, method, path, data=None, **kwargs):
        self.writes.append((method, path, data))
        if method == 'POST' and path == '/git/refs':
            self.tags[data['ref'].removeprefix('refs/tags/')] = data['sha']
            return {}
        if method == 'POST' and path == '/releases':
            record = self.add_release(draft=True, sha=data['target_commitish'])
            record.update(data)
            return deepcopy(record)
        if method == 'PATCH':
            record = next(item for item in self.records if item['id'] == int(path.rsplit('/', 1)[1]))
            record.update(data)
            return deepcopy(record)
        if method == 'DELETE':
            asset_id = int(path.rsplit('/', 1)[1])
            for record in self.records:
                record['assets'] = [a for a in record['assets'] if a['id'] != asset_id]
            return None
        if method == 'POST' and kwargs.get('binary'):
            if self.fail_upload:
                raise RuntimeError('Simulated upload failure')
            record = next(item for item in self.records if item['tag_name'] == CURRENT.tag)
            asset = {'id': 42, 'name': CURRENT.archive_name, 'size': len(data), 'state': 'uploaded',
                     'digest': 'sha256:' + ('0' * 64 if self.bad_digest else hashlib.sha256(data).hexdigest())}
            record['assets'].append(asset)
            return asset
        raise AssertionError((method, path))


class PublishTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.archive = Path(self.folder.name) / CURRENT.archive_name
        self.archive.write_bytes(b'verified package fixture')
        self.api = FakeGitHub()

    def publish(self, **kwargs):
        return publish(self.api, CURRENT, self.archive, SHA, kwargs.get('event', 'push'), kwargs.get('ref', 'refs/heads/main'))

    def test_non_main_pushes_and_tags_cannot_publish(self):
        for ref in ['refs/heads/develop', 'refs/tags/v1.0.2']:
            self.assertEqual(self.publish(ref=ref), 'skipped')
        self.assertEqual(self.publish(event='pull_request'), 'skipped')
        self.assertEqual(self.api.writes, [])

    def test_first_release_pins_commit_uploads_then_publishes(self):
        self.assertEqual(self.publish(), 'published')
        self.assertEqual(self.api.tags[CURRENT.tag], SHA)
        record = self.api.records[0]
        self.assertFalse(record['draft'])
        self.assertEqual(record['body'], CURRENT.notes)
        self.assertEqual(record['assets'][0]['name'], CURRENT.archive_name)
        self.assertEqual(self.api.writes[-1][2], {'draft': False, 'make_latest': 'true'})

    def test_same_version_after_new_commits_is_immutable(self):
        self.api.add_release(sha=OTHER_SHA)
        before = deepcopy(self.api.records)
        self.assertEqual(self.publish(), 'existing')
        self.assertEqual(self.api.records, before)
        self.assertEqual(self.api.writes, [])

    def test_newer_version_and_code_publish(self):
        self.api.add_release(Release('1.0.1', 10001, '- Previous.\n'), sha=OTHER_SHA)
        self.assertEqual(self.publish(), 'published')

    def test_rollback_cannot_publish_even_if_local_history_was_removed(self):
        self.api.add_release(Release('1.0.3', 10003, '- Newer.\n'))
        with self.assertRaisesRegex(ValueError, 'greater than published'):
            self.publish()
        self.assertEqual(self.api.writes, [])

    def test_code_must_increase_over_published_release(self):
        self.api.add_release(Release('1.0.1', 10002, '- Previous.\n'))
        with self.assertRaisesRegex(ValueError, 'versionCode'):
            self.publish()
        self.assertEqual(self.api.writes, [])

    def test_conflicting_tags_and_drafts_fail_without_writes(self):
        self.api.tags[CURRENT.tag] = OTHER_SHA
        with self.assertRaisesRegex(ValueError, 'different commit'):
            self.publish()
        self.assertEqual(self.api.writes, [])
        self.api = FakeGitHub()
        self.api.add_release(draft=True, sha=OTHER_SHA)
        self.api.tags[CURRENT.tag] = SHA
        with self.assertRaisesRegex(ValueError, 'different commit'):
            self.publish()
        self.assertEqual(self.api.writes, [])

    def test_upload_failure_remains_draft_and_retry_recovers(self):
        self.api.fail_upload = True
        with self.assertRaisesRegex(RuntimeError, 'upload failure'):
            self.publish()
        self.assertTrue(self.api.records[0]['draft'])
        self.api.fail_upload = False
        self.assertEqual(self.publish(), 'published')
        self.assertEqual(len(self.api.records), 1)

    def test_checksum_failure_recovers_by_replacing_draft_asset(self):
        self.api.bad_digest = True
        with self.assertRaisesRegex(RuntimeError, 'checksum mismatch'):
            self.publish()
        self.assertTrue(self.api.records[0]['draft'])
        self.api.bad_digest = False
        self.assertEqual(self.publish(), 'published')
        self.assertEqual(len(self.api.records[0]['assets']), 1)
        self.assertTrue(any(method == 'DELETE' for method, _, _ in self.api.writes))

    def test_missing_package_creates_neither_tag_nor_release(self):
        self.archive.unlink()
        with self.assertRaises(FileNotFoundError):
            self.publish()
        self.assertEqual(self.api.writes, [])

    def test_api_failure_does_not_become_first_release(self):
        with patch.object(self.api, 'releases', side_effect=RuntimeError('API unavailable')):
            with self.assertRaisesRegex(RuntimeError, 'API unavailable'):
                self.publish()
        self.assertEqual(self.api.writes, [])


class GitHubTransportTests(unittest.TestCase):
    def test_only_explicit_404_is_treated_as_missing(self):
        api = GitHub('owner/repo', 'test-token')
        for status in [401, 403, 429, 500]:
            error = urllib.error.HTTPError('https://api.github.com', status, 'test', {}, None)
            with self.subTest(status=status), patch('urllib.request.urlopen', side_effect=error):
                with self.assertRaises(RuntimeError):
                    api.request('GET', '/git/ref/tags/v1.0.2', missing_ok=True)
        error = urllib.error.HTTPError('https://api.github.com', 404, 'test', {}, None)
        with patch('urllib.request.urlopen', side_effect=error):
            self.assertIsNone(api.request('GET', '/git/ref/tags/v1.0.2', missing_ok=True))
            with self.assertRaises(RuntimeError):
                api.request('GET', '/releases')

    def test_annotated_tags_are_resolved(self):
        api = GitHub('owner/repo', 'test-token')
        with patch.object(api, 'request', side_effect=[{'object': {'type': 'tag', 'sha': OTHER_SHA}},
                                                       {'object': {'type': 'commit', 'sha': SHA}}]):
            self.assertEqual(api.tag_commit(CURRENT.tag), SHA)


if __name__ == '__main__':
    unittest.main()
