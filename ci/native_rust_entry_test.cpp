// Executes the production trampoline on synthetic ABI fixtures. No ROM code runs.
#include <cassert>
#include <cstdio>
#include <dlfcn.h>
#include <initializer_list>
#include "native_patterns.h"
static void* fixtureEntry=nullptr;
static void* fixtureOpen(const char*,int){return reinterpret_cast<void*>(1);}
static void* fixtureSymbol(void*,const char*);
#define dlopen fixtureOpen
#define dlsym fixtureSymbol
#include "../src/native/bundle_private_badge.cpp"
#undef dlopen
#undef dlsym
static bool registered(int32_t user){return user==998||user==997||user==999;}
static void* fixtureSymbol(void*,const char* name){
    return std::strcmp(name,BADGE_SYMBOL)==0?fixtureEntry:reinterpret_cast<void*>(registered);
}
struct Variant {const uint32_t* words;size_t size,head,tail;};
int main(){
    using namespace miapp;
    const Variant variants[]={
        {RUST_EARLY,sizeof(RUST_EARLY)/4,5,6},{RUST_CURRENT,sizeof(RUST_CURRENT)/4,6,7},
        {RUST_EARLY_STACK,sizeof(RUST_EARLY_STACK)/4,5,6},{RUST_CURRENT_STACK,sizeof(RUST_CURRENT_STACK)/4,5,6}};
    const uint32_t result[]={0xb9400029,0xf9000100,0xf9000509,0xf9000900,0xf9000d09,0xf9001100};
    size_t pageSize=static_cast<size_t>(sysconf(_SC_PAGESIZE));int count=0;
    for(auto v:variants){
        size_t ret=0;while(ret<v.size&&v.words[ret]!=0xd65f03c0)ret++;assert(ret<v.size&&ret+1>=v.tail);
        auto pages=static_cast<uint8_t*>(mmap(nullptr,pageSize*2,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0));assert(pages!=MAP_FAILED);
        // Exercise a 16-byte entry patch crossing an OS page boundary as well.
        auto entry=pages+pageSize-8;std::memcpy(entry,v.words,v.head*4);
        std::memcpy(entry+v.head*4,result,sizeof(result));
        std::memcpy(entry+v.head*4+sizeof(result),v.words+ret+1-v.tail,v.tail*4);
        __builtin___clear_cache(reinterpret_cast<char*>(entry),reinterpret_cast<char*>(entry+128));
        assert(mprotect(pages,pageSize*2,PROT_READ|PROT_EXEC)==0);
        auto call=reinterpret_cast<Function>(entry);int32_t id=998;void* manager=reinterpret_cast<void*>(0x12345678);
        auto before=call(manager,&id);uint64_t fields[5];std::memcpy(fields,before.bytes,40);assert(fields[1]==998);
        fixtureEntry=entry;intercept_internal_calls();assert(original!=call&&original);
        uint32_t jump;std::memcpy(&jump,entry,4);assert(jump==0x58000050);
        for(int32_t user:{998,997,999,0,42}){
            auto value=call(manager,&user);std::memcpy(fields,value.bytes,40);
            uint64_t expected=registered(user)?999:user;
            assert(fields[0]==reinterpret_cast<uint64_t>(manager)&&fields[1]==expected&&fields[2]==fields[0]&&fields[3]==expected&&fields[4]==fields[0]);
        }
        munmap(reinterpret_cast<void*>(original),pageSize);original=nullptr;fixtureEntry=nullptr;munmap(pages,pageSize*2);count++;
    }
    uint32_t unsafe[4]={0x14000001,0xd10243ff,0xa9067bfd,0xa90757f6};
    fixtureEntry=unsafe;intercept_internal_calls();assert(original==reinterpret_cast<Function>(unsafe)&&unsafe[0]==0x14000001);
    std::printf("PASS: %d PAC/stack ABI trampolines executed; 40-byte x8 result, registered user routing, page crossing and unsafe prefix refusal\n",count);
}
