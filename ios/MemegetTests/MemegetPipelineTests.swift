import CoreGraphics
import Foundation
import UIKit
import XCTest
@testable import Memeget

final class MemegetPipelineTests: XCTestCase {

    private func repoKnowledgePath(_ name: String) -> String {
        let bundlePath = Bundle.main.path(forResource: (name as NSString).deletingPathExtension, ofType: (name as NSString).pathExtension)
        if let bp = bundlePath, FileManager.default.fileExists(atPath: bp) {
            return bp
        }
        let fallback = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("native/app/src/main/assets/knowledge/\(name)").path
        return fallback
    }

    private func unitVec(_ pairs: [(Int, Float)]) -> [Float] {
        var v = [Float](repeating: 0, count: 512)
        for (i, val) in pairs { v[i] = val }
        let norm = sqrt(v.reduce(Float(0)) { $0 + $1 * $1 })
        if norm > 0 {
            for i in 0..<512 { v[i] /= norm }
        }
        return v
    }

    func testSharedCppKnowledgeBinariesAnd3StageMemeGate() throws {
        let ovPath = repoKnowledgePath("open_vocab_concepts.bin")
        let pvPath = repoKnowledgePath("pairwise_verifier.bin")
        XCTAssertTrue(FileManager.default.fileExists(atPath: ovPath), "open_vocab_concepts.bin missing")
        XCTAssertTrue(FileManager.default.fileExists(atPath: pvPath), "pairwise_verifier.bin missing")

        let bridge = MemegetCoreBridge(openVocabPath: ovPath, verifierPath: pvPath)
        XCTAssertNotNil(bridge.conceptPrototypeVector(forQuery: "tony soprano"), "6,028 OpenVocabConcepts should resolve Tony Soprano on iOS")

        // 1. Receipt hard-negative -> REJECTED (0)
        var receiptBoxes: [MGOcrBoxInput] = []
        for row in 0..<16 {
            let b = MGOcrBoxInput()
            b.left = 0.1; b.top = Float(row) * 0.05; b.right = 0.85; b.bottom = Float(row) * 0.05 + 0.03
            b.confidence = 0.96; b.text = "Item \(row) $14.99"
            receiptBoxes.append(b)
        }
        let dummyVec = unitVec([(12, 1.0)])
        let receiptOut = dummyVec.withUnsafeBufferPointer { ptr in
            bridge.evaluateStage1(
                withImageVec: ptr.baseAddress!, width: 720, height: 1280, isVideo: false, durationMs: 0,
                isScreenshotHint: false, panelCount: 1, hasGutter: false, ordinaryAnchorCos: 0.35, userRejectedCos: -1.0,
                ocrBoxes: receiptBoxes, fullOcrText: "Subtotal $29.98 Sales Tax $2.40 Total $32.38 Visa Invoice"
            )
        }
        XCTAssertEqual(receiptOut.verdict, GateVerdictCode.rejected.rawValue)

        // 2. Classic 4-panel captioned meme -> ACCEPTED (1)
        let capBox = MGOcrBoxInput()
        capBox.left = 0.08; capBox.top = 0.04; capBox.right = 0.92; capBox.bottom = 0.16
        capBox.confidence = 0.99; capBox.text = "POV: WHEN YOU DEPLOY ON FRIDAY"
        let tonyProto = bridge.conceptPrototypeVector(forQuery: "tony soprano")!
        let tonyFloats: [Float] = tonyProto.withUnsafeBytes { Array($0.bindMemory(to: Float.self)) }
        let memeOut = tonyFloats.withUnsafeBufferPointer { ptr in
            bridge.evaluateStage1(
                withImageVec: ptr.baseAddress!, width: 800, height: 800, isVideo: false, durationMs: 0,
                isScreenshotHint: false, panelCount: 4, hasGutter: true, ordinaryAnchorCos: 0.15, userRejectedCos: -1.0,
                ocrBoxes: [capBox], fullOcrText: "POV: WHEN YOU DEPLOY ON FRIDAY"
            )
        }
        XCTAssertEqual(memeOut.verdict, GateVerdictCode.accepted.rawValue)
        XCTAssertGreaterThanOrEqual(memeOut.memeProbability, 0.70)

        // 3. Text-ambiguous screenshot -> UNCERTAIN_TEXT (2) with valid d1-omni prompt
        let b1 = MGOcrBoxInput(); b1.left = 0.1; b1.top = 0.35; b1.right = 0.8; b1.bottom = 0.41; b1.text = "wait did they actually ship a fax machine integration"
        let b2 = MGOcrBoxInput(); b2.left = 0.1; b2.top = 0.45; b2.right = 0.8; b2.bottom = 0.51; b2.text = "in the year of our lord 2026"
        let neutralVec = unitVec([(99, 1.0)])
        let uncertainOut = neutralVec.withUnsafeBufferPointer { ptr in
            bridge.evaluateStage1(
                withImageVec: ptr.baseAddress!, width: 1170, height: 2532, isVideo: false, durationMs: 0,
                isScreenshotHint: true, panelCount: 1, hasGutter: false, ordinaryAnchorCos: 0.10, userRejectedCos: -1.0,
                ocrBoxes: [b1, b2], fullOcrText: "wait did they actually ship a fax machine integration\nin the year of our lord 2026"
            )
        }
        XCTAssertEqual(uncertainOut.verdict, GateVerdictCode.uncertainText.rawValue)
        XCTAssertTrue(uncertainOut.d1Prompt.contains("<|startoftext|><|reserved_7|>"))
        XCTAssertTrue(uncertainOut.d1Prompt.contains("<|mask|> meme:"))
        XCTAssertTrue(uncertainOut.d1Prompt.contains("<|reserved_10|><|reserved_9|><|mask|> non_meme:"))
    }

