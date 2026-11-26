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
#include "native_layout.h"

// Writable file data so the installer can fill it without recompiling on-device.
// ready remains zero in the distributed ZIP and is set only after full analysis.
MiappLayout miapp_layout={{'M','I','A','P','P','_','L','A','Y','O','U','T','_','V','1','!'},1,0,{},{},{},0,0,0,0,0,0,0,{},{}};

extern "C" bool Security_is_xspace_user_id(int32_t);
extern "C" void miapp_dart_x1();
extern "C" void miapp_dart_x2();
bool miapp_install_runtime_titles(uintptr_t);

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

// Validate the complete executable segment before applying any patch. An OTA or
// APK update after module installation must not reuse stale generated addresses.
static uint64_t code_hash(uintptr_t start,size_t length){
    uint64_t hash=0xcbf29ce484222325ULL;
    const auto bytes=reinterpret_cast<const uint8_t*>(start);
    for(size_t i=0;i<length;i++){hash^=bytes[i];hash*=0x100000001b3ULL;}
    return hash;
}
static uintptr_t library_base(const MiappLibraryLayout& layout){
    if(miapp_layout.version!=1||miapp_layout.ready!=1||!layout.codeSize||layout.codeSize>128*1024*1024)return 0;
    FILE* maps=std::fopen("/proc/self/maps","r");if(!maps)return 0;
    char line[1024];uintptr_t base=0,covered=0;
    while(std::fgets(line,sizeof(line),maps)){
        unsigned long start=0,end=0,offset=0;char perms[5]={};
        if(std::sscanf(line,"%lx-%lx %4s %lx",&start,&end,perms,&offset)!=4||
           !std::strstr(line,"/product/priv-app/MiuiHome/MiuiHome.apk"))continue;
        if(offset==layout.zipOffset&&perms[0]=='r')base=start;
        if(base&&perms[0]=='r'&&perms[2]=='x'){
            uintptr_t first=base+layout.codeAddress,last=first+layout.codeSize;
            // Check file offsets as well as addresses: only this APK entry counts.
            uintptr_t at=first+covered;
            if(start<=at&&end>at&&offset+(at-start)==layout.zipOffset+layout.codeFileOffset+covered)
                covered=(end<last?end:last)-first;
        }
    }
    std::fclose(maps);return covered==layout.codeSize?base:0;
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

static void* install(void*){
    if(miapp_layout.ready!=1){__android_log_print(ANDROID_LOG_ERROR,"MiAppDartBadge","Install-time layout missing");return nullptr;}
    for(int attempt=0;attempt<600;attempt++){
        uintptr_t base=library_base(miapp_layout.dart),native=library_base(miapp_layout.native);
        if(base&&native){
            if(code_hash(base+miapp_layout.dart.codeAddress,miapp_layout.dart.codeSize)!=miapp_layout.dart.codeHash){
                __android_log_print(ANDROID_LOG_ERROR,"MiAppDartBadge","Launcher changed after installation; reinstall module");return nullptr;
            }
            if(code_hash(native+miapp_layout.native.codeAddress,miapp_layout.native.codeSize)!=miapp_layout.native.codeHash){
                __android_log_print(ANDROID_LOG_ERROR,"MiAppRuntimeTitles","Launcher changed after installation; reinstall module");return nullptr;
            }
            if(!patch(base)||!prefer_clone_labels(base)||!miapp_install_runtime_titles(native))return nullptr;
            return nullptr;
        }
        usleep(100000);
    }
    __android_log_print(ANDROID_LOG_ERROR,"MiAppDartBadge","Runtime AOT mapping not found");return nullptr;
}

// HyperOS Runtime preloads libraries in its native spawner. Constructors alone
// cannot start a surviving child thread. Recheck the PID on native API use.
void miapp_ensure_dart_badges(){
    static std::atomic<pid_t> checked{0};pid_t pid=getpid();
    if(checked.load(std::memory_order_relaxed)==pid)return;
    char name[128]={};FILE* file=std::fopen("/proc/self/cmdline","r");
    if(file){std::fread(name,1,sizeof(name)-1,file);std::fclose(file);}
    // A native child can call us before it is renamed to com.miui.home.
    // Only claim this PID once the desktop identity is established.
    if(std::strcmp(name,"com.miui.home"))return;
    pid_t previous=checked.exchange(pid);if(previous==pid)return;
    pthread_t thread;
    if(!pthread_create(&thread,nullptr,install,nullptr)){
        pthread_detach(thread);__android_log_print(ANDROID_LOG_INFO,"MiAppDartBadge","Installing in native desktop PID %d",pid);
    }else{pid_t expected=pid;checked.compare_exchange_strong(expected,0);}
}

__attribute__((constructor)) static void initialize(){miapp_ensure_dart_badges();}
