#pragma once

#include <cstdint>
#include <string>
#include <unordered_map>
#include <vector>

namespace memeget::core {

struct ConceptHit {
    std::string label;
    std::string category;
    float posCos = 0.0f;
    float rawTxtCos = 0.0f;
    float zScore = 0.0f;
    bool isEntity = false;
    bool fromZTop1 = false;
};

struct ConceptIndexSummary {
    float maxHybridCos = 0.0f;
    float maxEntityZScore = -4.0f;
    std::vector<ConceptHit> hits;
};

// Portable C++20 reader & scorer for open_vocab_concepts.bin (6,028 cultural & meme prototypes)
// and pairwise_verifier.bin (FP16 Siamese verifier + 64-d PU residual head), shared across iOS & Android.
class OpenVocabIndex {
public:
    struct Entry {
        std::string label;
        std::string category;
        bool isEntity = false;
        float tagMean = 0.20f;
        float tagStd = 0.045f;
        std::vector<float> cHybrid; // 512
        std::vector<float> tStock;  // 512
    };

    static OpenVocabIndex loadFromFile(const std::string& path);
    static OpenVocabIndex loadFromBytes(const uint8_t* data, size_t size);

    bool empty() const { return entries_.empty(); }
    size_t size() const { return entries_.size(); }
    const std::vector<Entry>& entries() const { return entries_; }

    const Entry* findConcept(const std::string& rawQuery) const;
    ConceptIndexSummary evaluateImage(const float* imageVec512, const std::string& ocrText, int limit = 6) const;
    std::vector<std::string> semanticQueryTerms(const float* queryVec512, float minCos = 0.30f, int limit = 4) const;

    // Extracts a deterministic 256-concept diverse query bank (cHybrid + tStock) for CSLS anti-hubness calibration.
    std::vector<float> buildCslsQueryBank(int bankSize = 256) const;

    static std::vector<std::pair<std::string, std::string>> parentImplications(const std::string& label);

private:
    std::vector<Entry> entries_;
    std::unordered_map<std::string, size_t> byNormKey_;
};

class PairwiseVerifierCore {
public:
    struct Candidate {
        std::string key;
        const float* protoVec512 = nullptr;
        const float* textVec512 = nullptr;
        float calPosCos = 0.0f;
        float tagMean = 0.20f;
        float tagStd = 0.045f;
        bool hasStats = false;
        float negCos = -1.0f;
        int voteCount = 0;
        int support70 = 0;
        bool ocrHit = false;
        bool isEntity = false;
    };

    struct ScoredCandidate {
        std::string key;
        float baseLogit = 0.0f;
        float probability = 0.0f;
        std::vector<float> hidden64;
    };

    static PairwiseVerifierCore loadFromFile(const std::string& path);
    static PairwiseVerifierCore loadFromBytes(const uint8_t* data, size_t size);

    bool valid() const { return inDim_ == 512 && !wProj_.empty(); }
    int headDim() const { return headDim_; }

    std::vector<ScoredCandidate> scoreCandidates(
        const float* imageVec512,
        const std::vector<Candidate>& candidates,
        const float* residualDeltaW = nullptr,
        float residualDeltaB = 0.0f) const;

private:
    int inDim_ = 0;
    int projDim_ = 0;
    int scalarDim_ = 0;
    int h1Dim_ = 0;
    int headDim_ = 0;
    std::vector<float> wProj_;
    std::vector<float> lnProjW_;
    std::vector<float> lnProjB_;
    std::vector<float> wFc1_;
    std::vector<float> bFc1_;
    std::vector<float> wFc2_;
    std::vector<float> bFc2_;
    std::vector<float> wOut_;
    float bOut_ = 0.0f;
};

// Precomputed MobileCLIP-S2 zero-shot positive meme anchors & negative ordinary-photo anchors (`meme_gate_anchors.bin`).
class MemeGateAnchors {
public:
    static MemeGateAnchors loadFromFile(const std::string& path);

    bool empty() const { return negAnchors_.empty(); }
    float maxOrdinaryPhotoCos(const float* imageVec512) const;
    float maxMemeAnchorMargin(const float* imageVec512) const;

private:
    int posCount_ = 0;
    int negCount_ = 0;
    std::vector<float> posAnchors_; // [posCount * 512]
    std::vector<float> negAnchors_; // [negCount * 512]
};

} // namespace memeget::core
