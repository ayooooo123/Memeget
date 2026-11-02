import CommonCrypto
import Foundation
import XCTest
@testable import Memeget

// End-to-end iOS XCTest suite verifying uBit multi-platform video extraction, HLS AES-128 MPEG-TS demuxing,
// fast-start two-pass co64 MP4 muxing, shortlink/cookie propagation, and MemeLibraryCoordinator link intake.

private final class MockUbitURLProtocol: URLProtocol {
    nonisolated(unsafe) static var handler: ((URLRequest) throws -> (HTTPURLResponse, Data))?

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let handler = Self.handler else {
            client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse))
            return
        }
        do {
            let (response, data) = try handler(request)
            if (300..<400).contains(response.statusCode),
               let loc = response.value(forHTTPHeaderField: "Location"),
               let base = request.url,
               let redirectURL = URL(string: loc, relativeTo: base)?.absoluteURL {
                var nextReq = URLRequest(url: redirectURL)
                nextReq.httpMethod = "GET"
                client?.urlProtocol(self, wasRedirectedTo: nextReq, redirectResponse: response)
                return
            }
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }

    override func stopLoading() {}
}

final class UbitVideoDownloadTests: XCTestCase {

    private func mockSessionConfig() -> URLSessionConfiguration {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.protocolClasses = [MockUbitURLProtocol.self]
        return cfg
    }

    private func buildSyntheticMP4(dir: URL, name: String, kind: MP4TrackKind, payloadA: Data, payloadB: Data) throws -> Data {
        var stsd = BinaryBuffer(capacity: 128)
        stsd.u8(0); stsd.u24(0); stsd.u32(1)
        if kind == .video {
            stsd.box("avc1") { b in
                b.zeros(6); b.u16(1); b.zeros(16); b.u16(640); b.u16(360)
                b.u32(0x00480000); b.u32(0x00480000); b.u32(0); b.u16(1); b.zeros(32); b.u16(0x0018); b.u16(0xffff)
            }
        } else {
            stsd.box("mp4a") { b in
                b.zeros(6); b.u16(1); b.zeros(8); b.u16(2); b.u16(16); b.u32(0); b.u32(44100 << 16)
            }
        }
        let track = MP4Track(
            trackID: 1,
            kind: kind,
            timescale: kind == .video ? 30_000 : 44_100,
            width: kind == .video ? 640 : 0,
            height: kind == .video ? 360 : 0,
            stsdPayload: stsd.data,
            samples: [
                MP4Sample(size: UInt32(payloadA.count), duration: 1000, isSync: true, inlineData: payloadA),
                MP4Sample(size: UInt32(payloadB.count), duration: 1000, isSync: kind != .video, inlineData: payloadB),
            ]
        )
        let out = dir.appendingPathComponent(name)
        try MP4Muxer.writeMP4(tracks: [track], sourceFiles: [], destination: out, isAudioOnlyM4A: kind == .audio)
        return try Data(contentsOf: out)
    }

    private func makeTSPacket(pid: UInt16, pusi: Bool, payload: Data) -> Data {
        var pkt = [UInt8](repeating: 0xff, count: 188)
        pkt[0] = 0x47
        pkt[1] = (pusi ? 0x40 : 0x00) | UInt8((pid >> 8) & 0x1f)
        pkt[2] = UInt8(pid & 0xff)
        if payload.count == 184 {
            pkt[3] = 0x10
            pkt.replaceSubrange(4..<188, with: payload)
        } else {
            let stuffLen = 183 - payload.count
            pkt[3] = 0x30
            pkt[4] = UInt8(stuffLen & 0xff)
            if stuffLen > 0 { pkt[5] = 0x00 }
            pkt.replaceSubrange((5 + stuffLen)..<188, with: payload)
        }
        return Data(pkt)
    }

    private func wrapPES(streamId: UInt8, pts90k: UInt64, es: Data) -> Data {
        var b = BinaryBuffer(capacity: 14 + es.count)
        b.u8(0x00); b.u8(0x00); b.u8(0x01)
        b.u8(streamId)
        b.u16(0)
        b.u8(0x80); b.u8(0x80); b.u8(5)
        b.u8(0x20 | UInt8(((pts90k >> 30) & 0x07) << 1) | 1)
        b.u8(UInt8((pts90k >> 22) & 0xff))
        b.u8(UInt8(((pts90k >> 15) & 0x7f) << 1) | 1)
        b.u8(UInt8((pts90k >> 7) & 0xff))
        b.u8(UInt8((pts90k & 0x7f) << 1) | 1)
        b.bytes(es)
        return b.data
    }

