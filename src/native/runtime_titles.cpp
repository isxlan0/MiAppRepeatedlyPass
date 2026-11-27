#include <android/log.h>
#include <cstdint>
#include <cstring>
#include <cstdlib>
#include <cstdio>
#include <mutex>
#include <string>
#include <unordered_map>
#include <sys/stat.h>
#include <sys/mman.h>
#include <unistd.h>
#include "native_layout.h"

extern "C" bool Security_is_xspace_user_id(int32_t);
using GetText=void(*)(uint64_t*,const char*,size_t,int32_t);
using GetUser=void*(*)(void*);
using GetId=int32_t(*)(void*);
static GetText getText=nullptr;
static GetUser getUser=nullptr;
static GetId getId=nullptr;
using Encode=void(*)(void*,void*);
static Encode encode=nullptr;
static std::mutex labelsMutex;
static std::unordered_map<std::string,std::string> labels;
static ino_t labelInode=0;
#ifndef MIAPP_OWNER_LABELS_PATH
#define MIAPP_OWNER_LABELS_PATH "/data/user_de/0/com.miui.home/files/miapp-owner-labels.tsv"
#endif
static std::string owner_label(const std::string& package){
    std::lock_guard<std::mutex> guard(labelsMutex);
    const char* path=MIAPP_OWNER_LABELS_PATH;struct stat state{};
    if(!stat(path,&state)&&state.st_ino!=labelInode){
        FILE* file=std::fopen(path,"r");
        if(file){
            std::unordered_map<std::string,std::string> next;char line[8192];
            while(std::fgets(line,sizeof(line),file)){
                char* tab=std::strchr(line,'\t');if(!tab)continue;*tab++=0;
                size_t n=std::strlen(tab);while(n&&(tab[n-1]=='\n'||tab[n-1]=='\r'))tab[--n]=0;
                if(n&&n<4096)next.emplace(line,tab);
            }
            std::fclose(file);labels.swap(next);labelInode=state.st_ino;
        }
    }
    auto found=labels.find(package);return found==labels.end()?std::string():found->second;
}

static bool valid_string(const uint64_t* s,size_t bound){
    return s[0]!=UINT64_MAX&&s[0]<=bound&&s[2]&&s[2]<=s[0]&&s[1]>=4096;
}
static void replace_owned(uint8_t* slot,const uint64_t* base,char suffix){
    uint64_t old[3];std::memcpy(old,slot,24);
    if(old[0]!=UINT64_MAX&&(old[0]>65536||old[2]>old[0]||(old[0]&&old[1]<4096)))return;
    size_t n=base[2]+1;auto data=static_cast<char*>(std::malloc(n));if(!data)return;
    std::memcpy(data,reinterpret_cast<void*>(base[1]),base[2]);data[n-1]=suffix;
    if(old[0]!=UINT64_MAX&&old[0])std::free(reinterpret_cast<void*>(old[1]));
    const uint64_t out[]={n,reinterpret_cast<uint64_t>(data),n};std::memcpy(slot,out,24);
}
static void numbered_item(void* item,void* serializer){
    auto bytes=static_cast<uint8_t*>(item);uint32_t tag;int32_t user;
    std::memcpy(&tag,bytes+0x200,4);std::memcpy(&user,bytes+0x204,4);
    if(tag==1&&user>=997&&user<=999&&Security_is_xspace_user_id(user)){
        uint64_t pkg[3];std::memcpy(pkg,bytes+0x60,24);
        if(valid_string(pkg,4096)){
            auto name=owner_label(std::string(reinterpret_cast<const char*>(pkg[1]),pkg[2]));
            if(!name.empty()){
                const uint64_t owner[]={name.size(),reinterpret_cast<uint64_t>(name.data()),name.size()};
                char suffix=static_cast<char>('0'+1001-user);
                replace_owned(bytes+0x30,owner,suffix);replace_owned(bytes+0x48,owner,suffix);
            }
        }
    }
    // SseEncode consumes all owned strings. Each replacement has its own native
    // allocation; package, intent, real user ID, and every other field are intact.
    encode(item,serializer);
}

static void numbered_activity(uint64_t* out,const char* package,size_t length,int32_t resource,void* info){
    getText(out,package,length,resource);
    if(!info)return;
    void* handle=getUser(info);if(!handle)return;
    int32_t user=getId(handle);
    if(user<997||user>999||!Security_is_xspace_user_id(user))return;
    // Original get_text returns an owned Option<String> [capacity,pointer,length]
    // in the current locale. The bridge consumes and frees it after serialization.
    // This path appends to the current resource label without consulting the map.
    if(out[0]==UINT64_MAX||out[0]>65536||!out[2]||out[2]>out[0]||out[1]<4096)return;
    size_t size=out[2]+1;auto title=static_cast<char*>(std::malloc(size));if(!title)return;
    std::memcpy(title,reinterpret_cast<void*>(out[1]),out[2]);
    title[size-1]=static_cast<char>('0'+1001-user);
    if(out[0])std::free(reinterpret_cast<void*>(out[1]));
    out[0]=size;out[1]=reinterpret_cast<uint64_t>(title);out[2]=size;
    static unsigned logs=0;
    if(__atomic_fetch_add(&logs,1,__ATOMIC_RELAXED)<24)
        __android_log_print(ANDROID_LOG_INFO,"MiAppRuntimeTitles","Activity label user=%d package=%.*s title=%.*s",user,
                            static_cast<int>(length),package,static_cast<int>(size),title);
}

