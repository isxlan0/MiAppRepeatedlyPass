#pragma once
#include <elf.h>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <map>
#include <set>
#include <stdexcept>
#include <string>
#include <vector>
#include "native_patterns.h"

// The same bounded ELF reader is used before mounting ROM libraries and in the
// native launcher. No absolute function addresses or ROM version table.
namespace miapp {
// Literal diagnostics must not allocate a std::string on every scanned word.
inline void require(bool ok,const char* why){if(!ok)throw std::runtime_error(why);}
inline void require(bool ok,const std::string& why){if(!ok)throw std::runtime_error(why);}
using Bytes=std::vector<uint8_t>;
inline Bytes read(const std::string& path,uint64_t offset=0,uint64_t size=0){
    std::ifstream in(path,std::ios::binary|std::ios::ate);require(bool(in),"Cannot read "+path);
    auto end=in.tellg();require(end>0&&offset<static_cast<uint64_t>(end),"Empty input");
    if(!size)size=static_cast<uint64_t>(end)-offset;
    require(size<=128*1024*1024&&size<=static_cast<uint64_t>(end)-offset,"Oversized/truncated ELF");
    Bytes data(size);in.seekg(offset);require(bool(in.read(reinterpret_cast<char*>(data.data()),size)),"Short ELF read");return data;
}
inline uint64_t hash(const uint8_t* data,size_t length){uint64_t h=0xcbf29ce484222325ULL;for(size_t i=0;i<length;i++){h^=data[i];h*=0x100000001b3ULL;}return h;}
struct Pattern {const uint32_t* data;size_t size;};
template<size_t N> constexpr Pattern pattern(const uint32_t (&data)[N]){return {data,N};}
inline uint32_t mask(uint32_t op,int index,int length){
    if((op&0x7c000000)==0x14000000){int target=index+(static_cast<int32_t>(op<<6)>>6);return target>=0&&target<length?~0U:0xfc000000;}
    if((op&0x1f000000)==0x10000000)return 0x9f00001f;
    if((op&0x3b000000)==0x18000000)return 0xff00001f;
    return ~0U;
}
struct Elf {
    Bytes data;std::vector<Elf64_Phdr> loads;Elf64_Phdr dynamic{};
    std::map<std::string,Elf64_Sym> exports;std::map<uint64_t,std::string> imports;
    void range(uint64_t at,uint64_t size)const{require(at<=data.size()&&size<=data.size()-at,"Truncated ELF data");}
    template<class T> T get(uint64_t at)const{range(at,sizeof(T));T value;std::memcpy(&value,data.data()+at,sizeof(value));return value;}
    std::string string(uint64_t at,uint64_t length)const{
        range(at,length);auto p=reinterpret_cast<const char*>(data.data()+at);auto end=static_cast<const char*>(std::memchr(p,0,length));
        require(end!=nullptr,"Unterminated ELF string");return std::string(p,end);
    }
    explicit Elf(Bytes bytes):data(std::move(bytes)){
        auto h=get<Elf64_Ehdr>(0);
        require(!std::memcmp(h.e_ident,ELFMAG,SELFMAG)&&h.e_ident[EI_CLASS]==ELFCLASS64&&h.e_ident[EI_DATA]==ELFDATA2LSB&&h.e_machine==EM_AARCH64&&h.e_type==ET_DYN,"Expected AArch64 ELF library");
        require(h.e_phentsize==sizeof(Elf64_Phdr)&&h.e_phnum>0&&h.e_phnum<128,"Invalid ELF program table");
        for(unsigned i=0;i<h.e_phnum;i++){
            auto p=get<Elf64_Phdr>(h.e_phoff+i*sizeof(Elf64_Phdr));range(p.p_offset,p.p_filesz);
            if(p.p_type==PT_LOAD){require(p.p_filesz<=p.p_memsz,"Invalid ELF load size");loads.push_back(p);}
            if(p.p_type==PT_DYNAMIC)dynamic=p;
        }
        require(!loads.empty()&&loads[0].p_offset==0&&loads[0].p_vaddr==0,"Unsupported ELF load bias");
        require(h.e_shentsize==sizeof(Elf64_Shdr)&&h.e_shnum>0,"Missing ELF sections");
        std::vector<Elf64_Shdr> sections;int symbols=-1;
        for(unsigned i=0;i<h.e_shnum;i++){
            auto s=get<Elf64_Shdr>(h.e_shoff+i*sizeof(Elf64_Shdr));sections.push_back(s);
            if(s.sh_type==SHT_DYNSYM){require(symbols<0,"Multiple symbol tables");symbols=i;}
        }
        require(symbols>=0,"Missing ELF symbols");auto s=sections[symbols];
        require(s.sh_link<sections.size()&&s.sh_entsize==sizeof(Elf64_Sym)&&s.sh_size%sizeof(Elf64_Sym)==0,"Invalid symbol table");range(s.sh_offset,s.sh_size);
        auto strings=sections[s.sh_link];range(strings.sh_offset,strings.sh_size);std::vector<std::string> names;
        for(uint64_t at=0;at<s.sh_size;at+=sizeof(Elf64_Sym)){
            auto sym=get<Elf64_Sym>(s.sh_offset+at);require(sym.st_name<strings.sh_size,"Invalid symbol name");
            auto name=string(strings.sh_offset+sym.st_name,strings.sh_size-sym.st_name);names.push_back(name);
            if(sym.st_shndx!=SHN_UNDEF&&!name.empty())require(exports.emplace(name,sym).second,"Duplicate ELF export");
        }
        for(auto section:sections)if(section.sh_type==SHT_RELA){
            require(section.sh_entsize==sizeof(Elf64_Rela)&&section.sh_size%sizeof(Elf64_Rela)==0,"Invalid relocation table");range(section.sh_offset,section.sh_size);
            for(uint64_t at=0;at<section.sh_size;at+=sizeof(Elf64_Rela)){
                auto rel=get<Elf64_Rela>(section.sh_offset+at);
                if(ELF64_R_TYPE(rel.r_info)==R_AARCH64_JUMP_SLOT){auto index=ELF64_R_SYM(rel.r_info);require(index<names.size(),"Invalid relocation symbol");require(imports.emplace(rel.r_offset,names[index]).second,"Duplicate GOT slot");}
            }
        }
    }
    uint64_t file(uint64_t va,uint64_t size)const{
        for(auto s:loads)if(va>=s.p_vaddr&&size<=s.p_filesz&&va-s.p_vaddr<=s.p_filesz-size)return s.p_offset+va-s.p_vaddr;
        throw std::runtime_error("ELF virtual address outside file");
    }
    Elf64_Phdr executable()const{
        Elf64_Phdr found{};int count=0;for(auto s:loads)if(s.p_flags&PF_X){found=s;count++;}
        require(count==1,"Expected one executable segment");return found;
    }
    bool code(uint64_t va,uint64_t size)const{auto s=executable();return va>=s.p_vaddr&&size<=s.p_filesz&&va-s.p_vaddr<=s.p_filesz-size;}
    uint32_t instruction(uint64_t va)const{require(code(va,4),"Instruction outside code");return get<uint32_t>(file(va,4));}
    bool matches(uint64_t va,Pattern p,bool relocations=true)const{
        if(!code(va,p.size*4))return false;auto offset=file(va,p.size*4);
        for(size_t i=0;i<p.size;i++){auto m=relocations?mask(p.data[i],i,p.size):~0U;if((get<uint32_t>(offset+4*i)&m)!=(p.data[i]&m))return false;}return true;
    }
    uint64_t unique(const char* label,std::initializer_list<Pattern> patterns)const{
        auto s=executable();std::set<uint64_t> found;
        for(auto p:patterns)if(p.size>=2&&p.size*4<=s.p_filesz)for(uint64_t i=0;i<=s.p_filesz-p.size*4;i+=4){
            if(get<uint32_t>(s.p_offset+i)!=p.data[0]||get<uint32_t>(s.p_offset+i+4)!=p.data[1])continue;
            if(matches(s.p_vaddr+i,p))found.insert(s.p_vaddr+i);
        }
        require(found.size()==1,std::string(label)+": expected one complete match, found "+std::to_string(found.size()));return *found.begin();
    }
    uint64_t branch(uint64_t va,bool link)const{
        auto op=instruction(va);require((op&0xfc000000)==(link?0x94000000U:0x14000000U),"Expected direct branch");
        auto target=va+(static_cast<int64_t>(static_cast<int32_t>(op<<6))>>4);require(code(target,4),"Branch outside code");return target;
    }
    uint64_t plt(const char* name)const{
        auto s=executable();std::set<uint64_t> found;
        // The complete executable segment is already bounds checked. Read its
        // file offsets directly and inspect the rest only for an ADRP x16.
        for(uint64_t i=0;i+16<=s.p_filesz;i+=4){auto va=s.p_vaddr+i;auto a=get<uint32_t>(s.p_offset+i);
            if((a&0x9f00001f)!=0x90000010)continue;
            auto b=get<uint32_t>(s.p_offset+i+4),c=get<uint32_t>(s.p_offset+i+8),d=get<uint32_t>(s.p_offset+i+12);
            if((b&0xffc003ff)!=0xf9400211||(c&0xffc003ff)!=0x91000210||d!=0xd61f0220)continue;
            int64_t imm=((a>>29)&3)|(((a>>5)&0x7ffff)<<2);if(imm&(1<<20))imm-=(1<<21);
            auto got=(va&~4095ULL)+imm*4096+((b>>10)&4095)*8;auto it=imports.find(got);
            if(it!=imports.end()&&it->second==name&&((c>>10)&4095)==((b>>10)&4095)*8)found.insert(va);
        }require(found.size()==1,std::string("PLT relocation mismatch: ")+name);return *found.begin();
    }
    Elf64_Sym symbol(const char* name)const{auto it=exports.find(name);require(it!=exports.end(),std::string("Missing export: ")+name);require(code(it->second.st_value,it->second.st_size),"Export outside code");return it->second;}
    uint64_t sonameOffset()const{
        require(dynamic.p_filesz>0&&dynamic.p_filesz%sizeof(Elf64_Dyn)==0,"Missing dynamic table");uint64_t strings=0,length=0,name=UINT64_MAX;
        for(uint64_t at=0;at<dynamic.p_filesz;at+=sizeof(Elf64_Dyn)){
            auto d=get<Elf64_Dyn>(dynamic.p_offset+at);if(d.d_tag==DT_NULL)break;
            if(d.d_tag==DT_STRTAB)strings=d.d_un.d_ptr;if(d.d_tag==DT_STRSZ)length=d.d_un.d_val;if(d.d_tag==DT_SONAME)name=d.d_un.d_val;
        }require(length>0&&name<length,"Missing SONAME");auto offset=file(strings,length)+name;string(offset,length-name);return offset;
    }
    Bytes renamed(const std::string& old,const std::string& replacement)const{
        auto at=sonameOffset();require(string(at,data.size()-at)==old,"Unexpected SONAME; refusing an overlay as original");require(replacement.size()<=old.size(),"Replacement SONAME too long");
        Bytes result=data;std::memset(result.data()+at,0,old.size()+1);std::memcpy(result.data()+at,replacement.data(),replacement.size());return result;
    }
};
inline constexpr const char* names[]={"libhyper_os_security_public.so","libhyper_os_bundle_public.so","libhyper_os_bundle_private.dylib.so"};
inline constexpr const char* originals[]={"libmiapp_security_original.so","libmiapp_bundle_original.so","libmiapp_bundle_private_orig.so"};
inline constexpr const char* privateBadge="_RNvMs4_NtNtCs2Z0Otu41e4o_23hyper_os_bundle_private2pm15package_managerNtB5_14PackageManager20get_user_badged_icon";
inline void validateSystem(const Elf& e,int index){
    if(index==0){
        const uint32_t predicate[]={0xd503233f,0xa9bf7bfd,0x910003fd,0x710f9c1f,0x1a9f17e0,0xa8c17bfd,0xd50323bf,0xd65f03c0};
        const uint32_t tail[]={0xd503233f,0xa9bf7bfd,0x910003fd,0xa8c17bfd,0xd50323bf,0x14000187};
        auto s=e.symbol("Security_is_xspace_user_id");require(s.st_size==sizeof(predicate)&&e.matches(s.st_value,pattern(predicate),false),"Security predicate ABI changed");
        for(auto name:{"Security_is_xspace_user","Security_get_current_user_id"}){s=e.symbol(name);require(s.st_size==sizeof(tail)&&e.matches(s.st_value,pattern(tail)),"Security object ABI changed");}
    }else if(index==1){auto s=e.symbol("PackageManager_get_user_badged_icon");require(s.st_size==sizeof(PUBLIC_BADGE)&&e.matches(s.st_value,pattern(PUBLIC_BADGE)),"Public badge ABI changed");}
    else {auto s=e.symbol(privateBadge);require((s.st_size==sizeof(RUST_EARLY)&&e.matches(s.st_value,pattern(RUST_EARLY)))||(s.st_size==sizeof(RUST_CURRENT)&&e.matches(s.st_value,pattern(RUST_CURRENT))),"Rust badge ABI changed");}
}
}
