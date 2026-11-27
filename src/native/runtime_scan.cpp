#include "native_scan.h"
#include "native_layout.h"
#include <android/log.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <sys/mman.h>
#include <unistd.h>
#include <memory>

namespace {
struct Mapping {uintptr_t start,end;uint64_t offset,inode;unsigned major,minor;bool executable;std::string path;};
std::vector<Mapping> mappings(const char* source="/proc/self/maps"){
    std::vector<Mapping> result;FILE* file=std::fopen(source,"r");if(!file)return result;char line[8192];
    while(std::fgets(line,sizeof(line),file)){
        Mapping m{};char permissions[5];int at=0;
        if(std::sscanf(line,"%lx-%lx %4s %lx %x:%x %lu %n",&m.start,&m.end,permissions,&m.offset,&m.major,&m.minor,&m.inode,&at)!=7||permissions[0]!='r')continue;
        m.path=line+at;while(!m.path.empty()&&(m.path.back()=='\n'||m.path.back()=='\r'))m.path.pop_back();
        m.executable=permissions[2]=='x';result.push_back(std::move(m));
    }std::fclose(file);return result;
}
struct Input {
    int fd;uint64_t offset,length;struct stat state{};std::string path;
    explicit Input(const Mapping& map):fd(open(map.path.c_str(),O_RDONLY|O_CLOEXEC)),offset(map.offset),path(map.path){
        try{
            miapp::require(fd>=0&&fstat(fd,&state)==0,"Mapped APK is unavailable");
            bool same=static_cast<uint64_t>(state.st_ino)==map.inode&&major(state.st_dev)==map.major&&minor(state.st_dev)==map.minor;
            if(!same){
                // OverlayFS can expose its own st_dev while mmap uses the lower
                // file's device. Compare the backing identity of a private map.
                void* probe=mmap(nullptr,sysconf(_SC_PAGESIZE),PROT_READ,MAP_PRIVATE,fd,map.offset);
                if(probe!=MAP_FAILED){
                    uintptr_t address=reinterpret_cast<uintptr_t>(probe);
                    for(const auto& actual:mappings())if(actual.start<=address&&actual.end>address){
                        same=actual.inode==map.inode&&actual.major==map.major&&actual.minor==map.minor&&actual.offset+address-actual.start==map.offset;break;
                    }
                    munmap(probe,sysconf(_SC_PAGESIZE));
                }
            }
            miapp::require(same,"Mapped APK replaced while process is alive: "+path);
            miapp::require(state.st_size>0&&offset<static_cast<uint64_t>(state.st_size),"Invalid mapped file size");length=state.st_size-offset;
        }catch(...){if(fd>=0)close(fd);throw;}
    }
    ~Input(){close(fd);}
    void read(void* out,uint64_t at,size_t size)const{
        miapp::require(at<=length&&size<=length-at,"Truncated mapped ELF");size_t done=0;
        while(done<size){ssize_t n=pread(fd,static_cast<char*>(out)+done,size-done,offset+at+done);miapp::require(n>0,"Cannot read mapped ELF");done+=n;}
    }
    template<class T>T get(uint64_t at)const{T result;read(&result,at,sizeof(result));return result;}
    void raw(void* out,uint64_t at,size_t size)const{
        miapp::require(at<=static_cast<uint64_t>(state.st_size)&&size<=static_cast<uint64_t>(state.st_size)-at,"Truncated APK directory");size_t done=0;
        while(done<size){ssize_t n=pread(fd,static_cast<char*>(out)+done,size-done,at+done);miapp::require(n>0,"Cannot read APK directory");done+=n;}
    }
    template<class T>T raw(uint64_t at)const{T result;raw(&result,at,sizeof(result));return result;}
    std::string entryName(uint64_t& entrySize)const{
        entrySize=length;
        if(path.size()>=3&&path.compare(path.size()-3,3,".so")==0)return path.substr(path.find_last_of('/')+1);
        uint64_t size=state.st_size,tailSize=std::min<uint64_t>(size,65557);miapp::Bytes tail(tailSize);raw(tail.data(),size-tailSize,tail.size());
        uint64_t end=UINT64_MAX;
        for(int64_t i=static_cast<int64_t>(tail.size())-22;i>=0;i--){uint32_t magic;std::memcpy(&magic,tail.data()+i,4);if(magic!=0x06054b50)continue;
            uint16_t comment;std::memcpy(&comment,tail.data()+i+20,2);if(i+22+comment==tail.size()){end=size-tailSize+i;break;}}
        miapp::require(end!=UINT64_MAX,"Missing APK directory");
        auto count=raw<uint16_t>(end+10);uint64_t at=raw<uint32_t>(end+16),directorySize=raw<uint32_t>(end+12);
        miapp::require(raw<uint16_t>(end+4)==0&&raw<uint16_t>(end+6)==0&&raw<uint16_t>(end+8)==count&&at<=end&&directorySize==end-at,"Unsupported APK directory");
        std::set<std::string> found;std::string selected;
        for(unsigned i=0;i<count;i++){
            miapp::require(at+46<=end&&raw<uint32_t>(at)==0x02014b50,"Invalid APK directory record");
            auto n=raw<uint16_t>(at+28),extra=raw<uint16_t>(at+30),comment=raw<uint16_t>(at+32);miapp::require(n<4096&&at+46+n+extra+comment<=end,"Invalid APK entry name");
            std::string name(n,'\0');raw(&name[0],at+46,n);
            if(name=="lib/arm64-v8a/libapp.so"||name=="lib/arm64-v8a/libapp_launcher.so"){
                miapp::require(found.insert(name).second,"Duplicate APK library entry");uint64_t local=raw<uint32_t>(at+42);
                miapp::require(raw<uint32_t>(local)==0x04034b50,"Invalid APK local header");
                uint64_t data=local+30+raw<uint16_t>(local+26)+raw<uint16_t>(local+28);
                if(data==offset){
                    miapp::require(raw<uint16_t>(at+10)==0&&(raw<uint16_t>(at+8)&1)==0,"Mapped APK entry compressed or encrypted");
                    miapp::require(raw<uint32_t>(at+20)==raw<uint32_t>(at+24)&&data<=size&&raw<uint32_t>(at+24)<=size-data,"Invalid APK library size");
                    selected=name.substr(name.find_last_of('/')+1);entrySize=raw<uint32_t>(at+24);
                }
            }at+=46+n+extra+comment;
        }miapp::require(at==end,"APK directory length mismatch");return selected;
    }
    std::unique_ptr<miapp::Elf> target(std::string& name)const{
        uint64_t entrySize=0;name=entryName(entrySize);if(name!="libapp.so"&&name!="libapp_launcher.so")return nullptr;
        auto h=get<Elf64_Ehdr>(0);if(std::memcmp(h.e_ident,ELFMAG,4))return nullptr;
        miapp::require(h.e_phentsize==sizeof(Elf64_Phdr)&&h.e_phnum>0&&h.e_phnum<128,"Invalid mapped ELF program table");
        std::vector<Elf64_Phdr> loads;uint64_t extent=h.e_phoff+h.e_phnum*sizeof(Elf64_Phdr);
        for(int i=0;i<h.e_phnum;i++){auto p=get<Elf64_Phdr>(h.e_phoff+i*sizeof(Elf64_Phdr));if(p.p_type==PT_LOAD)loads.push_back(p);}
        miapp::require(h.e_shentsize==sizeof(Elf64_Shdr)&&h.e_shnum>0,"Missing mapped ELF sections");extent=std::max<uint64_t>(extent,h.e_shoff+h.e_shnum*sizeof(Elf64_Shdr));
        for(int i=0;i<h.e_shnum;i++){auto s=get<Elf64_Shdr>(h.e_shoff+i*sizeof(Elf64_Shdr));if(s.sh_type!=SHT_NOBITS){miapp::require(s.sh_offset<=length&&s.sh_size<=length-s.sh_offset,"Invalid mapped ELF section");extent=std::max<uint64_t>(extent,s.sh_offset+s.sh_size);}}
        for(auto p:loads){miapp::require(p.p_offset<=length&&p.p_filesz<=length-p.p_offset,"Invalid mapped ELF segment");extent=std::max<uint64_t>(extent,p.p_offset+p.p_filesz);}
        miapp::require(extent<=entrySize&&extent<=128*1024*1024,"Oversized mapped ELF");miapp::Bytes data(extent);read(data.data(),0,data.size());return std::unique_ptr<miapp::Elf>(new miapp::Elf(std::move(data)));
    }
};
struct Loaded {std::unique_ptr<miapp::Elf> elf;Mapping map;};
MiappLibraryLayout layout(const Loaded& library,const std::vector<Mapping>& maps){
    auto s=library.elf->executable();uintptr_t covered=0;
    for(const auto& m:maps)if(m.executable&&m.inode==library.map.inode&&m.major==library.map.major&&m.minor==library.map.minor&&m.path==library.map.path){
        auto at=library.map.start+s.p_vaddr+covered,last=library.map.start+s.p_vaddr+s.p_filesz;
        if(m.start<=at&&m.end>at&&m.offset+(at-m.start)==library.map.offset+s.p_offset+covered)covered=std::min<uintptr_t>(m.end,last)-(library.map.start+s.p_vaddr);
    }
    // The loader may have mapped the ELF header before its executable pages.
    // Wait for those mappings instead of treating startup timing as incompatibility.
    if(covered!=s.p_filesz)return {};
    auto disk=miapp::hash(library.elf->data.data()+s.p_offset,s.p_filesz);
    return {library.map.offset,s.p_vaddr,s.p_filesz,disk,s.p_offset};
}
struct Guard {uintptr_t address;miapp::Bytes expected;std::string label;};
thread_local std::vector<Guard> guards;
thread_local std::string refusal;
void guard(const miapp::Elf& elf,uintptr_t base,uint64_t at,size_t length,const char* label){
    auto offset=elf.file(at,length);miapp::require(elf.code(at,length),"Guard outside executable segment");
    guards.push_back({base+at,miapp::Bytes(elf.data.begin()+offset,elf.data.begin()+offset+length),label});
}
}

