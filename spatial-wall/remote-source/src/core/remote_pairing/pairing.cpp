// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
#include "pairing.h"

#include <openssl/curve25519.h>
#include <openssl/digest.h>
#include <openssl/hkdf.h>
#include <openssl/hmac.h>
#include <openssl/mem.h>
#include <openssl/sha2.h>

#include <algorithm>
#include <array>
#include <new>

namespace {
static_assert(SPAKE2_MAX_MSG_SIZE == 32 && SPAKE2_MAX_KEY_SIZE == 64 && SHA256_DIGEST_LENGTH == 32);
constexpr char identity_domain[] = "nyan-real/pair-v2/boringssl-spake2-draft02/identity";
constexpr char transcript_domain[] = "nyan-real/pair-v2/boringssl-spake2-draft02/transcript";
constexpr char server_label[] = "nyan-real/pair-v2/boringssl-spake2-draft02/server-confirm";
constexpr char client_label[] = "nyan-real/pair-v2/boringssl-spake2-draft02/client-confirm";
constexpr char enrollment_label[] = "nyan-real/pair-v2/boringssl-spake2-draft02/enrollment";
constexpr unsigned allowed_grants = NYAN_PAIRING_GRANT_REMOTE | NYAN_PAIRING_GRANT_GLASSES |
                                    NYAN_PAIRING_GRANT_CONTROLLER;
using Bytes32 = std::array<uint8_t, 32>;
using Context = std::array<uint8_t, NYAN_PAIRING_CONTEXT_BYTES>;
enum class State { generated, processed, confirmed, failed };

template<size_t N> struct Secret {
    std::array<uint8_t, N> bytes{};
    ~Secret() { OPENSSL_cleanse(bytes.data(), bytes.size()); }
};

auto name(int role, const Context &context)
{
    std::array<uint8_t, sizeof(identity_domain) - 1 + 1 + NYAN_PAIRING_CONTEXT_BYTES> bytes{};
    auto next = std::copy_n(identity_domain, sizeof(identity_domain) - 1, bytes.begin());
    *next++ = static_cast<uint8_t>(role);
    std::copy(context.begin(), context.end(), next);
    return bytes;
}

Bytes32 transcript(const Context &context, const Bytes32 &client, const Bytes32 &server)
{
    std::array<uint8_t, sizeof(transcript_domain) - 1 + NYAN_PAIRING_CONTEXT_BYTES + 64> bytes{};
    auto next = std::copy_n(transcript_domain, sizeof(transcript_domain) - 1, bytes.begin());
    next = std::copy(context.begin(), context.end(), next);
    next = std::copy(client.begin(), client.end(), next);
    std::copy(server.begin(), server.end(), next);
    Bytes32 hash{};
    SHA256(bytes.data(), bytes.size(), hash.data());
    return hash;
}

template<size_t N>
bool confirmation(const char (&label)[N], const Secret<64> &shared, const Context &context,
                  const Bytes32 &hash, Bytes32 &out)
{
    std::array<uint8_t, N - 1 + 32> info{};
    auto next = std::copy_n(label, N - 1, info.begin());
    std::copy(hash.begin(), hash.end(), next);
    Secret<32> key;
    if (!HKDF(key.bytes.data(), key.bytes.size(), EVP_sha256(), shared.bytes.data(),
              shared.bytes.size(), context.data() + 1, 32, info.data(), info.size()))
        return false;
    unsigned length = 0;
    return HMAC(EVP_sha256(), key.bytes.data(), key.bytes.size(), hash.data(), hash.size(),
                out.data(), &length) && length == out.size();
}
} // namespace

struct nyan_pairing {
    SPAKE2_CTX *spake = nullptr;
    State state = State::generated;
    int role = NYAN_PAIRING_CLIENT;
    Context context{};
    Bytes32 message{}, server_confirmation{}, client_confirmation{}, enrollment{};
};

namespace {
int fail(nyan_pairing *session)
{
    if (session) {
        SPAKE2_CTX_free(session->spake);
        session->spake = nullptr;
        OPENSSL_cleanse(session->server_confirmation.data(), session->server_confirmation.size());
        OPENSSL_cleanse(session->client_confirmation.data(), session->client_confirmation.size());
        OPENSSL_cleanse(session->enrollment.data(), session->enrollment.size());
        session->state = State::failed;
    }
    return 0;
}

bool output_valid(nyan_pairing *session, uint8_t *out, size_t size)
{
    return session && session->state != State::failed && out && size == 32;
}
} // namespace

