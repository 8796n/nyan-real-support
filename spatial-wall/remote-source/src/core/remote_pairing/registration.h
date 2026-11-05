// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
#pragma once
#include "snapshot.h"
#include "window.h"

namespace nyan {
// Canonical unpadded base64url for registration headers and the local QR bootstrap.
std::string remote_base64url(const uint8_t *bytes, size_t size);
// The owner holds one mutex across every call and the protected atomic writer,
// supplies monotonic milliseconds, and validates routes/methods/Origin/CORS.
// This service owns no listener, clock, OS resource or legacy token.
class remote_registration {
public:
    using bytes32 = pairing_window::bytes32;
    using code_bytes = pairing_window::code_bytes;
    struct response {
        enum class format { none, binary } content = format::none;
        unsigned status = 500;
        std::vector<uint8_t> body;
        // Persistence failure disables the registry. Stop listening and close
        // existing device sessions; an explicit validated reload is required.
        bool close_listener = false;
        response() = default;
        response(response &&) = default;
        response(const response &) = delete;
        response &operator=(const response &) = delete;
        ~response();
    };

    remote_registration(remote_devices &devices, const remote_snapshot &snapshot,
                        remote_snapshot::writer write);
    ~remote_registration();
    remote_registration(const remote_registration &) = delete;
    remote_registration &operator=(const remote_registration &) = delete;

    // P-256 SPKI DER is public. Changing identity or leaf cancels all pending
    // registration; calling with the same values retains the current windows.
    bool configure(const uint8_t *spki, size_t size, const bytes32 &leaf_hash);
    void cancel();
    bool start_code(uint8_t grants, uint64_t now_ms);
    bool display_code(uint64_t now_ms, code_bytes &out, unsigned &seconds_left);
    // Reuses the same live invitation. Expiry, consumption or a grant change
    // creates a fresh 60-second invitation, without disconnecting saved devices.
    bool invitation(uint8_t grants, uint64_t now_ms, bytes32 &out, unsigned &seconds_left);
    // Authenticated browser handoff: one fresh, single-use 60-second invitation
    // per parent. It cannot outlive the parent's registration or widen its grants.
    response app_invitation(std::string_view authorization, uint64_t now_ms);

    response pair_get(uint64_t now_ms);
    response pair_post(const uint8_t *body, size_t size, uint64_t now_ms);
    // Non-consuming admission check: 200 means enroll may proceed now. It never
    // saves or issues credentials; Open must recheck with enroll. Expiry still applies.
    unsigned enrollment_status(bool encrypted, std::string_view authorization, size_t body_size, uint64_t now_ms);
    // Only call for HTTPS POST /enroll-v2 or that WT logical route. The caller
    // passes the actual connection's encryption state, never a request field.
    // Authorization and success bodies contain secrets: never log either.
    response enroll(bool encrypted, std::string_view authorization, size_t body_size, uint64_t now_ms);

private:
    bool observe(uint64_t now_ms);
    void clear_invite();
    bool invite_current(uint64_t now_ms);
    struct app_invite {
        remote_devices::id parent{};
        bytes32 verifier{}, token{};
        uint64_t opened = 0;
        uint8_t grants = 0;
    };
    app_invite *find_app_invite(const uint8_t *token, uint64_t now_ms);
    response failure(unsigned status) const;
    remote_devices &devices_;
    const remote_snapshot &snapshot_;
    remote_snapshot::writer write_;
    pairing_window window_;
    std::array<app_invite, remote_devices::capacity> app_invites_{};
    std::vector<uint8_t> spki_;
    bytes32 spki_hash_{}, leaf_hash_{}, invite_{};
    uint64_t invite_opened_ = 0, last_time_ = 0;
    uint8_t invite_grants_ = 0;
    bool have_time_ = false, configured_ = false, have_invite_ = false;
};
} // namespace nyan
