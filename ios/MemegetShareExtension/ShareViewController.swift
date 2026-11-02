import UIKit
import UniformTypeIdentifiers

// Featherweight Share Extension relay: catches shared video/meme links or media from YouTube, TikTok, X, Reddit,
// Instagram, Safari, or Photos, queues them in the App Group if present, and opens Memeget's uBit downloader.

public final class ShareViewController: UIViewController {
    private static let appGroupID = "group.com.memeget.iosapp"
    private let statusLabel = UILabel()

    public override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor.black.withAlphaComponent(0.45)

        let card = UIView()
        card.translatesAutoresizingMaskIntoConstraints = false
        card.backgroundColor = UIColor(red: 0.086, green: 0.098, blue: 0.133, alpha: 1.0)
        card.layer.cornerRadius = 16
        card.layer.borderWidth = 1
        card.layer.borderColor = UIColor.white.withAlphaComponent(0.12).cgColor
        view.addSubview(card)

        let spinner = UIActivityIndicatorView(style: .medium)
        spinner.translatesAutoresizingMaskIntoConstraints = false
        spinner.color = UIColor(red: 0.831, green: 1.0, blue: 0.0, alpha: 1.0)
        spinner.startAnimating()
        card.addSubview(spinner)

        statusLabel.translatesAutoresizingMaskIntoConstraints = false
        statusLabel.text = "Sending to Memeget…"
        statusLabel.textColor = .white
        statusLabel.font = .systemFont(ofSize: 15, weight: .bold)
        statusLabel.textAlignment = .center
        statusLabel.numberOfLines = 2
        card.addSubview(statusLabel)

