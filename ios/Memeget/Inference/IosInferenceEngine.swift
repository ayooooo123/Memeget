import AVFoundation
import CoreGraphics
import Foundation
import ImageIO
import Vision

public struct VisionOcrRegion {
    public let text: String
    public let confidence: Float
    public let left: Float
    public let top: Float
    public let right: Float
    public let bottom: Float
}

public final class IosInferenceEngine: @unchecked Sendable {
    public let bridge: MemegetCoreBridge
    public let downloader: ModelDownloadManager
    private let tokenizerLock = NSLock()
    private var cachedTokenizer: ClipTokenizer?
    private var queryVecCache: [String: [Float]] = [:]

    public init(
        openVocabPath: String? = Bundle.main.path(forResource: "open_vocab_concepts", ofType: "bin"),
        verifierPath: String? = Bundle.main.path(forResource: "pairwise_verifier", ofType: "bin"),
        anchorsPath: String? = Bundle.main.path(forResource: "meme_gate_anchors", ofType: "bin"),
        downloader: ModelDownloadManager = ModelDownloadManager()
    ) {
        self.bridge = MemegetCoreBridge(openVocabPath: openVocabPath, verifierPath: verifierPath, anchorsPath: anchorsPath)
        self.downloader = downloader
    }

    public func cachedQueryVector(_ query: String) -> [Float]? {
        let key = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !key.isEmpty, key.count <= 160 else { return nil }
        tokenizerLock.lock()
        defer { tokenizerLock.unlock() }
        return queryVecCache[key]
    }

    public func embedText(_ text: String) async throws -> [Float] {
        let key = text.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if let hit = cachedQueryVector(key) { return hit }

        let tokURL = try await downloader.ensureDownloaded(MemegetModelRegistry.s2Tokenizer)
        let modelURL = try await downloader.ensureDownloaded(MemegetModelRegistry.s2Text)

        let tokenizer: ClipTokenizer = try {
            tokenizerLock.lock()
            defer { tokenizerLock.unlock() }
            if let existing = cachedTokenizer { return existing }
            let loaded = try ClipTokenizer(fileURL: tokURL)
            cachedTokenizer = loaded
            return loaded
        }()

        let ids = tokenizer.encode(text)
        bridge.resetCancel()
        let numbers = try ids.withUnsafeBufferPointer { ptr in
            try bridge.embedTextTokens77(ptr.baseAddress!, modelPath: modelURL.path)
        }
        let vec = numbers.map { $0.floatValue }
        if !key.isEmpty && key.count <= 160 {
            tokenizerLock.lock()
            if queryVecCache.count >= 64 { queryVecCache.removeAll(keepingCapacity: true) }
            queryVecCache[key] = vec
            tokenizerLock.unlock()
        }
        return vec
    }

    public func embedCGImage(_ cgImage: CGImage) async throws -> [Float] {
        let modelURL = try await downloader.ensureDownloaded(MemegetModelRegistry.s2Image)
        let planar = try Self.makePlanarRgb256(from: cgImage)
        bridge.resetCancel()
        let numbers = try planar.withUnsafeBufferPointer { ptr in
            try bridge.embedImagePlanarRgb(ptr.baseAddress!, modelPath: modelURL.path)
        }
        return numbers.map { $0.floatValue }
    }

    // Hardware ANE/CPU OCR via Apple Vision with top-left normalized [0..1] bounding boxes for MemeGateCore.
    public static func recognizeText(in cgImage: CGImage) -> (regions: [VisionOcrRegion], fullText: String) {
        var found: [VisionOcrRegion] = []
        let request = VNRecognizeTextRequest { req, _ in
            guard let observations = req.results as? [VNRecognizedTextObservation] else { return }
            for obs in observations {
                guard let topCand = obs.topCandidates(1).first else { continue }
                let str = topCand.string.trimmingCharacters(in: .whitespacesAndNewlines)
                if str.isEmpty { continue }
                // Vision boundingBox origin is bottom-left; flip Y to top-left [0..1]
                let bb = obs.boundingBox
                let left = Float(max(0.0, min(1.0, bb.minX)))
                let right = Float(max(0.0, min(1.0, bb.maxX)))
                let top = Float(max(0.0, min(1.0, 1.0 - bb.maxY)))
                let bottom = Float(max(0.0, min(1.0, 1.0 - bb.minY)))
                found.append(VisionOcrRegion(
                    text: str,
                    confidence: topCand.confidence,
                    left: left,
                    top: top,
                    right: right,
                    bottom: bottom
                ))
            }
        }
        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = true

        let handler = VNImageRequestHandler(cgImage: cgImage, options: [:])
        try? handler.perform([request])

        found.sort { a, b in
            if abs(a.top - b.top) > 0.035 { return a.top < b.top }
            return a.left < b.left
        }
        let full = found.map(\.text).joined(separator: "\n")
        return (found, full)
    }

    public func evaluateStage2D1(prompt: String) async throws -> MGD1Outcome {
        let d1URL = try await downloader.ensureDownloaded(MemegetModelRegistry.d1Omni)
        bridge.resetCancel()
        return try bridge.evaluateD1Gate(withPrompt: prompt, modelPath: d1URL.path)
    }

