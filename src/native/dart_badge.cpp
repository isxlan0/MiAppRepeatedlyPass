#include <android/log.h>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <initializer_list>
#include <cerrno>
#include <pthread.h>
#include <sys/mman.h>
#include <unistd.h>
#include <fcntl.h>
#include <string>
#include <sstream>
#include <chrono>
#include "native_layout.h"
#include "native_startup.h"

// Process-local recognition result. No device-specific addresses in the wrapper.
MiappLayout miapp_layout={{'M','I','A','P','P','_','L','A','Y','O','U','T','_','V','1','!'},1,0,{},{},{},0,0,0,0,0,0,0,{},{}};

extern "C" bool Security_is_xspace_user_id(int32_t);
extern "C" void miapp_dart_x1();
extern "C" void miapp_dart_x2();
bool miapp_install_runtime_titles(uintptr_t);

#ifndef MIAPP_BUILD_ID
#define MIAPP_BUILD_ID "unknown"
#endif
#ifndef MIAPP_NATIVE_STATUS_PATH
#define MIAPP_NATIVE_STATUS_PATH "/data/user/0/com.miui.home/files/miapp-native.status"
#endif
static void native_status(const char* state,const char* detail){
    char boot[64]={},stat[4096]={};
    FILE* file=std::fopen("/proc/sys/kernel/random/boot_id","r");if(!file)return;
    std::fgets(boot,sizeof(boot),file);std::fclose(file);boot[strcspn(boot,"\r\n")]=0;
    file=std::fopen("/proc/self/stat","r");if(!file)return;
    std::fgets(stat,sizeof(stat),file);std::fclose(file);
    const char* end=std::strrchr(stat,')');if(!end)return;
    std::istringstream fields(end+2);std::string start;
    for(int i=0;i<=19;i++)if(!(fields>>start))return;
    const char* path=MIAPP_NATIVE_STATUS_PATH;
    std::string temporary=std::string(path)+"."+std::to_string(getpid())+".tmp";
    int fd=open(temporary.c_str(),O_WRONLY|O_CREAT|O_TRUNC|O_NOFOLLOW|O_CLOEXEC,0600);
    if(fd<0){__android_log_print(ANDROID_LOG_WARN,"MiAppDartBadge","Cannot write native health: %d",errno);return;}
    FILE* out=fdopen(fd,"w");if(!out){close(fd);return;}
    std::fprintf(out,"boot=%s\npid=%d\nstart=%s\nbuild=%s\nstate=%s\ndetail=%s\n",boot,getpid(),start.c_str(),MIAPP_BUILD_ID,state,detail);
    if(std::fclose(out)==0)rename(temporary.c_str(),path);
}

extern "C" __attribute__((visibility("hidden"))) int miapp_dart_validate(int64_t id){
    // Do not truncate a 64-bit Dart ID into a valid clone ID.
    return id>=900&&id<=999&&Security_is_xspace_user_id(static_cast<int32_t>(id));
}

static bool branch(uintptr_t from,uintptr_t to,uint32_t* out){
    int64_t delta=static_cast<int64_t>(to)-static_cast<int64_t>(from);
    if((delta&3)||delta<-(1LL<<27)||delta>=(1LL<<27))return false;
    *out=0x14000000|(static_cast<uint32_t>(delta/4)&0x03ffffff);return true;
}

static void* nearby(uintptr_t base,size_t size){
    // Never replace an existing mapping, including on kernels that ignore the flag.
    uintptr_t aligned=base&~(size-1);
    for(uintptr_t distance=size;distance<0x07000000;distance+=size){
        for(int sign:{1,-1}){
            auto at=aligned+sign*distance;
            void* p=mmap(reinterpret_cast<void*>(at),size,PROT_READ|PROT_WRITE,
                         MAP_PRIVATE|MAP_ANONYMOUS|MAP_FIXED_NOREPLACE,-1,0);
            if(p==MAP_FAILED)continue;
            if(reinterpret_cast<uintptr_t>(p)==at)return p;
            munmap(p,size);
        }
    }
    return nullptr;
}

