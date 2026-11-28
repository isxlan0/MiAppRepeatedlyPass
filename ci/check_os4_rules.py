"""Host-only regression for OS4 DEX encoding normalization and reviewed variants.

Requires the local annibale, nine-OTA and three legacy fixture directories.
Never edits recognition patterns or invokes adb.
"""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess

ROOT=Path(__file__).resolve().parents[1]


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--annibale',type=Path,default=ROOT/'build/ota-annibale-20261128')
    parser.add_argument('--matrix',type=Path,default=ROOT/'build/ota-matrix-20261127')
    parser.add_argument('--legacy',type=Path,default=ROOT/'build/rom-audit')
    parser.add_argument('--output',type=Path,default=ROOT/'build/os4-rules-20261128/regressions')
    args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    classes=args.output/'classes';(classes/'assets').mkdir(parents=True,exist_ok=True)
    source=ROOT/'src/main/java/io/github/isxlan0/miappxspace'
    sources=[source/name for name in ('DexModel.java','ResourceTable.java','JavaPrepare.java','NativePrepare.java','NativePatterns.java')]
    tests=('DexCanonicalTest','Os4RulesTest','OtaAdaptationTest','OtaJavaRegressionTest','OtaCompatibility','FrameworkDiagnosticsTest')
    sources += [ROOT/'ci'/(name+'.java') for name in tests]
    results=[]
    def run(name,command):
        p=subprocess.run(list(map(str,command)),capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=240)
        (args.output/(name+'.log')).write_text(p.stdout+p.stderr,encoding='utf-8')
        results.append({'name':name,'exit_code':p.returncode,'status':'PASS' if p.returncode==0 else 'FAIL'})
        (args.output/'results.json').write_text(json.dumps(results,indent=2),encoding='utf-8')
        print(name,results[-1]['status'],flush=True)
        if p.returncode:raise RuntimeError(p.stdout+p.stderr)
    run('compile',['javac','--release','8','-Xlint:-options','-encoding','UTF-8','-d',classes,*sources])
    for name in ('java-patterns.properties','management-patterns.properties'):
        path=ROOT/'src/main/assets'/name;(classes/'assets'/name).write_bytes(path.read_bytes());sources.append(path)
    (args.output/'sources.json').write_text(json.dumps({str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},indent=2),encoding='utf-8')
    java=['java','-Xmx256m','-cp',classes]
    def test(name,*arguments):run(name,java+['io.github.isxlan0.miappxspace.'+name,*arguments])
    annibale=args.annibale/'annibale/files/product/app/SecurityCoreAdd/SecurityCoreAdd.apk'
    test('DexCanonicalTest',annibale)
    test('Os4RulesTest',annibale,args.output/'annibale-negative')
    run('FrameworkEncodingTest',java+['io.github.isxlan0.miappxspace.Os4RulesTest','--framework',
         args.matrix/'17max/files/system_ext/framework/miui-services.jar',
         args.annibale/'annibale/files/system_ext/framework/miui-services.jar'])
    test('OtaAdaptationTest',args.matrix/'18fold/files/product/app/SecurityCoreAdd/SecurityCoreAdd.apk')
    legacy=args.legacy/'4.0.6.0/files'
    test('OtaJavaRegressionTest',legacy/'product_a/app/SecurityCoreAdd/SecurityCoreAdd.apk',args.output/'legacy-negative',legacy/'system_ext_a/framework/miui-framework.jar')
    test('FrameworkDiagnosticsTest',args.legacy,args.output/'framework-negative')
    for version in ('4.0.0.44','4.0.4.0','4.0.6.0'):
        files=args.legacy/version/'files'
        run('legacy-'+version,java+['io.github.isxlan0.miappxspace.JavaPrepare',files/'system_a/system',files/'system_ext_a',files/'product_a/app/SecurityCoreAdd/SecurityCoreAdd.apk',args.output/(version+'.properties')])
        run('native-'+version,java+['io.github.isxlan0.miappxspace.OtaCompatibility','native',args.output/(version+'.native.properties'),files/'product_a/priv-app/MiuiHome/MiuiHome.apk',files/'system_ext_a/lib64'])
    print('PASS: host regressions completed at 256 MiB Java heap',flush=True)


if __name__=='__main__':main()
