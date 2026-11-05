// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
#include "snapshot.h"
#include <algorithm>
#include <cstring>
#include <openssl/crypto.h>

namespace nyan {
namespace {
constexpr std::array<uint8_t, 8> magic{'S', 'W', 'R', 'E', 'M', 'O', 'T', 2};
struct encoded {
    std::array<uint8_t, remote_snapshot::max_bytes> bytes{};
    ~encoded() { OPENSSL_cleanse(bytes.data(), bytes.size()); }
};
}

remote_snapshot::~remote_snapshot() { clear(); }
void remote_snapshot::erase_secret(uint8_t *bytes, size_t size)
{
    if (bytes && size) OPENSSL_cleanse(bytes, size);
}
void remote_snapshot::clear()
{
    OPENSSL_cleanse(key_.data(), key_.size());
    key_size_ = 0;
}
bool remote_snapshot::set_key(const uint8_t *pkcs8, size_t size)
{
    if (!pkcs8 || !size || size > key_.size()) { clear(); return false; }
    // memmove also supports a caller retaining this object's key view.
    std::memmove(key_.data(), pkcs8, size);
    OPENSSL_cleanse(key_.data() + size, key_.size() - size);
    key_size_ = size;
    return true;
}

bool remote_snapshot::restore(const uint8_t *bytes, size_t size, remote_devices::records &devices)
{
    clear();
    devices.clear();
    if (!bytes || size < 11 || size > max_bytes || !std::equal(magic.begin(), magic.end(), bytes)) return false;
    const size_t key_size = (size_t(bytes[8]) << 8) | bytes[9];
    const size_t count = bytes[10];
    if (!key_size || key_size > key_.size() || count > remote_devices::capacity || size < 11 + key_size)
        return false;
    size_t offset = 11 + key_size;
    remote_devices::records parsed;
    for (size_t i = 0; i < count; ++i) {
        if (size - offset < 50) return false;
        remote_devices::record item{};
        std::copy_n(bytes + offset, 16, item.device.begin());
        std::copy_n(bytes + offset + 16, 32, item.verifier.begin());
        item.grants = bytes[offset + 48];
        const size_t label_size = bytes[offset + 49];
        offset += 50;
        if (size - offset < label_size) return false;
        item.label.assign(reinterpret_cast<const char *>(bytes + offset), label_size);
        offset += label_size;
        parsed.push_back(std::move(item));
    }
    if (offset != size) return false;
    remote_devices validator;
    if (!validator.load(parsed)) return false;
    if (!set_key(bytes + 11, key_size)) return false;
    devices.swap(parsed);
    return true;
}

bool remote_snapshot::save(const remote_devices::records &devices, const writer &write) const
{
    if (!key_size_ || !write) return false;
    remote_devices validator;
    if (!validator.load(devices)) return false;
    encoded output;
    std::copy(magic.begin(), magic.end(), output.bytes.begin());
    output.bytes[8] = static_cast<uint8_t>(key_size_ >> 8);
    output.bytes[9] = static_cast<uint8_t>(key_size_);
    output.bytes[10] = static_cast<uint8_t>(devices.size());
    std::copy_n(key_.begin(), key_size_, output.bytes.begin() + 11);
    size_t offset = 11 + key_size_;
    for (const auto &item : devices) {
        std::copy(item.device.begin(), item.device.end(), output.bytes.begin() + offset);
        std::copy(item.verifier.begin(), item.verifier.end(), output.bytes.begin() + offset + 16);
        output.bytes[offset + 48] = item.grants;
        output.bytes[offset + 49] = static_cast<uint8_t>(item.label.size());
        offset += 50;
        std::copy(item.label.begin(), item.label.end(), output.bytes.begin() + offset);
        offset += item.label.size();
    }
    try { return write(output.bytes.data(), offset); } catch (...) { return false; }
}
} // namespace nyan
