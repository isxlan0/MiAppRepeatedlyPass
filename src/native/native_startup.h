#pragma once
#include <atomic>
#include <cstdint>
#include <unistd.h>

enum class MiappInstallState : uint64_t { Pending, Running, Ready, Failed };

// Constructors can run in the native spawner before fork. A child must never
// inherit a parent's completed state or wait on its vanished installer thread.
class MiappStartup {
    std::atomic<uint64_t> value{0};
public:
    MiappInstallState state(pid_t pid)const{
        auto current=value.load(std::memory_order_acquire);
        return (current>>2)==static_cast<uint64_t>(pid)?static_cast<MiappInstallState>(current&3):MiappInstallState::Pending;
    }
    template<class Install> MiappInstallState run(pid_t pid,Install install){
        const auto process=static_cast<uint64_t>(pid)<<2;
        for(;;){
            auto current=value.load(std::memory_order_acquire);
            if((current&~3ULL)==process){
                auto status=static_cast<MiappInstallState>(current&3);
                if(status==MiappInstallState::Ready||status==MiappInstallState::Failed)return status;
                if(status==MiappInstallState::Running){usleep(1000);continue;}
            }
            if(!value.compare_exchange_weak(current,process|static_cast<uint64_t>(MiappInstallState::Running)))continue;
            auto status=install();
            value.store(process|static_cast<uint64_t>(status),std::memory_order_release);
            return status;
        }
    }
};
