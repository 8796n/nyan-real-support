// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
#pragma once

#include <cstddef>
#include <cstdint>
#include <string_view>

inline bool ws_valid_utf8(std::string_view text)
{
	for (std::size_t at = 0; at < text.size();) {
		const auto first = static_cast<unsigned char>(text[at++]);
		if (first <= 0x7f)
			continue;
		std::uint32_t codepoint;
		std::uint32_t minimum;
		std::size_t remaining;
		if (first >= 0xc2 && first <= 0xdf) {
			codepoint = first & 0x1f;
			minimum = 0x80;
			remaining = 1;
		} else if (first >= 0xe0 && first <= 0xef) {
			codepoint = first & 0x0f;
			minimum = 0x800;
			remaining = 2;
		} else if (first >= 0xf0 && first <= 0xf4) {
			codepoint = first & 0x07;
			minimum = 0x10000;
			remaining = 3;
		} else {
			return false;
		}
		if (remaining > text.size() - at)
			return false;
		while (remaining > 0) {
			--remaining;
			const auto next = static_cast<unsigned char>(text[at++]);
			if ((next & 0xc0) != 0x80)
				return false;
			codepoint = (codepoint << 6) | (next & 0x3f);
		}
		if (codepoint < minimum || codepoint > 0x10ffff ||
		    (codepoint >= 0xd800 && codepoint <= 0xdfff))
			return false;
	}
	return true;
}

// Bound nesting before strict JSON parsing; this is not a JSON syntax check.
inline bool ws_json_depth_allowed(std::string_view text, std::size_t max_depth = 64)
{
	std::size_t depth = 0;
	bool in_string = false;
	bool escaped = false;
	for (char c : text) {
		if (in_string) {
			if (escaped)
				escaped = false;
			else if (c == '\\')
				escaped = true;
			else if (c == '"')
				in_string = false;
		} else if (c == '"') {
			in_string = true;
		} else if (c == '[' || c == '{') {
			if (depth == max_depth)
				return false;
			++depth;
		} else if ((c == ']' || c == '}') && depth > 0) {
			--depth;
		}
	}
	return true;
}