    public func evaluateStage3MiniCpm(cgImage: CGImage, ocrHint: String, candidateHints: String) async throws -> MGVlmGateOutcome {
        let modelURL = try await downloader.ensureDownloaded(MemegetModelRegistry.minicpmModel)
        let projURL = try await ensureConvertedProjector()
        let (rgbData, w, h) = try Self.makePackedRgbBounded(from: cgImage, maxPixels: 448 * 448)
        bridge.resetCancel()
        return try bridge.gateAndDescribeRgb(
            rgbData,
            width: w,
            height: h,
            ocrHint: ocrHint,
            candidateHints: candidateHints,
            modelPath: modelURL.path,
            projectorPath: projURL.path
        )
    }

    private func ensureConvertedProjector() async throws -> URL {
        let modelsDir = await downloader.modelsDir
        let converted = modelsDir.appendingPathComponent(MemegetModelRegistry.minicpmProjectorConvertedFilename)
        if let attrs = try? FileManager.default.attributesOfItem(atPath: converted.path),
           (attrs[.size] as? Int64) == MemegetModelRegistry.minicpmProjectorConvertedSize {
            return converted
        }
        let bf16 = try await downloader.ensureDownloaded(MemegetModelRegistry.minicpmProjectorBf16)
        let part = modelsDir.appendingPathComponent(MemegetModelRegistry.minicpmProjectorConvertedFilename + ".part")
        try? FileManager.default.removeItem(at: part)
        _ = try bridge.convertProjectorBf16(atPath: bf16.path, toF16Path: part.path)
        try? FileManager.default.removeItem(at: converted)
        try FileManager.default.moveItem(at: part, to: converted)
        try? FileManager.default.removeItem(at: bf16)
        return converted
    }

    // Renders upright CGImage into [1, 3, 256, 256] planar RGB float32 in [0, 1] matching export_mobileclip_s2.py.
    public static func makePlanarRgb256(from image: CGImage) throws -> [Float] {
        let side = 256
        var rgba = [UInt8](repeating: 0, count: side * side * 4)
        let cs = CGColorSpaceCreateDeviceRGB()
        guard let ctx = CGContext(
            data: &rgba,
            width: side,
            height: side,
            bitsPerComponent: 8,
            bytesPerRow: side * 4,
            space: cs,
            bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue
        ) else {
            throw NSError(domain: "com.memeget.image", code: 1, userInfo: [NSLocalizedDescriptionKey: "CGContext 256x256 creation failed"])
        }
        ctx.interpolationQuality = .high
        ctx.draw(image, in: CGRect(x: 0, y: 0, width: side, height: side))

        let plane = side * side
        var planar = [Float](repeating: 0, count: 3 * plane)
        let inv255: Float = 1.0 / 255.0
        for p in 0..<plane {
            let off = p * 4
            planar[p] = Float(rgba[off]) * inv255
            planar[plane + p] = Float(rgba[off + 1]) * inv255
            planar[2 * plane + p] = Float(rgba[off + 2]) * inv255
        }
        return planar
    }

    public static func makePackedRgbBounded(from image: CGImage, maxPixels: Int = 768 * 768) throws -> (Data, Int, Int) {
        let srcW = max(1, image.width)
        let srcH = max(1, image.height)
        let total = Double(srcW * srcH)
        let scale = total > Double(maxPixels) ? sqrt(Double(maxPixels) / total) : 1.0
        let w = max(1, Int(floor(Double(srcW) * scale)))
        let h = max(1, Int(floor(Double(srcH) * scale)))

        var rgba = [UInt8](repeating: 0, count: w * h * 4)
        let cs = CGColorSpaceCreateDeviceRGB()
        guard let ctx = CGContext(
            data: &rgba,
            width: w,
            height: h,
            bitsPerComponent: 8,
            bytesPerRow: w * 4,
            space: cs,
            bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue
        ) else {
            throw NSError(domain: "com.memeget.image", code: 2, userInfo: [NSLocalizedDescriptionKey: "CGContext RGB creation failed"])
        }
        ctx.interpolationQuality = .high
        ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))

        var rgb = [UInt8](repeating: 0, count: w * h * 3)
        var dst = 0
        for p in 0..<(w * h) {
            let off = p * 4
            rgb[dst] = rgba[off]
            rgb[dst + 1] = rgba[off + 1]
            rgb[dst + 2] = rgba[off + 2]
            dst += 3
        }
        return (Data(rgb), w, h)
    }

    public static func loadUprightCGImage(from url: URL) -> CGImage? {
        guard let src = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
        let opts: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: 1024
        ]
        return CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary)
    }

    public static func extractVideoPoster(from url: URL) -> CGImage? {
        let asset = AVURLAsset(url: url)
        let gen = AVAssetImageGenerator(asset: asset)
        gen.appliesPreferredTrackTransform = true
        gen.maximumSize = CGSize(width: 768, height: 768)
        let t = CMTime(seconds: 0.25, preferredTimescale: 600)
        return try? gen.copyCGImage(at: t, actualTime: nil)
    }
}