        NSLayoutConstraint.activate([
            card.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            card.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            card.widthAnchor.constraint(equalToConstant: 250),
            card.heightAnchor.constraint(equalToConstant: 112),
            spinner.topAnchor.constraint(equalTo: card.topAnchor, constant: 24),
            spinner.centerXAnchor.constraint(equalTo: card.centerXAnchor),
            statusLabel.topAnchor.constraint(equalTo: spinner.bottomAnchor, constant: 12),
            statusLabel.leadingAnchor.constraint(equalTo: card.leadingAnchor, constant: 16),
            statusLabel.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -16),
        ])

        Task {
            await handleSharedItems()
        }
    }

    private func handleSharedItems() async {
        guard let items = extensionContext?.inputItems as? [NSExtensionItem] else {
            complete()
            return
        }

        var sharedLinks: [String] = []
        var stagedFiles: [URL] = []

        for item in items {
            for provider in item.attachments ?? [] {
                if provider.hasItemConformingToTypeIdentifier(UTType.movie.identifier),
                   let fileURL = await loadFileCopy(from: provider, typeIdentifier: UTType.movie.identifier, defaultExt: "mp4") {
                    stagedFiles.append(fileURL)
                    continue
                }
                if provider.hasItemConformingToTypeIdentifier(UTType.image.identifier),
                   !provider.hasItemConformingToTypeIdentifier(UTType.url.identifier),
                   let fileURL = await loadFileCopy(from: provider, typeIdentifier: UTType.image.identifier, defaultExt: "jpg") {
                    stagedFiles.append(fileURL)
                    continue
                }
                if provider.hasItemConformingToTypeIdentifier(UTType.url.identifier),
                   let u = await loadURL(from: provider) {
                    if u.isFileURL {
                        if let copied = stageLocalFile(u) { stagedFiles.append(copied) }
                    } else if let extracted = Self.extractWebURL(from: u.absoluteString) {
                        sharedLinks.append(extracted)
                    }
                    continue
                }
                if provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier),
                   let txt = await loadText(from: provider),
                   let extracted = Self.extractWebURL(from: txt) {
                    sharedLinks.append(extracted)
                }
            }
        }

        enqueueInAppGroup(links: sharedLinks, files: stagedFiles)

        if let firstLink = sharedLinks.first,
           let encoded = firstLink.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed),
           let schemeURL = URL(string: "memeget://import?url=\(encoded)") {
            _ = await MainActor.run { openHostURL(schemeURL) }
        } else if !stagedFiles.isEmpty,
                  let schemeURL = URL(string: "memeget://import?queued=1") {
            _ = await MainActor.run { openHostURL(schemeURL) }
        }

        try? await Task.sleep(nanoseconds: 250_000_000)
        complete()
    }

    private func complete() {
        DispatchQueue.main.async { [weak self] in
            self?.extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
        }
    }

    @MainActor
    private func openHostURL(_ url: URL) -> Bool {
        extensionContext?.open(url, completionHandler: nil)
        var responder: UIResponder? = self
        let openSel = NSSelectorFromString("openURL:")
        while let current = responder {
            if current.responds(to: openSel) {
                _ = current.perform(openSel, with: url)
                return true
            }
            responder = current.next
        }
        return false
    }

    private func enqueueInAppGroup(links: [String], files: [URL]) {
        guard let groupRoot = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: Self.appGroupID) else {
            return
        }
        let inbox = groupRoot.appendingPathComponent("share_inbox", isDirectory: true)
        try? FileManager.default.createDirectory(at: inbox, withIntermediateDirectories: true)
        if !links.isEmpty {
            let payload = links.joined(separator: "\n")
            let linkFile = inbox.appendingPathComponent("links_\(UUID().uuidString.prefix(10)).txt")
            try? payload.write(to: linkFile, atomically: true, encoding: .utf8)
        }
        _ = files
    }

    private func stageLocalFile(_ src: URL) -> URL? {
        guard let groupRoot = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: Self.appGroupID) else {
            return nil
        }
        let inbox = groupRoot.appendingPathComponent("share_inbox", isDirectory: true)
        try? FileManager.default.createDirectory(at: inbox, withIntermediateDirectories: true)
        let ext = src.pathExtension.isEmpty ? "bin" : src.pathExtension
        let dst = inbox.appendingPathComponent("media_\(UUID().uuidString.prefix(10)).\(ext)")
        try? FileManager.default.copyItem(at: src, to: dst)
        return FileManager.default.fileExists(atPath: dst.path) ? dst : nil
    }

    private func loadURL(from provider: NSItemProvider) async -> URL? {
        await withCheckedContinuation { cont in
            provider.loadItem(forTypeIdentifier: UTType.url.identifier, options: nil) { item, _ in
                if let u = item as? URL { cont.resume(returning: u) }
                else if let data = item as? Data, let u = URL(dataRepresentation: data, relativeTo: nil) { cont.resume(returning: u) }
                else if let str = item as? String, let u = URL(string: str) { cont.resume(returning: u) }
                else { cont.resume(returning: nil) }
            }
        }
    }

    private func loadText(from provider: NSItemProvider) async -> String? {
        await withCheckedContinuation { cont in
            provider.loadItem(forTypeIdentifier: UTType.plainText.identifier, options: nil) { item, _ in
                if let s = item as? String { cont.resume(returning: s) }
                else if let data = item as? Data, let s = String(data: data, encoding: .utf8) { cont.resume(returning: s) }
                else { cont.resume(returning: nil) }
            }
        }
    }

    private func loadFileCopy(from provider: NSItemProvider, typeIdentifier: String, defaultExt: String) async -> URL? {
        await withCheckedContinuation { cont in
            provider.loadFileRepresentation(forTypeIdentifier: typeIdentifier) { tempURL, _ in
                guard let tempURL else {
                    cont.resume(returning: nil)
                    return
                }
                cont.resume(returning: self.stageLocalFile(tempURL))
            }
        }
    }

    private static func extractWebURL(from text: String) -> String? {
        guard let re = try? NSRegularExpression(pattern: "(https?://[^\\s\"'<>]+)", options: [.caseInsensitive]),
              let m = re.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)),
              let r = Range(m.range(at: 1), in: text) else {
            return nil
        }
        var candidate = String(text[r])
        while let last = candidate.last, [")", "]", ".", ",", "!", ";"].contains(String(last)) {
            candidate.removeLast()
        }
        return candidate
    }
}
