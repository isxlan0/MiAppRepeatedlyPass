// Diagnose every system library independently using the production validator.
#include "native_scan.h"
#include <cstdio>

int main(int argc,char** argv){
    if(argc!=2)return 2;
    bool accepted=true;
    for(int i=0;i<3;i++){
        try{
            miapp::Elf elf(miapp::read(std::string(argv[1])+"/"+miapp::names[i]));
            miapp::validateSystem(elf,i);
            elf.renamed(miapp::names[i],miapp::originals[i]);
            std::printf("PASS: %s\n",miapp::names[i]);
        }catch(const std::exception& error){
            accepted=false;
            std::printf("REFUSED: %s: %s\n",miapp::names[i],error.what());
        }
    }
    return accepted?0:1;
}