    private func buildSyntheticVideoTS() -> Data {
        var pat = BinaryBuffer(capacity: 32)
        pat.u8(0); pat.u8(0x00); pat.u16(0xb00d); pat.u16(1); pat.u8(0xc1); pat.u8(0); pat.u8(0)
        pat.u16(1); pat.u16(0xe100); pat.u32(0)

        var pmt = BinaryBuffer(capacity: 32)
        pmt.u8(0); pmt.u8(0x02); pmt.u16(0xb012); pmt.u16(1); pmt.u8(0xc1); pmt.u8(0); pmt.u8(0)
        pmt.u16(0xe101); pmt.u16(0xf000)
        pmt.u8(0x1b); pmt.u16(0xe101); pmt.u16(0xf000); pmt.u32(0)

        let sps = Data([0x67, 0x42, 0xc0, 0x1e, 0xd9, 0x01, 0x41, 0xfb, 0x01, 0x10, 0x00, 0x00, 0x03, 0x00, 0x10, 0x00, 0x00, 0x03, 0x03, 0xc0, 0xf1, 0x62, 0xe4, 0x80])
        let pps = Data([0x68, 0xce, 0x3c, 0x80])
        let idr = Data([0x65, 0x88, 0x84, 0x21, 0xa0, 0x55])
        var annexB = BinaryBuffer(capacity: 96)
        annexB.u32(1); annexB.bytes(sps)
        annexB.u32(1); annexB.bytes(pps)
        annexB.u32(1); annexB.bytes(idr)

        var out = Data()
        out.append(makeTSPacket(pid: 0, pusi: true, payload: pat.data))
        out.append(makeTSPacket(pid: 0x0100, pusi: true, payload: pmt.data))
        out.append(makeTSPacket(pid: 0x0101, pusi: true, payload: wrapPES(streamId: 0xe0, pts90k: 9000, es: annexB.data)))
        out.append(makeTSPacket(pid: 0x0101, pusi: false, payload: Data(repeating: 0, count: 184)))
        return out
    }

    private func buildSyntheticAudioTS() -> Data {
        var pat = BinaryBuffer(capacity: 32)
        pat.u8(0); pat.u8(0x00); pat.u16(0xb00d); pat.u16(1); pat.u8(0xc1); pat.u8(0); pat.u8(0)
        pat.u16(1); pat.u16(0xe100); pat.u32(0)

        var pmt = BinaryBuffer(capacity: 32)
        pmt.u8(0); pmt.u8(0x02); pmt.u16(0xb012); pmt.u16(1); pmt.u8(0xc1); pmt.u8(0); pmt.u8(0)
        pmt.u16(0xe102); pmt.u16(0xf000)
        pmt.u8(0x0f); pmt.u16(0xe102); pmt.u16(0xf000); pmt.u32(0)

        let rawAAC = Data([0x21, 0x10, 0x04, 0x60, 0x8c, 0x1c, 0x55, 0xaa])
        let flen = 7 + rawAAC.count
        var adts = Data(count: 7)
        adts[0] = 0xff; adts[1] = 0xf1
        adts[2] = UInt8((1 << 6) | (4 << 2))
        adts[3] = UInt8((2 << 6) | ((flen >> 11) & 0x03))
        adts[4] = UInt8((flen >> 3) & 0xff)
        adts[5] = UInt8(((flen & 0x07) << 5) | 0x1f)
        adts[6] = 0xfc
        adts.append(rawAAC)

        var out = Data()
        out.append(makeTSPacket(pid: 0, pusi: true, payload: pat.data))
        out.append(makeTSPacket(pid: 0x0100, pusi: true, payload: pmt.data))
        out.append(makeTSPacket(pid: 0x0102, pusi: true, payload: wrapPES(streamId: 0xc0, pts90k: 9000, es: adts)))
        out.append(makeTSPacket(pid: 0x0102, pusi: false, payload: Data(repeating: 0, count: 184)))
        return out
    }

