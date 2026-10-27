import Foundation
import Photos
import UIKit

public enum MemeImageResolver {
    public static func loadUIImage(for meme: MemeItem, fullResolution: Bool = false) -> UIImage? {
        let candidates = fullResolution ? [meme.uri, meme.thumbUri] : [meme.thumbUri, meme.uri]
        for raw in candidates where !raw.isEmpty && !raw.hasPrefix("photokit:") {
            if let url = URL(string: raw), url.isFileURL, let img = UIImage(contentsOfFile: url.path) {
                return img
            }
            if let img = UIImage(contentsOfFile: raw) {
                return img
            }
            // Re-base photokit_thumbs/<filename> across iOS app-update container UUID rotations
            let fileName = URL(string: raw)?.lastPathComponent ?? (raw as NSString).lastPathComponent
            if !fileName.isEmpty {
                for domain in [FileManager.SearchPathDirectory.applicationSupportDirectory, .cachesDirectory] {
                    if let base = FileManager.default.urls(for: domain, in: .userDomainMask).first {
                        let p = base.appendingPathComponent("photokit_thumbs").appendingPathComponent(fileName).path
                        if let img = UIImage(contentsOfFile: p) {
                            return img
                        }
                    }
                }
            }
        }
        if meme.uri.hasPrefix("photokit:") {
            let localId = String(meme.uri.dropFirst("photokit:".count))
            let assets = PHAsset.fetchAssets(withLocalIdentifiers: [localId], options: nil)
            if let asset = assets.firstObject {
                let opts = PHImageRequestOptions()
                opts.isSynchronous = true
                opts.deliveryMode = .highQualityFormat
                opts.isNetworkAccessAllowed = false
                let target = fullResolution ? CGSize(width: 1600, height: 1600) : CGSize(width: 600, height: 600)
                var fetched: UIImage?
                PHImageManager.default().requestImage(for: asset, targetSize: target, contentMode: .aspectFit, options: opts) { img, _ in
                    fetched = img
                }
                return fetched
            }
        }
        return nil
    }
}

@MainActor
public final class MemeLibraryCoordinator: ObservableObject {
    @Published public var isScanning: Bool = false
    @Published public var statusHeadline: String = ""
    @Published public var progressDone: Int = 0
    @Published public var progressTotal: Int = 0
    @Published public var photoAccessGranted: Bool = false
    @Published public var libraryRevision: Int = 0

    public let store: SqliteMemeStore
    public let engine: IosInferenceEngine
    public let cloud: IosCloudAssistant
    private var scanTask: Task<Void, Never>?

    public init(store: SqliteMemeStore, engine: IosInferenceEngine, cloud: IosCloudAssistant) {
        self.store = store
        self.engine = engine
        self.cloud = cloud
        let status = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        self.photoAccessGranted = (status == .authorized || status == .limited)
    }

    public func requestPhotoAccessAndScan() {
        Task {
            let status = await PHPhotoLibrary.requestAuthorization(for: .readWrite)
            let ok = (status == .authorized || status == .limited)
            self.photoAccessGranted = ok
            if ok {
                self.startCameraRollScan()
            }
        }
    }

    public func cancelScan() {
        scanTask?.cancel()
        engine.bridge.cancelActiveWork()
        isScanning = false
        statusHeadline = ""
    }

    public func startCameraRollScan() {
        guard !isScanning else { return }
        isScanning = true
        statusHeadline = "Scanning Camera Roll…"

        scanTask = Task.detached(priority: .utility) { [weak self] in
            guard let self else { return }
            await self.runThreeStageCameraRollPipeline()
            await MainActor.run {
                self.isScanning = false
                self.statusHeadline = ""
                self.libraryRevision &+= 1
            }
        }
    }

    private func identifyAcceptedLocally(_ image: CGImage, ocr: String, hints: [MemeTag]) async -> TwoDomainParsed? {
        guard !Task.isCancelled,
              let result = try? await engine.evaluateStage3MiniCpm(
                  cgImage: image, ocrHint: ocr, candidateHints: hints.map(\.label).joined(separator: ", ")
              ),
              result.isMeme, !result.replyText.isEmpty else { return nil }
        return TwoDomainParsed.parse(result.replyText, sourceTag: "vision")
    }

