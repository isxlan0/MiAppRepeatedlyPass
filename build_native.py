"""ROM-pinned native predicate wrapper; no change to the system partition or other exports."""
from pathlib import Path
import subprocess,os,hashlib
ROOT=Path(__file__).resolve().parent
SDK=Path(os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME') or str(Path(os.environ.get('LOCALAPPDATA', Path.home()/'AppData/Local'))/'Android/Sdk'))
BIN=SDK/'ndk/30.0.16248370/toolchains/llvm/prebuilt/windows-x86_64/bin'
OUT=ROOT/'module/system/system_ext/lib64'

def original(name,renamed,sha256):
    # Keep only the three required forwarding libraries, not whole APK/ROM dumps.
    source=OUT/renamed
    old=(name+'\0').encode();new=(renamed+'\0').encode()
    padded=new+b'\0'*(len(old)-len(new))
    data=source.read_bytes()
    if data.count(padded)!=1:raise RuntimeError('Unexpected original library SONAME: '+renamed)
    restored=data.replace(padded,old)
    if hashlib.sha256(restored).hexdigest()!=sha256:raise RuntimeError('Pinned ROM library checksum mismatch: '+renamed)
    return data

def main():
    original('libhyper_os_security_public.so','libmiapp_security_original.so','a3e3f75c43128d472454905a66e37276c77377af67b3383bfe2bb3d847947aa3')
    original('libhyper_os_bundle_public.so','libmiapp_bundle_original.so','22b6fbaa7da9d09c7b8811b8d37bb3cbc35ca6685d503400e794f0e6d4459597')
    original('libhyper_os_bundle_private.dylib.so','libmiapp_bundle_private_orig.so','9c7e33edbb11dae45950f0ea0a72d409d653d84b913a488cce2aa45a2975baf9')
    OUT.mkdir(parents=True,exist_ok=True)
    subprocess.run([str(BIN/'aarch64-linux-android30-clang++.cmd'),'-shared','-fPIC','-std=c++17','-O2','-fvisibility=hidden','-Wl,-soname,libhyper_os_security_public.so','-Wl,--no-as-needed','-L'+str(OUT),'-lmiapp_security_original','-Wl,--as-needed','-Wl,--allow-shlib-undefined',str(ROOT/'src/native/security_badge.cpp'),str(ROOT/'src/native/dart_badge.cpp'),str(ROOT/'src/native/dart_badge.S'),str(ROOT/'src/native/runtime_titles.cpp'),'-lbinder_ndk','-ldl','-llog','-static-libstdc++','-o',str(OUT/'libhyper_os_security_public.so')],check=True)
    subprocess.run([str(BIN/'llvm-readelf.exe'),'-d',str(OUT/'libhyper_os_security_public.so')],check=True)
    subprocess.run([str(BIN/'aarch64-linux-android30-clang++.cmd'),'-shared','-fPIC','-std=c++17','-O2','-fvisibility=hidden','-Wl,-soname,libhyper_os_bundle_public.so','-Wl,--no-as-needed','-L'+str(OUT),'-lmiapp_bundle_original','-lhyper_os_security_public','-Wl,--as-needed','-Wl,--allow-shlib-undefined',str(ROOT/'src/native/bundle_badge.cpp'),'-ldl','-o',str(OUT/'libhyper_os_bundle_public.so')],check=True)
    subprocess.run([str(BIN/'aarch64-linux-android30-clang++.cmd'),'-shared','-fPIC','-std=c++17','-O2','-fvisibility=hidden','-Wl,-soname,libhyper_os_bundle_private.dylib.so','-Wl,--no-as-needed','-L'+str(OUT),'-lmiapp_bundle_private_orig','-Wl,--as-needed','-Wl,--allow-shlib-undefined',str(ROOT/'src/native/bundle_private_badge.cpp'),'-ldl','-llog','-o',str(OUT/'libhyper_os_bundle_private.dylib.so')],check=True)
if __name__=='__main__':main()
