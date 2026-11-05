// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
#pragma once
#include "devices.h"

namespace nyan {
// One snapshot keeps the identity and its device registry in the same OS transaction.
class remote_snapshot {
public:
    static constexpr size_t max_key_bytes = 256;
    static constexpr size_t max_bytes = 11 + max_key_bytes + remote_devices::capacity * (50 + 96);
    using writer = std::function<bool(const uint8_t *, size_t)>;
    remote_snapshot() = default;
    ~remote_snapshot();
    remote_snapshot(const remote_snapshot &) = delete;
    remote_snapshot &operator=(const remote_snapshot &) = delete;

    // The identity backend validates PKCS#8 before listening; this codec checks structure only.
    bool set_key(const uint8_t *pkcs8, size_t size);
    const uint8_t *key() const { return key_.data(); }
    size_t key_size() const { return key_size_; }
    bool restore(const uint8_t *bytes, size_t size, remote_devices::records &devices);
    // Only pass the platform's protected store, never an ordinary file writer.
    bool save(const remote_devices::records &devices, const writer &write) const;
    // Erase temporary load/export buffers using the same vetted crypto primitive.
    static void erase_secret(uint8_t *bytes, size_t size);

private:
    void clear();
    std::array<uint8_t, max_key_bytes> key_{};
    size_t key_size_ = 0;
};
} // namespace nyan