    // Ingests arbitrary local image/video files (used by Share / Import Folder / Simulator E2E fixtures)
    // through the exact same 3-Stage Meme Gate (`CLIP+VisionOCR+Gutters -> d1-omni -> MiniCPM-V Metal`).
    public func ingestFileURLs(_ urls: [URL], useStage2And3IfAvailable: Bool = true) async -> (accepted: Int, rejected: Int) {
        var accepted = 0
        var rejected = 0
        let rejectedVecs = store.rejectedPhotoVectors(limit: 256)

        for url in urls {
            if Task.isCancelled { break }
            let ext = url.pathExtension.lowercased()
            let isVideo = ["mp4", "mov", "m4v", "webm"].contains(ext)
            guard let cgImage = isVideo ? IosInferenceEngine.extractVideoPoster(from: url) : IosInferenceEngine.loadUprightCGImage(from: url) else {
                continue
            }

            let (ocrRegions, fullOcr) = IosInferenceEngine.recognizeText(in: cgImage)
            let boxInputs: [MGOcrBoxInput] = ocrRegions.map { r in
                let b = MGOcrBoxInput()
                b.left = r.left; b.top = r.top; b.right = r.right; b.bottom = r.bottom
                b.confidence = r.confidence; b.text = r.text
                return b
            }

            let panelDet: MGPanelDetection
            if let (rgb, w, h) = try? IosInferenceEngine.makePackedRgbBounded(from: cgImage, maxPixels: 640 * 640) {
                panelDet = engine.bridge.detectPanels(fromRgb: rgb, width: w, height: h, ocrBoxCount: ocrRegions.count)
            } else {
                panelDet = MGPanelDetection()
                panelDet.panelCount = 1
                panelDet.hasGutter = false
                panelDet.crops = []
            }

            // Require genuine MobileCLIP-S2 embedding; never persist fabricated vectors if model is unavailable
            guard let imgVec = try? await engine.embedCGImage(cgImage) else {
                continue
            }

            var sceneVecs: [[Float]] = []
            if !panelDet.crops.isEmpty {
                sceneVecs.append(imgVec)
                for crop in panelDet.crops.prefix(4) {
                    let rect = CGRect(x: crop.x, y: crop.y, width: crop.width, height: crop.height)
                    if let sub = cgImage.cropping(to: rect), let cv = try? await engine.embedCGImage(sub) {
                        sceneVecs.append(cv)
                    }
                }
                if sceneVecs.count <= 1 { sceneVecs.removeAll() }
            }
            var maxUserRej: Float = -1.0
            for rv in rejectedVecs where rv.count == 512 {
                var dot: Float = 0
                for d in 0..<512 { dot += imgVec[d] * rv[d] }
                if dot > maxUserRej { maxUserRej = dot }
            }

            let stage1 = imgVec.withUnsafeBufferPointer { ptr in
                engine.bridge.evaluateStage1(
                    withImageVec: ptr.baseAddress!,
                    width: cgImage.width,
                    height: cgImage.height,
                    isVideo: isVideo,
                    durationMs: 0,
                    isScreenshotHint: url.lastPathComponent.lowercased().contains("screenshot"),
                    panelCount: panelDet.panelCount,
                    hasGutter: panelDet.hasGutter,
                    ordinaryAnchorCos: 0.0,
                    userRejectedCos: maxUserRej,
                    ocrBoxes: boxInputs,
                    fullOcrText: fullOcr
                )
            }

            var finalAccepted = (stage1.verdict == GateVerdictCode.accepted.rawValue)
            var twoDomainCaption = ""
            var extraTags: [MemeTag] = stage1.conceptHits.map {
                MemeTag(label: $0.label, category: $0.category, score: $0.score, source: "open_vocab", scoreDomain: "open-vocab", confirmation: "unreviewed", provenance: "open-vocab")
            }
            var extraTerms = stage1.extraTerms

            if stage1.verdict == GateVerdictCode.uncertainText.rawValue && useStage2And3IfAvailable {
                if let d1 = try? await engine.evaluateStage2D1(prompt: stage1.d1Prompt) {
                    finalAccepted = d1.isMeme
                }
            } else if stage1.verdict == GateVerdictCode.uncertainVisual.rawValue && useStage2And3IfAvailable {
                let hints = extraTags.map(\.label).joined(separator: ", ")
                if let vlm = try? await engine.evaluateStage3MiniCpm(cgImage: cgImage, ocrHint: fullOcr, candidateHints: hints) {
                    finalAccepted = vlm.isMeme
                    if vlm.isMeme && !vlm.replyText.isEmpty {
                        let parsed = TwoDomainParsed.parse(vlm.replyText, sourceTag: "vision")
                        twoDomainCaption = parsed.combinedCaption
                        extraTags.append(contentsOf: parsed.tags)
                        extraTerms = [extraTerms, parsed.searchTerms].filter { !$0.isEmpty }.joined(separator: " ")
                    }
                }
            }
            if finalAccepted && twoDomainCaption.isEmpty && useStage2And3IfAvailable,
               let parsed = await identifyAcceptedLocally(cgImage, ocr: fullOcr, hints: extraTags) {
                twoDomainCaption = parsed.combinedCaption
                extraTags.append(contentsOf: parsed.tags)
                extraTerms = [extraTerms, parsed.searchTerms].filter { !$0.isEmpty }.joined(separator: " ")
            }


            if finalAccepted {
                let memeId = store.upsertIndexedMeme(
                    uri: url.absoluteString,
                    name: url.lastPathComponent,
                    kind: isVideo ? "video" : "image",
                    modifiedAt: Int64(Date().timeIntervalSince1970 * 1000),
                    ocr: fullOcr,
                    caption: twoDomainCaption,
                    tags: extraTags,
                    extraTerms: extraTerms,
                    thumbUri: url.absoluteString,
                    imageVec: imgVec,
                    sceneVecs: sceneVecs
                )
                if let cloudOut = await cloud.analyzeMemeIfPermitted(cgImage: cgImage, ocrText: fullOcr, candidateLabels: extraTags.map(\.label)) {
                    store.applyCloudEnrichment(id: memeId, caption: cloudOut.caption, terms: cloudOut.terms, tags: cloudOut.tags)
                }
                accepted += 1
            } else {
                rejected += 1
            }
        }

        if accepted > 0 {
            let storeRef = self.store
            _ = await Task.detached(priority: .utility) { storeRef.propagateClusterIdentities() }.value
            await MainActor.run { self.libraryRevision &+= 1 }
        }
        return (accepted, rejected)
    }