    private func serveRange(request: URLRequest, url: URL, bytes: Data, contentType: String) -> (HTTPURLResponse, Data) {
        if let range = request.value(forHTTPHeaderField: "Range"), range.hasPrefix("bytes=") {
            let parts = range.dropFirst("bytes=".count).split(separator: "-", omittingEmptySubsequences: false)
            let s = Int(parts[0]) ?? 0
            let e = (parts.count > 1 && !parts[1].isEmpty) ? min(bytes.count - 1, Int(parts[1]) ?? (bytes.count - 1)) : (bytes.count - 1)
            if s >= 0 && s < bytes.count && e >= s {
                let slice = Data(bytes[s...e])
                let resp = HTTPURLResponse(
                    url: url,
                    statusCode: 206,
                    httpVersion: "HTTP/1.1",
                    headerFields: [
                        "Content-Type": contentType,
                        "Accept-Ranges": "bytes",
                        "Content-Range": "bytes \(s)-\(e)/\(bytes.count)"
                    ]
                )!
                return (resp, slice)
            }
        }
        let resp = HTTPURLResponse(
            url: url,
            statusCode: 200,
            httpVersion: "HTTP/1.1",
            headerFields: ["Content-Type": contentType, "Content-Length": "\(bytes.count)"]
        )!
        return (resp, bytes)
    }