static bool patch(uintptr_t base){
    struct Site{uintptr_t offset;uint32_t expected[5];void(*helper)();};
    const Site sites[]={
        {miapp_layout.badges[0],{0xf8473001,0xf10f9c3f,0x54000160,0xb84c3003,0x8b1c8063},miapp_dart_x1},
        {miapp_layout.badges[1],{0xf8473001,0xf10f9c3f,0x54000381,0xf85f03a3,0xb84c3002},miapp_dart_x1},
        {miapp_layout.badges[2],{0xf8473022,0xf10f9c5f,0x910082d0,0x9100c2d1,0x9a910200},miapp_dart_x2},
        // Inlined current-user/clone tests used to choose work-profile artwork.
        {miapp_layout.badges[3],{0xf8473022,0xf10f9c5f,0x910082d0,0x9100c2d1,0x9a910201},miapp_dart_x2},
        {miapp_layout.badges[4],{0x540000e0,0xf10f9c3f,0x540000a0,0x910082c0,0xaa1d03ef},miapp_dart_x1}
    };
    constexpr int siteCount=sizeof(sites)/sizeof(sites[0]);
    // Validate every full instruction sequence before changing any code.
    for(const auto& site:sites)if(std::memcmp(reinterpret_cast<void*>(base+site.offset),site.expected,sizeof(site.expected))){
        __android_log_print(ANDROID_LOG_ERROR,"MiAppDartBadge","Pinned Runtime instructions mismatch at %lx",site.offset);return false;
    }
    size_t pageSize=static_cast<size_t>(sysconf(_SC_PAGESIZE));
    auto code=static_cast<uint32_t*>(nearby(base+sites[0].offset,pageSize));
    if(!code){__android_log_print(ANDROID_LOG_ERROR,"MiAppDartBadge","No nearby executable page");return false;}
    uint32_t entry[siteCount]={};
    for(int i=0;i<siteCount;i++){
        auto thunk=code+i*32;uintptr_t pc=reinterpret_cast<uintptr_t>(thunk);
        // Save x16/x17 below the Dart stack, then establish a C stack below it.
        // The original CSP and LR live in a separate frame below those registers.
        const uint32_t instructions[]={
            0xa93f45f0, // stp x16,x17,[x15,#-16]
            0x910003f0, // mov x16,sp
            0xd10041f1, // sub x17,x15,#16
            0x927cee31, // and x17,x17,#-16
            0x9100023f, // mov sp,x17
            0xa9bf7bf0, // stp x16,x30,[sp,#-16]!
            0x58000150, // ldr x16, literal at +64
            0xd63f0200, // blr x16
            0xa8c17bf0, // ldp x16,x30,[sp],#16
            0x9100021f, // mov sp,x16
            0xa97f45f0, // ldp x16,x17,[x15,#-16]
            0,          // b original cmp+4
            0xd503201f,0xd503201f,0xd503201f,0xd503201f
        };
        std::memcpy(thunk,instructions,sizeof(instructions));
        auto helper=sites[i].helper;std::memcpy(thunk+16,&helper,sizeof(helper));
        uintptr_t cmp=base+sites[i].offset+4;
        if(!branch(pc+44,cmp+4,thunk+11)||!branch(cmp,pc,&entry[i])){munmap(code,pageSize);return false;}
    }
    __builtin___clear_cache(reinterpret_cast<char*>(code),reinterpret_cast<char*>(code)+pageSize);
    if(mprotect(code,pageSize,PROT_READ|PROT_EXEC)){munmap(code,pageSize);return false;}
    uintptr_t pages[siteCount]={};int count=0;
    for(const auto& site:sites){
        uintptr_t page=(base+site.offset+4)&~(pageSize-1);bool known=false;
        for(int j=0;j<count;j++)known|=pages[j]==page;
        if(!known)pages[count++]=page;
    }
    int writable=0;
    for(;writable<count;writable++)if(mprotect(reinterpret_cast<void*>(pages[writable]),pageSize,PROT_READ|PROT_WRITE|PROT_EXEC))break;
    if(writable!=count){
        for(int j=0;j<writable;j++)mprotect(reinterpret_cast<void*>(pages[j]),pageSize,PROT_READ|PROT_EXEC);
        munmap(code,pageSize);__android_log_print(ANDROID_LOG_ERROR,"MiAppDartBadge","Runtime code protection refused");return false;
    }
    for(int i=0;i<siteCount;i++){
        auto cmp=reinterpret_cast<uint32_t*>(base+sites[i].offset+4);
        __atomic_store_n(cmp,entry[i],__ATOMIC_RELEASE);
        __builtin___clear_cache(reinterpret_cast<char*>(cmp),reinterpret_cast<char*>(cmp)+4);
    }
    for(int j=0;j<count;j++)mprotect(reinterpret_cast<void*>(pages[j]),pageSize,PROT_READ|PROT_EXEC);
    __android_log_print(ANDROID_LOG_INFO,"MiAppDartBadge","Runtime badge predicates active: %d sites; real user IDs preserved",siteCount);return true;
}

