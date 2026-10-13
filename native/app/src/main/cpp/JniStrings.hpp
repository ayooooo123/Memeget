#pragma once

#include <jni.h>
#include <cstdint>
#include <stdexcept>
#include <string>
#include <string_view>
#include <utility>

// JNI's UTF helpers use modified UTF-8. Tokenizers and filesystem paths use UTF-8.
class JStringUtf {
public:
    JStringUtf(JNIEnv* env, jstring input) {
        if (!input) return;
        const auto length = env->GetStringLength(input);
        value_.reserve(static_cast<size_t>(length) * 3);
        const jchar* chars = env->GetStringCritical(input, nullptr);
        if (!chars) throw std::runtime_error("Cannot read Java text");
        // Capacity covers every UTF-16 code unit; no allocation or JNI call while pinned.
        for (jsize index = 0; index < length; ++index) {
            uint32_t point = chars[index];
            if (point >= 0xd800 && point <= 0xdbff && index + 1 < length &&
                chars[index + 1] >= 0xdc00 && chars[index + 1] <= 0xdfff) {
                point = 0x10000 + ((point - 0xd800) << 10) + (chars[++index] - 0xdc00);
            } else if (point >= 0xd800 && point <= 0xdfff) {
                point = '?'; // Matches String.getBytes(UTF_8) for an unpaired surrogate.
            }
            if (point < 0x80) {
                value_.push_back(static_cast<char>(point));
            } else if (point < 0x800) {
                value_.push_back(static_cast<char>(0xc0 | (point >> 6)));
                value_.push_back(static_cast<char>(0x80 | (point & 0x3f)));
            } else if (point < 0x10000) {
                value_.push_back(static_cast<char>(0xe0 | (point >> 12)));
                value_.push_back(static_cast<char>(0x80 | ((point >> 6) & 0x3f)));
                value_.push_back(static_cast<char>(0x80 | (point & 0x3f)));
            } else {
                value_.push_back(static_cast<char>(0xf0 | (point >> 18)));
                value_.push_back(static_cast<char>(0x80 | ((point >> 12) & 0x3f)));
                value_.push_back(static_cast<char>(0x80 | ((point >> 6) & 0x3f)));
                value_.push_back(static_cast<char>(0x80 | (point & 0x3f)));
            }
        }
        env->ReleaseStringCritical(input, chars);
    }

    const std::string& str() const & noexcept { return value_; }
    std::string str() && noexcept { return std::move(value_); }

private:
    std::string value_;
};

inline jstring newJavaString(JNIEnv* env, std::string_view bytes) {
    std::u16string text;
    text.reserve(bytes.size());
    size_t index = 0;
    while (index < bytes.size()) {
        uint32_t point = static_cast<unsigned char>(bytes[index++]);
        int continuation = 0;
        uint32_t minimum = 0;
        if (point < 0x80) {
            // ASCII, including NUL.
        } else if (point >= 0xc2 && point <= 0xdf) {
            point &= 0x1f; continuation = 1; minimum = 0x80;
        } else if (point >= 0xe0 && point <= 0xef) {
            point &= 0x0f; continuation = 2; minimum = 0x800;
        } else if (point >= 0xf0 && point <= 0xf4) {
            point &= 0x07; continuation = 3; minimum = 0x10000;
        } else {
            throw std::runtime_error("Native model returned invalid UTF-8");
        }
        if (bytes.size() - index < static_cast<size_t>(continuation))
            throw std::runtime_error("Native model returned incomplete UTF-8");
        for (int count = 0; count < continuation; ++count) {
            const auto next = static_cast<unsigned char>(bytes[index++]);
            if ((next & 0xc0) != 0x80) throw std::runtime_error("Native model returned invalid UTF-8");
            point = (point << 6) | (next & 0x3f);
        }
        if (point < minimum || point > 0x10ffff || (point >= 0xd800 && point <= 0xdfff))
            throw std::runtime_error("Native model returned invalid UTF-8");
        if (point < 0x10000) {
            text.push_back(static_cast<char16_t>(point));
        } else {
            point -= 0x10000;
            text.push_back(static_cast<char16_t>(0xd800 | (point >> 10)));
            text.push_back(static_cast<char16_t>(0xdc00 | (point & 0x3ff)));
        }
    }
    static_assert(sizeof(char16_t) == sizeof(jchar));
    return env->NewString(reinterpret_cast<const jchar*>(text.data()), static_cast<jsize>(text.size()));
}
