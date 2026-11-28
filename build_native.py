"""Build runtime recognizers and the pre-mount system-library preparer; no ROM inputs."""
from pathlib import Path
import subprocess,os,re
ROOT=Path(__file__).resolve().parent
SDK=Path(os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME') or str(Path(os.environ.get('LOCALAPPDATA', Path.home()/'AppData/Local'))/'Android/Sdk'))
BIN=SDK/'ndk/30.0.16248370/toolchains/llvm/prebuilt/windows-x86_64/bin'
OUT=ROOT/'module/system/system_ext/lib64'

def main():
    OUT.mkdir(parents=True,exist_ok=True)
    generated=ROOT/'build/native-generated';generated.mkdir(parents=True,exist_ok=True)
    java=ROOT/'src/main/java/io/github/isxlan0/miappxspace'
    arrays=re.findall(r'static final int\[\] (\w+)\s*=\s*\{([^}]+)\}',(java/'NativePatterns.java').read_text())
    badges=re.search(r'int\[\]\[\] BADGES=\{(.*?)\n    \};',(java/'NativePrepare.java').read_text(),re.S)
    if not arrays or not badges:raise ValueError('Native recognition patterns missing')
    arrays += [('BADGE_'+str(i),body) for i,body in enumerate(re.findall(r'\{([^}]+)\}',badges[1]))]
    header='#pragma once\n#include <cstdint>\nnamespace miapp {\n'
    header+=''.join('inline constexpr uint32_t '+name+'[]={'+body+'};\n' for name,body in arrays)+'}\n'
    (generated/'native_patterns.h').write_text(header,encoding='utf-8')
    compiler=BIN/'aarch64-linux-android30-clang++.cmd'
    includes=['-I'+str(generated),'-I'+str(ROOT/'src/native')]
    from build import source_build_id
    build_id=source_build_id()
    (generated/'build_identity.h').write_text('#define MIAPP_BUILD_ID "'+build_id+'"\n',encoding='ascii')
    includes+=['-include',str(generated/'build_identity.h')]
    subprocess.run([str(compiler),'-fPIE','-pie','-std=c++17','-O2',*includes,str(ROOT/'src/native/prepare_system.cpp'),'-static-libstdc++','-o',str(ROOT/'module/bin/prepare-system')],check=True)
    # These empty libraries supply DT_NEEDED names to the linker only. They are
    # never packaged. Real dependencies come from the target ROM during install.
    stubs=ROOT/'build/native-link-stubs';stubs.mkdir(parents=True,exist_ok=True)
    source=stubs/'empty.cpp';source.write_text('extern "C" void miapp_link_placeholder() {}\n',encoding='utf-8')
    for name in ('libmiapp_security_original.so','libmiapp_bundle_original.so','libmiapp_bundle_private_orig.so'):
        subprocess.run([str(BIN/'aarch64-linux-android30-clang++.cmd'),'-shared','-fPIC','-nostdlib','-Wl,-soname,'+name,str(source),'-o',str(stubs/name)],check=True)
    subprocess.run([str(compiler),'-shared','-fPIC','-std=c++17','-O2','-fvisibility=hidden',*includes,'-Wl,-soname,libhyper_os_security_public.so','-Wl,--no-as-needed','-L'+str(stubs),'-lmiapp_security_original','-Wl,--as-needed','-Wl,--allow-shlib-undefined',str(ROOT/'src/native/security_badge.cpp'),str(ROOT/'src/native/dart_badge.cpp'),str(ROOT/'src/native/dart_badge.S'),str(ROOT/'src/native/runtime_titles.cpp'),str(ROOT/'src/native/runtime_scan.cpp'),'-lbinder_ndk','-ldl','-llog','-static-libstdc++','-o',str(OUT/'libhyper_os_security_public.so')],check=True)
    subprocess.run([str(BIN/'llvm-readelf.exe'),'-d',str(OUT/'libhyper_os_security_public.so')],check=True)
    subprocess.run([str(BIN/'aarch64-linux-android30-clang++.cmd'),'-shared','-fPIC','-std=c++17','-O2','-fvisibility=hidden','-Wl,-soname,libhyper_os_bundle_public.so','-Wl,--no-as-needed','-L'+str(stubs),'-L'+str(OUT),'-lmiapp_bundle_original','-lhyper_os_security_public','-Wl,--as-needed','-Wl,--allow-shlib-undefined',str(ROOT/'src/native/bundle_badge.cpp'),'-ldl','-o',str(OUT/'libhyper_os_bundle_public.so')],check=True)
    subprocess.run([str(BIN/'aarch64-linux-android30-clang++.cmd'),'-shared','-fPIC','-std=c++17','-O2','-fvisibility=hidden',*includes,'-Wl,-soname,libhyper_os_bundle_private.dylib.so','-Wl,--no-as-needed','-L'+str(stubs),'-lmiapp_bundle_private_orig','-Wl,--as-needed','-Wl,--allow-shlib-undefined',str(ROOT/'src/native/bundle_private_badge.cpp'),'-ldl','-llog','-o',str(OUT/'libhyper_os_bundle_private.dylib.so')],check=True)
if __name__=='__main__':main()
