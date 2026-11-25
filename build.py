"""Local Android/KSU package build; no Gradle or target-app APK repacking."""
from pathlib import Path
import os, re, shutil, subprocess, sys, zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
SDK = Path(os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME') or str(Path(os.environ.get('LOCALAPPDATA', Path.home()/'AppData/Local'))/'Android/Sdk'))
TOOLS = SDK / 'build-tools/37.0.0'
ANDROID = SDK / 'platforms/android-37.0/android.jar'
API_CANDIDATES = ([Path(os.environ['XPOSED_API_JAR'])] if os.environ.get('XPOSED_API_JAR') else
                  list((Path.home()/'.gradle/caches/modules-2/files-2.1/de.robv.android.xposed/api/82').glob('*/api-82.jar')))
BUILD = ROOT / 'build'
DIST = ROOT / 'dist'

def run(*args):
    subprocess.run([str(a) for a in args], cwd=ROOT, check=True)

def release_metadata():
    props = dict(line.split('=', 1) for line in (ROOT/'module/module.prop').read_text(encoding='utf-8').splitlines()
                 if '=' in line and not line.lstrip().startswith('#'))
    manifest = ET.parse(ROOT/'src/main/AndroidManifest.xml').getroot()
    android = '{http://schemas.android.com/apk/res/android}'
    version, code = props['version'], props['versionCode']
    if not re.fullmatch(r'\d+\.\d+\.\d+', version) or not code.isdecimal() or int(code) <= 0:
        raise ValueError('module.prop requires a release version and positive versionCode')
    if manifest.get(android+'versionName') != version or manifest.get(android+'versionCode') != code:
        raise ValueError('AndroidManifest.xml version must match module.prop')
    package = manifest.get('package')
    if not package or not re.fullmatch(r'[a-zA-Z_]\w*(?:\.[a-zA-Z_]\w*)+', package):
        raise ValueError('Invalid Android package name')
    entry = (ROOT/'src/main/assets/xposed_init').read_text(encoding='utf-8').strip()
    if entry != package+'.ProfileHook':
        raise ValueError('xposed_init must match the Android package')
    return version, code, package

def build_apk(source, name):
    classes=BUILD/(name+'-classes'); dex=BUILD/(name+'-dex')
    for output in (classes,dex):
        if output.resolve().parent!=BUILD.resolve():raise ValueError('Build output escaped workspace build directory')
        if output.exists():shutil.rmtree(output)
    classes.mkdir(parents=True,exist_ok=True); dex.mkdir(parents=True,exist_ok=True)
    version, code, package = release_metadata()
    config=BUILD/'generated'/Path(*package.split('.'))/'BuildConfig.java'
    config.parent.mkdir(parents=True, exist_ok=True)
    config.write_text('package '+package+';\n\nfinal class BuildConfig {\n'
                      '    static final String VERSION_NAME = "'+version+'";\n'
                      '    static final int VERSION_CODE = '+str(int(code))+';\n}\n', encoding='utf-8')
    java=sorted((source/'java').rglob('*.java'))+[config]
    run('javac','-J-Duser.language=en','-encoding','UTF-8','--release','8','-Xlint:-options','-cp',str(ANDROID)+';'+str(API_CANDIDATES[0]),'-d',classes,*java)
    # A single JAR avoids cmd.exe's command-line limit in deeper Windows checkouts.
    compiled=BUILD/(name+'-classes.jar')
    with zipfile.ZipFile(compiled,'w',zipfile.ZIP_DEFLATED) as z:
        for path in sorted(classes.rglob('*.class')):z.write(path,path.relative_to(classes).as_posix())
    run(TOOLS/'d8.bat','--min-api','26','--lib',ANDROID,'--classpath',API_CANDIDATES[0],'--output',dex,compiled)
    link=['link','-I',ANDROID,'--manifest',source/'AndroidManifest.xml','-o',BUILD/(name+'-unsigned.apk')]
    if (source/'res').is_dir():
        resources=BUILD/(name+'-resources.zip'); run(TOOLS/'aapt2.exe','compile','--dir',source/'res','-o',resources); link.append(resources)
    if (source/'assets').is_dir():link+=['-A',source/'assets']
    run(TOOLS/'aapt2.exe',*link)
    with zipfile.ZipFile(BUILD/(name+'-unsigned.apk'),'a',zipfile.ZIP_DEFLATED) as z:
        for p in dex.glob('*.dex'):z.write(p,p.name)
    run(TOOLS/'zipalign.exe','-f','4',BUILD/(name+'-unsigned.apk'),BUILD/(name+'-aligned.apk'))
    key=ROOT/'.local/signing.p12'
    key.parent.mkdir(exist_ok=True)
    old_key=BUILD/'local-build.p12'
    if not key.exists() and old_key.exists():shutil.move(old_key,key)
    if not key.exists():run('keytool','-genkeypair','-keystore',key,'-storepass','miapp-local-build','-keypass','miapp-local-build','-alias','miapp','-keyalg','RSA','-keysize','2048','-validity','3650','-dname','CN=MiApp XSpace local build')
    result=DIST/(name+'.apk')
    run(TOOLS/'apksigner.bat','sign','--ks',key,'--ks-pass','pass:miapp-local-build','--out',result,BUILD/(name+'-aligned.apk'))
    run(TOOLS/'apksigner.bat','verify','--verbose',result)
    return result

def main():
    version, _, _ = release_metadata()
    if not API_CANDIDATES or not API_CANDIDATES[0].is_file():
        raise RuntimeError('Xposed API 82 jar missing; set XPOSED_API_JAR or populate the local Gradle cache')
    BUILD.mkdir(exist_ok=True);DIST.mkdir(exist_ok=True)
    run(sys.executable,ROOT/'build_native.py')
    bridge=build_apk(ROOT/'src/main','bridge')
    archive=DIST/('MiAppXSpace-'+version+'.zip')
    with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
        for path in sorted((ROOT/'module').rglob('*')):
            if not path.is_file():continue
            data=path.read_bytes()
            if path.suffix in ('.sh','.prop','.md') or path.name=='miapp':data=data.replace(b'\r\n',b'\n')
            info=zipfile.ZipInfo(path.relative_to(ROOT/'module').as_posix());info.compress_type=zipfile.ZIP_DEFLATED
            info.external_attr=(0o100755 if path.suffix=='.sh' or path.name=='miapp' else 0o100644)<<16
            z.writestr(info,data)
        z.write(bridge,'bridge.apk')
    print(archive)

if __name__=='__main__':main()
