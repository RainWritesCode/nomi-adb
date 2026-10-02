#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace adb::spake2 {

enum class Role { Alice, Bob };

struct Context {
    Role role = Role::Alice;
    uint8_t privateKey[32] = {};
    uint8_t message[32] = {};
    uint8_t passwordScalar[32] = {};
    uint8_t passwordHash[64] = {};
    std::vector<uint8_t> myName;
    std::vector<uint8_t> theirName;
};

Context create(Role role, const uint8_t* myName, size_t myNameLength, const uint8_t* theirName, size_t theirNameLength);
bool generate(Context& context, const uint8_t* password, size_t passwordLength, const uint8_t random[64]);
bool process(const Context& context, const uint8_t theirMessage[32], uint8_t key[64]);

void sha512(const uint8_t* data, size_t length, uint8_t out[64]);

class Sha512 {
public:
    Sha512();
    void update(const uint8_t* data, size_t length);
    void finish(uint8_t out[64]);

private:
    void block(const uint8_t* data);
    uint64_t state_[8];
    uint8_t buffer_[128];
    size_t buffered_ = 0;
    uint64_t total_ = 0;
};

}
