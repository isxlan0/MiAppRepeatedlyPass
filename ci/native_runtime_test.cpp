// Standalone Android test: maps APK copies privately, never executes their code.
#include <cassert>
#include <elf.h>
#include <fcntl.h>
#include <vector>
#include <fstream>
#include <string>
static std::string statusPath;
#define MIAPP_NATIVE_STATUS_PATH statusPath.c_str()
#include "../src/native/dart_badge.cpp"
extern "C" bool Security_is_xspace_user_id(int32_t){return false;}

static uintptr_t map_library(int fd,const MiappLibraryLayout& layout){
    Elf64_Ehdr header{};assert(pread(fd,&header,sizeof(header),layout.zipOffset)==sizeof(header));assert(header.e_phentsize==sizeof(Elf64_Phdr));
    std::vector<Elf64_Phdr> segments(header.e_phnum);
    assert(pread(fd,segments.data(),segments.size()*sizeof(Elf64_Phdr),layout.zipOffset+header.e_phoff)==static_cast<ssize_t>(segments.size()*sizeof(Elf64_Phdr)));
    size_t page=sysconf(_SC_PAGESIZE),length=0;
    for(const auto& s:segments)if(s.p_type==PT_LOAD)length=std::max(length,static_cast<size_t>((s.p_vaddr+s.p_memsz+page-1)&~(page-1)));
    void* reservation=mmap(nullptr,length,PROT_NONE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);assert(reservation!=MAP_FAILED);uintptr_t base=reinterpret_cast<uintptr_t>(reservation);
    for(const auto& s:segments)if(s.p_type==PT_LOAD&&s.p_filesz){
        size_t start=s.p_vaddr&~(page-1),size=((s.p_vaddr+s.p_filesz+page-1)&~(page-1))-start,file=(layout.zipOffset+s.p_offset)&~(page-1);assert(start+size<=length);
        assert(mmap(reinterpret_cast<void*>(base+start),size,PROT_READ|((s.p_flags&PF_X)?PROT_EXEC:0),MAP_PRIVATE|MAP_FIXED,fd,file)==reinterpret_cast<void*>(base+start));
    }return base;
}
int main(int argc,char** argv){
    assert(argc==3||argc==4);MiappLayout expected{};std::ifstream in(argv[1],std::ios::binary);assert(in.read(reinterpret_cast<char*>(&expected),sizeof(expected)));assert(expected.ready==1);
    // OTA audits can supply just the two verified ZIP offsets when an unrelated
    // system-library refusal prevents the Java scanner producing a full layout.
    // The production C++ scanner must discover every code address independently.
    bool probe=argc==4&&std::strcmp(argv[3],"probe")==0;
    statusPath=std::string(argv[1])+".native.status";
    int fd=open(argv[2],O_RDONLY);assert(fd>=0);uintptr_t dart=map_library(fd,expected.dart);
    uintptr_t pendingDart=0,pendingNative=0;assert(miapp_scan_runtime(miapp_layout,pendingDart,pendingNative)==0&&miapp_layout.ready==0);
    assert(try_install()==MiappInstallState::Pending);
    uintptr_t native=map_library(fd,expected.native);close(fd);
    if(probe){
        MiappLayout discovered{};uintptr_t a=0,b=0;
        if(miapp_scan_runtime(discovered,a,b)!=1){std::fprintf(stderr,"OTA runtime recognition refused: %s\n",miapp_scan_error());return 2;}
        assert(a==dart&&b==native);expected=discovered;
    }
    if(argc==4&&std::strcmp(argv[3],"refuse")==0){
        assert(miapp_scan_runtime(miapp_layout,pendingDart,pendingNative)==-1&&miapp_layout.ready==0);
        puts("PASS: unsupported or ambiguous APK instructions rejected before publication");return 0;
    }
    size_t pageSize=sysconf(_SC_PAGESIZE);uintptr_t codeStart=(native+expected.native.codeAddress)&~(pageSize-1);
    size_t codeSize=((native+expected.native.codeAddress+expected.native.codeSize+pageSize-1)&~(pageSize-1))-codeStart;
    assert(!mprotect(reinterpret_cast<void*>(codeStart),codeSize,PROT_READ));
    assert(miapp_scan_runtime(miapp_layout,pendingDart,pendingNative)==0&&miapp_layout.ready==0);
    assert(try_install()==MiappInstallState::Pending);
    assert(!mprotect(reinterpret_cast<void*>(codeStart),codeSize,PROT_READ|PROT_EXEC));
    uint32_t before=*reinterpret_cast<uint32_t*>(dart+expected.badges[0]+4);
    if(argc==4&&std::strcmp(argv[3],"unrelated")==0){
        // Another module's patch away from our target functions must coexist.
        uintptr_t at=native+expected.native.codeAddress,page=at&~(pageSize-1);
        assert(!mprotect(reinterpret_cast<void*>(page),pageSize,PROT_READ|PROT_WRITE|PROT_EXEC));*reinterpret_cast<uint32_t*>(at)^=1;
        assert(!mprotect(reinterpret_cast<void*>(page),pageSize,PROT_READ|PROT_EXEC));
    }else if(argc==4&&!probe){
        size_t size=sysconf(_SC_PAGESIZE);uintptr_t at=native+expected.encode,page=at&~(size-1);
        assert(!mprotect(reinterpret_cast<void*>(page),size,PROT_READ|PROT_WRITE|PROT_EXEC));*reinterpret_cast<uint32_t*>(at)^=1;
        assert(!mprotect(reinterpret_cast<void*>(page),size,PROT_READ|PROT_EXEC));
        uintptr_t a=0,b=0;assert(miapp_scan_runtime(miapp_layout,a,b)==-1);assert(miapp_layout.ready==0);assert(*reinterpret_cast<uint32_t*>(dart+expected.badges[0]+4)==before);
        assert(std::strstr(miapp_scan_error(),"item serializer"));
        puts("PASS: conflicting target instructions rejected before publishing addresses or patching");return 0;
    }
    uintptr_t a=0,b=0;assert(miapp_scan_runtime(miapp_layout,a,b)==1);assert(a==dart&&b==native);assert(!std::memcmp(&miapp_layout,&expected,sizeof(expected)));
    // Exercise the actual exported-API initialization path. All patches must
    // exist when it returns, even after an early attempt found partial mappings.
    assert(std::strlen(argv[0])>=std::strlen("com.miui.home"));
    std::strcpy(argv[0],"com.miui.home");
    miapp_ensure_dart_badges();
    assert(startup.state(getpid())==MiappInstallState::Ready);
    miapp_ensure_dart_badges();
    for(auto site:miapp_layout.badges)assert((*reinterpret_cast<uint32_t*>(dart+site+4)&0xfc000000)==0x14000000);
    assert((*reinterpret_cast<uint32_t*>(dart+miapp_layout.labelEntry)&0xfc000000)==0x14000000);
    assert((*reinterpret_cast<uint32_t*>(native+miapp_layout.titleCall)&0xfc000000)==0x94000000);
    assert((*reinterpret_cast<uint32_t*>(native+miapp_layout.encode)&0xfc000000)==0x14000000);
    puts(probe?"PASS: production runtime recognition and all 8 private-map patches; no ROM functions executed":
        "PASS: runtime scan matches Java recognizer; all 8 patches complete before first native API return after pending mappings");
    return 0;
}
