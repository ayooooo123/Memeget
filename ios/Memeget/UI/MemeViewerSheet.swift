import SwiftUI
import UIKit

public struct MemeViewerSheet: View {
    @State var meme: MemeItem
    @ObservedObject var coordinator: MemeLibraryCoordinator
    let onSearchTag: (String) -> Void
    let onRemoved: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var newTagInput: String = ""
    @State private var copiedBanner: Bool = false

    public var body: some View {
        NavigationStack {
            ZStack {
                MemegetPalette.bg.ignoresSafeArea()

                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        // Full Meme Preview
                        if let img = MemeImageResolver.loadUIImage(for: meme, fullResolution: true) {
                            Image(uiImage: img)
                                .resizable()
                                .scaledToFit()
                                .frame(maxWidth: .infinity, maxHeight: 420)
                                .clipShape(RoundedRectangle(cornerRadius: 12))
                        }

                        // Primary Actions + 1-Tap "Not a meme" Quick-Fix
                        HStack(spacing: 10) {
                            Button(action: {
                                if let img = MemeImageResolver.loadUIImage(for: meme, fullResolution: true) {
                                    UIPasteboard.general.image = img
                                    coordinator.store.recordCopy(id: meme.id)
                                    copiedBanner = true
                                }
                            }) {
                                Label(copiedBanner ? "Copied!" : "Copy Meme", systemImage: "doc.on.doc")
                                    .font(.system(size: 13, weight: .bold))
                                    .foregroundColor(MemegetPalette.bg)
                                    .padding(.horizontal, 14)
                                    .padding(.vertical, 9)
                                    .background(MemegetPalette.volt)
                                    .clipShape(RoundedRectangle(cornerRadius: 8))
                            }

                            Button(action: {
                                Task {
                                    let cg = MemeImageResolver.loadUIImage(for: meme, fullResolution: false)?.cgImage
                                    if let out = await coordinator.cloud.analyzeMemeIfPermitted(cgImage: cg, ocrText: meme.ocr, candidateLabels: meme.tags.map(\.label)) {
                                        coordinator.store.applyCloudEnrichment(id: meme.id, caption: out.caption, terms: out.terms, tags: out.tags)
                                        if let refreshed = coordinator.store.meme(id: meme.id) {
                                            await MainActor.run {
                                                meme = refreshed
                                                coordinator.libraryRevision &+= 1
                                            }
                                        }
                                    }
                                }
                            }) {
                                Text("Cloud ID")
                                    .font(.system(size: 12, weight: .semibold))
                                    .foregroundColor(MemegetPalette.textPrimary)
                                    .padding(.horizontal, 10)
                                    .padding(.vertical, 8)
                                    .background(MemegetPalette.surface)
                                    .clipShape(RoundedRectangle(cornerRadius: 8))
                            }
                            Spacer()
                            Button(action: {
                                coordinator.store.markNotAMeme(id: meme.id)
                                coordinator.libraryRevision &+= 1
                                onRemoved()
                            }) {
                                Text("Not a meme")
                                    .font(.system(size: 12, weight: .bold))
                                    .foregroundColor(MemegetPalette.warning)
                                    .padding(.horizontal, 12)
                                    .padding(.vertical, 8)
                                    .overlay(RoundedRectangle(cornerRadius: 8).stroke(MemegetPalette.warning, lineWidth: 1))
                            }
                            .accessibilityIdentifier("not_a_meme_button")
                        }

                        // Quick-Fix Tags
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Identified Concepts & Quick-Fix")
                                .font(.system(size: 13, weight: .bold))
                                .foregroundColor(MemegetPalette.textSecondary)

                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 6) {
                                    ForEach(meme.tags) { tag in
                                        HStack(spacing: 4) {
                                            Button(action: { onSearchTag(tag.label) }) {
                                                Text(tag.label)
                                                    .font(.system(size: 12, weight: .medium))
                                                    .foregroundColor(MemegetPalette.volt)
                                            }
                                            Button(action: {
                                                coordinator.store.removeTag(id: meme.id, label: tag.label)
                                                if let refreshed = coordinator.store.meme(id: meme.id) {
                                                    meme = refreshed
                                                }
                                                coordinator.libraryRevision &+= 1
                                            }) {
                                                Text("✕")
                                                    .font(.system(size: 10, weight: .bold))
                                                    .foregroundColor(MemegetPalette.textMuted)
                                            }
                                        }
                                        .padding(.horizontal, 9)
                                        .padding(.vertical, 5)
                                        .background(MemegetPalette.surface)
                                        .clipShape(Capsule())
                                    }
                                }
                            }

                            HStack(spacing: 8) {
                                TextField("Add tag (e.g. tony soprano, cooked)…", text: $newTagInput)
                                    .font(.system(size: 13))
                                    .padding(8)
                                    .background(MemegetPalette.surface)
                                    .clipShape(RoundedRectangle(cornerRadius: 8))
                                Button("Add") {
                                    let trimmed = newTagInput.trimmingCharacters(in: .whitespacesAndNewlines)
                                    guard !trimmed.isEmpty else { return }
                                    coordinator.store.addManualTag(id: meme.id, label: trimmed)
                                    newTagInput = ""
                                    if let refreshed = coordinator.store.meme(id: meme.id) {
                                        meme = refreshed
                                    }
                                    coordinator.libraryRevision &+= 1
                                }
                                .font(.system(size: 13, weight: .bold))
                                .foregroundColor(MemegetPalette.volt)
                            }
                        }

