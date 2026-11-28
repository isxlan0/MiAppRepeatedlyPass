#pragma once
#include <cstdint>
#include <cstring>
#include <initializer_list>
#include "native_patterns.h"

namespace miapp {
// Each reviewed prefix saves registers/adjusts SP only (plus optional PACIASP).
// No copied instruction uses PC-relative addressing. Keep recognition and the
// actual direct-call trampoline on the same four complete ABI variants.
inline bool rustEntry(const void* entry){
    for(auto p:{RUST_EARLY,RUST_CURRENT,RUST_EARLY_STACK,RUST_CURRENT_STACK})
        if(std::memcmp(entry,p,16)==0)return true;
    return false;
}
}
