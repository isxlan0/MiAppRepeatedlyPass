#include "native_scan.h"
#include <cstdio>
#include <sys/stat.h>
#include <unistd.h>

// Standalone ELF analysis: no JVM, package manager, Binder or zygote dependency.
int main(int argc,char** argv){
    try{
        miapp::require(argc==3,"Usage: prepare-system <original-lib64> <output-lib64>");
        std::vector<miapp::Bytes> output;
        for(int i=0;i<3;i++){
            miapp::Elf elf(miapp::read(std::string(argv[1])+"/"+miapp::names[i]));miapp::validateSystem(elf,i);
            output.push_back(elf.renamed(miapp::names[i],miapp::originals[i]));
        }
        for(int i=0;i<3;i++){
            std::string path=std::string(argv[2])+"/"+miapp::originals[i];
            FILE* file=std::fopen(path.c_str(),"wb");miapp::require(file!=nullptr,"Cannot create original library");
            bool ok=std::fwrite(output[i].data(),1,output[i].size(),file)==output[i].size();
            ok=std::fflush(file)==0&&ok;ok=fsync(fileno(file))==0&&ok;ok=std::fclose(file)==0&&ok;
            miapp::require(ok&&chmod(path.c_str(),0644)==0,"Cannot publish original library");
        }
        std::puts("PASS: current ROM system libraries recognized and extracted");return 0;
    }catch(const std::exception& e){std::fprintf(stderr,"System adaptation refused: %s\n",e.what());return 1;}
}
