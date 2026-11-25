#include <cstdint>
#include <dlfcn.h>
#include <cstdlib>

extern "C" bool Security_is_xspace_user_id(int32_t id);
// ROM-pinned AArch64 C ABI: 40-byte indirect result in x8; PackageManager* x0,
// UserHandle* x1. The original reads the handle's first int and tests it against 999.
struct alignas(8) BadgeResult { uint8_t bytes[40]; };
extern "C" __attribute__((visibility("default"))) BadgeResult PackageManager_get_user_badged_icon(void* manager,const int32_t* user){
    using Function=BadgeResult(*)(void*,const int32_t*);
    static Function original=[](){
        void* library=dlopen("libmiapp_bundle_original.so",RTLD_NOW|RTLD_LOCAL);
        auto function=library?reinterpret_cast<Function>(dlsym(library,"PackageManager_get_user_badged_icon")):nullptr;
        if(!function)std::abort();return function;
    }();
    int32_t artwork=999;
    if(user&&*user!=999&&Security_is_xspace_user_id(*user))return original(manager,&artwork);
    return original(manager,user);
}
