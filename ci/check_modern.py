"""Host regressions for API 102 hook semantics and current-boot health state."""
from pathlib import Path
import os
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import build
from prepare_dependencies import prepare

def main():
    deps=prepare()
    output=ROOT/'build/api102-tests'
    classes=output/'classes'
    classes.mkdir(parents=True,exist_ok=True)
    cp=os.pathsep.join(str(p) for p in (ROOT/'build/bridge-classes',deps['api'],build.ANDROID,classes))
    subprocess.run(['javac','--release','17','-encoding','UTF-8','-cp',cp,'-d',str(classes),
                    *[str(ROOT/'ci'/name) for name in ('ModernHookTest.java','HealthStateTest.java','RootProviderTest.java','ActivationStatusTest.java','SystemHookInstallTest.java')]],check=True)
    for test in ('ModernHookTest','HealthStateTest','ActivationStatusTest'):
        subprocess.run(['java','-cp',cp,'io.github.isxlan0.miappxspace.'+test,str(output)],check=True)

if __name__=='__main__':main()
