import Foundation

// Exact byte-level BPE mirror of Kotlin's ClipTokenizer.kt so iOS search queries & label prompts
// produce identical 77-token MobileCLIP-S2 IDs (`cosine = 1.000000` across platforms).
public final class ClipTokenizer: @unchecked Sendable {
    public static let contextLength = 77
    public static let startToken: Int64 = 49406
    public static let endToken: Int64 = 49407
    private static let startText = "<|startoftext|>"
    private static let endText = "<|endoftext|>"
    private static let wordEnd = "</w>"

    private let vocab: [String: Int]
    private let ranks: [String: Int]
    private let lock = NSLock()
    private var cache: [String: [Int]] = [:]

    private static let byteToChar: [Character] = {
        var printable = Set<Int>()
        for c in Int(UInt8(ascii: "!"))...Int(UInt8(ascii: "~")) { printable.insert(c) }
        for c in 0xA1...0xAC { printable.insert(c) }
        for c in 0xAE...0xFF { printable.insert(c) }
        var table: [Character] = []
        table.reserveCapacity(256)
        var next = 256
        for b in 0..<256 {
            if printable.contains(b), let sc = UnicodeScalar(b) {
                table.append(Character(sc))
            } else {
                table.append(Character(UnicodeScalar(next)!))
                next += 1
            }
        }
        return table
    }()

    private static let wordRegex: NSRegularExpression = {
        let pattern = "<\\|startoftext\\|>|<\\|endoftext\\|>|'s|'t|'re|'ve|'m|'ll|'d|[\\p{L}]+|[\\p{N}]|[^\\s\\p{L}\\p{N}]+"
        return try! NSRegularExpression(pattern: pattern, options: [])
    }()

    public init(jsonData: Data) throws {
        guard let root = try JSONSerialization.jsonObject(with: jsonData) as? [String: Any],
              let model = root["model"] as? [String: Any],
              (model["type"] as? String) == "BPE",
              let vocabObj = model["vocab"] as? [String: Int],
              let mergesArr = model["merges"] as? [Any] else {
            throw NSError(domain: "com.memeget.tokenizer", code: 1, userInfo: [NSLocalizedDescriptionKey: "Invalid CLIP BPE tokenizer.json"])
        }
        self.vocab = vocabObj
        var mergeRanks: [String: Int] = [:]
        mergeRanks.reserveCapacity(mergesArr.count * 2)
        for (idx, item) in mergesArr.enumerated() {
            let key: String
            if let pair = item as? [String], pair.count == 2 {
                key = "\(pair[0]) \(pair[1])"
            } else if let str = item as? String {
                key = str
            } else {
                continue
            }
            if mergeRanks[key] == nil {
                mergeRanks[key] = idx
            }
        }
        self.ranks = mergeRanks
    }

    public convenience init(fileURL: URL) throws {
        try self.init(jsonData: try Data(contentsOf: fileURL))
    }

    public func encode(_ text: String) -> [Int64] {
        var ids = [Int64](repeating: 0, count: Self.contextLength)
        ids[0] = Self.startToken
        var n = 1
        let normalized = text.precomposedStringWithCanonicalMapping
            .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
            .lowercased()
        let ns = normalized as NSString
        let matches = Self.wordRegex.matches(in: normalized, options: [], range: NSRange(location: 0, length: ns.length))
        outer: for m in matches {
            let piece = ns.substring(with: m.range)
            for id in bpeWord(piece) {
                if n == Self.contextLength - 1 { break outer }
                ids[n] = Int64(id)
                n += 1
            }
        }
        ids[n] = Self.endToken
        return ids
    }

    private func bpeWord(_ piece: String) -> [Int] {
        if piece == Self.startText { return [Int(Self.startToken)] }
        if piece == Self.endText { return [Int(Self.endToken)] }
        lock.lock()
        if let hit = cache[piece] {
            lock.unlock()
            return hit
        }
        lock.unlock()

        let utf8 = Array(piece.utf8)
        if utf8.isEmpty { return [] }
        var symbols = utf8.map { String(Self.byteToChar[Int($0)]) }
        symbols[symbols.count - 1] += Self.wordEnd

        while symbols.count > 1 {
            var bestRank = Int.max
            var first = ""
            var second = ""
            for i in 0..<(symbols.count - 1) {
                let pairKey = "\(symbols[i]) \(symbols[i + 1])"
                if let r = ranks[pairKey], r < bestRank {
                    bestRank = r
                    first = symbols[i]
                    second = symbols[i + 1]
                }
            }
            if bestRank == Int.max { break }
            var merged: [String] = []
            merged.reserveCapacity(symbols.count)
            var i = 0
            while i < symbols.count {
                if i < symbols.count - 1 && symbols[i] == first && symbols[i + 1] == second {
                    merged.append(first + second)
                    i += 2
                } else {
                    merged.append(symbols[i])
                    i += 1
                }
            }
            symbols = merged
        }

        let encoded = symbols.map { vocab[$0] ?? Int(Self.endToken) }
        lock.lock()
        if cache.count > 4096 { cache.removeAll(keepingCapacity: true) }
        cache[piece] = encoded
        lock.unlock()
        return encoded
    }
}
