"""Build wrappers with an empty install-time layout; no proprietary ROM inputs."""
from pathlib import Path
import subprocess,os
ROOT=Path(__file__).resolve().parent
SDK=Path(os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME') or str(Path(os.environ.get('LOCALAPPDATA', Path.home()/'AppData/Local'))/'Android/Sdk'))
BIN=SDK/'ndk/30.0.16248370/toolchains/llvm/prebuilt/windows-x86_64/bin'
OUT=ROOT/'module/system/system_ext/lib64'

def main():
    OUT.mkdir(parents=True,exist_ok=True)
    # These empty libraries supply DT_NEEDED names to the linker only. They are
    # never packaged. Real dependencies come from the target ROM during install.
    stubs=ROOT/'build/native-link-stubs';stubs.mkdir(parents=True,exist_ok=True)
    source=stubs/'empty.cpp';source.write_text('extern "C" void miapp_link_placeholder() {}\n',encoding='utf-8')
    for name in ('libmiapp_security_original.so','libmiapp_bundle_original.so','libmiapp_bundle_private_orig.so'):
        subprocess.run([str(BIN/'aarch64-linux-android30-clang++.cmd'),'-shared','-fPIC','-nostdlib','-Wl,-soname,'+name,str(source),'-o',str(stubs/name)],check=True)
    subprocess.run([str(BIN/'aarch64-linux-android30-clang++.cmd'),'-shared','-fPIC','-std=c++17','-O2','-fvisibility=hidden','-Wl,-soname,libhyper_os_security_public.so','-Wl,--no-as-needed','-L'+str(stubs),'-lmiapp_security_original','-Wl,--as-needed','-Wl,--allow-shlib-undefined',str(ROOT/'src/native/security_badge.cpp'),str(ROOT/'src/native/dart_badge.cpp'),str(ROOT/'src/native/dart_badge.S'),str(ROOT/'src/native/runtime_titles.cpp'),'-lbinder_ndk','-ldl','-llog','-static-libstdc++','-o',str(OUT/'libhyper_os_security_public.so')],check=True)
    subprocess.run([str(BIN/'llvm-readelf.exe'),'-d',str(OUT/'libhyper_os_security_public.so')],check=True)
    subprocess.run([str(BIN/'aarch64-linux-android30-clang++.cmd'),'-shared','-fPIC','-std=c++17','-O2','-fvisibility=hidden','-Wl,-soname,libhyper_os_bundle_public.so','-Wl,--no-as-needed','-L'+str(stubs),'-L'+str(OUT),'-lmiapp_bundle_original','-lhyper_os_security_public','-Wl,--as-needed','-Wl,--allow-shlib-undefined',str(ROOT/'src/native/bundle_badge.cpp'),'-ldl','-o',str(OUT/'libhyper_os_bundle_public.so')],check=True)
    subprocess.run([str(BIN/'aarch64-linux-android30-clang++.cmd'),'-shared','-fPIC','-std=c++17','-O2','-fvisibility=hidden','-Wl,-soname,libhyper_os_bundle_private.dylib.so','-Wl,--no-as-needed','-L'+str(stubs),'-lmiapp_bundle_private_orig','-Wl,--as-needed','-Wl,--allow-shlib-undefined',str(ROOT/'src/native/bundle_private_badge.cpp'),'-ldl','-llog','-o',str(OUT/'libhyper_os_bundle_private.dylib.so')],check=True)
if __name__=='__main__':main()
