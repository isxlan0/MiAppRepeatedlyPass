#include "native_scan.h"
#include "rust_entry.h"
#include <cassert>
#include <cstdio>
static void refuses(const miapp::Bytes& bytes,int index){
    bool refused=false;try{miapp::validateSystem(miapp::Elf(bytes),index);}catch(const std::exception&){refused=true;}assert(refused);
}
int main(int argc,char** argv){
    assert(argc==2);int checks=0;
    for(int i=0;i<3;i++){
        miapp::Elf e(miapp::read(std::string(argv[1])+"/"+miapp::names[i]));miapp::validateSystem(e,i);
        auto symbol=e.symbol(i==0?"Security_is_xspace_user_id":i==1?"PackageManager_get_user_badged_icon":miapp::privateBadge);
        if(i==2)assert(miapp::rustEntry(e.data.data()+e.file(symbol.st_value,16)));
        // Mutate each non-relocation word: no user literal, stack slot,
        // register ABI or conditional branch may be silently ignored.
        for(size_t n=0;n<symbol.st_size/4;n++){
            size_t at=e.file(symbol.st_value+4*n,4);auto op=e.get<uint32_t>(at);
            if(miapp::mask(op,n,symbol.st_size/4)!=~0U)continue;
            auto bytes=e.data;uint32_t changed=op^1;std::memcpy(bytes.data()+at,&changed,4);refuses(bytes,i);checks++;
        }
    }
    std::printf("PASS: all original ABIs and Rust entry; %d semantic/ABI instruction mutations refused\n",checks);
}
