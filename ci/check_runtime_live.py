"""Read-only scan of a running desktop using the production recognizer.

The probe substitutes /proc/PID/mem reads for pointer reads. It never loads a
library into the desktop, installs patches, writes app data or restarts it.
Only its own executable is copied to a unique /data/local/tmp path.
"""
import argparse
from pathlib import Path
import shlex
import subprocess
import sys
import uuid

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import build_native


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    args = parser.parse_args()
    output = ROOT / 'build/live-runtime-probe'
    output.mkdir(exist_ok=True)
    source = (ROOT / 'src/native/runtime_scan.cpp').read_text(encoding='utf-8')
    replacements = {
        'auto maps=mappings();Loaded dart{},native{};': 'auto maps=mappings(probeMaps.c_str());Loaded dart{},native{};',
        'std::memcmp(reinterpret_cast<const void*>(m.start),ELFMAG,4)': 'std::memcmp(probeRead(m.start,4).data(),ELFMAG,4)',
        'std::memcmp(reinterpret_cast<const void*>(g.address),g.expected.data(),g.expected.size())': 'std::memcmp(probeRead(g.address,g.expected.size()).data(),g.expected.data(),g.expected.size())',
    }
    for original, replacement in replacements.items():
        assert source.count(original) == 1, original
        source = source.replace(original, replacement)
    header = r'''
#include <string>
#include <vector>
#include <unistd.h>
#include <fcntl.h>
#include <stdexcept>
#include <cstdio>
static std::string probeMaps;
static int probeMem;
static std::vector<unsigned char> probeRead(uintptr_t at,size_t size){
    std::vector<unsigned char> data(size);size_t done=0;
    while(done<size){auto n=pread(probeMem,data.data()+done,size-done,at+done);
        if(n<=0)throw std::runtime_error("Cannot read target memory");done+=n;}
    return data;
}
'''
    footer = r'''
int main(int argc,char** argv){
    if(argc!=2)return 2;
    std::string proc=std::string("/proc/")+argv[1];probeMaps=proc+"/maps";
    char name[128]={};FILE* cmd=fopen((proc+"/cmdline").c_str(),"r");
    if(!cmd)return 3;fread(name,1,sizeof(name)-1,cmd);fclose(cmd);
    if(strcmp(name,"com.miui.home"))return 4;
    probeMem=open((proc+"/mem").c_str(),O_RDONLY);if(probeMem<0)return 5;
    MiappLayout out{};uintptr_t dart=0,native=0;int result=miapp_scan_runtime(out,dart,native);
    if(result==1&&miapp_validate_runtime())printf("PASS: current desktop backing files and all target instruction guards verified; no code written (PID %s)\n",argv[1]);
    else printf("REFUSED: scan=%d %s\n",result,miapp_scan_error());
    close(probeMem);return result==1?0:1;
}
'''
    cpp = output / 'probe.cpp'
    cpp.write_text(header + source + footer, encoding='utf-8')
    binary = output / 'probe'
    subprocess.run([str(build_native.BIN / 'aarch64-linux-android30-clang++.cmd'), '-std=c++17', '-O2',
                    '-DMIAPP_SCAN_DIAGNOSTICS', '-I'+str(ROOT/'src/native'), '-I'+str(ROOT/'build/native-generated'),
                    str(cpp), '-llog', '-static-libstdc++', '-o', str(binary)], check=True)
    adb = [str(build_native.SDK/'platform-tools/adb.exe'), '-s', args.serial]
    remote = '/data/local/tmp/miapp-live-probe-' + uuid.uuid4().hex[:12]
    def shell(command):
        return subprocess.run(adb+['shell', 'su -c '+shlex.quote(command)], capture_output=True,
                              text=True, encoding='utf-8', errors='replace', timeout=45)
    result = shell('pidof com.miui.home')
    pid = result.stdout.strip()
    if not pid.isdecimal():
        raise RuntimeError('Expected one running desktop: '+pid)
    try:
        subprocess.run(adb+['push', str(binary), remote], check=True, capture_output=True)
        result = shell(f'chmod 0755 {remote} && {remote} {pid}')
        report = result.stdout + result.stderr
        (output/'result.log').write_text(report, encoding='utf-8')
        print(report, flush=True)
        if result.returncode:
            raise RuntimeError('Live scanner refused; see result.log')
    finally:
        assert remote.startswith('/data/local/tmp/miapp-live-probe-') and len(remote.rsplit('/', 1)[1]) == 29
        shell('rm -f '+remote)


if __name__ == '__main__':
    main()
