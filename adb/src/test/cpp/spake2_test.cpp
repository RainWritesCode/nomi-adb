#include <cstdio>
#include <cstring>

#include "adb/spake2.hpp"

namespace {

const uint8_t kClient[] = "adb pair client";
const uint8_t kServer[] = "adb pair server";

adb::spake2::Context alice() {
    return adb::spake2::create(adb::spake2::Role::Alice, kClient, sizeof(kClient), kServer, sizeof(kServer));
}

adb::spake2::Context bob() {
    return adb::spake2::create(adb::spake2::Role::Bob, kServer, sizeof(kServer), kClient, sizeof(kClient));
}

bool sha512Matches() {
    const char* text = "abc";
    const uint8_t expected[64] = {
        0xdd, 0xaf, 0x35, 0xa1, 0x93, 0x61, 0x7a, 0xba, 0xcc, 0x41, 0x73, 0x49, 0xae, 0x20, 0x41, 0x31,
        0x12, 0xe6, 0xfa, 0x4e, 0x89, 0xa9, 0x7e, 0xa2, 0x0a, 0x9e, 0xee, 0xe6, 0x4b, 0x55, 0xd3, 0x9a,
        0x21, 0x92, 0x99, 0x2a, 0x27, 0x4f, 0xc1, 0xa8, 0x36, 0xba, 0x3c, 0x23, 0xa3, 0xfe, 0xeb, 0xbd,
        0x45, 0x4d, 0x44, 0x23, 0x64, 0x3c, 0xe8, 0x0e, 0x2a, 0x9a, 0xc9, 0x4f, 0xa5, 0x4c, 0xa4, 0x9f};
    uint8_t out[64];
    adb::spake2::sha512(reinterpret_cast<const uint8_t*>(text), 3, out);
    return std::memcmp(out, expected, 64) == 0;
}

}

int main() {
    uint8_t ra[64], rb[64];
    for (int i = 0; i < 64; i++) {
        ra[i] = uint8_t(i * 7 + 3);
        rb[i] = uint8_t(i * 13 + 5);
    }
    const uint8_t right[] = "123456";
    const uint8_t wrong[] = "123457";
    auto a = alice();
    auto b = bob();
    auto e = bob();
    bool generated = adb::spake2::generate(a, right, 6, ra) && adb::spake2::generate(b, right, 6, rb)
                     && adb::spake2::generate(e, wrong, 6, rb);
    uint8_t ka[64], kb[64], ka2[64], ke[64];
    bool processed = adb::spake2::process(a, b.message, ka) && adb::spake2::process(b, a.message, kb)
                     && adb::spake2::process(a, e.message, ka2) && adb::spake2::process(e, a.message, ke);
    bool agree = generated && processed && std::memcmp(ka, kb, 64) == 0;
    bool differ = std::memcmp(ka2, ke, 64) != 0;
    bool hash = sha512Matches();
    std::printf("sha512 %s\nsame code agrees %s\nwrong code differs %s\n", hash ? "ok" : "FAIL", agree ? "ok" : "FAIL",
                differ ? "ok" : "FAIL");
    return hash && agree && differ ? 0 : 1;
}