static bool branch(uintptr_t from,uintptr_t to,uint32_t* out){
    int64_t delta=static_cast<int64_t>(to)-static_cast<int64_t>(from);
    if((delta&3)||delta<-(1LL<<27)||delta>=(1LL<<27))return false;
    *out=0x14000000|(static_cast<uint32_t>(delta/4)&0x03ffffff);return true;
}

bool miapp_install_runtime_titles(uintptr_t base){
    uintptr_t call=base+miapp_layout.titleCall;
    // Validate the exact get_text BL and the following load of Option<String>.
    const auto& pinned=miapp_layout.titleExpected;
    if(std::memcmp(reinterpret_cast<void*>(call),pinned,sizeof(pinned))){
        __android_log_print(ANDROID_LOG_ERROR,"MiAppRuntimeTitles","Activity label bridge mismatch");return false;
    }
    uintptr_t entry=base+miapp_layout.encode;
    const auto& entryPinned=miapp_layout.encodeExpected;
    if(std::memcmp(reinterpret_cast<void*>(entry),entryPinned,sizeof(entryPinned)))return false;
    size_t size=sysconf(_SC_PAGESIZE);uintptr_t page=call&~(size-1);uint32_t* thunk=nullptr;
    for(uintptr_t distance=size;distance<0x07000000&&!thunk;distance+=size){
        const int signs[]={1,-1};
        for(int sign:signs){
            uintptr_t at=page+sign*distance;
            void* p=mmap(reinterpret_cast<void*>(at),size,PROT_READ|PROT_WRITE,
                         MAP_PRIVATE|MAP_ANONYMOUS|MAP_FIXED_NOREPLACE,-1,0);
            if(p==MAP_FAILED)continue;
            if(reinterpret_cast<uintptr_t>(p)==at){thunk=static_cast<uint32_t*>(p);break;}
            munmap(p,size);
        }
    }
    if(!thunk)return false;
    // BL preserves the caller's return address. Capture internal info from x23
    // as the fifth C argument, then tail-call the wrapper through x16.
    const uint32_t ops[]={0xf94002e4,0x58000070,0xd61f0200,0xd503201f};
    std::memcpy(thunk,ops,sizeof(ops));auto hook=numbered_activity;std::memcpy(thunk+4,&hook,8);
    thunk[8]=0x58000050;thunk[9]=0xd61f0200;auto itemHook=numbered_item;std::memcpy(thunk+10,&itemHook,8);
    thunk[16]=entryPinned[0];uint32_t redirect=0,itemRedirect=0;
    if(!branch(call,reinterpret_cast<uintptr_t>(thunk),&redirect)||
       !branch(entry,reinterpret_cast<uintptr_t>(thunk+8),&itemRedirect)||
       !branch(reinterpret_cast<uintptr_t>(thunk+17),entry+4,thunk+17)){munmap(thunk,size);return false;}
    redirect|=0x80000000;
    __builtin___clear_cache(reinterpret_cast<char*>(thunk),reinterpret_cast<char*>(thunk)+72);
    if(mprotect(thunk,size,PROT_READ|PROT_EXEC)||mprotect(reinterpret_cast<void*>(page),size,PROT_READ|PROT_WRITE|PROT_EXEC)){
        munmap(thunk,size);return false;
    }
    getText=reinterpret_cast<GetText>(base+miapp_layout.getText);
    getUser=reinterpret_cast<GetUser>(base+miapp_layout.getUser);
    getId=reinterpret_cast<GetId>(base+miapp_layout.getId);
    encode=reinterpret_cast<Encode>(thunk+16);
    uintptr_t itemPage=entry&~(size-1);
    if(mprotect(reinterpret_cast<void*>(itemPage),size,PROT_READ|PROT_WRITE|PROT_EXEC)){
        mprotect(reinterpret_cast<void*>(page),size,PROT_READ|PROT_EXEC);munmap(thunk,size);return false;
    }
    __atomic_store_n(reinterpret_cast<uint32_t*>(entry),itemRedirect,__ATOMIC_RELEASE);
    __builtin___clear_cache(reinterpret_cast<char*>(entry),reinterpret_cast<char*>(entry)+4);
    __atomic_store_n(reinterpret_cast<uint32_t*>(call),redirect,__ATOMIC_RELEASE);
    __builtin___clear_cache(reinterpret_cast<char*>(call),reinterpret_cast<char*>(call)+4);
    mprotect(reinterpret_cast<void*>(page),size,PROT_READ|PROT_EXEC);
    mprotect(reinterpret_cast<void*>(itemPage),size,PROT_READ|PROT_EXEC);
    __android_log_print(ANDROID_LOG_INFO,"MiAppRuntimeTitles","Native activity labels active: 999=2, 998=3, 997=4");return true;
}
