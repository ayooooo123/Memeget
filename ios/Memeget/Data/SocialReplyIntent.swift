import Foundation

public struct DistilledSearchQuery {
    public let rawQuery: String
    public let exactTerms: [String]
    public let socialTerms: Set<String>
    public let embedText: String
    public let isPastedPost: Bool
    public let pureSlangReaction: Bool
}

// Mirrors Kotlin's SocialReplyIntent.kt so pasting a tweet/message or typing slang ("cooked", "joever",
// "copium", "we are so back") bridges straight to visual reaction emotions & concepts on iOS too.
public enum SocialReplyIntent {
    public static let scaffoldWords: Set<String> = [
        "meme", "image", "picture", "photo", "video", "gif", "clip", "guy", "man", "woman", "person", "someone"
    ]

    private static let slangMap: [String: (bridgePrompt: String, terms: [String])] = [
        "cooked": ("defeated overwhelmed doomed person staring in despair", ["cooked", "doomed", "defeated", "despair", "overwhelmed"]),
        "joever": ("slumped defeated man it is all over reaction", ["joever", "over", "defeated", "despair", "sad"]),
        "copium": ("inhaling copium mask in delusional denial", ["copium", "cope", "denial", "delusional"]),
        "hopium": ("naive wishful optimism inhaling hopium", ["hopium", "hope", "optimism"]),
        "so back": ("triumphant hyped celebration we are so back", ["celebrating", "triumphant", "hyped", "back"]),
        "side eye": ("suspicious skeptical sideways glance", ["side", "eye", "suspicious", "skeptical"]),
        "locked in": ("leaning forward intensely focused gamer concentration", ["focused", "serious", "leaning", "locked"]),
        "disbelief": ("stunned shocked person staring in disbelief", ["disbelief", "shocked", "stunned", "confused"]),
        "smug": ("cocky self-satisfied smirk grin", ["smug", "smirk", "grin", "superior"]),
        "crying": ("sobbing tears streaming down face emotional breakdown", ["crying", "tears", "sobbing", "sad"])
    ]

    public static func distill(_ raw: String) -> DistilledSearchQuery {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        let tokens = wordTokens(trimmed)
        let isPost = tokens.count >= 7 || trimmed.contains("http://") || trimmed.contains("https://")
        let lower = trimmed.lowercased()

        var social = Set<String>()
        var bridges: [String] = []
        for (slang, entry) in slangMap {
            if lower == slang || lower.contains(slang) {
                entry.terms.forEach { social.insert($0) }
                bridges.append(entry.bridgePrompt)
            }
        }

        let pureSlang = !bridges.isEmpty && tokens.count <= 3
        let filteredTokens: [String]
        if isPost {
            let stop: Set<String> = ["the", "and", "for", "that", "this", "with", "you", "are", "was", "have", "just", "from", "https", "http", "com"]
            filteredTokens = tokens.filter { !stop.contains($0) }.prefix(10).map { $0 }
        } else {
            filteredTokens = tokens
        }

        let embed = if pureSlang {
            bridges.joined(separator: ", ")
        } else if !bridges.isEmpty {
            "\(trimmed) — \(bridges.joined(separator: ", "))"
        } else {
            trimmed
        }

        return DistilledSearchQuery(
            rawQuery: trimmed,
            exactTerms: filteredTokens,
            socialTerms: social,
            embedText: embed.isEmpty ? "meme" : String(embed.prefix(240)),
            isPastedPost: isPost,
            pureSlangReaction: pureSlang
        )
    }

    public static func wordTokens(_ text: String) -> [String] {
        let lower = text.lowercased()
        let regex = try! NSRegularExpression(pattern: "[a-z0-9]{2,}", options: [])
        let ns = lower as NSString
        var out: [String] = []
        var seen = Set<String>()
        for m in regex.matches(in: lower, range: NSRange(location: 0, length: ns.length)) {
            let w = ns.substring(with: m.range)
            if seen.insert(w).inserted { out.append(w) }
        }
        return out
    }
}
