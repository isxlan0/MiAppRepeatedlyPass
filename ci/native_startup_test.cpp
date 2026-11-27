#include "../src/native/native_startup.h"
#include <cassert>
#include <cstdio>
#include <future>
#include <sys/wait.h>
#include <thread>

int main(){
    using State=MiappInstallState;
    MiappStartup gate;pid_t pid=getpid();int calls=0;
    assert(gate.run(pid,[&]{calls++;return State::Pending;})==State::Pending);
    std::promise<void> started,release,readerStarted;
    auto released=release.get_future().share();
    auto installer=std::async(std::launch::async,[&]{return gate.run(pid,[&]{
        calls++;started.set_value();released.wait();return State::Ready;
    });});
    started.get_future().wait();assert(gate.state(pid)==State::Running);

    // A fork while the parent installer is active must not inherit its wait.
    pid_t child=fork();assert(child>=0);
    if(child==0){
        alarm(3);
        bool ran=false;
        assert(gate.run(getpid(),[&]{ran=true;return State::Ready;})==State::Ready&&ran);
        _exit(0);
    }
    int childStatus=0;assert(waitpid(child,&childStatus,0)==child);
    assert(WIFEXITED(childStatus)&&WEXITSTATUS(childStatus)==0);

    auto reader=std::async(std::launch::async,[&]{
        readerStarted.set_value();return gate.run(pid,[]{assert(false);return State::Failed;});
    });
    readerStarted.get_future().wait();
    assert(reader.wait_for(std::chrono::milliseconds(30))==std::future_status::timeout);
    release.set_value();assert(installer.get()==State::Ready&&reader.get()==State::Ready);
    assert(calls==2);
    assert(gate.run(pid,[]{assert(false);return State::Failed;})==State::Ready);
    MiappStartup failed;
    assert(failed.run(pid,[]{return State::Failed;})==State::Failed);
    assert(failed.run(pid,[]{assert(false);return State::Ready;})==State::Failed);
    puts("PASS: pending retry, synchronous startup barrier, concurrent caller, fork recovery and terminal failure");
}
