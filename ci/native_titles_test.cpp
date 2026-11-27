#include <cassert>
#include <string>
static std::string labelPath;
#define MIAPP_OWNER_LABELS_PATH labelPath.c_str()
#include "../src/native/runtime_titles.cpp"
MiappLayout miapp_layout{};
extern "C" bool Security_is_xspace_user_id(int32_t id){return id>=997&&id<=999;}

static void owned(uint64_t* out,const std::string& text){
    void* data=std::malloc(text.size());assert(data);std::memcpy(data,text.data(),text.size());
    out[0]=text.size();out[1]=reinterpret_cast<uint64_t>(data);out[2]=text.size();
}
static std::string text(const uint64_t* slot){return {reinterpret_cast<const char*>(slot[1]),static_cast<size_t>(slot[2])};}
static int expectedUser;
static std::string expectedLabel;
static uint64_t packagePointer;
static bool consumed;
static void consume(void* item,void*){
    auto bytes=static_cast<uint8_t*>(item);
    assert(*reinterpret_cast<int32_t*>(bytes+0x204)==expectedUser);
    for(auto at:{0x30,0x48}){
        auto slot=reinterpret_cast<uint64_t*>(bytes+at);
        assert(text(slot)==expectedLabel);std::free(reinterpret_cast<void*>(slot[1]));
    }
    auto package=reinterpret_cast<uint64_t*>(bytes+0x60);
    assert(package[1]==packagePointer&&text(package)=="com.test.app");
    std::free(reinterpret_cast<void*>(package[1]));consumed=true;
}
int main(int argc,char** argv){
    assert(argc==2);labelPath=argv[1];
    FILE* file=std::fopen(labelPath.c_str(),"w");assert(file);
    std::fputs("com.test.app\t微信\n",file);assert(std::fclose(file)==0);
    encode=consume;
    for(int user:{0,997,998,999,996,1019}){
        alignas(8) uint8_t item[0x208]={};
        *reinterpret_cast<uint32_t*>(item+0x200)=1;
        *reinterpret_cast<int32_t*>(item+0x204)=user;
        // Pre-numbered values must be rebuilt from owner labels, not doubled.
        owned(reinterpret_cast<uint64_t*>(item+0x30),"旧名称3");
        owned(reinterpret_cast<uint64_t*>(item+0x48),"旧名称3");
        auto package=reinterpret_cast<uint64_t*>(item+0x60);owned(package,"com.test.app");packagePointer=package[1];
        expectedUser=user;expectedLabel=user>=997&&user<=999?"微信"+std::to_string(1001-user):"旧名称3";
        consumed=false;numbered_item(item,nullptr);assert(consumed);
    }
    getText=[](uint64_t* out,const char*,size_t,int32_t){owned(out,"微信");};
    getUser=[](void* info)->void*{return info;};getId=[](void* handle){return *static_cast<int32_t*>(handle);};
    for(int user:{0,997,998,999,996,1019}){
        uint64_t out[3]={};numbered_activity(out,"com.test.app",12,1,&user);
        assert(text(out)==(user>=997&&user<=999?"微信"+std::to_string(1001-user):"微信"));
        std::free(reinterpret_cast<void*>(out[1]));
    }
    assert(!unlink(labelPath.c_str()));
    puts("PASS: item/activity titles 999=2, 998=3, 997=4; owner and serial IDs preserved; owned strings consumed");
}
