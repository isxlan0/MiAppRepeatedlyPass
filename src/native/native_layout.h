#pragma once
#include <cstdint>

// Filled in the native launcher after inspecting its actual mapped libraries.
// Addresses are ELF virtual addresses relative to the library's load bias.
struct MiappLibraryLayout {
    uint64_t zipOffset, codeAddress, codeSize, codeHash, codeFileOffset;
};
struct MiappLayout {
    char magic[16];
    uint64_t version, ready;
    MiappLibraryLayout dart, native;
    uint64_t badges[5];
    uint64_t labelEntry, labelTarget;
    uint64_t titleCall, getText, encode, getUser, getId;
    uint32_t titleExpected[4], encodeExpected[4];
};
static_assert(sizeof(MiappLayout)==240,"NativePrepare layout must match");
extern MiappLayout miapp_layout;
int miapp_scan_runtime(MiappLayout&,uintptr_t& dartBase,uintptr_t& nativeBase);
const char* miapp_scan_error();
bool miapp_validate_runtime();
