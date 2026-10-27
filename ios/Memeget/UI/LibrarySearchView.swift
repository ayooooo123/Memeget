import SwiftUI
import UIKit

public struct LibrarySearchView: View {
    @ObservedObject var coordinator: MemeLibraryCoordinator

    @State private var query: String = ProcessInfo.processInfo.environment["MEMEGET_INITIAL_QUERY"] ?? ""
    @State private var kindFilter: String? = nil
    @State private var selectedTag: String? = nil
    @State private var usedOnly: Bool = false
    @State private var memes: [MemeItem] = []
    @State private var topTags: [String] = []
    @State private var totalCount: Int = 0
    @State private var activeMeme: MemeItem? = nil
    @State private var showSettings: Bool = false
    @State private var copiedId: Int64? = nil
    @State private var searchTask: Task<Void, Never>? = nil

    private let columns = [
        GridItem(.flexible(), spacing: 6),
        GridItem(.flexible(), spacing: 6),
        GridItem(.flexible(), spacing: 6)
    ]

    public var body: some View {
        ZStack {
            MemegetPalette.bg.ignoresSafeArea()

            VStack(spacing: 0) {
                // Header: Brand + On-Device Badge + Settings Gear
                HStack {
                    HStack(spacing: 0) {
                        Text("Memeget")
                            .font(.system(size: 24, weight: .black))
                            .foregroundColor(MemegetPalette.textPrimary)
                        Text(".")
                            .font(.system(size: 24, weight: .black))
                            .foregroundColor(MemegetPalette.volt)
                    }

                    Spacer()

                    HStack(spacing: 6) {
                        Circle()
                            .fill(coordinator.isScanning ? MemegetPalette.warning : MemegetPalette.success)
                            .frame(width: 7, height: 7)
                        Text(coordinator.isScanning ? "scanning" : "on-device")
                            .font(.system(size: 11, weight: .medium))
                            .foregroundColor(MemegetPalette.textPrimary)
                    }
                    .padding(.horizontal, 9)
                    .padding(.vertical, 5)
                    .background(MemegetPalette.surface)
                    .clipShape(Capsule())
                    .overlay(Capsule().stroke(MemegetPalette.border, lineWidth: 0.6))

                    Button(action: { showSettings = true }) {
                        Image(systemName: "gearshape.fill")
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundColor(MemegetPalette.textSecondary)
                            .frame(width: 32, height: 32)
                    }
                    .accessibilityIdentifier("settings_button")
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 10)

                // Dead-Simple Search Bar with 1-tap Clipboard Reply Paste
                HStack(spacing: 8) {
                    Image(systemName: "magnifyingglass")
                        .foregroundColor(MemegetPalette.textSecondary)
                        .font(.system(size: 15))

                    TextField(totalCount > 0 ? "Search \(totalCount) memes, quotes, slang…" : "Search memes…", text: $query)
                        .foregroundColor(MemegetPalette.textPrimary)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .accessibilityIdentifier("meme_search_field")

                    if !query.isEmpty {
                        Button(action: { query = "" }) {
                            Image(systemName: "xmark.circle.fill")
                                .foregroundColor(MemegetPalette.textMuted)
                        }
                    } else {
                        Button(action: {
                            if let pasted = UIPasteboard.general.string?.trimmingCharacters(in: .whitespacesAndNewlines), !pasted.isEmpty {
                                usedOnly = false
                                selectedTag = nil
                                query = String(pasted.prefix(400))
                            }
                        }) {
                            Text("📋")
                                .font(.system(size: 14))
                        }
                        .accessibilityLabel("Paste post for contextual meme search")
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .background(MemegetPalette.surface)
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(MemegetPalette.border, lineWidth: 0.8))
                .padding(.horizontal, 14)
                .padding(.bottom, 6)

                // Quick Filter Chip Rail
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        chipButton(label: "⚡ Used", active: usedOnly) {
                            usedOnly.toggle()
                            if usedOnly { selectedTag = nil; query = "" }
                        }
                        chipButton(label: "▦ Images", active: kindFilter == "image") {
                            kindFilter = (kindFilter == "image") ? nil : "image"
                        }
                        chipButton(label: "▶ Videos", active: kindFilter == "video") {
                            kindFilter = (kindFilter == "video") ? nil : "video"
                        }
                        ForEach(topTags, id: \.self) { tag in
                            chipButton(label: tag, active: selectedTag == tag) {
                                if selectedTag == tag {
                                    selectedTag = nil
                                } else {
                                    usedOnly = false
                                    selectedTag = tag
                                    query = ""
                                }
                            }
                        }
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 4)
                }

                // Active Camera-Roll Scan Progress Banner
                if coordinator.isScanning {
                    HStack {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(coordinator.statusHeadline.isEmpty ? "Scanning Camera Roll…" : coordinator.statusHeadline)
                                .font(.system(size: 12, weight: .semibold))
                                .foregroundColor(MemegetPalette.textPrimary)
                            if coordinator.progressTotal > 0 {
                                ProgressView(value: Double(coordinator.progressDone), total: Double(max(1, coordinator.progressTotal)))
                                    .tint(MemegetPalette.volt)
                            }
                        }
                        Spacer()
                        Button("Stop") { coordinator.cancelScan() }
                            .font(.system(size: 12, weight: .bold))
                            .foregroundColor(MemegetPalette.danger)
                    }
                    .padding(10)
                    .background(MemegetPalette.surface)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                    .padding(.horizontal, 14)
                    .padding(.vertical, 4)
                }

                // Grid or Dead-Simple Onboarding
                if totalCount == 0 && !coordinator.isScanning {
                    Spacer()
                    onboardingCard
                    Spacer()
                } else if memes.isEmpty {
                    Spacer()
                    Text(query.isEmpty ? "No memes in this filter yet" : "No memes matched “\(query)”")
                        .font(.system(size: 14, weight: .medium))
                        .foregroundColor(MemegetPalette.textMuted)
                    Spacer()
                } else {
                    ScrollView {
                        LazyVGrid(columns: columns, spacing: 6) {
                            ForEach(memes) { meme in
                                memeTile(meme)
                            }
                        }
                        .padding(.horizontal, 10)
                        .padding(.top, 6)
                        .padding(.bottom, 24)
                    }
                }
            }
        }
        .onAppear { reloadResults() }
        .onChange(of: query) { _, _ in reloadResults() }
        .onChange(of: kindFilter) { _, _ in reloadResults() }
        .onChange(of: selectedTag) { _, _ in reloadResults() }
        .onChange(of: usedOnly) { _, _ in reloadResults() }
        .onChange(of: coordinator.libraryRevision) { _, _ in reloadResults() }
        .sheet(item: $activeMeme) { selected in
            MemeViewerSheet(
                meme: selected,
                coordinator: coordinator,
                onSearchTag: { tag in
                    activeMeme = nil
                    query = tag
                },
                onRemoved: {
                    activeMeme = nil
                    reloadResults()
                }
            )
        }
        .sheet(isPresented: $showSettings) {
            SettingsSheet(coordinator: coordinator)
        }
    }

    private var onboardingCard: some View {
        VStack(spacing: 14) {
            Text("🐸")
                .font(.system(size: 40))
            Text("Search your memes instantly")
                .font(.system(size: 18, weight: .bold))
                .foregroundColor(MemegetPalette.textPrimary)
            Text("Memeget scans your iPhone camera roll locally on-device, filters out personal photos & receipts using our 3-stage Meme Gate, and makes every meme searchable.")
                .font(.system(size: 13))
                .foregroundColor(MemegetPalette.textSecondary)
                .multilineTextAlignment(.center)

            Button(action: {
                if coordinator.photoAccessGranted {
                    coordinator.startCameraRollScan()
                } else {
                    coordinator.requestPhotoAccessAndScan()
                }
            }) {
                Text(coordinator.photoAccessGranted ? "Scan Camera Roll Now" : "Scan Camera Roll (100% Local)")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundColor(MemegetPalette.bg)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(MemegetPalette.volt)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
            }
            .accessibilityIdentifier("scan_camera_roll_button")
        }
        .padding(22)
        .background(MemegetPalette.surface)
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(MemegetPalette.border, lineWidth: 1))
        .padding(.horizontal, 24)
    }

    private func chipButton(label: String, active: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 12, weight: active ? .bold : .regular))
                .foregroundColor(active ? MemegetPalette.volt : MemegetPalette.textSecondary)
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(active ? MemegetPalette.volt.opacity(0.14) : MemegetPalette.surface)
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(active ? MemegetPalette.volt : MemegetPalette.border, lineWidth: 0.7))
        }
    }

    private func memeTile(_ meme: MemeItem) -> some View {
        ZStack(alignment: .topTrailing) {
            Button(action: { activeMeme = meme }) {
                GeometryReader { geo in
                    if let uiImg = MemeImageResolver.loadUIImage(for: meme, fullResolution: false) {
                        Image(uiImage: uiImg)
                            .resizable()
                            .scaledToFill()
                            .frame(width: geo.size.width, height: geo.size.width)
                            .clipped()
                    } else {
                        ZStack {
                            MemegetPalette.surface
                            Text(meme.name)
                                .font(.system(size: 10))
                                .foregroundColor(MemegetPalette.textMuted)
                                .padding(6)
                        }
                        .frame(width: geo.size.width, height: geo.size.width)
                    }
                }
                .aspectRatio(1.0, contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: 8))
            }
            .buttonStyle(.plain)

            // 1-Tap Fast-Draw Copy Pill
            Button(action: {
                if let img = MemeImageResolver.loadUIImage(for: meme, fullResolution: true) {
                    UIPasteboard.general.image = img
                    coordinator.store.recordCopy(id: meme.id)
                    copiedId = meme.id
                }
            }) {
                Text(copiedId == meme.id ? "✓" : "⧉")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundColor(copiedId == meme.id ? MemegetPalette.bg : MemegetPalette.textPrimary)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 3)
                    .background(copiedId == meme.id ? MemegetPalette.volt : Color.black.opacity(0.65))
                    .clipShape(Capsule())
            }
            .padding(5)
        }
    }

    private func reloadResults() {
        searchTask?.cancel()
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        totalCount = coordinator.store.countMemes()
        topTags = coordinator.store.topTagChips(limit: 20)

        if trimmed.isEmpty {
            memes = coordinator.store.browsePage(kind: kindFilter, tag: selectedTag, usedOnly: usedOnly, limit: 180)
            return
        }

        // Instant <2 ms lexical + slang pass
        let lexicalHits = coordinator.store.search(query: trimmed, queryVec: nil, kind: kindFilter, tag: selectedTag, limit: 90)
        memes = lexicalHits.map(\.meme)

        // Progressive dense + CSLS + Scene MaxSim pass when MobileCLIP-S2 text encoder is ready
        let kFilter = kindFilter
        let tFilter = selectedTag
        searchTask = Task {
            let distilled = SocialReplyIntent.distill(trimmed)
            if let cached = coordinator.engine.cachedQueryVector(distilled.embedText) {
                let dense = coordinator.store.search(query: trimmed, queryVec: cached, kind: kFilter, tag: tFilter, limit: 90)
                await MainActor.run { self.memes = dense.map(\.meme) }
                return
            }
            try? await Task.sleep(nanoseconds: 140_000_000)
            if Task.isCancelled { return }
            if let vec = try? await coordinator.engine.embedText(distilled.embedText), !Task.isCancelled {
                let dense = coordinator.store.search(query: trimmed, queryVec: vec, kind: kFilter, tag: tFilter, limit: 90)
                await MainActor.run { self.memes = dense.map(\.meme) }
            }
        }
    }
}