                        if !meme.caption.isEmpty {
                            VStack(alignment: .leading, spacing: 4) {
                                Text("Two-Domain Identification")
                                    .font(.system(size: 12, weight: .bold))
                                    .foregroundColor(MemegetPalette.textSecondary)
                                Text(meme.caption)
                                    .font(.system(size: 13))
                                    .foregroundColor(MemegetPalette.textPrimary)
                            }
                            .padding(12)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(MemegetPalette.surface)
                            .clipShape(RoundedRectangle(cornerRadius: 10))
                        }

                        if !meme.ocr.isEmpty {
                            VStack(alignment: .leading, spacing: 4) {
                                Text("Text Inside Meme (Vision OCR)")
                                    .font(.system(size: 12, weight: .bold))
                                    .foregroundColor(MemegetPalette.textSecondary)
                                Text(meme.ocr)
                                    .font(.system(size: 13))
                                    .foregroundColor(MemegetPalette.textPrimary)
                            }
                            .padding(12)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(MemegetPalette.surface)
                            .clipShape(RoundedRectangle(cornerRadius: 10))
                        }
                    }
                    .padding(16)
                }
            }
            .navigationTitle(meme.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                        .foregroundColor(MemegetPalette.volt)
                }
            }
        }
    }
}

public struct SettingsSheet: View {
    @ObservedObject var coordinator: MemeLibraryCoordinator
    @Environment(\.dismiss) private var dismiss

    @State private var cloudConfig = IosCloudConfig()
    @State private var cfToken: String = ""
    @State private var vlmToken: String = ""

    public var body: some View {
        NavigationStack {
            Form {
                Section("Camera Roll & 3-Stage Meme Gate") {
                    HStack {
                        Text("Indexed Memes")
                        Spacer()
                        Text("\(coordinator.store.countMemes())")
                            .foregroundColor(.secondary)
                    }
                    Button(coordinator.isScanning ? "Scanning…" : "Re-scan Camera Roll Now") {
                        coordinator.startCameraRollScan()
                        dismiss()
                    }
                    .disabled(coordinator.isScanning)
                }

                Section("Optional Cloud Hybrid Cascade (Clairic → Clef → VLM)") {
                    Toggle("Enable Hybrid Cloud Escalation", isOn: $cloudConfig.enabled)
                    Toggle("Allow Visual Frames (Gate-Passed Memes Only)", isOn: $cloudConfig.consentVisualMedia)
                    Toggle("Allow OCR Text Evidence", isOn: $cloudConfig.consentTextState)
                    Toggle("Use Cloudflare Clairic Typed Extraction", isOn: $cloudConfig.useClairicExtraction)
                    TextField("Cloudflare Account ID (32 hex)", text: $cloudConfig.cloudflareAccountId)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    SecureField("Cloudflare Workers AI Token", text: $cfToken)
                    TextField("Two-Domain VLM Endpoint URL", text: $cloudConfig.vlmEndpointUrl)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    SecureField("Two-Domain VLM API Key", text: $vlmToken)

                    Button("Save Cloud Settings") {
                        Task {
                            await coordinator.cloud.saveConfig(cloudConfig, cloudflareToken: cfToken, vlmToken: vlmToken)
                            cfToken = ""
                            vlmToken = ""
                        }
                    }
                }
            }
            .navigationTitle("Settings")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Close") { dismiss() }
                }
            }
            .task {
                cloudConfig = await coordinator.cloud.loadConfig()
            }
        }
    }
}
