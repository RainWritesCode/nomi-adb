#include <jni.h>

#include <cstdint>
#include <cstring>
#include <vector>

#include "adb/spake2.hpp"

namespace {

const uint8_t kClientName[] = "adb pair client";
const uint8_t kServerName[] = "adb pair server";

std::vector<uint8_t> fromJava(JNIEnv* env, jbyteArray array) {
    std::vector<uint8_t> out(size_t(env->GetArrayLength(array)));
    env->GetByteArrayRegion(array, 0, jsize(out.size()), reinterpret_cast<jbyte*>(out.data()));
    return out;
}

jbyteArray toJava(JNIEnv* env, const uint8_t* data, size_t length) {
    jbyteArray array = env->NewByteArray(jsize(length));
    if (array) env->SetByteArrayRegion(array, 0, jsize(length), reinterpret_cast<const jbyte*>(data));
    return array;
}

adb::spake2::Context* context(jlong handle) {
    return reinterpret_cast<adb::spake2::Context*>(static_cast<intptr_t>(handle));
}

}

extern "C" {

JNIEXPORT jlong JNICALL Java_gg_nomi_adb_Spake2_start(JNIEnv* env, jclass, jbyteArray password, jbyteArray random) {
    if (!password || !random) return 0;
    std::vector<uint8_t> pw = fromJava(env, password);
    std::vector<uint8_t> rnd = fromJava(env, random);
    if (rnd.size() != 64) return 0;
    auto* created = new adb::spake2::Context(adb::spake2::create(adb::spake2::Role::Alice, kClientName, sizeof(kClientName),
                                                                 kServerName, sizeof(kServerName)));
    bool ok = adb::spake2::generate(*created, pw.data(), pw.size(), rnd.data());
    std::memset(pw.data(), 0, pw.size());
    std::memset(rnd.data(), 0, rnd.size());
    if (!ok) {
        delete created;
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(created));
}

JNIEXPORT jbyteArray JNICALL Java_gg_nomi_adb_Spake2_message(JNIEnv* env, jclass, jlong handle) {
    if (!handle) return nullptr;
    return toJava(env, context(handle)->message, 32);
}

JNIEXPORT jbyteArray JNICALL Java_gg_nomi_adb_Spake2_finish(JNIEnv* env, jclass, jlong handle, jbyteArray theirs) {
    adb::spake2::Context* current = context(handle);
    if (!current) return nullptr;
    std::vector<uint8_t> message = theirs ? fromJava(env, theirs) : std::vector<uint8_t>();
    uint8_t key[64];
    bool ok = message.size() == 32 && adb::spake2::process(*current, message.data(), key);
    std::memset(current->privateKey, 0, sizeof(current->privateKey));
    std::memset(current->passwordScalar, 0, sizeof(current->passwordScalar));
    std::memset(current->passwordHash, 0, sizeof(current->passwordHash));
    delete current;
    if (!ok) return nullptr;
    jbyteArray out = toJava(env, key, 64);
    std::memset(key, 0, sizeof(key));
    return out;
}

}
