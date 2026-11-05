// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
#include <jni.h>
#include <array>

// Erase JNI copies with the linked crypto target. All PAKE operations remain
// in the isolated, prefixed BoringSSL core target.
#include <openssl/crypto.h>
#include "remote_pairing/pairing.h"

namespace {
template<size_t N> struct CopiedBytes {
    std::array<uint8_t, N> bytes{};
    ~CopiedBytes() { OPENSSL_cleanse(bytes.data(), bytes.size()); }
};

template<size_t N>
bool copy(JNIEnv *env, jbyteArray source, CopiedBytes<N> &out)
{
    if (!source || env->GetArrayLength(source) != static_cast<jsize>(N)) return false;
    env->GetByteArrayRegion(source, 0, static_cast<jsize>(N), reinterpret_cast<jbyte *>(out.bytes.data()));
    return !env->ExceptionCheck();
}

jbyteArray result(JNIEnv *env, jlong handle, int (*read)(nyan_pairing *, uint8_t *, size_t))
{
    CopiedBytes<32> output;
    if (!handle || !read(reinterpret_cast<nyan_pairing *>(handle), output.bytes.data(), output.bytes.size()))
        return nullptr;
    jbyteArray value = env->NewByteArray(static_cast<jsize>(output.bytes.size()));
    if (!value) return nullptr;
    env->SetByteArrayRegion(value, 0, static_cast<jsize>(output.bytes.size()),
                            reinterpret_cast<const jbyte *>(output.bytes.data()));
    return env->ExceptionCheck() ? nullptr : value;
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_jp_nyan_spatialwall_probe_NativePairing_createNative(
    JNIEnv *env, jclass, jint role, jbyteArray context, jbyteArray code)
{
    CopiedBytes<NYAN_PAIRING_CONTEXT_BYTES> context_copy;
    CopiedBytes<NYAN_PAIRING_CODE_BYTES> code_copy;
    if (!copy(env, context, context_copy) || !copy(env, code, code_copy)) return 0;
    return reinterpret_cast<jlong>(nyan_pairing_new(role, context_copy.bytes.data(), context_copy.bytes.size(),
                                                   code_copy.bytes.data(), code_copy.bytes.size()));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_jp_nyan_spatialwall_probe_NativePairing_messageNative(JNIEnv *env, jclass, jlong handle)
{
    return result(env, handle, nyan_pairing_message);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_jp_nyan_spatialwall_probe_NativePairing_processPeerNative(
    JNIEnv *env, jclass, jlong handle, jbyteArray message)
{
    CopiedBytes<NYAN_PAIRING_MESSAGE_BYTES> peer;
    return handle && copy(env, message, peer) &&
        nyan_pairing_process_peer(reinterpret_cast<nyan_pairing *>(handle), peer.bytes.data(), peer.bytes.size());
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_jp_nyan_spatialwall_probe_NativePairing_confirmationNative(JNIEnv *env, jclass, jlong handle)
{
    return result(env, handle, nyan_pairing_confirmation);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_jp_nyan_spatialwall_probe_NativePairing_verifyConfirmationNative(
    JNIEnv *env, jclass, jlong handle, jbyteArray confirmation)
{
    CopiedBytes<NYAN_PAIRING_CONFIRMATION_BYTES> peer;
    return handle && copy(env, confirmation, peer) &&
        nyan_pairing_verify_confirmation(reinterpret_cast<nyan_pairing *>(handle), peer.bytes.data(), peer.bytes.size());
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_jp_nyan_spatialwall_probe_NativePairing_enrollmentProofNative(JNIEnv *env, jclass, jlong handle)
{
    return result(env, handle, nyan_pairing_enrollment_proof);
}

extern "C" JNIEXPORT void JNICALL
Java_jp_nyan_spatialwall_probe_NativePairing_destroyNative(JNIEnv *, jclass, jlong handle)
{
    nyan_pairing_free(reinterpret_cast<nyan_pairing *>(handle));
}
