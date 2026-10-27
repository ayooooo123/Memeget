#include "KnowledgeCore.hpp"
#include "ResidentSearchCore.hpp"

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstring>
#include <fstream>
#include <stdexcept>
#include <unordered_set>

namespace memeget::core {
namespace {

constexpr uint32_t kMagicOv = 0x4D474F56u; // 'MGOV'
constexpr uint32_t kMagicPv = 0x4D475056u; // 'MGPV'
constexpr int kDim = 512;

struct BigEndianReader {
    const uint8_t* ptr = nullptr;
    size_t len = 0;
    size_t pos = 0;

    void requireBytes(size_t n) const {
        if (pos + n > len) throw std::runtime_error("Truncated knowledge binary");
    }

    uint8_t readU8() {
        requireBytes(1);
        return ptr[pos++];
    }

    uint16_t readU16() {
        requireBytes(2);
        uint16_t v = (static_cast<uint16_t>(ptr[pos]) << 8) | static_cast<uint16_t>(ptr[pos + 1]);
        pos += 2;
        return v;
    }

    uint32_t readU32() {
        requireBytes(4);
        uint32_t v = (static_cast<uint32_t>(ptr[pos]) << 24) |
                     (static_cast<uint32_t>(ptr[pos + 1]) << 16) |
                     (static_cast<uint32_t>(ptr[pos + 2]) << 8) |
                     static_cast<uint32_t>(ptr[pos + 3]);
        pos += 4;
        return v;
    }

    int32_t readI32() {
        return static_cast<int32_t>(readU32());
    }

    float readF32() {
        uint32_t bits = readU32();
        float f = 0.0f;
        std::memcpy(&f, &bits, sizeof(float));
        return f;
    }

