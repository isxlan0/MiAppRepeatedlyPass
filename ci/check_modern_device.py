"""Run API 102 adapter/health probes in app_process, without installing or hooking anything."""
import argparse
from pathlib import Path
import shlex
import subprocess
import sys
import uuid
import zipfile

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
import build
from prepare_dependencies import prepare

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',required=True)
    args=parser.parse_args()
    adb=[str(build.SDK/'platform-tools/adb.exe'),'-s',args.serial]
    remote='/data/local/tmp/miapp-modern-'+uuid.uuid4().hex[:12]
    output=ROOT/'build/api102-tests'
    dex=output/'dex';dex.mkdir(parents=True,exist_ok=True)
    jar=output/'tests.jar'
    with zipfile.ZipFile(jar,'w') as archive:
        for file in sorted((output/'classes').rglob('*.class')):archive.write(file,file.relative_to(output/'classes').as_posix())
    deps=prepare()
    subprocess.run([str(build.TOOLS/'d8.bat'),'--min-api','26','--lib',str(build.ANDROID),
                    '--classpath',str(deps['api']),'--output',str(dex),str(ROOT/'build/bridge-classes.jar'),str(jar)],check=True)
    testapk=output/'tests.apk'
    with zipfile.ZipFile(testapk,'w') as archive:
        for file in dex.glob('*.dex'):archive.write(file,file.name)
    def shell(command):
        result=subprocess.run(adb+['shell','su -c '+shlex.quote(command)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=55)
        if result.returncode:raise RuntimeError(result.stdout+result.stderr)
        return result.stdout+result.stderr
    shell(f'test ! -e {remote} && mkdir {remote}')
    try:
        subprocess.run(adb+['push',str(testapk),remote+'/tests.apk'],check=True,capture_output=True)
        subprocess.run(adb+['push',str(ROOT/'dist/bridge.apk'),remote+'/bridge.apk'],check=True,capture_output=True)
        shell(f'cp /data/adb/modules/zygisk_lsposed/framework.dex {remote}/framework.dex')
        for test in ('ModernHookTest','HealthStateTest','ActivationStatusTest','RootProviderTest','SystemHookInstallTest'):
            result=shell(f'CLASSPATH={remote}/tests.apk:{remote}/bridge.apk:{remote}/framework.dex app_process /system/bin io.github.isxlan0.miappxspace.{test} {remote}')
            assert 'PASS:' in result,result
            print(result.strip(),flush=True)
        stage=output/'health-probe';stage.mkdir(exist_ok=True)
        version=build.release_metadata()[0]
        with zipfile.ZipFile(ROOT/f'dist/MiAppXSpace-{version}.zip') as archive:
            for name in ('module.prop','build-id'): (stage/name).write_bytes(archive.read(name))
        subprocess.run(adb+['push',str(stage),remote+'/module'],check=True,capture_output=True)
        health=f'CLASSPATH={remote}/bridge.apk app_process /system/bin io.github.isxlan0.miappxspace.ProfileControl health {remote}/module'
        result=shell(health)
        assert result.strip()=='✓ 正常运行中',result
        assert 'Unable to find app for caller' not in result,result
        description=shell(f'cat {remote}/module/module.prop')
        assert '[✓ 正常运行中]' in description and 'Github:isxlan0/MiAppRepeatedlyPass' in description,description
        shell(f'touch {remote}/module/skip_mount')
        error=shell(health)
        assert '系统库异常' in error and '正常运行中' not in error,error
        shell(f'rm {remote}/module/skip_mount')
        recovered=shell(health)
        assert recovered.strip()=='✓ 正常运行中',recovered
        print('PASS: Android manager status defaults to normal without reports; explicit failure and recovery update description',flush=True)
        (output/'android-health-probe.log').write_text(result,encoding='utf-8')
    finally:
        assert remote.startswith('/data/local/tmp/miapp-modern-') and len(remote.rsplit('/',1)[1])==25
        shell('rm -rf '+remote)

if __name__=='__main__':main()
