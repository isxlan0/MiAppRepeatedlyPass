"""Verify the module ZIP, embedded APK, generated versions and signing certificate."""
from pathlib import Path
import hashlib
import os
import re
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import build
from release_metadata import read_releases


def output(*args):
    return subprocess.check_output([str(arg) for arg in args], cwd=ROOT, text=True, encoding='utf-8')


def check(condition, message):
    if not condition:
        raise ValueError(message)


def verify():
    release = read_releases()[0]
    archive = ROOT / 'dist' / release.archive_name
    apk = ROOT / 'dist/bridge.apk'
    required = {'module.prop', 'bridge.apk', 'customize.sh', 'prepare-native.sh', 'service.sh',
                'action.sh', 'uninstall.sh', 'upgrade.sh', 'bin/miapp',
                'system/system_ext/lib64/libhyper_os_security_public.so',
                'system/system_ext/lib64/libhyper_os_bundle_public.so',
                'system/system_ext/lib64/libhyper_os_bundle_private.dylib.so'}
    with zipfile.ZipFile(archive) as package:
        check(package.testzip() is None, 'Corrupt module ZIP')
        names = package.namelist()
        check(len(names) == len(set(names)), 'Duplicate ZIP entries')
        check(required <= set(names), 'Missing module files or unexpected enclosing directory')
        check(package.read('bridge.apk') == apk.read_bytes(), 'ZIP contains a different APK')
        props = dict(line.split('=', 1) for line in package.read('module.prop').decode('utf-8').splitlines()
                     if '=' in line and not line.lstrip().startswith('#'))
        check(props['version'] == release.version and props['versionCode'] == str(release.version_code),
              'Module version differs from CHANGELOG.md')
        for entry in package.infolist():
            path = Path(entry.filename)
            check(not path.is_absolute() and '..' not in path.parts and '\\' not in entry.filename,
                  'Unsafe ZIP path')
            check(path.suffix.lower() not in ('.p12', '.jks', '.keystore', '.pfx'), 'Signing key in ZIP')
            check(path.name not in ('libmiapp_security_original.so', 'libmiapp_bundle_original.so',
                                    'libmiapp_bundle_private_orig.so'), 'ROM original in ZIP')
            executable = path.suffix == '.sh' or entry.filename == 'bin/miapp'
            mode = 0o100755 if executable else 0o100644
            check(entry.create_system == 3 and entry.external_attr >> 16 == mode,
                  f'Wrong Unix permissions: {entry.filename}')
            if executable or path.suffix in ('.prop', '.md'):
                check(b'\r' not in package.read(entry), f'Non-Unix line endings: {entry.filename}')
    badging = output(build.TOOLS / 'aapt2.exe', 'dump', 'badging', apk)
    package_name = build.release_metadata()[2]
    check(f"name='{package_name}'" in badging and f"versionCode='{release.version_code}'" in badging
          and f"versionName='{release.version}'" in badging, 'APK version or package mismatch')
    classes = ROOT / 'build/bridge-classes'
    config = output('javap', '-constants', '-classpath', classes, package_name + '.BuildConfig')
    check(f'VERSION_NAME = "{release.version}"' in config
          and f'VERSION_CODE = {release.version_code}' in config, 'Compiled BuildConfig version mismatch')
    certs = output(build.TOOLS / 'apksigner.bat', 'verify', '--verbose', '--print-certs', apk)
    certificate = re.search(r'certificate SHA-256 digest: ([0-9a-fA-F]+)', certs)
    check(certificate is not None, 'APK certificate not found')
    key = build.signing_key()
    exported = subprocess.check_output(['keytool', '-exportcert', '-keystore', str(key), '-alias', 'miapp',
                                        '-storepass:env', 'MIAPP_SIGNING_PASSWORD'], cwd=ROOT)
    check(certificate[1].lower() == hashlib.sha256(exported).hexdigest(), 'APK signing certificate changed')
    print(f'Package verified: {archive.name}; versionCode={release.version_code}; signing certificate matches')


if __name__ == '__main__':
    verify()
