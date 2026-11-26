#include <cstdint>
#include <dlfcn.h>
#include <cstdlib>
#include <cstring>
#include <sys/mman.h>
#include <unistd.h>
#include <android/log.h>

// The Flutter launcher calls this Rust export directly, bypassing the public C bridge.
// Verified ROM AArch64 ABI: self in x0, UserHandle* in x1, 40-byte result in x8.
#define BADGE_SYMBOL "_RNvMs4_NtNtCs2Z0Otu41e4o_23hyper_os_bundle_private2pm15package_managerNtB5_14PackageManager20get_user_badged_icon"
struct alignas(8) RustBadgeResult { uint8_t bytes[40]; };
extern "C" RustBadgeResult clone_badge(void*,const int32_t*) __asm__(BADGE_SYMBOL);
using Function=RustBadgeResult(*)(void*,const int32_t*);
static Function original=nullptr;
static void absolute_jump(uint8_t* code,const void* target){
    const uint32_t jump[]={0x58000050,0xd61f0200}; // ldr x16, PC+8; br x16
    std::memcpy(code,jump,8);std::memcpy(code+8,&target,8);
}
__attribute__((constructor)) static void intercept_internal_calls(){
    void* library=dlopen("libmiapp_bundle_private_orig.so",RTLD_NOW|RTLD_LOCAL);
    auto entry=library?reinterpret_cast<uint8_t*>(dlsym(library,BADGE_SYMBOL)):nullptr;
    if(!entry)return;
    original=reinterpret_cast<Function>(entry);
    // Internal Rust calls use direct branches and bypass ELF symbol interposition.
    // These four pinned instructions contain no PC-relative operands; preserve PAC and stack setup.
    const uint8_t expected[]={0x3f,0x23,0x03,0xd5,0xff,0xc3,0x02,0xd1,0xfd,0x7b,0x07,0xa9,0xf7,0x43,0x00,0xf9};
    const uint8_t earlier[]={0x3f,0x23,0x03,0xd5,0xff,0x43,0x02,0xd1,0xfd,0x7b,0x06,0xa9,0xf6,0x57,0x07,0xa9};
    if(std::memcmp(entry,expected,16)!=0&&std::memcmp(entry,earlier,16)!=0){__android_log_print(ANDROID_LOG_ERROR,"MiAppNativeBadge","Rust entry mismatch");return;}
    size_t pageSize=static_cast<size_t>(sysconf(_SC_PAGESIZE));
    auto trampoline=reinterpret_cast<uint8_t*>(mmap(nullptr,pageSize,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0));
    if(trampoline==MAP_FAILED)return;
    std::memcpy(trampoline,entry,16);absolute_jump(trampoline+16,entry+16);
    __builtin___clear_cache(reinterpret_cast<char*>(trampoline),reinterpret_cast<char*>(trampoline+32));
    if(mprotect(trampoline,pageSize,PROT_READ|PROT_EXEC)!=0){munmap(trampoline,pageSize);return;}
    auto page=reinterpret_cast<void*>(reinterpret_cast<uintptr_t>(entry)&~(pageSize-1));
    if(mprotect(page,pageSize,PROT_READ|PROT_WRITE|PROT_EXEC)!=0){munmap(trampoline,pageSize);__android_log_print(ANDROID_LOG_ERROR,"MiAppNativeBadge","Rust entry protection refused");return;}
    original=reinterpret_cast<Function>(trampoline);
    absolute_jump(entry,reinterpret_cast<void*>(clone_badge));
    __builtin___clear_cache(reinterpret_cast<char*>(entry),reinterpret_cast<char*>(entry+16));
    mprotect(page,pageSize,PROT_READ|PROT_EXEC);
    __android_log_print(ANDROID_LOG_INFO,"MiAppNativeBadge","Rust direct calls intercepted");
}
extern "C" __attribute__((visibility("default"))) RustBadgeResult clone_badge(void* manager,const int32_t* user){
    if(!original)std::abort();
    using Predicate=bool(*)(int32_t);
    static Predicate registered=[](){
        void* library=dlopen("libhyper_os_security_public.so",RTLD_NOW|RTLD_LOCAL);
        return library?reinterpret_cast<Predicate>(dlsym(library,"Security_is_xspace_user_id")):nullptr;
    }();
    int32_t artwork=999;
    if(user&&*user!=999&&registered&&registered(*user)){
        __android_log_print(ANDROID_LOG_INFO,"MiAppNativeBadge","Rust badge artwork user=%d -> 999",*user);
        return original(manager,&artwork);
    }
    return original(manager,user);
}
