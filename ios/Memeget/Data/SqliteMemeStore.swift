import Accelerate
import Foundation
import SQLite3
private let SQLITE_TRANSIENT_SWIFT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

// Native SQLite3 FTS4 + 30,000-item Int8/RaBitQ/CSLS/MaxSim Resident Search Store for iOS.
// Mirrors Android's SqliteLibraryStore.kt schema and ranking calibration byte-for-byte.
public final class SqliteMemeStore: @unchecked Sendable {
    public static let maxResidentMemes = 30_000

    private var db: OpaquePointer?
    private let lock = NSRecursiveLock()
    private let bridge: MemegetCoreBridge

    private struct ResidentEntry {
        var meme: MemeItem
        let tagWords: Set<String>
        let humanTagWords: Set<String>
        let quoteWords: Set<String>
        let captionWords: Set<String>
        let docWords: Set<String>
        let normalizedTags: Set<String>
        let phraseDoc: String
        let imageInt8: [Int8]
        let imageRabit64: [UInt64]
        let hasImage: Bool
        let cslsHubness: Float
        let scenesInt8: [Int8]
        let sceneCount: Int
        let captionInt8: [Int8]?
    }

    private struct PackedResident {
        var entries: [ResidentEntry]
        var docFreq: [String: Int]
        var tagLabels: [String]
        var imageMatInt8: [Int8]
        var imageRabit64: [UInt64]
        var hasImage: [UInt8]
        var imgCslsHubness: [Float]
        var scenePoolInt8: [Int8]
        var sceneOffsets: [Int32]
        var captionMatInt8: [Int8]?
        var hasCaption: [UInt8]
        var capHubness: [Float]
    }

    private var cachedResident: PackedResident?

    public init(databaseURL: URL? = nil, bridge: MemegetCoreBridge) throws {
        self.bridge = bridge
        let targetURL: URL
        if let custom = databaseURL {
            targetURL = custom
        } else {
            let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            targetURL = dir.appendingPathComponent("memeget_ios.db")
        }

        if sqlite3_open_v2(targetURL.path, &db, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX, nil) != SQLITE_OK {
            throw NSError(domain: "com.memeget.sqlite", code: 1, userInfo: [NSLocalizedDescriptionKey: "Failed opening SQLite database"])
        }
        try setupSchema()
    }

    deinit {
        if let ptr = db {
            sqlite3_close(ptr)
        }
    }

    private func setupSchema() throws {
        let ddl = """
        PRAGMA journal_mode = WAL;
        CREATE TABLE IF NOT EXISTS memes (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            uri TEXT UNIQUE NOT NULL,
            name TEXT NOT NULL,
            kind TEXT NOT NULL,
            ocr_text TEXT NOT NULL DEFAULT '',
            caption TEXT NOT NULL DEFAULT '',
            transcript TEXT NOT NULL DEFAULT '',
            tags TEXT NOT NULL DEFAULT '[]',
            extra_terms TEXT NOT NULL DEFAULT '',
            thumb_uri TEXT NOT NULL DEFAULT '',
            indexed_at INTEGER NOT NULL DEFAULT 0,
            modified_at INTEGER NOT NULL DEFAULT 0,
            pending INTEGER NOT NULL DEFAULT 0,
            vision_state TEXT NOT NULL DEFAULT 'pending',
            content_hash TEXT NOT NULL DEFAULT '',
            cloud_caption TEXT NOT NULL DEFAULT '',
            cloud_terms TEXT NOT NULL DEFAULT '',
            rejected_labels TEXT NOT NULL DEFAULT '[]',
            copy_count INTEGER NOT NULL DEFAULT 0,
            last_copied_at INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE IF NOT EXISTS vectors (
            id INTEGER PRIMARY KEY,
            image BLOB NOT NULL,
            caption BLOB,
            scenes BLOB,
            model TEXT NOT NULL DEFAULT ''
        );
        CREATE TABLE IF NOT EXISTS camera_roll_gate (
            source_key TEXT PRIMARY KEY,
            uri TEXT NOT NULL,
            name TEXT NOT NULL,
            kind TEXT NOT NULL,
            modified_at INTEGER NOT NULL DEFAULT 0,
            width INTEGER NOT NULL DEFAULT 0,
            height INTEGER NOT NULL DEFAULT 0,
            duration_ms INTEGER NOT NULL DEFAULT 0,
            verdict INTEGER NOT NULL DEFAULT 0,
            meme_prob REAL NOT NULL DEFAULT 0,
            d1_prompt TEXT NOT NULL DEFAULT '',
            ocr_text TEXT NOT NULL DEFAULT '',
            candidate_hints TEXT NOT NULL DEFAULT '',
            image_vec BLOB,
            scene_vecs BLOB,
            meme_id INTEGER NOT NULL DEFAULT 0,
            updated_at INTEGER NOT NULL DEFAULT 0
        );
        CREATE INDEX IF NOT EXISTS idx_cr_verdict ON camera_roll_gate(verdict, modified_at DESC);
        CREATE TABLE IF NOT EXISTS rejected_photo_vectors (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            source_key TEXT UNIQUE NOT NULL,
            vector BLOB NOT NULL,
            created_at INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS settings (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        );
        CREATE VIRTUAL TABLE IF NOT EXISTS meme_search_fts USING fts4(
            name, ocr, caption, transcript, tags, extra_terms, tokenize=unicode61
        );
        """
        var errMsg: UnsafeMutablePointer<CChar>?
        if sqlite3_exec(db, ddl, nil, nil, &errMsg) != SQLITE_OK {
            let msg = errMsg.map { String(cString: $0) } ?? "DDL failed"
            sqlite3_free(errMsg)
            throw NSError(domain: "com.memeget.sqlite", code: 2, userInfo: [NSLocalizedDescriptionKey: msg])
        }
    }

    public func countMemes() -> Int {
        lock.lock()
        defer { lock.unlock() }
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(db, "SELECT COUNT(*) FROM memes WHERE pending = 0;", -1, &stmt, nil) == SQLITE_OK,
              sqlite3_step(stmt) == SQLITE_ROW else { return 0 }
        return Int(sqlite3_column_int64(stmt, 0))
    }

    public func setting(_ key: String) -> String? {
        lock.lock()
        defer { lock.unlock() }
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(db, "SELECT value FROM settings WHERE key = ?;", -1, &stmt, nil) == SQLITE_OK else { return nil }
        sqlite3_bind_text(stmt, 1, key, -1, SQLITE_TRANSIENT_SWIFT)
        if sqlite3_step(stmt) == SQLITE_ROW, let c = sqlite3_column_text(stmt, 0) {
            return String(cString: c)
        }
        return nil
    }