    std::string readString(size_t n) {
        requireBytes(n);
        std::string s(reinterpret_cast<const char*>(ptr + pos), n);
        pos += n;
        return s;
    }
};

float halfToFloat(uint16_t hbits) {
    uint32_t mant = hbits & 0x03ffu;
    uint32_t exp = hbits & 0x7c00u;
    if (exp == 0x7c00u) {
        exp = 0x3fc00u;
    } else if (exp != 0) {
        exp += 0x1c000u;
    } else if (mant != 0) {
        exp = 0x1c400u;
        do {
            mant <<= 1;
            exp -= 0x400u;
        } while ((mant & 0x400u) == 0);
        mant &= 0x3ffu;
    }
    const uint32_t bits = ((static_cast<uint32_t>(hbits & 0x8000u)) << 16) | ((exp | mant) << 13);
    float out = 0.0f;
    std::memcpy(&out, &bits, sizeof(float));
    return out;
}

std::vector<float> readFp16Vec(BigEndianReader& r, size_t count) {
    r.requireBytes(count * 2);
    std::vector<float> out(count);
    for (size_t i = 0; i < count; ++i) {
        out[i] = halfToFloat(r.readU16());
    }
    return out;
}

std::string normalizeKey(const std::string& raw) {
    std::string out;
    out.reserve(raw.size());
    bool space = true;
    for (unsigned char ch : raw) {
        if (std::isalnum(ch)) {
            out.push_back(static_cast<char>(std::tolower(ch)));
            space = false;
        } else if (!space) {
            out.push_back(' ');
            space = true;
        }
    }
    if (!out.empty() && out.back() == ' ') out.pop_back();
    return out;
}

std::vector<uint8_t> readWholeFile(const std::string& path) {
    std::ifstream f(path, std::ios::binary | std::ios::ate);
    if (!f) throw std::runtime_error("Cannot open knowledge binary: " + path);
    const std::streamsize sz = f.tellg();
    if (sz <= 0) throw std::runtime_error("Empty knowledge binary: " + path);
    std::vector<uint8_t> buf(static_cast<size_t>(sz));
    f.seekg(0);
    if (!f.read(reinterpret_cast<char*>(buf.data()), sz)) {
        throw std::runtime_error("Failed reading knowledge binary: " + path);
    }
    return buf;
}

float silu(float x) {
    if (x >= 20.0f) return x;
    if (x <= -20.0f) return 0.0f;
    return x / (1.0f + std::exp(-x));
}

bool matchesOcrConcept(const std::string& label, bool isEntity, const std::string& normPaddedOcr) {
    if (normPaddedOcr.empty()) return false;
    if (label == "the lion does not concern himself" && normPaddedOcr.find(" the lion ") != std::string::npos) return true;
    if (label == "memri tv" && normPaddedOcr.find(" memri ") != std::string::npos) return true;
    if (label == "they live" && normPaddedOcr.find(" obey ") != std::string::npos && normPaddedOcr.find(" consume ") != std::string::npos) return true;
    if (!isEntity && label.find(' ') == std::string::npos && label.size() < 6) return false;
    if (label.size() < 4) return false;
    const std::string clean = normalizeKey(label);
    if (clean.size() < 4) return false;
    static const std::unordered_set<std::string> kSkip = {
        "meme", "text", "like", "just", "with", "that", "this", "from", "your", "when", "what", "have",
        "will", "good", "time", "look", "make", "know", "people", "think", "going", "want", "really",
        "white", "black", "green", "water", "first", "other", "there", "where", "after", "about", "under",
        "world", "woman", "women", "young", "house", "state", "right", "night", "money", "point", "power"
    };
    if (kSkip.count(clean)) return false;
    return normPaddedOcr.find(" " + clean + " ") != std::string::npos;
}

} // namespace

OpenVocabIndex OpenVocabIndex::loadFromFile(const std::string& path) {
    const auto buf = readWholeFile(path);
    return loadFromBytes(buf.data(), buf.size());
}

OpenVocabIndex OpenVocabIndex::loadFromBytes(const uint8_t* data, size_t size) {
    BigEndianReader r{data, size, 0};
    if (r.readU32() != kMagicOv) throw std::runtime_error("Invalid open_vocab_concepts magic");
    if (r.readI32() != 1) throw std::runtime_error("Unsupported open_vocab_concepts version");
    const int32_t count = r.readI32();
    if (count < 1 || count > 20000) throw std::runtime_error("Invalid open_vocab_concepts count");

    OpenVocabIndex idx;
    idx.entries_.reserve(count);
    for (int32_t i = 0; i < count; ++i) {
        const uint16_t lblLen = r.readU16();
        const uint16_t catLen = r.readU16();
        const bool isEnt = r.readU8() != 0;
        const float mean = r.readF32();
        const float stdv = r.readF32();
        std::string lbl = r.readString(lblLen);
        std::string cat = r.readString(catLen);
        std::vector<float> cHyb = readFp16Vec(r, kDim);
        std::vector<float> tStk = readFp16Vec(r, kDim);
        const std::string key = normalizeKey(lbl);
        if (!key.empty() && idx.byNormKey_.find(key) == idx.byNormKey_.end()) {
            idx.byNormKey_[key] = idx.entries_.size();
        }
        idx.entries_.push_back(Entry{
            std::move(lbl), std::move(cat), isEnt, mean, stdv, std::move(cHyb), std::move(tStk)
        });
    }
    return idx;
}

const OpenVocabIndex::Entry* OpenVocabIndex::findConcept(const std::string& rawQuery) const {
    const std::string norm = normalizeKey(rawQuery);
    if (norm.empty()) return nullptr;
    const int words = 1 + static_cast<int>(std::count(norm.begin(), norm.end(), ' '));
    if (words > 3) return nullptr;
    auto it = byNormKey_.find(norm);
    if (it != byNormKey_.end()) return &entries_[it->second];
    if (norm.rfind("the ", 0) == 0 && norm.size() > 4) {
        const std::string stripped = norm.substr(4);
        if (stripped.find(' ') != std::string::npos) {
            auto it2 = byNormKey_.find(stripped);
            if (it2 != byNormKey_.end()) return &entries_[it2->second];
        }
    }
    return nullptr;
}

ConceptIndexSummary OpenVocabIndex::evaluateImage(
    const float* imageVec512,
    const std::string& ocrText,
    int limit) const {
    ConceptIndexSummary summary;
    if (imageVec512 == nullptr || entries_.empty() || dotFp32(imageVec512, imageVec512, kDim) < 0.5f) {
        return summary;
    }

    const std::string normOcr = ocrText.empty() ? "" : (" " + normalizeKey(ocrText) + " ");
    const size_t n = entries_.size();
    std::vector<float> hyCos(n, 0.0f);
    int bestEnt1 = -1;
    float bestZ1 = -999.0f;
    float bestRaw1 = 0.0f;
    float bestZ2 = -999.0f;

    for (size_t i = 0; i < n; ++i) {
        const Entry& e = entries_[i];
        float hc = dotFp32(e.cHybrid.data(), imageVec512, kDim);
        if (!normOcr.empty() && matchesOcrConcept(e.label, e.isEntity, normOcr)) {
            hc = std::max(hc, 0.72f);
        }
        hyCos[i] = hc;
        if (hc > summary.maxHybridCos) summary.maxHybridCos = hc;

        if (e.isEntity) {
            const float raw = dotFp32(e.tStock.data(), imageVec512, kDim);
            const float z = (raw - e.tagMean) / std::max(e.tagStd, 0.025f);
            if (z > bestZ1) {
                bestZ2 = bestZ1;
                bestZ1 = z;
                bestRaw1 = raw;
                bestEnt1 = static_cast<int>(i);
            } else if (z > bestZ2) {
                bestZ2 = z;
            }
        }
    }
    summary.maxEntityZScore = (bestRaw1 >= 0.22f) ? bestZ1 : -4.0f;

    std::vector<ConceptHit> candidates;
    if (bestEnt1 >= 0 && bestZ1 >= 3.30f && bestRaw1 >= 0.23f && (bestZ1 - bestZ2) >= 0.20f) {
        const Entry& e = entries_[bestEnt1];
        candidates.push_back(ConceptHit{
            e.label, e.category, std::max(hyCos[bestEnt1], 0.80f), bestRaw1, bestZ1, e.isEntity, true
        });
    }

    // Collect top hybrid hits above 0.50
    std::vector<size_t> order;
    order.reserve(64);
    for (size_t i = 0; i < n; ++i) {
        if (hyCos[i] >= 0.50f && static_cast<int>(i) != bestEnt1) {
            order.push_back(i);
        }
    }
    std::sort(order.begin(), order.end(), [&](size_t a, size_t b) {
        return hyCos[a] > hyCos[b];
    });
    for (size_t idx : order) {
        if (static_cast<int>(candidates.size()) >= limit) break;
        const Entry& e = entries_[idx];
        const float raw = dotFp32(e.tStock.data(), imageVec512, kDim);
        const float z = (raw - e.tagMean) / std::max(e.tagStd, 0.025f);
        candidates.push_back(ConceptHit{
            e.label, e.category, hyCos[idx], raw, z, e.isEntity, false
        });
    }

    summary.hits = std::move(candidates);
    return summary;
}

std::vector<std::string> OpenVocabIndex::semanticQueryTerms(
    const float* queryVec512,
    float minCos,
    int limit) const {
    if (queryVec512 == nullptr || entries_.empty() || limit <= 0) return {};
    std::vector<int> topIdx(limit, -1);
    std::vector<float> topCos(limit, minCos);

    for (size_t i = 0; i < entries_.size(); ++i) {
        const float c = dotFp32(entries_[i].tStock.data(), queryVec512, kDim);
        if (c <= topCos[limit - 1]) continue;
        int k = limit - 1;
        while (k > 0 && topCos[k - 1] < c) {
            topCos[k] = topCos[k - 1];
            topIdx[k] = topIdx[k - 1];
            --k;
        }
        topCos[k] = c;
        topIdx[k] = static_cast<int>(i);
    }

    std::vector<std::string> words;
    std::unordered_set<std::string> seen;
    auto pushWords = [&](const std::string& phrase) {
        const std::string norm = normalizeKey(phrase);
        size_t s = 0;
        while (s < norm.size()) {
            size_t sp = norm.find(' ', s);
            std::string w = (sp == std::string::npos) ? norm.substr(s) : norm.substr(s, sp - s);
            if (w.size() >= 2 && seen.insert(w).second) words.push_back(w);
            if (sp == std::string::npos) break;
            s = sp + 1;
        }
    };

    for (int idx : topIdx) {
        if (idx < 0) break;
        const std::string& lbl = entries_[idx].label;
        pushWords(lbl);
        for (const auto& [parentLbl, _] : parentImplications(lbl)) {
            pushWords(parentLbl);
        }
    }
    return words;
}

std::vector<float> OpenVocabIndex::buildCslsQueryBank(int bankSize) const {
    if (entries_.empty() || bankSize <= 0) return {};
    const int actual = std::min<int>(bankSize, static_cast<int>(entries_.size()));
    std::vector<float> flat(static_cast<size_t>(actual) * kDim, 0.0f);
    const double step = static_cast<double>(entries_.size()) / static_cast<double>(actual);
    for (int b = 0; b < actual; ++b) {
        const size_t idx = std::min(entries_.size() - 1, static_cast<size_t>(b * step));
        // Alternate tStock (text query geometry) and cHybrid (visual-bridged concept geometry) for Dual-Bank balance!
        const std::vector<float>& src = (b % 2 == 0) ? entries_[idx].tStock : entries_[idx].cHybrid;
        std::memcpy(flat.data() + static_cast<size_t>(b) * kDim, src.data(), sizeof(float) * kDim);
    }
    return flat;
}

std::vector<std::pair<std::string, std::string>> OpenVocabIndex::parentImplications(const std::string& label) {
    const std::string norm = normalizeKey(label);
    if (norm == "tony soprano" || norm == "james gandolfini" || norm == "paulie walnuts" ||
        norm == "christopher moltisanti" || norm == "uncle junior" || norm == "livia soprano") {
        return {{"sopranos", "tv"}};
    }
    if (norm == "patrick bateman" || norm == "paul allen scene" || norm == "business card scene") {
        return {{"american psycho", "movie"}};
    }
    if (norm == "walter white" || norm == "jesse pinkman" || norm == "gustavo fring") {
        return {{"breaking bad", "tv"}};
    }
    if (norm == "saul goodman") {
        return {{"better call saul", "tv"}, {"breaking bad", "tv"}};
    }
    if (norm == "jack torrance" || norm == "wendy torrance" || norm == "heres johnny") {
        return {{"the shining", "movie"}};
    }
    if (norm == "apu apustaja" || norm == "apustaja" || norm == "groyper" || norm == "sad pepe" || norm == "smug pepe") {
        return {{"pepe", "character"}};
    }
    if (norm == "soyjak" || norm == "doomer" || norm == "npc wojak" || norm == "crying wojak") {
        return {{"wojak", "character"}};
    }
    return {};
}

PairwiseVerifierCore PairwiseVerifierCore::loadFromFile(const std::string& path) {
    const auto buf = readWholeFile(path);
    return loadFromBytes(buf.data(), buf.size());
}

PairwiseVerifierCore PairwiseVerifierCore::loadFromBytes(const uint8_t* data, size_t size) {
    BigEndianReader r{data, size, 0};
    if (r.readU32() != kMagicPv) throw std::runtime_error("Invalid pairwise_verifier magic");
    if (r.readI32() != 2) throw std::runtime_error("Unsupported pairwise_verifier version");

    PairwiseVerifierCore pv;
    pv.inDim_ = r.readI32();
    pv.projDim_ = r.readI32();
    pv.scalarDim_ = r.readI32();
    pv.h1Dim_ = r.readI32();
    pv.headDim_ = r.readI32();
    if (pv.inDim_ != 512 || pv.scalarDim_ != 11) {
        throw std::runtime_error("Unexpected pairwise_verifier dimensions");
    }
    const int pairDim = pv.projDim_ * 2 + pv.scalarDim_;
    pv.wProj_ = readFp16Vec(r, static_cast<size_t>(pv.projDim_) * pv.inDim_);
    pv.lnProjW_ = readFp16Vec(r, pv.projDim_);
    pv.lnProjB_ = readFp16Vec(r, pv.projDim_);
    pv.wFc1_ = readFp16Vec(r, static_cast<size_t>(pv.h1Dim_) * pairDim);
    pv.bFc1_ = readFp16Vec(r, pv.h1Dim_);
    pv.wFc2_ = readFp16Vec(r, static_cast<size_t>(pv.headDim_) * pv.h1Dim_);
    pv.bFc2_ = readFp16Vec(r, pv.headDim_);
    pv.wOut_ = readFp16Vec(r, pv.headDim_);
    pv.bOut_ = readFp16Vec(r, 1)[0];
    return pv;
}

std::vector<PairwiseVerifierCore::ScoredCandidate> PairwiseVerifierCore::scoreCandidates(
    const float* imageVec512,
    const std::vector<Candidate>& candidates,
    const float* residualDeltaW,
    float residualDeltaB) const {
    if (!valid() || imageVec512 == nullptr || candidates.empty()) return {};

    auto project = [&](const float* vec) {
        std::vector<float> out(projDim_, 0.0f);
        float mean = 0.0f;
        for (int r = 0; r < projDim_; ++r) {
            out[r] = dotFp32(wProj_.data() + static_cast<size_t>(r) * inDim_, vec, inDim_);
            mean += out[r];
        }
        mean /= static_cast<float>(projDim_);
        float varSum = 0.0f;
        for (float v : out) {
            const float d = v - mean;
            varSum += d * d;
        }
        const float invStd = 1.0f / std::sqrt(varSum / static_cast<float>(projDim_) + 1e-5f);
        for (int i = 0; i < projDim_; ++i) {
            out[i] = (out[i] - mean) * invStd * lnProjW_[i] + lnProjB_[i];
        }
        return out;
    };

    const std::vector<float> u = project(imageVec512);
    const int pairDim = projDim_ * 2 + scalarDim_;
    std::vector<float> pair(pairDim, 0.0f);
    std::vector<float> h1(h1Dim_, 0.0f);
    std::vector<ScoredCandidate> results;
    results.reserve(candidates.size());

    for (const auto& c : candidates) {
        if (c.protoVec512 == nullptr) continue;
        const std::vector<float> w = project(c.protoVec512);
        for (int d = 0; d < projDim_; ++d) {
            pair[d] = u[d] * w[d];
            pair[projDim_ + d] = std::abs(u[d] - w[d]);
        }
        const float rawVisCos = dotFp32(imageVec512, c.protoVec512, inDim_);
        const float txtCos = (c.textVec512 != nullptr) ? dotFp32(imageVec512, c.textVec512, inDim_) : rawVisCos;
        const float zScaled = c.hasStats
            ? std::clamp((txtCos - c.tagMean) / std::max(c.tagStd, 0.025f), -4.0f, 8.0f) * 0.125f
            : std::clamp((txtCos - 0.20f) / 0.045f, -4.0f, 8.0f) * 0.125f;
        const bool hasNeg = c.negCos > 0.30f;
        const float negClamped = hasNeg ? c.negCos : 0.0f;
        const float negMargin = hasNeg ? std::clamp(c.negCos - rawVisCos, -1.0f, 1.0f) : -0.5f;

        const int sBase = projDim_ * 2;
        pair[sBase + 0] = rawVisCos;
        pair[sBase + 1] = c.calPosCos;
        pair[sBase + 2] = txtCos;
        pair[sBase + 3] = zScaled;
        pair[sBase + 4] = hasNeg ? 1.0f : 0.0f;
        pair[sBase + 5] = negClamped;
        pair[sBase + 6] = negMargin;
        pair[sBase + 7] = std::min(1.0f, c.voteCount / 5.0f);
        pair[sBase + 8] = std::min(1.0f, c.support70 / 5.0f);
        pair[sBase + 9] = c.ocrHit ? 1.0f : 0.0f;
        pair[sBase + 10] = c.isEntity ? 1.0f : 0.0f;

        for (int i = 0; i < h1Dim_; ++i) {
            h1[i] = silu(bFc1_[i] + dotFp32(wFc1_.data() + static_cast<size_t>(i) * pairDim, pair.data(), pairDim));
        }

        ScoredCandidate sc;
        sc.key = c.key;
        sc.hidden64.resize(headDim_);
        float logit = bOut_;
        for (int i = 0; i < headDim_; ++i) {
            const float act = silu(bFc2_[i] + dotFp32(wFc2_.data() + static_cast<size_t>(i) * h1Dim_, h1.data(), h1Dim_));
            sc.hidden64[i] = act;
            logit += wOut_[i] * act;
        }
        sc.baseLogit = logit;
        float rel = logit + residualDeltaB;
        if (residualDeltaW != nullptr) {
            rel += dotFp32(residualDeltaW, sc.hidden64.data(), headDim_);
        }
        rel = std::clamp(rel, -20.0f, 20.0f);
        sc.probability = 1.0f / (1.0f + std::exp(-rel));
        results.push_back(std::move(sc));
    }

    return results;
}

MemeGateAnchors MemeGateAnchors::loadFromFile(const std::string& path) {
    const auto buf = readWholeFile(path);
    if (buf.size() < 16) throw std::runtime_error("Truncated meme_gate_anchors.bin");
    uint32_t header[4];
    std::memcpy(header, buf.data(), 16);
    if (header[0] != 0x4D474741u || header[1] != 1u) {
        throw std::runtime_error("Invalid meme_gate_anchors.bin header");
    }
    const int pCnt = static_cast<int>(header[2]);
    const int nCnt = static_cast<int>(header[3]);
    const size_t expected = 16 + static_cast<size_t>(pCnt + nCnt) * kDim * sizeof(float);
    if (buf.size() < expected) throw std::runtime_error("Short meme_gate_anchors.bin payload");

    MemeGateAnchors out;
    out.posCount_ = pCnt;
    out.negCount_ = nCnt;
    out.posAnchors_.resize(static_cast<size_t>(pCnt) * kDim);
    out.negAnchors_.resize(static_cast<size_t>(nCnt) * kDim);
    if (pCnt > 0) {
        std::memcpy(out.posAnchors_.data(), buf.data() + 16, out.posAnchors_.size() * sizeof(float));
    }
    if (nCnt > 0) {
        std::memcpy(out.negAnchors_.data(), buf.data() + 16 + out.posAnchors_.size() * sizeof(float), out.negAnchors_.size() * sizeof(float));
    }
    return out;
}

float MemeGateAnchors::maxOrdinaryPhotoCos(const float* imageVec512) const {
    if (imageVec512 == nullptr || negAnchors_.empty()) return 0.0f;
    float best = -2.0f;
    for (int i = 0; i < negCount_; ++i) {
        const float c = dotFp32(imageVec512, negAnchors_.data() + static_cast<size_t>(i) * kDim, kDim);
        if (c > best) best = c;
    }
    return best;
}

float MemeGateAnchors::maxMemeAnchorMargin(const float* imageVec512) const {
    if (imageVec512 == nullptr || posAnchors_.empty()) return 0.0f;
    const float neg = maxOrdinaryPhotoCos(imageVec512);
    float bestPos = -2.0f;
    for (int i = 0; i < posCount_; ++i) {
        const float c = dotFp32(imageVec512, posAnchors_.data() + static_cast<size_t>(i) * kDim, kDim);
        if (c > bestPos) bestPos = c;
    }
    return bestPos - std::max(0.0f, neg);
}

} // namespace memeget::core