static bool prefer_clone_labels(uintptr_t base){
    // _resolveActivityLabel first tries owner resources, then LauncherApps(user).
    // Fixed clones must use the second path to retain their real user identity.
    uintptr_t entry=base+miapp_layout.labelEntry;const uint32_t expected=0xaa0003e3;
    if(std::memcmp(reinterpret_cast<void*>(entry),&expected,4))return false;
    size_t size=sysconf(_SC_PAGESIZE);auto code=static_cast<uint32_t*>(nearby(entry,size));if(!code)return false;
    const uint32_t ops[]={0xa9bf45f0,0xf85303b0,0xb841b210,0x7200021f,0x54000101,
                         0x13017e10,0x510f9610,0x71000a1f,0x54000088,
                         0xa8c145f0,0xaa1603e0,0,0xa8c145f0,0xaa0003e3,0};
    std::memcpy(code,ops,sizeof(ops));uint32_t redirect;
    if(!branch(reinterpret_cast<uintptr_t>(code+11),base+miapp_layout.labelTarget,code+11)||
       !branch(reinterpret_cast<uintptr_t>(code+14),entry+4,code+14)||
       !branch(entry,reinterpret_cast<uintptr_t>(code),&redirect)){munmap(code,size);return false;}
    __builtin___clear_cache(reinterpret_cast<char*>(code),reinterpret_cast<char*>(code)+sizeof(ops));
    uintptr_t page=entry&~(size-1);
    if(mprotect(code,size,PROT_READ|PROT_EXEC)||mprotect(reinterpret_cast<void*>(page),size,PROT_READ|PROT_WRITE|PROT_EXEC)){
        munmap(code,size);return false;
    }
    __atomic_store_n(reinterpret_cast<uint32_t*>(entry),redirect,__ATOMIC_RELEASE);
    __builtin___clear_cache(reinterpret_cast<char*>(entry),reinterpret_cast<char*>(entry)+4);
    mprotect(reinterpret_cast<void*>(page),size,PROT_READ|PROT_EXEC);
    __android_log_print(ANDROID_LOG_INFO,"MiAppDartBadge","Clone labels resolve through LauncherApps with actual users");return true;
}

static MiappStartup startup;
static MiappInstallState try_install(){
    return startup.run(getpid(),[]{
        native_status("starting","Recognizing launcher libraries before native API return");
        auto started=std::chrono::steady_clock::now();
        uintptr_t base=0,native=0;int result=miapp_scan_runtime(miapp_layout,base,native);
        if(result<0){native_status("error",miapp_scan_error());return MiappInstallState::Failed;}
        if(result==1){
            if(!miapp_validate_runtime()){native_status("error",miapp_scan_error());return MiappInstallState::Failed;}
            if(!patch(base)||!prefer_clone_labels(base)||!miapp_install_runtime_titles(native)){
                native_status("error","Launcher patch installation failed; see MiAppDartBadge/MiAppRuntimeTitles log");return MiappInstallState::Failed;
            }
            native_status("ready","Launcher badge and title hooks installed");
            auto elapsed=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-started).count();
            __android_log_print(ANDROID_LOG_INFO,"MiAppDartBadge","Launcher hooks ready before native API return in %.1f ms (PID %d)",elapsed,getpid());
            return MiappInstallState::Ready;
        }
        return MiappInstallState::Pending;
    });
}
static void* install(void*){
    for(int attempt=0;attempt<600;attempt++){
        if(try_install()!=MiappInstallState::Pending)return nullptr;
        usleep(100000);
    }
    startup.run(getpid(),[]{
        native_status("error","Runtime AOT mapping not found after 60 seconds");
        __android_log_print(ANDROID_LOG_ERROR,"MiAppDartBadge","Runtime AOT mapping not found");
        return MiappInstallState::Failed;
    });
    return nullptr;
}

// HyperOS Runtime preloads libraries in its native spawner. Constructors alone
// cannot start a surviving child thread. Recheck the PID on native API use.
void miapp_ensure_dart_badges(){
    pid_t pid=getpid();auto status=startup.state(pid);
    if(status==MiappInstallState::Ready||status==MiappInstallState::Failed)return;
    char name[128]={};FILE* file=std::fopen("/proc/self/cmdline","r");
    if(file){std::fread(name,1,sizeof(name)-1,file);std::fclose(file);}
    // A native child can call us before it is renamed to com.miui.home.
    // Only attempt installation once the desktop identity is established.
    if(std::strcmp(name,"com.miui.home"))return;
    // Do not let the first API call race an asynchronous full-ELF scan: the
    // launcher caches its initial item titles and badges before the worker ends.
    // If called by a constructor before both libraries are mapped, return to the
    // loader and retry on the next API call as well as in the fallback worker.
    if(try_install()!=MiappInstallState::Pending)return;
    static std::atomic<pid_t> worker{0};
    if(worker.exchange(pid)==pid)return;
    pthread_t thread;
    if(!pthread_create(&thread,nullptr,install,nullptr)){
        pthread_detach(thread);__android_log_print(ANDROID_LOG_INFO,"MiAppDartBadge","Waiting for native desktop mappings in PID %d",pid);
    }else{pid_t expected=pid;worker.compare_exchange_strong(expected,0);}
}

__attribute__((constructor)) static void initialize(){miapp_ensure_dart_badges();}