    public func setSetting(_ key: String, value: String) {
        lock.lock()
        defer { lock.unlock() }
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        if sqlite3_prepare_v2(db, "INSERT OR REPLACE INTO settings(key, value) VALUES(?, ?);", -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, key, -1, SQLITE_TRANSIENT_SWIFT)
            sqlite3_bind_text(stmt, 2, value, -1, SQLITE_TRANSIENT_SWIFT)
            sqlite3_step(stmt)
        }
    }

    @discardableResult
    public func upsertIndexedMeme(
        uri: String,
        name: String,
        kind: String,
        modifiedAt: Int64,
        ocr: String,
        caption: String = "",
        tags: [MemeTag],
        extraTerms: String,
        thumbUri: String,
        imageVec: [Float],
        sceneVecs: [[Float]] = [],
        captionVec: [Float]? = nil,
        contentHash: String = ""
    ) -> Int64 {
        lock.lock()
        defer { lock.unlock() }
        cachedResident = nil

        let tagsJson = (try? String(data: JSONEncoder().encode(tags), encoding: .utf8)) ?? "[]"
        let now = Int64(Date().timeIntervalSince1970 * 1000)

        var existingId: Int64 = 0
        do {
            var q: OpaquePointer?
            defer { sqlite3_finalize(q) }
            if sqlite3_prepare_v2(db, "SELECT id FROM memes WHERE uri = ?;", -1, &q, nil) == SQLITE_OK {
                sqlite3_bind_text(q, 1, uri, -1, SQLITE_TRANSIENT_SWIFT)
                if sqlite3_step(q) == SQLITE_ROW {
                    existingId = sqlite3_column_int64(q, 0)
                }
            }
        }

        let memeId: Int64
        if existingId > 0 {
            memeId = existingId
            var up: OpaquePointer?
            defer { sqlite3_finalize(up) }
            let sql = """
            UPDATE memes SET name=?, kind=?, ocr_text=?, caption=?, tags=?, extra_terms=?,
                thumb_uri=?, indexed_at=?, modified_at=?, pending=0, vision_state=?, content_hash=?
            WHERE id=?;
            """
            if sqlite3_prepare_v2(db, sql, -1, &up, nil) == SQLITE_OK {
                sqlite3_bind_text(up, 1, name, -1, SQLITE_TRANSIENT_SWIFT)
                sqlite3_bind_text(up, 2, kind, -1, SQLITE_TRANSIENT_SWIFT)
                sqlite3_bind_text(up, 3, ocr, -1, SQLITE_TRANSIENT_SWIFT)
                sqlite3_bind_text(up, 4, caption, -1, SQLITE_TRANSIENT_SWIFT)
                sqlite3_bind_text(up, 5, tagsJson, -1, SQLITE_TRANSIENT_SWIFT)
                sqlite3_bind_text(up, 6, extraTerms, -1, SQLITE_TRANSIENT_SWIFT)
                sqlite3_bind_text(up, 7, thumbUri, -1, SQLITE_TRANSIENT_SWIFT)
                sqlite3_bind_int64(up, 8, now)
                sqlite3_bind_int64(up, 9, modifiedAt)
                sqlite3_bind_text(up, 10, caption.isEmpty ? "pending" : "done", -1, SQLITE_TRANSIENT_SWIFT)
                sqlite3_bind_text(up, 11, contentHash, -1, SQLITE_TRANSIENT_SWIFT)
                sqlite3_bind_int64(up, 12, memeId)
                sqlite3_step(up)
            }
        } else {
            var ins: OpaquePointer?
            defer { sqlite3_finalize(ins) }
            let sql = """
            INSERT INTO memes(uri, name, kind, ocr_text, caption, tags, extra_terms, thumb_uri, indexed_at, modified_at, pending, vision_state, content_hash)
            VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?);
            """
            if sqlite3_prepare_v2(db, sql, -1, &ins, nil) == SQLITE_OK {
                sqlite3_bind_text(ins, 1, uri, -1, SQLITE_TRANSIENT_SWIFT)
                sqlite3_bind_text(ins, 2, name, -1, SQLITE_TRANSIENT_SWIFT)
                sqlite3_bind_text(ins, 3, kind, -1, SQLITE_TRANSIENT_SWIFT)
                sqlite3_bind_text(ins, 4, ocr, -1, SQLITE_TRANSIENT_SWIFT)
                sqlite3_bind_text(ins, 5, caption, -1, SQLITE_TRANSIENT_SWIFT)
                sqlite3_bind_text(ins, 6, tagsJson, -1, SQLITE_TRANSIENT_SWIFT)
                sqlite3_bind_text(ins, 7, extraTerms, -1, SQLITE_TRANSIENT_SWIFT)
                sqlite3_bind_text(ins, 8, thumbUri, -1, SQLITE_TRANSIENT_SWIFT)
                sqlite3_bind_int64(ins, 9, now)
                sqlite3_bind_int64(ins, 10, modifiedAt)
                sqlite3_bind_text(ins, 11, caption.isEmpty ? "pending" : "done", -1, SQLITE_TRANSIENT_SWIFT)
                sqlite3_bind_text(ins, 12, contentHash, -1, SQLITE_TRANSIENT_SWIFT)
                sqlite3_step(ins)
            }
            memeId = sqlite3_last_insert_rowid(db)
        }

        // Persist vectors
        var vStmt: OpaquePointer?
        defer { sqlite3_finalize(vStmt) }
        if sqlite3_prepare_v2(db, "INSERT OR REPLACE INTO vectors(id, image, caption, scenes, model) VALUES(?, ?, ?, ?, ?);", -1, &vStmt, nil) == SQLITE_OK {
            sqlite3_bind_int64(vStmt, 1, memeId)
            let imgData = Self.floatsToData(imageVec)
            imgData.withUnsafeBytes { raw in
                sqlite3_bind_blob(vStmt, 2, raw.baseAddress, Int32(imgData.count), SQLITE_TRANSIENT_SWIFT)
            }
            if let cv = captionVec, cv.count == 512 {
                let capData = Self.floatsToData(cv)
                capData.withUnsafeBytes { raw in
                    sqlite3_bind_blob(vStmt, 3, raw.baseAddress, Int32(capData.count), SQLITE_TRANSIENT_SWIFT)
                }
            } else {
                sqlite3_bind_null(vStmt, 3)
            }
            if !sceneVecs.isEmpty {
                let flat = sceneVecs.flatMap { $0 }
                let scnData = Self.floatsToData(flat)
                scnData.withUnsafeBytes { raw in
                    sqlite3_bind_blob(vStmt, 4, raw.baseAddress, Int32(scnData.count), SQLITE_TRANSIENT_SWIFT)
                }
            } else {
                sqlite3_bind_null(vStmt, 4)
            }
            sqlite3_bind_text(vStmt, 5, MemegetModelRegistry.primaryFingerprint, -1, SQLITE_TRANSIENT_SWIFT)
            sqlite3_step(vStmt)
        }

        updateFts(id: memeId, name: name, ocr: ocr, caption: caption, transcript: "", tags: tags.map(\.label).joined(separator: " "), extraTerms: extraTerms)
        return memeId
    }

    private func updateFts(id: Int64, name: String, ocr: String, caption: String, transcript: String, tags: String, extraTerms: String) {
        var del: OpaquePointer?
        if sqlite3_prepare_v2(db, "DELETE FROM meme_search_fts WHERE rowid = ?;", -1, &del, nil) == SQLITE_OK {
            sqlite3_bind_int64(del, 1, id)
            sqlite3_step(del)
        }
        sqlite3_finalize(del)

        var ins: OpaquePointer?
        if sqlite3_prepare_v2(db, "INSERT INTO meme_search_fts(rowid, name, ocr, caption, transcript, tags, extra_terms) VALUES(?, ?, ?, ?, ?, ?, ?);", -1, &ins, nil) == SQLITE_OK {
            sqlite3_bind_int64(ins, 1, id)
            sqlite3_bind_text(ins, 2, name, -1, SQLITE_TRANSIENT_SWIFT)
            sqlite3_bind_text(ins, 3, ocr, -1, SQLITE_TRANSIENT_SWIFT)
            sqlite3_bind_text(ins, 4, caption, -1, SQLITE_TRANSIENT_SWIFT)
            sqlite3_bind_text(ins, 5, transcript, -1, SQLITE_TRANSIENT_SWIFT)
            sqlite3_bind_text(ins, 6, tags, -1, SQLITE_TRANSIENT_SWIFT)
            sqlite3_bind_text(ins, 7, extraTerms, -1, SQLITE_TRANSIENT_SWIFT)
            sqlite3_step(ins)
        }
        sqlite3_finalize(ins)
    }

    public func addManualTag(id: Int64, label: String, category: String = "topic") {
        lock.lock()
        defer { lock.unlock() }
        guard var current = meme(id: id) else { return }
        let norm = MemeTag.normalizeLabel(label)
        guard !norm.isEmpty else { return }
        var tags = current.tags.filter { $0.label != norm }
        tags.insert(MemeTag(label: norm, category: category, score: 1.0, source: "manual", scoreDomain: "human", confirmation: "confirmed", provenance: "user"), at: 0)
        let extra = current.extraTerms.contains(norm) ? current.extraTerms : "\(current.extraTerms) \(norm)".trimmingCharacters(in: .whitespaces)
        saveUpdatedTags(id: id, current: current, tags: tags, rejected: current.rejectedLabels.filter { $0 != norm }, extra: extra)
    }

    public func removeTag(id: Int64, label: String) {
        lock.lock()
        defer { lock.unlock() }
        guard let current = meme(id: id) else { return }
        let norm = MemeTag.normalizeLabel(label)
        let tags = current.tags.filter { $0.label != norm }
        var rej = current.rejectedLabels
        if !rej.contains(norm) { rej.append(norm) }
        saveUpdatedTags(id: id, current: current, tags: tags, rejected: rej, extra: current.extraTerms)
    }

    private func saveUpdatedTags(id: Int64, current: MemeItem, tags: [MemeTag], rejected: [String], extra: String) {
        cachedResident = nil
        let tagsJson = (try? String(data: JSONEncoder().encode(tags), encoding: .utf8)) ?? "[]"
        let rejJson = (try? String(data: JSONEncoder().encode(rejected), encoding: .utf8)) ?? "[]"
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        if sqlite3_prepare_v2(db, "UPDATE memes SET tags=?, rejected_labels=?, extra_terms=? WHERE id=?;", -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, tagsJson, -1, SQLITE_TRANSIENT_SWIFT)
            sqlite3_bind_text(stmt, 2, rejJson, -1, SQLITE_TRANSIENT_SWIFT)
            sqlite3_bind_text(stmt, 3, extra, -1, SQLITE_TRANSIENT_SWIFT)
            sqlite3_bind_int64(stmt, 4, id)
            sqlite3_step(stmt)
        }
        updateFts(id: id, name: current.name, ocr: current.ocr, caption: "\(current.caption) \(current.cloudCaption)".trimmingCharacters(in: .whitespaces), transcript: current.transcript, tags: tags.map(\.label).joined(separator: " "), extraTerms: "\(extra) \(current.cloudTerms)".trimmingCharacters(in: .whitespaces))
    }

    public func applyCloudEnrichment(id: Int64, caption: String, terms: String, tags: [MemeTag]) {
        lock.lock()
        defer { lock.unlock() }
        guard let current = meme(id: id) else { return }
        cachedResident = nil
        let rejSet = Set(current.rejectedLabels.map(MemeTag.normalizeLabel))
        var merged = current.tags
        var seen = Set(merged.map { MemeTag.normalizeLabel($0.label) })
        for ct in tags {
            let norm = MemeTag.normalizeLabel(ct.label)
            if !norm.isEmpty && !seen.contains(norm) && !rejSet.contains(norm) {
                merged.append(ct)
                seen.insert(norm)
            }
        }
        let tagsJson = (try? String(data: JSONEncoder().encode(merged), encoding: .utf8)) ?? "[]"
        let newCloudCap = caption.isEmpty ? current.cloudCaption : caption
        let newCloudTerms = terms.isEmpty ? current.cloudTerms : terms
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        if sqlite3_prepare_v2(db, "UPDATE memes SET tags=?, cloud_caption=?, cloud_terms=? WHERE id=?;", -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, tagsJson, -1, SQLITE_TRANSIENT_SWIFT)
            sqlite3_bind_text(stmt, 2, newCloudCap, -1, SQLITE_TRANSIENT_SWIFT)
            sqlite3_bind_text(stmt, 3, newCloudTerms, -1, SQLITE_TRANSIENT_SWIFT)
            sqlite3_bind_int64(stmt, 4, id)
            sqlite3_step(stmt)
        }
        updateFts(
            id: id,
            name: current.name,
            ocr: current.ocr,
            caption: "\(current.caption) \(newCloudCap)".trimmingCharacters(in: .whitespaces),
            transcript: current.transcript,
            tags: merged.map(\.label).joined(separator: " "),
            extraTerms: "\(current.extraTerms) \(newCloudTerms)".trimmingCharacters(in: .whitespaces)
        )
    }

    public func recordCopy(id: Int64) {
        lock.lock()
        defer { lock.unlock() }
        cachedResident = nil
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        if sqlite3_prepare_v2(db, "UPDATE memes SET copy_count = copy_count + 1, last_copied_at = ? WHERE id = ?;", -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_int64(stmt, 1, now)
            sqlite3_bind_int64(stmt, 2, id)
            sqlite3_step(stmt)
        }
    }

    @discardableResult
    public func markNotAMeme(id: Int64) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard let existing = meme(id: id) else { return false }
        cachedResident = nil
        let now = Int64(Date().timeIntervalSince1970 * 1000)

        if let imgVec = imageVector(for: id), imgVec.count == 512 {
            var rStmt: OpaquePointer?
            defer { sqlite3_finalize(rStmt) }
            if sqlite3_prepare_v2(db, "INSERT OR REPLACE INTO rejected_photo_vectors(source_key, vector, created_at) VALUES(?, ?, ?);", -1, &rStmt, nil) == SQLITE_OK {
                sqlite3_bind_text(rStmt, 1, existing.uri, -1, SQLITE_TRANSIENT_SWIFT)
                let blob = Self.floatsToData(imgVec)
                blob.withUnsafeBytes { raw in
                    sqlite3_bind_blob(rStmt, 2, raw.baseAddress, Int32(blob.count), SQLITE_TRANSIENT_SWIFT)
                }
                sqlite3_bind_int64(rStmt, 3, now)
                sqlite3_step(rStmt)
            }
        }

        var gStmt: OpaquePointer?
        if sqlite3_prepare_v2(db, "INSERT OR REPLACE INTO camera_roll_gate(source_key, uri, name, kind, modified_at, verdict, meme_prob, meme_id, updated_at) VALUES(?, ?, ?, ?, ?, 0, 0.01, 0, ?);", -1, &gStmt, nil) == SQLITE_OK {
            sqlite3_bind_text(gStmt, 1, existing.uri, -1, SQLITE_TRANSIENT_SWIFT)
            sqlite3_bind_text(gStmt, 2, existing.uri, -1, SQLITE_TRANSIENT_SWIFT)
            sqlite3_bind_text(gStmt, 3, existing.name, -1, SQLITE_TRANSIENT_SWIFT)
            sqlite3_bind_text(gStmt, 4, existing.kind, -1, SQLITE_TRANSIENT_SWIFT)
            sqlite3_bind_int64(gStmt, 5, existing.modifiedAt)
            sqlite3_bind_int64(gStmt, 6, now)
            sqlite3_step(gStmt)
        }
        sqlite3_finalize(gStmt)

        for sql in ["DELETE FROM meme_search_fts WHERE rowid = ?;", "DELETE FROM vectors WHERE id = ?;", "DELETE FROM memes WHERE id = ?;"] {
            var d: OpaquePointer?
            if sqlite3_prepare_v2(db, sql, -1, &d, nil) == SQLITE_OK {
                sqlite3_bind_int64(d, 1, id)
                sqlite3_step(d)
            }
            sqlite3_finalize(d)
        }
        return true
    }

    public func rejectedPhotoVectors(limit: Int = 256) -> [[Float]] {
        lock.lock()
        defer { lock.unlock() }
        var out: [[Float]] = []
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(db, "SELECT vector FROM rejected_photo_vectors ORDER BY created_at DESC LIMIT ?;", -1, &stmt, nil) == SQLITE_OK else { return out }
        sqlite3_bind_int(stmt, 1, Int32(limit))
        while sqlite3_step(stmt) == SQLITE_ROW {
            if let v = Self.readFloatBlob(stmt, col: 0), v.count == 512 {
                out.append(v)
            }
        }
        return out
    }

    public func seenCameraRollStates() -> [String: Int64] {
        lock.lock()
        defer { lock.unlock() }
        var out: [String: Int64] = [:]
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(db, "SELECT source_key, modified_at FROM camera_roll_gate;", -1, &stmt, nil) == SQLITE_OK else { return out }
        while sqlite3_step(stmt) == SQLITE_ROW {
            if let k = sqlite3_column_text(stmt, 0) {
                out[String(cString: k)] = sqlite3_column_int64(stmt, 1)
            }
        }
        return out
    }

    public func upsertCameraRollGate(_ rec: CameraRollGateRecord) {
        lock.lock()
        defer { lock.unlock() }
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        let sql = """
        INSERT OR REPLACE INTO camera_roll_gate(
            source_key, uri, name, kind, modified_at, width, height, duration_ms,
            verdict, meme_prob, d1_prompt, ocr_text, candidate_hints, image_vec, scene_vecs, meme_id, updated_at
        ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
        """
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return }
        sqlite3_bind_text(stmt, 1, rec.sourceKey, -1, SQLITE_TRANSIENT_SWIFT)
        sqlite3_bind_text(stmt, 2, rec.uri, -1, SQLITE_TRANSIENT_SWIFT)
        sqlite3_bind_text(stmt, 3, rec.name, -1, SQLITE_TRANSIENT_SWIFT)
        sqlite3_bind_text(stmt, 4, rec.kind, -1, SQLITE_TRANSIENT_SWIFT)
        sqlite3_bind_int64(stmt, 5, rec.modifiedAt)
        sqlite3_bind_int(stmt, 6, Int32(rec.width))
        sqlite3_bind_int(stmt, 7, Int32(rec.height))
        sqlite3_bind_int64(stmt, 8, rec.durationMs)
        sqlite3_bind_int(stmt, 9, Int32(rec.verdict.rawValue))
        sqlite3_bind_double(stmt, 10, Double(rec.memeProbability))
        sqlite3_bind_text(stmt, 11, rec.d1Prompt, -1, SQLITE_TRANSIENT_SWIFT)
        sqlite3_bind_text(stmt, 12, rec.ocrText, -1, SQLITE_TRANSIENT_SWIFT)
        sqlite3_bind_text(stmt, 13, rec.candidateHints, -1, SQLITE_TRANSIENT_SWIFT)
        if let iv = rec.imageVector, iv.count == 512 {
            let d = Self.floatsToData(iv)
            d.withUnsafeBytes { raw in sqlite3_bind_blob(stmt, 14, raw.baseAddress, Int32(d.count), SQLITE_TRANSIENT_SWIFT) }
        } else {
            sqlite3_bind_null(stmt, 14)
        }
        if !rec.sceneVectors.isEmpty {
            let d = Self.floatsToData(rec.sceneVectors.flatMap { $0 })
            d.withUnsafeBytes { raw in sqlite3_bind_blob(stmt, 15, raw.baseAddress, Int32(d.count), SQLITE_TRANSIENT_SWIFT) }
        } else {
            sqlite3_bind_null(stmt, 15)
        }
        sqlite3_bind_int64(stmt, 16, rec.memeId)
        sqlite3_bind_int64(stmt, 17, Int64(Date().timeIntervalSince1970 * 1000))
        sqlite3_step(stmt)
    }

    public func pendingCameraRollGate(verdict: GateVerdictCode, limit: Int = 24) -> [CameraRollGateRecord] {
        lock.lock()
        defer { lock.unlock() }
        var out: [CameraRollGateRecord] = []
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        let sql = """
        SELECT source_key, uri, name, kind, modified_at, width, height, duration_ms,
               verdict, meme_prob, d1_prompt, ocr_text, candidate_hints, image_vec, scene_vecs, meme_id
        FROM camera_roll_gate WHERE verdict = ? ORDER BY modified_at DESC LIMIT ?;
        """
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return out }
        sqlite3_bind_int(stmt, 1, Int32(verdict.rawValue))
        sqlite3_bind_int(stmt, 2, Int32(limit))
        while sqlite3_step(stmt) == SQLITE_ROW {
            let scenesFlat = Self.readFloatBlob(stmt, col: 14) ?? []
            var scenes: [[Float]] = []
            let sCount = scenesFlat.count / 512
            for s in 0..<sCount {
                scenes.append(Array(scenesFlat[(s * 512)..<((s + 1) * 512)]))
            }
            out.append(CameraRollGateRecord(
                sourceKey: String(cString: sqlite3_column_text(stmt, 0)),
                uri: String(cString: sqlite3_column_text(stmt, 1)),
                name: String(cString: sqlite3_column_text(stmt, 2)),
                kind: String(cString: sqlite3_column_text(stmt, 3)),
                modifiedAt: sqlite3_column_int64(stmt, 4),
                width: Int(sqlite3_column_int(stmt, 5)),
                height: Int(sqlite3_column_int(stmt, 6)),
                durationMs: sqlite3_column_int64(stmt, 7),
                verdict: GateVerdictCode(rawValue: Int(sqlite3_column_int(stmt, 8))) ?? .rejected,
                memeProbability: Float(sqlite3_column_double(stmt, 9)),
                d1Prompt: sqlite3_column_text(stmt, 10).map { String(cString: $0) } ?? "",
                ocrText: sqlite3_column_text(stmt, 11).map { String(cString: $0) } ?? "",
                candidateHints: sqlite3_column_text(stmt, 12).map { String(cString: $0) } ?? "",
                imageVector: Self.readFloatBlob(stmt, col: 13),
                sceneVectors: scenes,
                memeId: sqlite3_column_int64(stmt, 15)
            ))
        }
        return out
    }

    public func meme(id: Int64) -> MemeItem? {
        lock.lock()
        defer { lock.unlock() }
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        let sql = """
        SELECT id, uri, name, kind, ocr_text, caption, transcript, tags, extra_terms, thumb_uri,
               modified_at, pending, vision_state, content_hash, cloud_caption, cloud_terms,
               rejected_labels, copy_count, last_copied_at
        FROM memes WHERE id = ?;
        """
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return nil }
        sqlite3_bind_int64(stmt, 1, id)
        guard sqlite3_step(stmt) == SQLITE_ROW else { return nil }
        return Self.readMemeRow(stmt)
    }

    public func imageVector(for id: Int64) -> [Float]? {
        lock.lock()
        defer { lock.unlock() }
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(db, "SELECT image FROM vectors WHERE id = ?;", -1, &stmt, nil) == SQLITE_OK else { return nil }
        sqlite3_bind_int64(stmt, 1, id)
        guard sqlite3_step(stmt) == SQLITE_ROW else { return nil }
        return Self.readFloatBlob(stmt, col: 0)
    }

    public func browsePage(kind: String? = nil, tag: String? = nil, usedOnly: Bool = false, limit: Int = 180) -> [MemeItem] {
        lock.lock()
        defer { lock.unlock() }
        let resident = ensureResidentPacked()
        let normTag = tag.map(MemeTag.normalizeLabel)
        var filtered = resident.entries.filter { entry in
            if let k = kind, !k.isEmpty, entry.meme.kind != k { return false }
            if usedOnly && entry.meme.copyCount <= 0 { return false }
            if let nt = normTag, !nt.isEmpty, !entry.normalizedTags.contains(nt) { return false }
            return true
        }
        if usedOnly {
            filtered.sort { a, b in
                if a.meme.copyCount != b.meme.copyCount { return a.meme.copyCount > b.meme.copyCount }
                return a.meme.lastCopiedAt > b.meme.lastCopiedAt
            }
        }
        return Array(filtered.prefix(limit).map(\.meme))
    }

    public func topTagChips(limit: Int = 20) -> [String] {
        lock.lock()
        defer { lock.unlock() }
        let resident = ensureResidentPacked()
        var counts: [String: Int] = [:]
        for e in resident.entries {
            for t in e.meme.tags where !t.label.isEmpty {
                counts[t.label, default: 0] += 1
            }
        }
        return counts.sorted { a, b in
            if a.value != b.value { return a.value > b.value }
            return a.key < b.key
        }.prefix(limit).map(\.key)
    }

    public func search(query: String, queryVec: [Float]?, kind: String? = nil, tag: String? = nil, limit: Int = 90) -> [SearchHitItem] {
        lock.lock()
        defer { lock.unlock() }
        let resident = ensureResidentPacked()
        let count = resident.entries.count
        if count == 0 { return [] }

        let distilled = SocialReplyIntent.distill(query)
        let exactTerms = distilled.exactTerms
        var semanticTerms = distilled.socialTerms
        if let qv = queryVec, qv.count == 512 {
            let expanded = qv.withUnsafeBufferPointer { ptr in
                bridge.semanticQueryTerms(forVector: ptr.baseAddress!)
            }
            expanded.forEach { semanticTerms.insert($0) }
        }

        let exactWeights: [Float] = exactTerms.map { term in
            let df = resident.docFreq[term] ?? 0
            var w = Float(log(1.0 + Double(count + 1) / Double(df + 1)))
            if SocialReplyIntent.scaffoldWords.contains(term) && exactTerms.count >= 2 {
                w *= 0.22
            }
            return w
        }
        let totalExactW = max(1e-4, exactWeights.reduce(0, +))
        let phrase = exactTerms.count >= 2 ? " \(exactTerms.joined(separator: " ")) " : ""

        var denseScores = [Float](repeating: 0, count: count)
        if let qv = queryVec, qv.count == 512 {
            let protoData = bridge.conceptPrototypeVector(forQuery: distilled.rawQuery)
            qv.withUnsafeBufferPointer { qPtr in
                resident.imageMatInt8.withUnsafeBufferPointer { imgPtr in
                    resident.imageRabit64.withUnsafeBufferPointer { rabPtr in
                        resident.hasImage.withUnsafeBufferPointer { hasImgPtr in
                            resident.imgCslsHubness.withUnsafeBufferPointer { cslsPtr in
                                resident.scenePoolInt8.withUnsafeBufferPointer { scnPtr in
                                    resident.sceneOffsets.withUnsafeBufferPointer { scnOffPtr in
                                        resident.hasCaption.withUnsafeBufferPointer { hasCapPtr in
                                            resident.capHubness.withUnsafeBufferPointer { capHubPtr in
                                                denseScores.withUnsafeMutableBufferPointer { outPtr in
                                                    let protoFloats: [Float]? = protoData.map { d in
                                                        d.withUnsafeBytes { Array($0.bindMemory(to: Float.self)) }
                                                    }
                                                    let capPtrOpt = resident.captionMatInt8
                                                    if let pFloats = protoFloats, pFloats.count == 512 {
                                                        pFloats.withUnsafeBufferPointer { pPtr in
                                                            if let cMat = capPtrOpt {
                                                                cMat.withUnsafeBufferPointer { cmPtr in
                                                                    _ = MemegetCoreBridge.batchDenseScores(
                                                                        withQuery: qPtr.baseAddress!, protoVec: pPtr.baseAddress,
                                                                        imageMatInt8: imgPtr.baseAddress!, imageRabit64: rabPtr.baseAddress,
                                                                        hasImage: hasImgPtr.baseAddress!, imgCslsHubness: cslsPtr.baseAddress,
                                                                        scenePoolInt8: scnPtr.baseAddress, sceneOffsets: scnOffPtr.baseAddress,
                                                                        captionMatInt8: cmPtr.baseAddress, hasCaption: hasCapPtr.baseAddress,
                                                                        capHubness: capHubPtr.baseAddress, count: count, outScores: outPtr.baseAddress!
                                                                    )
                                                                }
                                                            } else {
                                                                _ = MemegetCoreBridge.batchDenseScores(
                                                                    withQuery: qPtr.baseAddress!, protoVec: pPtr.baseAddress,
                                                                    imageMatInt8: imgPtr.baseAddress!, imageRabit64: rabPtr.baseAddress,
                                                                    hasImage: hasImgPtr.baseAddress!, imgCslsHubness: cslsPtr.baseAddress,
                                                                    scenePoolInt8: scnPtr.baseAddress, sceneOffsets: scnOffPtr.baseAddress,
                                                                    captionMatInt8: nil, hasCaption: hasCapPtr.baseAddress,
                                                                    capHubness: capHubPtr.baseAddress, count: count, outScores: outPtr.baseAddress!
                                                                )
                                                            }
                                                        }
                                                    } else if let cMat = capPtrOpt {
                                                        cMat.withUnsafeBufferPointer { cmPtr in
                                                            _ = MemegetCoreBridge.batchDenseScores(
                                                                withQuery: qPtr.baseAddress!, protoVec: nil,
                                                                imageMatInt8: imgPtr.baseAddress!, imageRabit64: rabPtr.baseAddress,
                                                                hasImage: hasImgPtr.baseAddress!, imgCslsHubness: cslsPtr.baseAddress,
                                                                scenePoolInt8: scnPtr.baseAddress, sceneOffsets: scnOffPtr.baseAddress,
                                                                captionMatInt8: cmPtr.baseAddress, hasCaption: hasCapPtr.baseAddress,
                                                                capHubness: capHubPtr.baseAddress, count: count, outScores: outPtr.baseAddress!
                                                            )
                                                        }
                                                    } else {
                                                        _ = MemegetCoreBridge.batchDenseScores(
                                                            withQuery: qPtr.baseAddress!, protoVec: nil,
                                                            imageMatInt8: imgPtr.baseAddress!, imageRabit64: rabPtr.baseAddress,
                                                            hasImage: hasImgPtr.baseAddress!, imgCslsHubness: cslsPtr.baseAddress,
                                                            scenePoolInt8: scnPtr.baseAddress, sceneOffsets: scnOffPtr.baseAddress,
                                                            captionMatInt8: nil, hasCaption: hasCapPtr.baseAddress,
                                                            capHubness: capHubPtr.baseAddress, count: count, outScores: outPtr.baseAddress!
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        let normTag = tag.map(MemeTag.normalizeLabel)
        let semantic = (queryVec != nil)
        var hits: [SearchHitItem] = []
        hits.reserveCapacity(min(limit * 2, count))

        for i in 0..<count {
            let e = resident.entries[i]
            if let k = kind, !k.isEmpty, e.meme.kind != k { continue }
            if let nt = normTag, !nt.isEmpty, !e.normalizedTags.contains(nt) { continue }

            let phraseHit = !phrase.isEmpty && e.phraseDoc.contains(phrase)
            let usageBoost = e.meme.copyCount > 0 ? min(Float(0.045), 0.016 * Float(log(1.0 + Double(e.meme.copyCount)))) : 0

            var score: Float = semantic ? denseScores[i] : 0
            var matched = false
            var tagW: Float = 0
            var coreW: Float = 0
            var anyW: Float = 0
            var matchedCount = 0

            for (tIdx, term) in exactTerms.enumerated() {
                let w = exactWeights[tIdx]
                if e.humanTagWords.contains(term) {
                    tagW += w * 1.15; coreW += w; anyW += w; matchedCount += 1
                } else if e.tagWords.contains(term) {
                    tagW += w; coreW += w; anyW += w; matchedCount += 1
                } else if e.quoteWords.contains(term) {
                    coreW += w * 0.95; anyW += w; matchedCount += 1
                } else if e.captionWords.contains(term) {
                    coreW += w * 0.88; anyW += w; matchedCount += 1
                } else if e.docWords.contains(term) {
                    anyW += w * 0.55; matchedCount += 1
                }
            }

            if !semantic {
                if matchedCount > 0 {
                    matched = true
                    let covCore = min(1.0, coreW / totalExactW)
                    let covTag = min(1.0, tagW / totalExactW)
                    let covAny = min(1.0, anyW / totalExactW)
                    score += 0.40 * covAny + 1.10 * covCore * covCore + 2.80 * covTag
                    if matchedCount == exactTerms.count && covCore >= 0.85 { score += 0.60 }
                    if matchedCount == exactTerms.count && covTag >= 0.85 { score += 3.00 }
                } else {
                    let soc = semanticTerms.filter { e.docWords.contains($0) }.count
                    if soc > 0 {
                        matched = true
                        score += 0.35 * min(1.0, Float(soc) / 1.5)
                    }
                }
                if phraseHit { matched = true; score += 6.0 }
                if !matched || score <= 0 { continue }
                score += usageBoost
            } else {
                if anyW > 0 && !distilled.pureSlangReaction {
                    matched = true
                    let covCore = min(1.0, coreW / totalExactW)
                    let covTag = min(1.0, tagW / totalExactW)
                    let covAny = min(1.0, anyW / totalExactW)
                    let approxBm25 = min(Float(3.6), tagW * 1.4 + coreW * 0.8 + max(0, anyW - coreW) * 0.6)
                    score += 0.05 * covAny + 0.06 * covTag + 0.022 * approxBm25
                    if covCore >= 0.985 || covAny >= 0.985 { score += 0.22 }
                    if covTag >= 0.985 { score += 0.30 }
                } else if !semanticTerms.isEmpty && semanticTerms.contains(where: { e.docWords.contains($0) }) {
                    matched = true
                    score += 0.06
                }
                if phraseHit && !distilled.pureSlangReaction { score += 0.45 }
                if !matched && score < 0.16 { continue }
                score += usageBoost
            }
            hits.append(SearchHitItem(meme: e.meme, score: score))
        }

        hits.sort { a, b in
            if a.score != b.score { return a.score > b.score }
            if a.meme.modifiedAt != b.meme.modifiedAt { return a.meme.modifiedAt > b.meme.modifiedAt }
            return a.meme.id > b.meme.id
        }
        return Array(hits.prefix(limit))
    }

    // Bounded, lock-free Transductive Template Cluster Propagation on iOS (never blocks MainActor or search lock!)
    @discardableResult
    public func propagateClusterIdentities(maxSeeds: Int = 512, maxTargets: Int = 1024) -> Int {
        var rows: [(MemeItem, [Float])] = []
        lock.lock()
        do {
            var stmt: OpaquePointer?
            defer { sqlite3_finalize(stmt) }
            let sql = """
            SELECT m.id, m.uri, m.name, m.kind, m.ocr_text, m.caption, m.transcript, m.tags, m.extra_terms,
                   m.thumb_uri, m.modified_at, m.pending, m.vision_state, m.content_hash, m.cloud_caption,
                   m.cloud_terms, m.rejected_labels, m.copy_count, m.last_copied_at, v.image
            FROM memes m JOIN vectors v ON m.id = v.id
            WHERE m.pending = 0
            ORDER BY m.modified_at DESC, m.id DESC
            LIMIT ?;
            """
            if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
                sqlite3_bind_int(stmt, 1, Int32(max(maxSeeds, maxTargets) * 2))
                while sqlite3_step(stmt) == SQLITE_ROW {
                    let m = Self.readMemeRow(stmt)
                    if let v = Self.readFloatBlob(stmt, col: 19), v.count == 512 {
                        rows.append((m, v))
                    }
                }
            }
        }
        lock.unlock()
        if rows.count < 2 { return 0 }

        @inline(__always)
        func rabitSketch64(_ v: [Float]) -> UInt64 {
            var dummyI8 = [Int8](repeating: 0, count: 512)
            var rabit8 = [UInt64](repeating: 0, count: 8)
            v.withUnsafeBufferPointer { vp in
                dummyI8.withUnsafeMutableBufferPointer { ip in
                    rabit8.withUnsafeMutableBufferPointer { rp in
                        MemegetCoreBridge.quantizeVec512(vp.baseAddress!, dstInt8: ip.baseAddress!, dstRabit64: rp.baseAddress!)
                    }
                }
            }
            return rabit8[0]
        }

        let entityCats: Set<String> = ["format", "character", "person", "tv", "movie"]
        var rawSeeds: [(id: Int64, vec: [Float], sketch: UInt64, normLabel: String, tag: MemeTag)] = []
        for (m, v) in rows {
            for t in m.tags where entityCats.contains(t.category.lowercased()) {
                if t.isHumanConfirmed || (t.source != "propagated" && t.score >= 0.84) {
                    let nl = MemeTag.normalizeLabel(t.label)
                    if !nl.isEmpty {
                        rawSeeds.append((m.id, v, rabitSketch64(v), nl, t))
                    }
                }
            }
        }
        if rawSeeds.isEmpty { return 0 }
        rawSeeds.sort { a, b in
            if a.tag.isHumanConfirmed != b.tag.isHumanConfirmed { return a.tag.isHumanConfirmed }
            return a.tag.score > b.tag.score
        }
        let seeds = Array(rawSeeds.prefix(maxSeeds))
        let targets = Array(rows.prefix(maxTargets))

        var pendingUpdates: [(id: Int64, added: [MemeTag])] = []
        for (idx, pair) in targets.enumerated() {
            if idx % 32 == 0 && Task.isCancelled { break }
            let (target, tv) = pair
            let tSketch = rabitSketch64(tv)
            let existing = Set(target.tags.map { MemeTag.normalizeLabel($0.label) })
            let rejected = Set(target.rejectedLabels.map(MemeTag.normalizeLabel))
            var added: [MemeTag] = []
            var addedLabels = Set<String>()
            tv.withUnsafeBufferPointer { tvPtr in
                guard let tvBase = tvPtr.baseAddress else { return }
                for seed in seeds where seed.id != target.id {
                    if (tSketch ^ seed.sketch).nonzeroBitCount > 22 { continue }
                    let norm = seed.normLabel
                    if existing.contains(norm) || rejected.contains(norm) || addedLabels.contains(norm) { continue }
                    var dot: Float = 0
                    seed.vec.withUnsafeBufferPointer { svPtr in
                        if let svBase = svPtr.baseAddress {
                            vDSP_dotpr(tvBase, 1, svBase, 1, &dot, 512)
                        }
                    }
                    if dot >= 0.84 {
                        added.append(MemeTag(label: norm, category: seed.tag.category, score: dot, source: "propagated", scoreDomain: "cluster-propagation", confirmation: "unreviewed", provenance: "cluster:\(seed.id)"))
                        addedLabels.insert(norm)
                        if added.count >= 2 { break }
                    }
                }
            }
            if !added.isEmpty {
                pendingUpdates.append((target.id, added))
            }
        }
        if pendingUpdates.isEmpty { return 0 }
        lock.lock()
        defer { lock.unlock() }
        var updated = 0
        for (tid, added) in pendingUpdates {
            guard let current = meme(id: tid) else { continue }
            let existing = Set(current.tags.map { MemeTag.normalizeLabel($0.label) })
            let rejected = Set(current.rejectedLabels.map(MemeTag.normalizeLabel))
            let fresh = added.filter { !existing.contains($0.label) && !rejected.contains($0.label) }
            guard !fresh.isEmpty else { continue }
            let merged = current.tags + fresh
            let extra = ([current.extraTerms] + fresh.map(\.label)).filter { !$0.isEmpty }.joined(separator: " ")
            saveUpdatedTags(id: tid, current: current, tags: merged, rejected: current.rejectedLabels, extra: extra)
            updated += 1
        }
        return updated
    }

    private func ensureResidentPacked() -> PackedResident {
        if let existing = cachedResident { return existing }
        var entries: [ResidentEntry] = []
        var df: [String: Int] = [:]
        var tagSet = Set<String>()

        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        let sql = """
        SELECT m.id, m.uri, m.name, m.kind, m.ocr_text, m.caption, m.transcript, m.tags, m.extra_terms,
               m.thumb_uri, m.modified_at, m.pending, m.vision_state, m.content_hash, m.cloud_caption,
               m.cloud_terms, m.rejected_labels, m.copy_count, m.last_copied_at,
               v.image, v.caption, v.scenes
        FROM memes m LEFT JOIN vectors v ON m.id = v.id
        WHERE m.pending = 0
        ORDER BY m.modified_at DESC, m.id DESC
        LIMIT ?;
        """
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_int(stmt, 1, Int32(Self.maxResidentMemes))
            while sqlite3_step(stmt) == SQLITE_ROW {
                let meme = Self.readMemeRow(stmt)
                let imgFp = Self.readFloatBlob(stmt, col: 19)
                let capFp = Self.readFloatBlob(stmt, col: 20)
                let scnFp = Self.readFloatBlob(stmt, col: 21)

                var imgI8 = [Int8](repeating: 0, count: 512)
                var rabit = [UInt64](repeating: 0, count: 8)
                var csls: Float = 0
                let validImg = (imgFp?.count == 512)
                if let iv = imgFp, validImg {
                    iv.withUnsafeBufferPointer { srcPtr in
                        imgI8.withUnsafeMutableBufferPointer { dstPtr in
                            rabit.withUnsafeMutableBufferPointer { rabPtr in
                                MemegetCoreBridge.quantizeVec512(srcPtr.baseAddress!, dstInt8: dstPtr.baseAddress!, dstRabit64: rabPtr.baseAddress)
                            }
                        }
                        csls = bridge.computeCslsHubness(forImageVec: srcPtr.baseAddress!)
                    }
                }

                let capI8: [Int8]? = if let cv = capFp, cv.count == 512 {
                    {
                        var buf = [Int8](repeating: 0, count: 512)
                        cv.withUnsafeBufferPointer { s in
                            buf.withUnsafeMutableBufferPointer { d in
                                MemegetCoreBridge.quantizeVec512(s.baseAddress!, dstInt8: d.baseAddress!, dstRabit64: nil)
                            }
                        }
                        return buf
                    }()
                } else { nil }

                let sCnt = (scnFp?.count ?? 0) / 512
                var scnI8: [Int8] = []
                if let sf = scnFp, sCnt > 0 {
                    scnI8 = [Int8](repeating: 0, count: sCnt * 512)
                    for s in 0..<sCnt {
                        let slice = Array(sf[(s * 512)..<((s + 1) * 512)])
                        slice.withUnsafeBufferPointer { sp in
                            scnI8.withUnsafeMutableBufferPointer { dp in
                                MemegetCoreBridge.quantizeVec512(sp.baseAddress!, dstInt8: dp.baseAddress! + s * 512, dstRabit64: nil)
                            }
                        }
                    }
                }

                let labels = meme.tags.map(\.label)
                labels.forEach { tagSet.insert($0) }
                let humanLbls = meme.tags.filter(\.isHumanConfirmed).map(\.label)
                let humanW = Set(SocialReplyIntent.wordTokens(humanLbls.joined(separator: " ")))
                let tagW = Set(SocialReplyIntent.wordTokens(labels.joined(separator: " ")))
                let quoteW = Set(SocialReplyIntent.wordTokens("\(meme.ocr) \(meme.transcript)"))
                let capW = Set(SocialReplyIntent.wordTokens("\(meme.caption) \(meme.cloudCaption)"))
                let extraW = Set(SocialReplyIntent.wordTokens("\(meme.extraTerms) \(meme.cloudTerms) \(meme.name)"))
                let docW = tagW.union(quoteW).union(capW).union(extraW)
                for w in docW { df[w, default: 0] += 1 }
                let fullDoc = " \(SocialReplyIntent.wordTokens("\(meme.ocr) \(meme.caption) \(meme.cloudCaption) \(labels.joined(separator: " ")) \(meme.extraTerms) \(meme.cloudTerms)").joined(separator: " ")) "
                entries.append(ResidentEntry(
                    meme: meme,
                    tagWords: tagW,
                    humanTagWords: humanW,
                    quoteWords: quoteW,
                    captionWords: capW,
                    docWords: docW,
                    normalizedTags: Set(labels.map(MemeTag.normalizeLabel)),
                    phraseDoc: fullDoc,
                    imageInt8: imgI8,
                    imageRabit64: rabit,
                    hasImage: validImg,
                    cslsHubness: csls,
                    scenesInt8: scnI8,
                    sceneCount: sCnt,
                    captionInt8: capI8
                ))
            }
        }

        let n = entries.count
        var imgMat = [Int8](repeating: 0, count: max(1, n * 512))
        var rabMat = [UInt64](repeating: 0, count: max(1, n * 8))
        var hasImg = [UInt8](repeating: 0, count: max(1, n))
        var cslsArr = [Float](repeating: 0, count: max(1, n))
        var sOffsets = [Int32](repeating: 0, count: n + 1)
        var totalScenes = 0
        var anyCap = false
        var hasCap = [UInt8](repeating: 0, count: max(1, n))

        for (i, e) in entries.enumerated() {
            sOffsets[i] = Int32(totalScenes)
            totalScenes += e.sceneCount
            if e.hasImage {
                imgMat.replaceSubrange((i * 512)..<((i + 1) * 512), with: e.imageInt8)
                rabMat.replaceSubrange((i * 8)..<((i + 1) * 8), with: e.imageRabit64)
                hasImg[i] = 1
                cslsArr[i] = e.cslsHubness
            }
            if e.captionInt8 != nil {
                anyCap = true
                hasCap[i] = 1
            }
        }
        sOffsets[n] = Int32(totalScenes)

        var scnPool = [Int8](repeating: 0, count: max(1, totalScenes * 512))
        if totalScenes > 0 {
            for (i, e) in entries.enumerated() where e.sceneCount > 0 {
                let start = Int(sOffsets[i]) * 512
                scnPool.replaceSubrange(start..<(start + e.sceneCount * 512), with: e.scenesInt8)
            }
        }

        var capMat: [Int8]? = nil
        if anyCap {
            var cm = [Int8](repeating: 0, count: n * 512)
            for (i, e) in entries.enumerated() {
                if let c = e.captionInt8 {
                    cm.replaceSubrange((i * 512)..<((i + 1) * 512), with: c)
                }
            }
            capMat = cm
        }

        let packed = PackedResident(
            entries: entries,
            docFreq: df,
            tagLabels: tagSet.sorted(),
            imageMatInt8: imgMat,
            imageRabit64: rabMat,
            hasImage: hasImg,
            imgCslsHubness: cslsArr,
            scenePoolInt8: scnPool,
            sceneOffsets: sOffsets,
            captionMatInt8: capMat,
            hasCaption: hasCap,
            capHubness: [Float](repeating: 0.65, count: max(1, n))
        )
        cachedResident = packed
        return packed
    }

    private static func readMemeRow(_ stmt: OpaquePointer?) -> MemeItem {
        let tagsStr = sqlite3_column_text(stmt, 7).map { String(cString: $0) } ?? "[]"
        let rejStr = sqlite3_column_text(stmt, 16).map { String(cString: $0) } ?? "[]"
        let tags = (try? JSONDecoder().decode([MemeTag].self, from: Data(tagsStr.utf8))) ?? []
        let rej = (try? JSONDecoder().decode([String].self, from: Data(rejStr.utf8))) ?? []
        return MemeItem(
            id: sqlite3_column_int64(stmt, 0),
            uri: sqlite3_column_text(stmt, 1).map { String(cString: $0) } ?? "",
            name: sqlite3_column_text(stmt, 2).map { String(cString: $0) } ?? "",
            kind: sqlite3_column_text(stmt, 3).map { String(cString: $0) } ?? "image",
            ocr: sqlite3_column_text(stmt, 4).map { String(cString: $0) } ?? "",
            caption: sqlite3_column_text(stmt, 5).map { String(cString: $0) } ?? "",
            transcript: sqlite3_column_text(stmt, 6).map { String(cString: $0) } ?? "",
            tags: tags,
            extraTerms: sqlite3_column_text(stmt, 8).map { String(cString: $0) } ?? "",
            thumbUri: sqlite3_column_text(stmt, 9).map { String(cString: $0) } ?? "",
            modifiedAt: sqlite3_column_int64(stmt, 10),
            pending: sqlite3_column_int(stmt, 11) != 0,
            visionState: sqlite3_column_text(stmt, 12).map { String(cString: $0) } ?? "pending",
            contentHash: sqlite3_column_text(stmt, 13).map { String(cString: $0) } ?? "",
            cloudCaption: sqlite3_column_text(stmt, 14).map { String(cString: $0) } ?? "",
            cloudTerms: sqlite3_column_text(stmt, 15).map { String(cString: $0) } ?? "",
            rejectedLabels: rej,
            copyCount: Int(sqlite3_column_int(stmt, 17)),
            lastCopiedAt: sqlite3_column_int64(stmt, 18)
        )
    }

    private static func floatsToData(_ floats: [Float]) -> Data {
        floats.withUnsafeBufferPointer { Data(buffer: $0) }
    }

    private static func readFloatBlob(_ stmt: OpaquePointer?, col: Int32) -> [Float]? {
        guard sqlite3_column_type(stmt, col) != SQLITE_NULL,
              let bytes = sqlite3_column_blob(stmt, col) else { return nil }
        let len = Int(sqlite3_column_bytes(stmt, col))
        guard len >= 4 && len % 4 == 0 else { return nil }
        let count = len / 4
        let ptr = bytes.assumingMemoryBound(to: Float.self)
        return Array(UnsafeBufferPointer(start: ptr, count: count))
    }
}