    func testYouTubeAdaptiveVideoAndAudioMuxIntoFastStartMP4() async throws {
        let tmpDir = FileManager.default.temporaryDirectory.appendingPathComponent("ubit_ios_yt_\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tmpDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tmpDir) }

        let vSample1 = Data("IOS_YT_VID_SAMPLE_1".utf8)
        let vSample2 = Data("IOS_YT_VID_SAMPLE_2".utf8)
        let aSample1 = Data("IOS_YT_AUD_SAMPLE_1".utf8)
        let aSample2 = Data("IOS_YT_AUD_SAMPLE_2".utf8)

        let vMP4 = try buildSyntheticMP4(dir: tmpDir, name: "src_v.mp4", kind: .video, payloadA: vSample1, payloadB: vSample2)
        let aM4A = try buildSyntheticMP4(dir: tmpDir, name: "src_a.m4a", kind: .audio, payloadA: aSample1, payloadB: aSample2)

        MockUbitURLProtocol.handler = { req in
            let u = req.url!
            switch u.path {
            case "/watch":
                let html = #"<html><script>var ytcfg = {"VISITOR_DATA":"CgtIosVisitor99"};</script></html>"#
                return (HTTPURLResponse(url: u, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: nil)!, Data(html.utf8))
            case "/youtubei/v1/player":
                let json = """
                {
                  "playabilityStatus": {"status": "OK"},
                  "videoDetails": {
                    "videoId": "aqz-KE-bpKQ",
                    "title": "Sopranos Reaction Clip",
                    "author": "HBO",
                    "lengthSeconds": "9",
                    "isLiveContent": false
                  },
                  "streamingData": {
                    "adaptiveFormats": [
                      {
                        "itag": 137,
                        "url": "https://cdn.example.test/yt/v1080.mp4",
                        "mimeType": "video/mp4; codecs=\\"avc1.640028\\"",
                        "width": 1920,
                        "height": 1080,
                        "fps": 30,
                        "bitrate": 2400000,
                        "contentLength": "\(vMP4.count)"
                      },
                      {
                        "itag": 140,
                        "url": "https://cdn.example.test/yt/a128.m4a",
                        "mimeType": "audio/mp4; codecs=\\"mp4a.40.2\\"",
                        "bitrate": 128000,
                        "contentLength": "\(aM4A.count)"
                      }
                    ]
                  }
                }
                """
                return (HTTPURLResponse(url: u, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/json"])!, Data(json.utf8))
            case "/yt/v1080.mp4":
                return self.serveRange(request: req, url: u, bytes: vMP4, contentType: "video/mp4")
            case "/yt/a128.m4a":
                return self.serveRange(request: req, url: u, bytes: aM4A, contentType: "audio/mp4")
            default:
                return (HTTPURLResponse(url: u, statusCode: 404, httpVersion: "HTTP/1.1", headerFields: nil)!, Data())
            }
        }
        defer { MockUbitURLProtocol.handler = nil }

        let outDir = tmpDir.appendingPathComponent("downloads", isDirectory: true)
        let item = try await UbitLinkDownloader.download(
            rawInput: "Check this clip https://youtu.be/aqz-KE-bpKQ!",
            outputDirectory: outDir,
            sessionConfiguration: mockSessionConfig()
        )

        XCTAssertEqual(item.fileName, "youtube_aqz-KE-bpKQ.mp4")
        XCTAssertEqual(item.kind, "video")
        XCTAssertEqual(item.site, "YouTube")

        let muxedData = try Data(contentsOf: item.fileURL)
        let topBoxes = MP4Muxer.listBoxes(in: muxedData, from: 0, to: muxedData.count).map(\.type)
        XCTAssertEqual(topBoxes, ["ftyp", "moov", "mdat"])

        let tracks = try MP4Muxer.readTracks(from: item.fileURL)
        XCTAssertEqual(tracks.count, 2)
        XCTAssertEqual(tracks[0].kind, .video)
        XCTAssertEqual(tracks[1].kind, .audio)
        let s0 = tracks[0].samples[0]
        XCTAssertEqual(Data(muxedData[Int(s0.offset)..<(Int(s0.offset) + Int(s0.size))]), vSample1)
        let a1 = tracks[1].samples[1]
        XCTAssertEqual(Data(muxedData[Int(a1.offset)..<(Int(a1.offset) + Int(a1.size))]), aSample2)
    }

    func testRedditSeparateAudioAES128HLSDemuxesAndTikTokShortlinkCookiesPropagate() async throws {
        let tmpDir = FileManager.default.temporaryDirectory.appendingPathComponent("ubit_ios_hls_\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tmpDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tmpDir) }

        let aesKey = Data((0..<16).map { UInt8(0x21 + $0) })
        let aesIV = Data((0..<16).map { UInt8(0xa0 - $0) })
        let ivHex = aesIV.map { String(format: "%02x", $0) }.joined()

        let rawVideoTS = buildSyntheticVideoTS()
        let rawAudioTS = buildSyntheticAudioTS()

        // Encrypt rawVideoTS with AES-128-CBC PKCS7
        let encCap = rawVideoTS.count + kCCBlockSizeAES128
        var encVideoTS = Data(count: encCap)
        var encLen: size_t = 0
        let ccStatus = encVideoTS.withUnsafeMutableBytes { outPtr in
            rawVideoTS.withUnsafeBytes { inPtr in
                aesKey.withUnsafeBytes { keyPtr in
                    aesIV.withUnsafeBytes { ivPtr in
                        CCCrypt(
                            CCOperation(kCCEncrypt),
                            CCAlgorithm(kCCAlgorithmAES),
                            CCOptions(kCCOptionPKCS7Padding),
                            keyPtr.baseAddress, kCCKeySizeAES128,
                            ivPtr.baseAddress,
                            inPtr.baseAddress, rawVideoTS.count,
                            outPtr.baseAddress, encCap,
                            &encLen
                        )
                    }
                }
            }
        }
        XCTAssertEqual(Int(ccStatus), kCCSuccess)
        encVideoTS.count = encLen

        let tiktokMP4 = try buildSyntheticMP4(dir: tmpDir, name: "tt.mp4", kind: .video, payloadA: Data("TT1".utf8), payloadB: Data("TT2".utf8))

        MockUbitURLProtocol.handler = { req in
            let u = req.url!
            switch (u.host ?? "", u.path) {
            case ("www.reddit.com", "/r/memes/comments/r123/cooked"):
                return (HTTPURLResponse(url: u, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: nil)!, Data("<html></html>".utf8))
            case ("www.reddit.com", "/r/memes/comments/r123/cooked.json"):
                let json = """
                [{"data":{"children":[{"data":{
                  "id":"r123","title":"Cooked Clip","author":"op",
                  "secure_media":{"reddit_video":{"duration":4,"hls_url":"https://v.redd.it/r123/master.m3u8"}}
                }}]}}]
                """
                return (HTTPURLResponse(url: u, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/json"])!, Data(json.utf8))
            case ("v.redd.it", "/r123/master.m3u8"):
                let m3u8 = """
                #EXTM3U
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",DEFAULT=YES,URI="https://v.redd.it/r123/audio.m3u8"
                #EXT-X-STREAM-INF:BANDWIDTH=1500000,RESOLUTION=320x240,CODECS="avc1.42c01e,mp4a.40.2",AUDIO="aud"
                video.m3u8
                """
                return (HTTPURLResponse(url: u, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: nil)!, Data(m3u8.utf8))
            case ("v.redd.it", "/r123/video.m3u8"):
                let m3u8 = """
                #EXTM3U
                #EXT-X-TARGETDURATION:4
                #EXT-X-KEY:METHOD=AES-128,URI="https://v.redd.it/r123/key.bin",IV=0x\(ivHex)
                #EXTINF:4.0,
                seg_v.ts
                #EXT-X-ENDLIST
                """
                return (HTTPURLResponse(url: u, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: nil)!, Data(m3u8.utf8))
            case ("v.redd.it", "/r123/audio.m3u8"):
                let m3u8 = """
                #EXTM3U
                #EXT-X-TARGETDURATION:4
                #EXTINF:4.0,
                seg_a.ts
                #EXT-X-ENDLIST
                """
                return (HTTPURLResponse(url: u, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: nil)!, Data(m3u8.utf8))
            case ("v.redd.it", "/r123/key.bin"):
                return (HTTPURLResponse(url: u, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: nil)!, aesKey)
            case ("v.redd.it", "/r123/seg_v.ts"):
                return (HTTPURLResponse(url: u, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: nil)!, encVideoTS)
            case ("v.redd.it", "/r123/seg_a.ts"):
                return (HTTPURLResponse(url: u, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: nil)!, rawAudioTS)

            case ("vm.tiktok.com", "/ZMrIosShort"), ("vm.tiktok.com", "/ZMrIosShort/"):
                if req.httpMethod == "HEAD" {
                    return (HTTPURLResponse(url: u, statusCode: 405, httpVersion: "HTTP/1.1", headerFields: nil)!, Data())
                }
                return (HTTPURLResponse(url: u, statusCode: 302, httpVersion: "HTTP/1.1", headerFields: ["Location": "https://www.tiktok.com/@pepe/video/7499999999999999999"])!, Data())
            case ("www.tiktok.com", "/@pepe/video/7499999999999999999"):
                let html = """
                <html><script id="__UNIVERSAL_DATA_FOR_REHYDRATION__" type="application/json">
                {"__DEFAULT_SCOPE__":{"webapp.video-detail":{"itemInfo":{"itemStruct":{
                  "desc":"pepe vibe check",
                  "author":{"uniqueId":"pepe"},
                  "video":{"width":576,"height":1024,"duration":6,"playAddr":"https://v16m.tiktokcdn-us.com/cdn/vibe.mp4"}
                }}}}}
                </script></html>
                """
                return (
                    HTTPURLResponse(url: u, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: ["Set-Cookie": "tt_chain_token=ios_secret_777; Path=/"])!,
                    Data(html.utf8)
                )
            case ("v16m.tiktokcdn-us.com", "/cdn/vibe.mp4"):
                let ck = req.value(forHTTPHeaderField: "Cookie") ?? ""
                let ref = req.value(forHTTPHeaderField: "Referer") ?? ""
                guard ck.contains("tt_chain_token=ios_secret_777"), ref.contains("tiktok.com") else {
                    return (HTTPURLResponse(url: u, statusCode: 403, httpVersion: "HTTP/1.1", headerFields: nil)!, Data())
                }
                return self.serveRange(request: req, url: u, bytes: tiktokMP4, contentType: "video/mp4")
            default:
                return (HTTPURLResponse(url: u, statusCode: 404, httpVersion: "HTTP/1.1", headerFields: nil)!, Data())
            }
        }
        defer { MockUbitURLProtocol.handler = nil }

        let redditOut = try await UbitLinkDownloader.download(
            rawInput: "https://www.reddit.com/r/memes/comments/r123/cooked/",
            outputDirectory: tmpDir.appendingPathComponent("reddit"),
            sessionConfiguration: mockSessionConfig()
        )
        XCTAssertEqual(redditOut.fileName, "reddit_r123.mp4")
        let redditTracks = try MP4Muxer.readTracks(from: redditOut.fileURL)
        XCTAssertEqual(redditTracks.count, 2)
        XCTAssertEqual(redditTracks[0].kind, .video)
        XCTAssertEqual(redditTracks[1].kind, .audio)

        let tiktokOut = try await UbitLinkDownloader.download(
            rawInput: "https://vm.tiktok.com/ZMrIosShort/",
            outputDirectory: tmpDir.appendingPathComponent("tiktok"),
            sessionConfiguration: mockSessionConfig()
        )
        XCTAssertEqual(tiktokOut.fileName, "tiktok_7499999999999999999.mp4")
        XCTAssertEqual(try Data(contentsOf: tiktokOut.fileURL), tiktokMP4)
    }

    @MainActor
    func testCoordinatorImportsSharedVideoLinkDeduplicatesByHashAndSearchesImmediately() async throws {
        let tmpDir = FileManager.default.temporaryDirectory.appendingPathComponent("ubit_coord_\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tmpDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tmpDir) }

        let clipBytes = try buildSyntheticMP4(dir: tmpDir, name: "clip.mp4", kind: .video, payloadA: Data("TW_1".utf8), payloadB: Data("TW_2".utf8))

        MockUbitURLProtocol.handler = { req in
            let u = req.url!
            switch (u.host ?? "", u.path) {
            case ("gql.twitch.tv", "/gql"):
                let json = #"[{"data":{"clip":{"title":"Absolute Cinema Scorsese","durationSeconds":11,"broadcaster":{"displayName":"KinoGuy"},"playbackAccessToken":{"signature":"s1","value":"{\"ok\":true}"},"videoQualities":[{"quality":"1080","frameRate":60,"sourceURL":"https://clips-media.twitch.tv/kino.mp4"}]}}}]"#
                return (HTTPURLResponse(url: u, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/json"])!, Data(json.utf8))
            case ("clips-media.twitch.tv", "/kino.mp4"):
                return self.serveRange(request: req, url: u, bytes: clipBytes, contentType: "video/mp4")
            default:
                return (HTTPURLResponse(url: u, statusCode: 404, httpVersion: "HTTP/1.1", headerFields: nil)!, Data())
            }
        }
        defer { MockUbitURLProtocol.handler = nil }

        let dbURL = tmpDir.appendingPathComponent("test.db")
        // Fail fast on offline model fetches during unit test so coordinator exercises immediate lexical/metadata indexing
        let noNetDownloader = ModelDownloadManager(modelsDir: tmpDir.appendingPathComponent("models"))
        let engine = IosInferenceEngine(downloader: noNetDownloader)
        let store = try SqliteMemeStore(databaseURL: dbURL, bridge: engine.bridge)
        let cloud = IosCloudAssistant(store: store)
        let coordinator = MemeLibraryCoordinator(store: store, engine: engine, cloud: cloud)

        let importsDir = tmpDir.appendingPathComponent("imports", isDirectory: true)
        let firstId = try await coordinator.importSharedLink(
            "https://clips.twitch.tv/CinemaSlug99",
            outputDirectory: importsDir,
            sessionConfiguration: mockSessionConfig()
        )
        XCTAssertGreaterThan(firstId, 0)
        XCTAssertEqual(store.countMemes(), 1)

        // Immediately searchable by extracted title & platform without waiting for heavy model weights
        let hits = store.search(query: "scorsese twitch", queryVec: nil)
        XCTAssertEqual(hits.first?.meme.id, firstId)
        XCTAssertEqual(hits.first?.meme.kind, "video")

        // Sharing the exact same clip link again deduplicates via SHA-256 contentHash
        let secondId = try await coordinator.importSharedLink(
            "https://clips.twitch.tv/CinemaSlug99",
            outputDirectory: importsDir,
            sessionConfiguration: mockSessionConfig()
        )
        XCTAssertEqual(secondId, firstId)
        XCTAssertEqual(store.countMemes(), 1)
    }
}