    func testFourPanelGutterDetectorResidentCslsSearchAndNotAMemeMemory() throws {
        let ovPath = repoKnowledgePath("open_vocab_concepts.bin")
        let pvPath = repoKnowledgePath("pairwise_verifier.bin")
        let bridge = MemegetCoreBridge(openVocabPath: ovPath, verifierPath: pvPath)

        // Synthetic 320x320 2x2 grid with white gutters at 156..163
        let w = 320, h = 320
        var rgb = [UInt8](repeating: 0, count: w * h * 3)
        for y in 0..<h {
            for x in 0..<w {
                let off = (y * w + x) * 3
                if (156...163).contains(x) || (156...163).contains(y) {
                    rgb[off] = 255; rgb[off + 1] = 255; rgb[off + 2] = 255
                } else {
                    rgb[off] = UInt8(((x * 7 + y * 3) & 0x7F) + 30)
                    rgb[off + 1] = UInt8(((x * 5 + y * 11) & 0x7F) + 20)
                    rgb[off + 2] = UInt8(((x * 13 + y * 17) & 0x7F) + 40)
                }
            }
        }
        let det = bridge.detectPanels(fromRgb: Data(rgb), width: w, height: h, ocrBoxCount: 1)
        XCTAssertTrue(det.hasGutter)
        XCTAssertEqual(det.panelCount, 4)
        XCTAssertEqual(det.crops.count, 4)

        // End-to-end SQLite + 30k Int8/CSLS/MaxSim search + Cluster Propagation + Not-a-Meme Quick-Fix
        let tmpDb = FileManager.default.temporaryDirectory.appendingPathComponent("memeget_test_\(UUID().uuidString).db")
        defer { try? FileManager.default.removeItem(at: tmpDb) }
        let store = try SqliteMemeStore(databaseURL: tmpDb, bridge: bridge)

        let vSopranos1 = unitVec([(4, 0.95), (5, 0.31)])
        let vSopranosRemix = unitVec([(4, 0.94), (5, 0.33), (6, 0.04)])
        let vFalsePositive = unitVec([(40, 0.99)])

        _ = store.upsertIndexedMeme(
            uri: "file:///sopranos1.jpg", name: "sopranos1.jpg", kind: "image", modifiedAt: 1000,
            ocr: "Woke up this morning got some gabagool",
            caption: "Reference: The Sopranos. Reply use: exhausted mafia boss reaction.",
            tags: [MemeTag(label: "tony soprano", category: "character", score: 1.0, source: "manual", scoreDomain: "human", confirmation: "confirmed", provenance: "user")],
            extraTerms: "sopranos james gandolfini", thumbUri: "file:///sopranos1.jpg", imageVec: vSopranos1
        )
        let remixId = store.upsertIndexedMeme(
            uri: "file:///sopranos_remix.jpg", name: "sopranos_remix.jpg", kind: "image", modifiedAt: 2000,
            ocr: "When the PR has 184 files changed",
            tags: [], extraTerms: "", thumbUri: "file:///sopranos_remix.jpg", imageVec: vSopranosRemix
        )
        let fpId = store.upsertIndexedMeme(
            uri: "file:///parking_lot.jpg", name: "parking_lot.jpg", kind: "image", modifiedAt: 3000,
            ocr: "Level B3 Pillar 14",
            tags: [], extraTerms: "", thumbUri: "file:///parking_lot.jpg", imageVec: vFalsePositive
        )

        // Cluster propagation spreads Tony Soprano onto the visual remix without copying OCR!
        XCTAssertEqual(store.propagateClusterIdentities(), 1)
        let updatedRemix = store.meme(id: remixId)!
        XCTAssertTrue(updatedRemix.tags.contains(where: { $0.label == "tony soprano" && $0.source == "propagated" }))
        XCTAssertFalse(updatedRemix.ocr.contains("gabagool"))

        // Search finds both Sopranos memes immediately
        let hits = store.search(query: "tony soprano", queryVec: vSopranos1, limit: 10)
        XCTAssertGreaterThanOrEqual(hits.count, 2)
        XCTAssertEqual(hits.first?.meme.name, "sopranos1.jpg")

        // "Not a meme" hides false positive and records rejection vector
        XCTAssertTrue(store.markNotAMeme(id: fpId))
        XCTAssertNil(store.meme(id: fpId))
        XCTAssertEqual(store.rejectedPhotoVectors().count, 1)
    }