const char* miapp_scan_error(){return refusal.c_str();}
bool miapp_validate_runtime(){
    for(const auto& g:guards)if(std::memcmp(reinterpret_cast<const void*>(g.address),g.expected.data(),g.expected.size())){
        refusal="Launcher target already modified: "+g.label;return false;
    }
    return !guards.empty();
}

// 0 means the loader has not mapped both libraries yet; -1 is an unsupported
// layout. Publish only once both images and every signature have been checked.
int miapp_scan_runtime(MiappLayout& output,uintptr_t& dartBase,uintptr_t& nativeBase){
    guards.clear();refusal.clear();
    try{
        auto maps=mappings();Loaded dart{},native{};
        for(const auto& m:maps){
            if(m.end-m.start<sizeof(Elf64_Ehdr)||m.path.empty()||m.path[0]!='/'||
               (m.path.find(".apk")==std::string::npos&&m.path.find("libapp")==std::string::npos))continue;
            if(std::memcmp(reinterpret_cast<const void*>(m.start),ELFMAG,4))continue;
            Input input(m);std::string name;auto elf=input.target(name);if(!elf)continue;
            Loaded& target=name=="libapp.so"?dart:native;miapp::require(!target.elf,"Multiple mapped launcher libraries");target.elf=std::move(elf);target.map=m;
        }
        if(!dart.elf||!native.elf)return 0;
        MiappLayout result{};std::memcpy(result.magic,"MIAPP_LAYOUT_V1!",16);result.version=1;
        result.dart=layout(dart,maps);result.native=layout(native,maps);
        if(!result.dart.codeSize||!result.native.codeSize)return 0;
        auto& d=*dart.elf;auto& n=*native.elf;
        using miapp::pattern;
        const miapp::Pattern badges[]={pattern(miapp::BADGE_0),pattern(miapp::BADGE_1),pattern(miapp::BADGE_2),pattern(miapp::BADGE_3),pattern(miapp::BADGE_4)};
        for(int i=0;i<5;i++){result.badges[i]=d.unique("Dart badge",{badges[i]});miapp::require(d.matches(result.badges[i],badges[i],false),"Dart badge instructions changed");}
        miapp::require(result.badges[1]-result.badges[0]==0xd0,"Dart paired predicate control flow changed");
        result.labelEntry=d.unique("Dart label",{pattern(miapp::LABEL_HEAD)});result.labelTarget=d.branch(result.labelEntry+56,false);
        miapp::require(result.labelTarget==result.labelEntry+176&&d.matches(result.labelTarget,pattern(miapp::LABEL_TARGET),false),"Dart fallback control flow changed");
        result.titleCall=n.unique("Activity label call",{pattern(miapp::TITLE_CALL)})+20;result.getText=n.branch(result.titleCall,true);
        miapp::require(n.matches(result.getText,pattern(miapp::TEXT_EARLY))||n.matches(result.getText,pattern(miapp::TEXT_CURRENT)),"get_text ABI changed");
        result.encode=n.unique("Item serializer",{pattern(miapp::SERIALIZER_EARLY),pattern(miapp::SERIALIZER_CURRENT)});
        result.getUser=n.plt("LauncherActivityInfoInternal_get_user");result.getId=n.plt("UserHandle_get_handle");
        for(int i=0;i<4;i++){result.titleExpected[i]=n.instruction(result.titleCall+i*4);result.encodeExpected[i]=n.instruction(result.encode+i*4);}
        for(int i=0;i<5;i++)guard(d,dart.map.start,result.badges[i],20,"badge predicate");
        guard(d,dart.map.start,result.labelEntry,176+sizeof(miapp::LABEL_TARGET),"label resolver");
        guard(n,native.map.start,result.titleCall-20,sizeof(miapp::TITLE_CALL),"activity title call");
        guard(n,native.map.start,result.getText,n.matches(result.getText,pattern(miapp::TEXT_EARLY))?sizeof(miapp::TEXT_EARLY):sizeof(miapp::TEXT_CURRENT),"get_text");
        guard(n,native.map.start,result.encode,n.matches(result.encode,pattern(miapp::SERIALIZER_EARLY))?sizeof(miapp::SERIALIZER_EARLY):sizeof(miapp::SERIALIZER_CURRENT),"item serializer");
        guard(n,native.map.start,result.getUser,16,"get_user PLT");
        guard(n,native.map.start,result.getId,16,"get_handle PLT");
        if(!miapp_validate_runtime())throw std::runtime_error(refusal);
        result.ready=1;output=result;dartBase=dart.map.start;nativeBase=native.map.start;
        __android_log_print(ANDROID_LOG_INFO,"MiAppDartBadge","Recognized current launcher: %s; %s",dart.map.path.c_str(),native.map.path.c_str());return 1;
    }catch(const std::exception& e){
        refusal=e.what();guards.clear();
#ifdef MIAPP_SCAN_DIAGNOSTICS
        std::fprintf(stderr,"Runtime recognition refused: %s\n",e.what());
#endif
        __android_log_print(ANDROID_LOG_ERROR,"MiAppDartBadge","Runtime recognition refused: %s",e.what());return -1;
    }
}