nyan_pairing *nyan_pairing_new(int role, const uint8_t *context, size_t context_len,
                              const uint8_t *code, size_t code_len)
{
    if ((role != NYAN_PAIRING_CLIENT && role != NYAN_PAIRING_SERVER) || !context ||
        context_len != NYAN_PAIRING_CONTEXT_BYTES || !code || code_len != NYAN_PAIRING_CODE_BYTES ||
        context[0] != NYAN_PAIRING_VERSION || context[97] == 0 || (context[97] & ~allowed_grants))
        return nullptr;
    for (size_t i = 0; i < code_len; ++i)
        if (code[i] < '0' || code[i] > '9') return nullptr;
    auto *session = new (std::nothrow) nyan_pairing;
    if (!session) return nullptr;
    session->role = role;
    std::copy_n(context, context_len, session->context.begin());
    const auto mine = name(role, session->context);
    const auto theirs = name(1 - role, session->context);
    session->spake = SPAKE2_CTX_new(role == NYAN_PAIRING_CLIENT ? spake2_role_alice : spake2_role_bob,
                                   mine.data(), mine.size(), theirs.data(), theirs.size());
    size_t length = 0;
    if (!session->spake || !SPAKE2_generate_msg(session->spake, session->message.data(), &length,
                                              session->message.size(), code, code_len) ||
        length != session->message.size()) {
        nyan_pairing_free(session);
        return nullptr;
    }
    return session;
}

int nyan_pairing_message(nyan_pairing *session, uint8_t *out, size_t out_len)
{
    if (!output_valid(session, out, out_len)) return fail(session);
    std::copy(session->message.begin(), session->message.end(), out);
    return 1;
}

int nyan_pairing_process_peer(nyan_pairing *session, const uint8_t *peer, size_t peer_len)
{
    if (!session || session->state != State::generated || !peer || peer_len != 32)
        return fail(session);
    Secret<64> shared;
    size_t length = 0;
    if (!SPAKE2_process_msg(session->spake, shared.bytes.data(), &length, shared.bytes.size(),
                           peer, peer_len) || length != shared.bytes.size())
        return fail(session);
    SPAKE2_CTX_free(session->spake);
    session->spake = nullptr;
    Bytes32 peer_message{};
    std::copy_n(peer, peer_len, peer_message.begin());
    const auto hash = session->role == NYAN_PAIRING_CLIENT
        ? transcript(session->context, session->message, peer_message)
        : transcript(session->context, peer_message, session->message);
    if (!confirmation(server_label, shared, session->context, hash, session->server_confirmation) ||
        !confirmation(client_label, shared, session->context, hash, session->client_confirmation) ||
        !confirmation(enrollment_label, shared, session->context, hash, session->enrollment))
        return fail(session);
    session->state = State::processed;
    return 1;
}

int nyan_pairing_confirmation(nyan_pairing *session, uint8_t *out, size_t out_len)
{
    if (!output_valid(session, out, out_len) ||
        (session->state != State::confirmed &&
         !(session->role == NYAN_PAIRING_SERVER && session->state == State::processed)))
        return fail(session);
    const auto &mac = session->role == NYAN_PAIRING_SERVER
        ? session->server_confirmation : session->client_confirmation;
    std::copy(mac.begin(), mac.end(), out);
    return 1;
}

int nyan_pairing_verify_confirmation(nyan_pairing *session, const uint8_t *peer, size_t peer_len)
{
    if (!session || session->state != State::processed || !peer || peer_len != 32)
        return fail(session);
    const auto &expected = session->role == NYAN_PAIRING_CLIENT
        ? session->server_confirmation : session->client_confirmation;
    if (CRYPTO_memcmp(expected.data(), peer, expected.size()) != 0) return fail(session);
    session->state = State::confirmed;
    return 1;
}

int nyan_pairing_enrollment_proof(nyan_pairing *session, uint8_t *out, size_t out_len)
{
    if (!output_valid(session, out, out_len) || session->state != State::confirmed)
        return fail(session);
    std::copy(session->enrollment.begin(), session->enrollment.end(), out);
    return 1;
}

void nyan_pairing_free(nyan_pairing *session)
{
    fail(session);
    delete session;
}