    func testClairicAndTwoDomainCompositeParsing() {
        let body = IosCloudAssistant.buildClairicRequestBody(stateText: "Bush 9/11 AI meme", imageBase64DataUri: "data:image/jpeg;base64,/9j/4AAQ")
        XCTAssertEqual(body["model"] as? String, "clairic")

        let sampleJson = """
        {
          "result": {
            "model": "clairic",
            "answers": {
              "ocr_lines": {"type": "list", "confidence": 0.99, "value": ["Sir, another open-weights model hit HF"]},
              "handles_and_brands": {"type": "list", "confidence": 0.92, "value": ["Cloudflare", "Anthropic"]},
              "meme_score": {"type": "estimate", "median": 95, "p10": 89, "p90": 98}
            }
          }
        }
        """.data(using: .utf8)!
        let parsed = IosCloudAssistant.parseClairicAnswers(sampleJson)
        XCTAssertNotNil(parsed)
        XCTAssertEqual(parsed?.handles, ["Cloudflare", "Anthropic"])
        XCTAssertEqual(parsed?.memeEstimate, 95.0)

        let td = TwoDomainParsed.parse("YES|scene: Jack Torrance grinning through chopped door|base_reference: The Shining, Jack Nicholson|target_topic: Monday standup Slack ping|reply_intent: unhinged intrusion|tags: heres johnny, the shining")
        XCTAssertTrue(td.baseReference.contains("The Shining"))
        XCTAssertTrue(td.targetTopic.contains("Slack"))
        XCTAssertTrue(td.tags.contains(where: { $0.label.contains("the shining") }))
        let movie = TwoDomainParsed.parse("YES|scene: confused exchange in a bar|source_type: film|source_title: Accepted|target_topic: apologizing|reply_intent: surprise|tags: confused")
        XCTAssertEqual(movie.baseReference, "Accepted")
        XCTAssertTrue(movie.tags.contains(where: { $0.label == "accepted" && $0.category == "movie" }))
        XCTAssertTrue(movie.searchTerms.contains("Accepted"))
        let unsupported = TwoDomainParsed.parse("YES|scene: office meeting|base_reference: Accepted|source_type: unknown|source_title: none|target_topic: stress")
        XCTAssertFalse(unsupported.tags.contains(where: { $0.category == "movie" }))
    }
    func testExplicitMovieSourceBecomesSearchableWithoutPhotoAccess() throws {
        let bridge = MemegetCoreBridge(openVocabPath: repoKnowledgePath("open_vocab_concepts.bin"),
                                       verifierPath: repoKnowledgePath("pairwise_verifier.bin"))
        let tmpDb = FileManager.default.temporaryDirectory.appendingPathComponent("memeget_movie_\(UUID().uuidString).db")
        defer { try? FileManager.default.removeItem(at: tmpDb) }
        let store = try SqliteMemeStore(databaseURL: tmpDb, bridge: bridge)
        let parsed = TwoDomainParsed.parse("YES|scene: friends at a party|source_type: movie|source_title: Accepted|target_topic: awkward reunion|reply_intent: disbelief|tags: party")
        let id = store.upsertIndexedMeme(
            uri: "file:///synthetic_movie.jpg", name: "synthetic_movie.jpg", kind: "image", modifiedAt: 1000,
            ocr: "not a film title", caption: parsed.combinedCaption, tags: parsed.tags,
            extraTerms: parsed.searchTerms, thumbUri: "file:///synthetic_movie.jpg", imageVec: unitVec([(1, 1)])
        )
        XCTAssertTrue(store.search(query: "accepted", queryVec: nil, limit: 10).contains(where: { $0.meme.id == id }))
        XCTAssertFalse(store.search(query: "the shining", queryVec: nil, limit: 10).contains(where: { $0.meme.id == id }))
    }


