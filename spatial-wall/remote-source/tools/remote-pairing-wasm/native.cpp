// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
// Local pipe fixture only. Messages, MACs, and proofs must never be logged.
#include "remote_pairing/pairing.h"
#include <array>
#include <cstdio>
#include <cstring>
#include <memory>

namespace {
int nibble(char c) { return c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : -1; }
template<size_t N> bool read_hex(std::array<uint8_t, N> &bytes)
{
    std::array<char, 2 * N + 2> line{};
    if (!std::fgets(line.data(), static_cast<int>(line.size()), stdin) ||
        std::strlen(line.data()) != 2 * N + 1 || line[2 * N] != '\n') return false;
    for (size_t i = 0; i < N; ++i) {
        const int high = nibble(line[2 * i]), low = nibble(line[2 * i + 1]);
        if (high < 0 || low < 0) return false;
        bytes[i] = static_cast<uint8_t>(high * 16 + low);
    }
    return true;
}
template<size_t N> void write_hex(const std::array<uint8_t, N> &bytes)
{
    for (uint8_t byte : bytes) std::printf("%02x", byte);
    std::putchar('\n');
    std::fflush(stdout);
}
}

int main(int argc, char **argv)
{
    if (argc != 2 || (std::strcmp(argv[1], "0") && std::strcmp(argv[1], "1"))) return 2;
    const int role = argv[1][0] - '0';
    std::array<uint8_t, NYAN_PAIRING_CONTEXT_BYTES> context{};
    std::array<uint8_t, NYAN_PAIRING_CODE_BYTES> code{};
    if (!read_hex(context) || !read_hex(code)) return 2;
    std::unique_ptr<nyan_pairing, decltype(&nyan_pairing_free)> session(
        nyan_pairing_new(role, context.data(), context.size(), code.data(), code.size()), nyan_pairing_free);
    code.fill(0);
    if (!session) return 2;
    std::array<uint8_t, 32> bytes{};
    if (!nyan_pairing_message(session.get(), bytes.data(), bytes.size())) return 2;
    write_hex(bytes);
    if (!read_hex(bytes) || !nyan_pairing_process_peer(session.get(), bytes.data(), bytes.size())) return 2;
    if (role == NYAN_PAIRING_SERVER) {
        if (!nyan_pairing_confirmation(session.get(), bytes.data(), bytes.size())) return 2;
        write_hex(bytes);
    }
    if (!read_hex(bytes)) return 2;
    if (!nyan_pairing_verify_confirmation(session.get(), bytes.data(), bytes.size())) {
        std::puts("REJECT");
        std::fflush(stdout);
        return 0;
    }
    std::puts("OK");
    std::fflush(stdout);
    if (role == NYAN_PAIRING_CLIENT) {
        if (!nyan_pairing_confirmation(session.get(), bytes.data(), bytes.size())) return 2;
        write_hex(bytes);
    }
    if (!nyan_pairing_enrollment_proof(session.get(), bytes.data(), bytes.size())) return 2;
    write_hex(bytes);
    std::array<uint8_t, 32> peer_proof{};
    if (!read_hex(peer_proof)) return 2;
    // Test-only equality oracle, not the product's atomic enrollment gate.
    std::puts(bytes == peer_proof ? "PROOF_OK" : "PROOF_REJECT");
    std::fflush(stdout);
    bytes.fill(0);
    peer_proof.fill(0);
    return 0;
}
