import CryptoKit
import Foundation

public struct MemeTag: Codable, Hashable, Identifiable {
    public var id: String { "\(label.lowercased()):\(source)" }
    public let label: String
    public var category: String
    public var score: Float
    public var source: String
    public var scoreDomain: String
    public var confirmation: String
    public var provenance: String

    public init(
        label: String,
        category: String = "topic",
        score: Float = 0.0,
        source: String = "open_vocab",
        scoreDomain: String = "open-vocab",
        confirmation: String = "unreviewed",
        provenance: String = "open-vocab"
    ) {
        self.label = Self.normalizeLabel(label)
        self.category = category
        self.score = score
        self.source = source
        self.scoreDomain = scoreDomain
        self.confirmation = confirmation
        self.provenance = provenance
    }

    public var isHumanConfirmed: Bool {
        source == "manual" || confirmation == "confirmed"
    }

    public static func normalizeLabel(_ raw: String) -> String {
        raw.lowercased()
            .replacingOccurrences(of: "[^a-z0-9\\s'-]+", with: " ", options: .regularExpression)
            .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

public struct MemeItem: Identifiable, Hashable {
    public let id: Int64
    public let uri: String
    public let name: String
    public let kind: String // "image" | "video"
    public var ocr: String
    public var caption: String
    public var transcript: String
    public var tags: [MemeTag]
    public var extraTerms: String
    public var thumbUri: String
    public var modifiedAt: Int64
    public var pending: Bool
    public var visionState: String
    public var contentHash: String
    public var cloudCaption: String
    public var cloudTerms: String
    public var rejectedLabels: [String]
    public var copyCount: Int
    public var lastCopiedAt: Int64
}

public struct SearchHitItem: Identifiable {
    public var id: Int64 { meme.id }
    public let meme: MemeItem
    public let score: Float
}

public enum GateVerdictCode: Int {
    case rejected = 0
    case accepted = 1
    case uncertainText = 2
    case uncertainVisual = 3
}

public struct CameraRollGateRecord {
    public let sourceKey: String
    public let uri: String
    public let name: String
    public let kind: String
    public let modifiedAt: Int64
    public let width: Int
    public let height: Int
    public let durationMs: Int64
    public var verdict: GateVerdictCode
    public var memeProbability: Float
    public var d1Prompt: String
    public var ocrText: String
    public var candidateHints: String
    public var imageVector: [Float]?
    public var sceneVectors: [[Float]]
    public var memeId: Int64
}

public struct TwoDomainParsed {
    public let scene: String
    public let baseReference: String
    public let targetTopic: String
    public let replyIntent: String
    public let tags: [MemeTag]
    public let combinedCaption: String
    public let searchTerms: String

    public static func parse(_ raw: String, sourceTag: String = "vision") -> TwoDomainParsed {
        var cleaned = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if cleaned.hasPrefix("YES|") { cleaned = String(cleaned.dropFirst(4)) }
        else if cleaned.hasPrefix("YES:") { cleaned = String(cleaned.dropFirst(4)) }
        cleaned = cleaned.trimmingCharacters(in: .whitespacesAndNewlines)

        var fields: [String: String] = [:]
        let parts = cleaned.contains("|") ? cleaned.components(separatedBy: "|") : cleaned.components(separatedBy: .newlines)
        for chunk in parts {
            guard let colon = chunk.firstIndex(of: ":") else { continue }
            let key = chunk[..<colon].trimmingCharacters(in: .whitespacesAndNewlines).lowercased().replacingOccurrences(of: " ", with: "_")
            let val = chunk[chunk.index(after: colon)...].trimmingCharacters(in: .whitespacesAndNewlines)
            if key.count <= 28 && !val.isEmpty && val.lowercased() != "none" && val.lowercased() != "unknown" {
                fields[key] = val
            }
        }

        let scene = fields["scene"] ?? fields["description"] ?? String(cleaned.prefix(400))
        let sourceType = fields["source_type"]?.lowercased()
        let sourceTitle = fields["source_title"] ?? ""
        let hasMovieSource = sourceType.map { ["movie", "film"].contains($0) } == true && !sourceTitle.isEmpty
        let baseRef = hasMovieSource ? sourceTitle : (fields["base_reference"] ?? fields["base"] ?? fields["template"] ?? "")
        let targetTopic = fields["target_topic"] ?? fields["target"] ?? fields["topic"] ?? ""
        let replyIntent = fields["reply_intent"] ?? fields["intent"] ?? fields["situation"] ?? ""
        let rawTags = fields["tags"] ?? ""

        var tags: [MemeTag] = []
        var seen = Set<String>()
        func addTag(_ rawLbl: String, category: String, score: Float) {
            let norm = MemeTag.normalizeLabel(rawLbl)
            guard norm.count >= 2, !["none", "unknown", "meme", "image", "photo"].contains(norm), seen.insert(norm).inserted else { return }
            tags.append(MemeTag(label: norm, category: category, score: score, source: sourceTag, scoreDomain: "two-domain", confirmation: "unreviewed", provenance: "\(sourceTag):two-domain"))
        }

        if !baseRef.isEmpty {
            if hasMovieSource {
                addTag(baseRef, category: "movie", score: 0.78)
            } else {
                baseRef.components(separatedBy: CharacterSet(charactersIn: ",;")).forEach { addTag($0, category: "format", score: 0.78) }
            }
        }
        if !targetTopic.isEmpty {
            targetTopic.components(separatedBy: CharacterSet(charactersIn: ",;")).forEach { addTag($0, category: "topic", score: 0.74) }
        }
        if !rawTags.isEmpty {
            rawTags.components(separatedBy: CharacterSet(charactersIn: ",;")).prefix(8).forEach { addTag($0, category: "topic", score: 0.68) }
        }

        let captionParts = [
            scene.isEmpty ? nil : scene,
            baseRef.isEmpty ? nil : "Reference: \(baseRef).",
            targetTopic.isEmpty ? nil : "About: \(targetTopic).",
            replyIntent.isEmpty ? nil : "Reply use: \(replyIntent)."
        ].compactMap { $0 }

        let terms = [baseRef, targetTopic, replyIntent, rawTags].filter { !$0.isEmpty }.joined(separator: " ")
        return TwoDomainParsed(
            scene: scene,
            baseReference: baseRef,
            targetTopic: targetTopic,
            replyIntent: replyIntent,
            tags: tags,
            combinedCaption: captionParts.joined(separator: " "),
            searchTerms: terms
        )
    }
}

public struct ModelArtifactSpec {
    public let id: String
    public let filename: String
    public let url: URL
    public let sha256Hex: String
    public let expectedSizeBytes: Int64
}

// Byte-for-byte pinned model artifacts identical to Android's ModelRegistry.kt so vectors, thresholds,
// and collection exports match across iPhone, Mac, and Android to 7 decimal places.
public enum MemegetModelRegistry {
    public static let primaryFingerprint = "mobileclip-s2@512:dd6a357f:e7576174:8e019f93:recipe-et0.9.2"

    public static let s2Image = ModelArtifactSpec(
        id: "mobileclip-s2-image",
        filename: "mobileclip_s2_image_xnnpack_fp32.pte",
        url: URL(string: "https://github.com/ayooooo123/Memeget/releases/download/models-v1/mobileclip_s2_image_xnnpack_fp32.pte")!,
        sha256Hex: "dd6a357fea3199b00eff17cd0898023213cf176e7566f7cbdf8dab9f0a1fe7f5",
        expectedSizeBytes: 143_278_592
    )

    public static let s2Text = ModelArtifactSpec(
        id: "mobileclip-s2-text",
        filename: "mobileclip_s2_text_xnnpack_fp32.pte",
        url: URL(string: "https://github.com/ayooooo123/Memeget/releases/download/models-v1/mobileclip_s2_text_xnnpack_fp32.pte")!,
        sha256Hex: "e7576174e756d5579179df4c76ea136123db0a66ee222974a3cf8e2a590ff488",
        expectedSizeBytes: 254_061_440
    )

    public static let s2Tokenizer = ModelArtifactSpec(
        id: "mobileclip-s2-tokenizer",
        filename: "mobileclip_s2_tokenizer.json",
        url: URL(string: "https://github.com/ayooooo123/Memeget/releases/download/models-v1/mobileclip_s2_tokenizer.json")!,
        sha256Hex: "8e019f9349caf5f9bb92514b0e4fc00c92841aef6ae8df9d833d975c623b7c72",
        expectedSizeBytes: 3_642_348
    )

    public static let d1Omni = ModelArtifactSpec(
        id: "d1-omni-600m-memeget-q8_0",
        filename: "d1-omni-600M-memeget-Q8_0.gguf",
        url: URL(string: "https://github.com/ayooooo123/Memeget/releases/download/models-v1/d1-omni-600M-memeget-Q8_0.gguf")!,
        sha256Hex: "ed64250aecb7bf62ebd5a655ac68012e9d5af859c968a648184c6f5bcea6d88d",
        expectedSizeBytes: 407_203_808
    )

    public static let minicpmModel = ModelArtifactSpec(
        id: "minicpm-v-4.7-1b-q4km",
        filename: "MiniCPM-V-4_7-1B-Q4_K_M.gguf",
        url: URL(string: "https://huggingface.co/openbmb/MiniCPM-V-4.7-1B-gguf/resolve/59cd286146b6ce70f818508ccf512e11438d8957/MiniCPM-V-4_7-1B-Q4_K_M.gguf")!,
        sha256Hex: "588c6c94c82f756b86dba35c42ed9ba9ec187d65824eb52f039c347df563e63d",
        expectedSizeBytes: 672_003_680
    )

    public static let minicpmProjectorBf16 = ModelArtifactSpec(
        id: "minicpm-v-4.7-1b-mmproj-f16",
        filename: "mmproj-model-f16.gguf",
        url: URL(string: "https://huggingface.co/openbmb/MiniCPM-V-4.7-1B-gguf/resolve/59cd286146b6ce70f818508ccf512e11438d8957/mmproj-model-f16.gguf")!,
        sha256Hex: "fb9cc047a3cdad5317fd8433a4aeda2d22f00f02ca4bd93b34ad84165b25a900",
        expectedSizeBytes: 1_110_117_984
    )

    public static let minicpmProjectorConvertedFilename = "mmproj-model-f16-converted.gguf"
    public static let minicpmProjectorConvertedSha256 = "204a7993d345456fb36d19892dbfd4ab6b5ce09026c7bc0bd3fec1e4831834b7"
    public static let minicpmProjectorConvertedSize: Int64 = 1_110_117_984
}

public actor ModelDownloadManager {
    public let modelsDir: URL

    public init(modelsDir: URL? = nil) {
        if let dir = modelsDir {
            self.modelsDir = dir
        } else {
            let appSup = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
            self.modelsDir = appSup.appendingPathComponent("memeget_models", isDirectory: true)
        }
        try? FileManager.default.createDirectory(at: self.modelsDir, withIntermediateDirectories: true)
    }

    public func localFileIfVerified(_ spec: ModelArtifactSpec) -> URL? {
        let target = modelsDir.appendingPathComponent(spec.filename)
        guard let attrs = try? FileManager.default.attributesOfItem(atPath: target.path),
              let sz = attrs[.size] as? Int64,
              sz == spec.expectedSizeBytes else {
            return nil
        }
        return target
    }

    public func ensureDownloaded(_ spec: ModelArtifactSpec, onProgress: (@Sendable (Double) -> Void)? = nil) async throws -> URL {
        if let ready = localFileIfVerified(spec) {
            onProgress?(1.0)
            return ready
        }
        // Check optional local seed directory (MEMEGET_MODEL_CACHE_DIR) during simulator / Mac testing
        if let seedDir = ProcessInfo.processInfo.environment["MEMEGET_MODEL_CACHE_DIR"] {
            let seedFile = URL(fileURLWithPath: seedDir).appendingPathComponent(spec.filename)
            if FileManager.default.fileExists(atPath: seedFile.path),
               let attrs = try? FileManager.default.attributesOfItem(atPath: seedFile.path),
               (attrs[.size] as? Int64) == spec.expectedSizeBytes {
                onProgress?(1.0)
                return seedFile
            }
        }

        let target = modelsDir.appendingPathComponent(spec.filename)
        let part = modelsDir.appendingPathComponent(spec.filename + ".part")
        var req = URLRequest(url: spec.url)
        var existingBytes: Int64 = 0
        if let attrs = try? FileManager.default.attributesOfItem(atPath: part.path),
           let sz = attrs[.size] as? Int64, sz > 0, sz < spec.expectedSizeBytes {
            existingBytes = sz
            req.setValue("bytes=\(existingBytes)-", forHTTPHeaderField: "Range")
        } else {
            try? FileManager.default.removeItem(at: part)
        }

        let (asyncBytes, response) = try await URLSession.shared.bytes(for: req)
        guard let http = response as? HTTPURLResponse, (200...206).contains(http.statusCode) else {
            throw NSError(domain: "com.memeget.models", code: 1, userInfo: [NSLocalizedDescriptionKey: "Failed downloading \(spec.filename)"])
        }
        if http.statusCode == 200 && existingBytes > 0 {
            try? FileManager.default.removeItem(at: part)
            existingBytes = 0
        }
        if !FileManager.default.fileExists(atPath: part.path) {
            FileManager.default.createFile(atPath: part.path, contents: nil)
        }
        let handle = try FileHandle(forWritingTo: part)
        defer { try? handle.close() }
        try handle.seekToEnd()

        var written = existingBytes
        var chunk = Data()
        chunk.reserveCapacity(65_536)
        for try await byte in asyncBytes {
            try Task.checkCancellation()
            chunk.append(byte)
            if chunk.count >= 65_536 {
                try handle.write(contentsOf: chunk)
                written += Int64(chunk.count)
                chunk.removeAll(keepingCapacity: true)
                onProgress?(Double(written) / Double(max(1, spec.expectedSizeBytes)))
            }
        }
        if !chunk.isEmpty {
            try handle.write(contentsOf: chunk)
            written += Int64(chunk.count)
        }
        try handle.close()

        let actualHash = try Self.sha256Hex(of: part)
        guard written == spec.expectedSizeBytes, actualHash == spec.sha256Hex.lowercased() else {
            try? FileManager.default.removeItem(at: part)
            throw NSError(domain: "com.memeget.models", code: 2, userInfo: [NSLocalizedDescriptionKey: "SHA-256 mismatch for \(spec.filename)"])
        }
        try? FileManager.default.removeItem(at: target)
        try FileManager.default.moveItem(at: part, to: target)
        onProgress?(1.0)
        return target
    }

    public static func sha256Hex(of fileURL: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: fileURL)
        defer { try? handle.close() }
        var hasher = SHA256()
        while autoreleasepool(invoking: {
            let data = (try? handle.read(upToCount: 131_072)) ?? Data()
            if data.isEmpty { return false }
            hasher.update(data: data)
            return true
        }) {}
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }
}