    private final class MockCloudURLProtocol: URLProtocol {
        nonisolated(unsafe) static var handler: ((URLRequest) throws -> (HTTPURLResponse, Data))?
        override class func canInit(with request: URLRequest) -> Bool { true }
        override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
        override func startLoading() {
            guard let h = Self.handler else {
                client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse))
                return
            }
            do {
                let (resp, data) = try h(request)
                client?.urlProtocol(self, didReceive: resp, cacheStoragePolicy: .notAllowed)
                client?.urlProtocol(self, didLoad: data)
                client?.urlProtocolDidFinishLoading(self)
            } catch {
                client?.urlProtocol(self, didFailWithError: error)
            }
        }
        override func stopLoading() {}
    }

    func testCloudCascadeRespectsClefNoEscalationAndConsentBoundaries() async throws {
        let ovPath = repoKnowledgePath("open_vocab_concepts.bin")
        let pvPath = repoKnowledgePath("pairwise_verifier.bin")
        let bridge = MemegetCoreBridge(openVocabPath: ovPath, verifierPath: pvPath)
        let tmpDb = FileManager.default.temporaryDirectory.appendingPathComponent("memeget_cloud_\(UUID().uuidString).db")
        defer { try? FileManager.default.removeItem(at: tmpDb) }
        let store = try SqliteMemeStore(databaseURL: tmpDb, bridge: bridge)
        let cloud = IosCloudAssistant(store: store)

        let cfgSession = URLSessionConfiguration.ephemeral
        cfgSession.protocolClasses = [MockCloudURLProtocol.self]
        let mockSession = URLSession(configuration: cfgSession)

        // Small 8x8 RGB CGImage
        var px = [UInt8](repeating: 180, count: 8 * 8 * 4)
        let cs = CGColorSpaceCreateDeviceRGB()
        let ctx = CGContext(data: &px, width: 8, height: 8, bitsPerComponent: 8, bytesPerRow: 32, space: cs, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        let cgImg = ctx.makeImage()!

        // Case A: consentTextState == false -> secret OCR & secret candidate labels MUST NOT be transmitted!
        var cA = IosCloudConfig()
        cA.enabled = true
        cA.consentVisualMedia = true
        cA.consentTextState = false
        cA.useClairicExtraction = true
        cA.cloudflareAccountId = "0123456789abcdef0123456789abcdef"
        cA.vlmEndpointUrl = "https://vlm.example.com/v1/chat/completions"
        await cloud.saveConfig(cA, cloudflareToken: nil, vlmToken: nil)

        var capturedBodies: [String] = []
        var vlmCallCount = 0
        MockCloudURLProtocol.handler = { req in
            var bodyBytes = Data()
            if let d = req.httpBody { bodyBytes = d }
            else if let stream = req.httpBodyStream {
                stream.open()
                var buf = [UInt8](repeating: 0, count: 4096)
                while stream.hasBytesAvailable {
                    let n = stream.read(&buf, maxLength: buf.count)
                    if n > 0 { bodyBytes.append(buf, count: n) } else { break }
                }
                stream.close()
            }
            capturedBodies.append(String(data: bodyBytes, encoding: .utf8) ?? "")
            let urlStr = req.url?.absoluteString ?? ""
            if urlStr.contains("vlm.example.com") {
                vlmCallCount += 1
                let reply = #"{"choices":[{"message":{"content":"YES|scene: cat coding|base_reference: Hacker Cat|target_topic: debugging|reply_intent: ship it|tags: hacker cat"}}]}"#.data(using: .utf8)!
                return (HTTPURLResponse(url: req.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!, reply)
            }
            let cfReply = #"{"result":{"answers":{"ocr_lines":{"confidence":0.9,"value":["extracted line"]},"handles_and_brands":{"confidence":0.9,"value":[]}}}}"#.data(using: .utf8)!
            return (HTTPURLResponse(url: req.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!, cfReply)
        }

        let outA = await cloud.analyzeMemeIfPermitted(
            cgImage: cgImg,
            ocrText: "TOP_SECRET_OCR_STRING",
            candidateLabels: ["TOP_SECRET_CANDIDATE"],
            urlSession: mockSession,
            overrideCloudflareToken: "cf_tok",
            overrideVlmToken: "vlm_tok"
        )
        XCTAssertNotNil(outA)
        for b in capturedBodies {
            XCTAssertFalse(b.contains("TOP_SECRET_OCR_STRING"), "OCR must never transmit without consentTextState")
            XCTAssertFalse(b.contains("TOP_SECRET_CANDIDATE"), "Candidate labels must never transmit without consentTextState")
        }

        // Case B: consentTextState == true & Clef verifies candidate using exact documented Clef schema -> VLM NOT invoked!
        var cB = cA
        cB.consentTextState = true
        await cloud.saveConfig(cB, cloudflareToken: nil, vlmToken: nil)
        vlmCallCount = 0
        var clefSchemaValidated = false
        MockCloudURLProtocol.handler = { req in
            var bodyBytes = Data()
            if let d = req.httpBody { bodyBytes = d }
            else if let stream = req.httpBodyStream {
                stream.open()
                var buf = [UInt8](repeating: 0, count: 4096)
                while stream.hasBytesAvailable {
                    let n = stream.read(&buf, maxLength: buf.count)
                    if n > 0 { bodyBytes.append(buf, count: n) } else { break }
                }
                stream.close()
            }
            let urlStr = req.url?.absoluteString ?? ""
            if urlStr.contains("vlm.example.com") {
                vlmCallCount += 1
                return (HTTPURLResponse(url: req.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!, Data("{}".utf8))
            }
            if urlStr.hasSuffix("/@cf/cloudflare/clef") {
                guard let json = try? JSONSerialization.jsonObject(with: bodyBytes) as? [String: Any],
                      let model = json["model"] as? String, ["clef", "clef-flash"].contains(model),
                      json["state"] is String,
                      json["fields"] == nil,
                      let questions = json["questions"] as? [String: Any],
                      let vr = questions["verified_reference"] as? [String: Any],
                      (vr["type"] as? String) == "choice",
                      let criteria = vr["criteria"] as? [String: String],
                      criteria["candidate_0"] == "the shining",
                      criteria["unknown"] != nil else {
                    return (HTTPURLResponse(url: req.url!, statusCode: 400, httpVersion: nil, headerFields: nil)!, Data(#"{"error":"invalid clef schema"}"#.utf8))
                }
                clefSchemaValidated = true
                let clefOk = #"{"result":{"answers":{"verified_reference":{"choice":"candidate_0","probabilities":{"candidate_0":0.95,"unknown":0.05}}}}}"#.data(using: .utf8)!
                return (HTTPURLResponse(url: req.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!, clefOk)
            }
            let cfReply = #"{"result":{"answers":{"ocr_lines":{"confidence":0.9,"value":["heres johnny"]},"handles_and_brands":{"confidence":0.9,"value":[]}}}}"#.data(using: .utf8)!
            return (HTTPURLResponse(url: req.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!, cfReply)
        }

        let outB = await cloud.analyzeMemeIfPermitted(
            cgImage: cgImg,
            ocrText: "heres johnny",
            candidateLabels: ["the shining"],
            urlSession: mockSession,
            overrideCloudflareToken: "cf_tok",
            overrideVlmToken: "vlm_tok"
        )
        XCTAssertTrue(clefSchemaValidated, "Clef request must match documented model/state/questions/criteria schema")
        XCTAssertNotNil(outB)
        XCTAssertEqual(vlmCallCount, 0, "Open-world VLM must NOT be called when Clef verifies a candidate")
        XCTAssertTrue(outB!.tags.contains(where: { $0.label == "the shining" && $0.provenance == "cloud:clef-flash" }))

        let mid = store.upsertIndexedMeme(uri: "file:///shining.jpg", name: "shining.jpg", kind: "image", modifiedAt: 1, ocr: "", tags: [], extraTerms: "", thumbUri: "file:///shining.jpg", imageVec: unitVec([(2, 1.0)]))
        store.applyCloudEnrichment(id: mid, caption: outB!.caption, terms: outB!.terms, tags: outB!.tags)
        XCTAssertEqual(store.search(query: "the shining", queryVec: nil).count, 1)

        // Case C: Consent revoked while Clairic is in flight -> neither Clef nor VLM executes and result is nil!
        var clefCallsAfterRevoke = 0
        var vlmCallsAfterRevoke = 0
        MockCloudURLProtocol.handler = { req in
            let urlStr = req.url?.absoluteString ?? ""
            if urlStr.contains("/clairic") {
                let sem = DispatchSemaphore(value: 0)
                Task {
                    var revoked = cB
                    revoked.consentVisualMedia = false
                    revoked.enabled = false
                    await cloud.saveConfig(revoked, cloudflareToken: nil, vlmToken: nil)
                    sem.signal()
                }
                sem.wait()
                let cfReply = #"{"result":{"answers":{"ocr_lines":{"confidence":0.9,"value":["revoked"]},"handles_and_brands":{"confidence":0.9,"value":[]}}}}"#.data(using: .utf8)!
                return (HTTPURLResponse(url: req.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!, cfReply)
            }
            if urlStr.contains("/clef") { clefCallsAfterRevoke += 1 }
            if urlStr.contains("vlm.example.com") { vlmCallsAfterRevoke += 1 }
            return (HTTPURLResponse(url: req.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!, Data("{}".utf8))
        }
        let outC = await cloud.analyzeMemeIfPermitted(
            cgImage: cgImg,
            ocrText: "heres johnny",
            candidateLabels: ["the shining"],
            urlSession: mockSession,
            overrideCloudflareToken: "cf_tok",
            overrideVlmToken: "vlm_tok"
        )
        XCTAssertNil(outC, "Revoking consent mid-cascade must drop enrichment result")
        XCTAssertEqual(clefCallsAfterRevoke, 0, "Clef must not be called after consent revocation")
        XCTAssertEqual(vlmCallsAfterRevoke, 0, "VLM must not be called after consent revocation")
    }

    func testBoundedRaBitQClusterPropagationScalesOnSyntheticLargeLibrary() throws {
        let ovPath = repoKnowledgePath("open_vocab_concepts.bin")
        let pvPath = repoKnowledgePath("pairwise_verifier.bin")
        let bridge = MemegetCoreBridge(openVocabPath: ovPath, verifierPath: pvPath)
        let tmpDb = FileManager.default.temporaryDirectory.appendingPathComponent("memeget_scale_\(UUID().uuidString).db")
        defer { try? FileManager.default.removeItem(at: tmpDb) }
        let store = try SqliteMemeStore(databaseURL: tmpDb, bridge: bridge)

        let seedVec = unitVec([(0, 0.96), (1, 0.28)])
        for i in 0..<700 {
            let vec = (i >= 695) ? seedVec : unitVec([((i % 60) + 2, 0.91), (((i + 11) % 60) + 2, 0.41)])
            let tags: [MemeTag] = (i == 699)
                ? [MemeTag(label: "tony soprano", category: "character", score: 1.0, source: "manual", scoreDomain: "human", confirmation: "confirmed", provenance: "user")]
                : ((i < 550) ? [MemeTag(label: "seed_\(i)", category: "character", score: 0.90, source: "open_vocab", scoreDomain: "open-vocab", confirmation: "unreviewed", provenance: "open-vocab")] : [])
            _ = store.upsertIndexedMeme(
                uri: "file:///scale_\(i).jpg", name: "scale_\(i).jpg", kind: "image", modifiedAt: Int64(i),
                ocr: "", tags: tags, extraTerms: "", thumbUri: "file:///scale_\(i).jpg", imageVec: vec
            )
        }
        let t0 = CFAbsoluteTimeGetCurrent()
        let propagated = store.propagateClusterIdentities(maxSeeds: 256, maxTargets: 512)
        let elapsed = CFAbsoluteTimeGetCurrent() - t0
        XCTAssertGreaterThanOrEqual(propagated, 4)
        XCTAssertLessThan(elapsed, 1.5, "Bounded RaBitQ propagation must complete rapidly (<1.5s)")
    }
}