    private func runThreeStageCameraRollPipeline() async {
        let fetchOpts = PHFetchOptions()
        fetchOpts.sortDescriptors = [NSSortDescriptor(key: "modificationDate", ascending: false)]
        fetchOpts.predicate = NSPredicate(format: "mediaType == %d || mediaType == %d", PHAssetMediaType.image.rawValue, PHAssetMediaType.video.rawValue)

        let imgManager = PHImageManager.default()
        let reqOpts = PHImageRequestOptions()
        reqOpts.isSynchronous = true
        reqOpts.deliveryMode = .highQualityFormat
        reqOpts.resizeMode = .fast
        reqOpts.isNetworkAccessAllowed = false

        let cacheDir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
            .appendingPathComponent("photokit_thumbs", isDirectory: true)
        try? FileManager.default.createDirectory(at: cacheDir, withIntermediateDirectories: true)

        var anyAcceptedAcrossBatches = false
        var skippedUnavailableThisSweep = Set<String>()

        while !Task.isCancelled {
            var stage1Processed = 0
            var d1Processed = 0
            var vlmProcessed = 0
            var s2ModelBlocked = false

            let fetchResult = PHAsset.fetchAssets(with: fetchOpts)
            let seen = store.seenCameraRollStates()
            var unseenAssets: [PHAsset] = []
            if fetchResult.count > 0 {
                fetchResult.enumerateObjects { asset, _, stop in
                    if asset.mediaType == .video && asset.duration > 180.0 { return }
                    let modMs = Int64((asset.modificationDate ?? asset.creationDate ?? Date.distantPast).timeIntervalSince1970 * 1000)
                    let key = "photokit:\(asset.localIdentifier)"
                    if seen[key] != modMs && !skippedUnavailableThisSweep.contains(key) {
                        unseenAssets.append(asset)
                        if unseenAssets.count >= 120 { stop.pointee = true }
                    }
                }
            }
            if !unseenAssets.isEmpty {
                let total = unseenAssets.count
                let rejectedVecs = store.rejectedPhotoVectors(limit: 256)

                for (idx, asset) in unseenAssets.enumerated() {
                    if Task.isCancelled { return }
                    await MainActor.run {
                        self.progressDone = idx
                        self.progressTotal = total
                        self.statusHeadline = "Filtering Camera Roll (\(idx + 1)/\(total))"
                    }

                    let sourceKey = "photokit:\(asset.localIdentifier)"
                    let modMs = Int64((asset.modificationDate ?? asset.creationDate ?? Date.distantPast).timeIntervalSince1970 * 1000)
                    let isScreenshot = asset.mediaSubtypes.contains(.photoScreenshot)
                    let isVideo = (asset.mediaType == .video)

                    var fetchedUIImage: UIImage?
                    imgManager.requestImage(
                        for: asset,
                        targetSize: CGSize(width: 768, height: 768),
                        contentMode: .aspectFit,
                        options: reqOpts
                    ) { img, _ in
                        fetchedUIImage = img
                    }
                    guard let uiImage = fetchedUIImage, let cgImage = uiImage.cgImage else {
                        if skippedUnavailableThisSweep.insert(sourceKey).inserted {
                            stage1Processed += 1
                        }
                        continue
                    }

                    guard let imgVec = try? await engine.embedCGImage(cgImage) else {
                        s2ModelBlocked = true
                        break
                    }

                    let safeId = asset.localIdentifier.replacingOccurrences(of: "/", with: "_")
                    let thumbURL = cacheDir.appendingPathComponent("\(safeId).jpg")
                    if let jpg = uiImage.jpegData(compressionQuality: 0.88) {
                        try? jpg.write(to: thumbURL, options: .atomic)
                    }

                    let (ocrRegions, fullOcr) = IosInferenceEngine.recognizeText(in: cgImage)
                    let boxInputs: [MGOcrBoxInput] = ocrRegions.map { r in
                        let b = MGOcrBoxInput()
                        b.left = r.left; b.top = r.top; b.right = r.right; b.bottom = r.bottom
                        b.confidence = r.confidence; b.text = r.text
                        return b
                    }

                    let panelDet: MGPanelDetection
                    if let (rgb, w, h) = try? IosInferenceEngine.makePackedRgbBounded(from: cgImage, maxPixels: 640 * 640) {
                        panelDet = engine.bridge.detectPanels(fromRgb: rgb, width: w, height: h, ocrBoxCount: ocrRegions.count)
                    } else {
                        panelDet = MGPanelDetection()
                        panelDet.panelCount = 1; panelDet.hasGutter = false; panelDet.crops = []
                    }

                    var sceneVecs: [[Float]] = []
                    if !panelDet.crops.isEmpty {
                        sceneVecs.append(imgVec)
                        for crop in panelDet.crops.prefix(4) {
                            if let sub = cgImage.cropping(to: CGRect(x: crop.x, y: crop.y, width: crop.width, height: crop.height)),
                               let cv = try? await engine.embedCGImage(sub) {
                                sceneVecs.append(cv)
                            }
                        }
                        if sceneVecs.count <= 1 { sceneVecs.removeAll() }
                    }

                    var maxUserRej: Float = -1.0
                    for rv in rejectedVecs where rv.count == 512 {
                        var dot: Float = 0
                        for d in 0..<512 { dot += imgVec[d] * rv[d] }
                        if dot > maxUserRej { maxUserRej = dot }
                    }

                    let s1 = imgVec.withUnsafeBufferPointer { ptr in
                        engine.bridge.evaluateStage1(
                            withImageVec: ptr.baseAddress!,
                            width: asset.pixelWidth,
                            height: asset.pixelHeight,
                            isVideo: isVideo,
                            durationMs: Int64(asset.duration * 1000.0),
                            isScreenshotHint: isScreenshot,
                            panelCount: panelDet.panelCount,
                            hasGutter: panelDet.hasGutter,
                            ordinaryAnchorCos: 0.0,
                            userRejectedCos: maxUserRej,
                            ocrBoxes: boxInputs,
                            fullOcrText: fullOcr
                        )
                    }

                    let verdict = GateVerdictCode(rawValue: s1.verdict) ?? .rejected
                    let tags = s1.conceptHits.map {
                        MemeTag(label: $0.label, category: $0.category, score: $0.score, source: "open_vocab", scoreDomain: "open-vocab", confirmation: "unreviewed", provenance: "open-vocab")
                    }


                    if verdict == .accepted {
                        let parsed = await identifyAcceptedLocally(cgImage, ocr: fullOcr, hints: tags)
                        let enrichedTags = tags + (parsed?.tags ?? [])
                        let enrichedTerms = [s1.extraTerms, parsed?.searchTerms ?? ""].filter { !$0.isEmpty }.joined(separator: " ")
                        let memeId = store.upsertIndexedMeme(
                            uri: sourceKey, name: safeId, kind: isVideo ? "video" : "image", modifiedAt: modMs,
                            ocr: fullOcr, caption: parsed?.combinedCaption ?? "", tags: enrichedTags, extraTerms: enrichedTerms,
                            thumbUri: thumbURL.absoluteString, imageVec: imgVec, sceneVecs: sceneVecs
                        )
                        if let cloudOut = await cloud.analyzeMemeIfPermitted(cgImage: cgImage, ocrText: fullOcr, candidateLabels: enrichedTags.map(\.label)) {
                            store.applyCloudEnrichment(id: memeId, caption: cloudOut.caption, terms: cloudOut.terms, tags: cloudOut.tags)
                        }
                        store.upsertCameraRollGate(CameraRollGateRecord(
                            sourceKey: sourceKey, uri: thumbURL.absoluteString, name: safeId, kind: isVideo ? "video" : "image",
                            modifiedAt: modMs, width: asset.pixelWidth, height: asset.pixelHeight, durationMs: Int64(asset.duration * 1000),
                            verdict: .accepted, memeProbability: s1.memeProbability, d1Prompt: "", ocrText: fullOcr,
                            candidateHints: "", imageVector: nil, sceneVectors: [], memeId: memeId
                        ))
                        anyAcceptedAcrossBatches = true
                        await MainActor.run { self.libraryRevision &+= 1 }
                    } else if verdict == .rejected {
                        try? FileManager.default.removeItem(at: thumbURL)
                        store.upsertCameraRollGate(CameraRollGateRecord(
                            sourceKey: sourceKey, uri: thumbURL.absoluteString, name: safeId, kind: isVideo ? "video" : "image",
                            modifiedAt: modMs, width: asset.pixelWidth, height: asset.pixelHeight, durationMs: Int64(asset.duration * 1000),
                            verdict: .rejected, memeProbability: s1.memeProbability, d1Prompt: "", ocrText: "",
                            candidateHints: "", imageVector: nil, sceneVectors: [], memeId: 0
                        ))
                    } else {
                        store.upsertCameraRollGate(CameraRollGateRecord(
                            sourceKey: sourceKey, uri: thumbURL.absoluteString, name: safeId, kind: isVideo ? "video" : "image",
                            modifiedAt: modMs, width: asset.pixelWidth, height: asset.pixelHeight, durationMs: Int64(asset.duration * 1000),
                            verdict: verdict, memeProbability: s1.memeProbability, d1Prompt: s1.d1Prompt, ocrText: fullOcr,
                            candidateHints: tags.map(\.label).joined(separator: ", "), imageVector: imgVec, sceneVectors: sceneVecs, memeId: 0
                        ))
                    }
                    stage1Processed += 1
                }
            }

            let pendingD1 = store.pendingCameraRollGate(verdict: .uncertainText, limit: 24)
            for item in pendingD1 {
                if Task.isCancelled { return }
                await MainActor.run { self.statusHeadline = "d1-omni Screenshot Check…" }
                guard let out = try? await engine.evaluateStage2D1(prompt: item.d1Prompt) else { break }
                d1Processed += 1
                if out.isMeme, let iv = item.imageVector {
                    let hits = iv.withUnsafeBufferPointer { engine.bridge.classifyMeme(withImageVec: $0.baseAddress!, ocrText: item.ocrText) }
                    let tags = hits.map { MemeTag(label: $0.label, category: $0.category, score: $0.score) }
                    let cg = URL(string: item.uri).flatMap { IosInferenceEngine.loadUprightCGImage(from: $0) }
                    let parsed: TwoDomainParsed?
                    if let cg {
                        parsed = await identifyAcceptedLocally(cg, ocr: item.ocrText, hints: tags)
                    } else {
                        parsed = nil
                    }
                    let enrichedTags = tags + (parsed?.tags ?? [])
                    let id = store.upsertIndexedMeme(
                        uri: item.sourceKey.hasPrefix("photokit:") ? item.sourceKey : item.uri,
                        name: item.name, kind: item.kind, modifiedAt: item.modifiedAt,
                        ocr: item.ocrText, caption: parsed?.combinedCaption ?? "", tags: enrichedTags,
                        extraTerms: [tags.map(\.label).joined(separator: " "), parsed?.searchTerms ?? ""].filter { !$0.isEmpty }.joined(separator: " "),
                        thumbUri: item.uri, imageVec: iv, sceneVecs: item.sceneVectors
                    )
                    if let cloudOut = await cloud.analyzeMemeIfPermitted(cgImage: cg, ocrText: item.ocrText, candidateLabels: enrichedTags.map(\.label)) {
                        store.applyCloudEnrichment(id: id, caption: cloudOut.caption, terms: cloudOut.terms, tags: cloudOut.tags)
                    }
                    var updated = item; updated.verdict = .accepted; updated.memeId = id; updated.imageVector = nil
                    store.upsertCameraRollGate(updated)
                    anyAcceptedAcrossBatches = true
                    await MainActor.run { self.libraryRevision &+= 1 }
                } else {
                    if let u = URL(string: item.uri) { try? FileManager.default.removeItem(at: u) }
                    var updated = item; updated.verdict = .rejected; updated.imageVector = nil
                    store.upsertCameraRollGate(updated)
                }
            }

            let pendingVlm = store.pendingCameraRollGate(verdict: .uncertainVisual, limit: 12)
            for item in pendingVlm {
                if Task.isCancelled { return }
                await MainActor.run { self.statusHeadline = "MiniCPM-V Visual Meme Check…" }
                guard let u = URL(string: item.uri), let cg = IosInferenceEngine.loadUprightCGImage(from: u) else {
                    var updated = item; updated.verdict = .rejected; updated.imageVector = nil
                    store.upsertCameraRollGate(updated)
                    vlmProcessed += 1
                    continue
                }
                guard let out = try? await engine.evaluateStage3MiniCpm(cgImage: cg, ocrHint: item.ocrText, candidateHints: item.candidateHints) else {
                    break
                }
                vlmProcessed += 1
                if out.isMeme, let iv = item.imageVector {
                    let parsed = TwoDomainParsed.parse(out.replyText, sourceTag: "vision")
                    let hits = iv.withUnsafeBufferPointer { engine.bridge.classifyMeme(withImageVec: $0.baseAddress!, ocrText: item.ocrText) }
                    let tags = hits.map { MemeTag(label: $0.label, category: $0.category, score: $0.score) } + parsed.tags
                    let id = store.upsertIndexedMeme(
                        uri: item.sourceKey.hasPrefix("photokit:") ? item.sourceKey : item.uri,
                        name: item.name, kind: item.kind, modifiedAt: item.modifiedAt,
                        ocr: item.ocrText, caption: parsed.combinedCaption, tags: tags,
                        extraTerms: [tags.map(\.label).joined(separator: " "), parsed.searchTerms].joined(separator: " "),
                        thumbUri: item.uri, imageVec: iv, sceneVecs: item.sceneVectors
                    )
                    if let cloudOut = await cloud.analyzeMemeIfPermitted(cgImage: cg, ocrText: item.ocrText, candidateLabels: tags.map(\.label)) {
                        store.applyCloudEnrichment(id: id, caption: cloudOut.caption, terms: cloudOut.terms, tags: cloudOut.tags)
                    }
                    var updated = item; updated.verdict = .accepted; updated.memeId = id; updated.imageVector = nil
                    store.upsertCameraRollGate(updated)
                    anyAcceptedAcrossBatches = true
                    await MainActor.run { self.libraryRevision &+= 1 }
                } else {
                    try? FileManager.default.removeItem(at: u)
                    var updated = item; updated.verdict = .rejected; updated.imageVector = nil
                    store.upsertCameraRollGate(updated)
                }
            }

            if s2ModelBlocked && d1Processed == 0 && vlmProcessed == 0 {
                break
            }
            if stage1Processed == 0 && d1Processed == 0 && vlmProcessed == 0 {
                break
            }
        }

        if anyAcceptedAcrossBatches && !Task.isCancelled {
            let storeRef = self.store
            _ = await Task.detached(priority: .utility) { storeRef.propagateClusterIdentities() }.value
        }
    }
}
