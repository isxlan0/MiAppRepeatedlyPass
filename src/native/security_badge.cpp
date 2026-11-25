#include <android/binder_ibinder.h>
#include <android/binder_parcel.h>
#include <android/log.h>
#include <chrono>
#include <mutex>
#include <cstdint>
#include <dlfcn.h>
extern "C" AIBinder* AServiceManager_checkService(const char* name);
void miapp_ensure_dart_badges();

// Preserve every original export through DT_NEEDED; override only the native ID predicate.
static void* created(void* userdata){return userdata;}
static void destroyed(void*){}
static binder_status_t incoming(AIBinder*,transaction_code_t,const AParcel*,AParcel*){return STATUS_UNKNOWN_TRANSACTION;}
extern "C" __attribute__((visibility("default"))) bool Security_is_xspace_user_id(int32_t id){
    miapp_ensure_dart_badges();
    if(id==999)return true;
    if(id<900||id>998)return false;
    static std::mutex mutex;
    static bool values[99]={};
    static int64_t times[99]={};
    std::lock_guard<std::mutex> lock(mutex);
    auto now=std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
    int index=id-900;if(times[index]&&now-times[index]<1000)return values[index];
    bool valid=false;
    AIBinder* service=AServiceManager_checkService("user");
    if(service){
        static AIBinder_Class* type=AIBinder_Class_define("android.os.IUserManager",created,destroyed,incoming);
        AParcel *in=nullptr,*out=nullptr;
        if(AIBinder_associateClass(service,type)&&AIBinder_prepareTransaction(service,&in)==STATUS_OK){
            AParcel_writeInt32(in,id);
            if(AIBinder_transact(service,0x00584302,&in,&out,0)==STATUS_OK){
                int32_t exception=-1,result=0;
                if(AParcel_readInt32(out,&exception)==STATUS_OK&&exception==0&&AParcel_readInt32(out,&result)==STATUS_OK)valid=result==1;
            }
        }
        if(in)AParcel_delete(in);if(out)AParcel_delete(out);AIBinder_decStrong(service);
    }
    values[index]=valid;times[index]=now;
    if(valid)__android_log_print(ANDROID_LOG_INFO,"MiAppNativeBadge","Registered XSpace %d",id);
    return valid;
}

// ROM UserInfo ABI: its first int is the actual user ID. The object predicate is
// separate from the ID predicate and the launcher uses it when choosing badge type.
extern "C" __attribute__((visibility("default"))) bool Security_is_xspace_user(const int32_t* user){
    return user&&Security_is_xspace_user_id(*user);
}

// A normal Runtime API call supplies a reliable post-fork initialization point.
// Forward its value unchanged; the native desktop has no Java Application hook.
extern "C" __attribute__((visibility("default"))) int32_t Security_get_current_user_id(){
    miapp_ensure_dart_badges();
    using Function=int32_t(*)();
    static Function original=[](){
        void* library=dlopen("libmiapp_security_original.so",RTLD_NOW|RTLD_LOCAL);
        return library?reinterpret_cast<Function>(dlsym(library,"Security_get_current_user_id")):nullptr;
    }();
    return original?original():-1;
}
