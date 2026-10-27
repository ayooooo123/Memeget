import Photos
import SwiftUI
import UIKit
public enum MemegetPalette {
    public static let bg = Color(red: 0.051, green: 0.059, blue: 0.078)          // #0D0F14
    public static let surface = Color(red: 0.086, green: 0.098, blue: 0.133)     // #161922
    public static let surfaceBright = Color(red: 0.13, green: 0.15, blue: 0.20)
    public static let border = Color.white.opacity(0.10)
    public static let volt = Color(red: 0.831, green: 1.0, blue: 0.0)            // #D4FF00
    public static let textPrimary = Color.white
    public static let textSecondary = Color(white: 0.72)
    public static let textMuted = Color(white: 0.48)
    public static let danger = Color(red: 1.0, green: 0.32, blue: 0.36)
    public static let warning = Color(red: 1.0, green: 0.72, blue: 0.20)
    public static let success = Color(red: 0.22, green: 0.88, blue: 0.52)
}

@main
struct MemegetIosApp: App {
    @StateObject private var coordinator: MemeLibraryCoordinator

    init() {
        let engine = IosInferenceEngine()
        let store = try! SqliteMemeStore(bridge: engine.bridge)
        let cloud = IosCloudAssistant(store: store)
        let coord = MemeLibraryCoordinator(store: store, engine: engine, cloud: cloud)
        _coordinator = StateObject(wrappedValue: coord)
    }

    var body: some Scene {
        WindowGroup {
            LibrarySearchView(coordinator: coordinator)
                .preferredColorScheme(.dark)
                .task {
                    // Support deterministic Simulator E2E fixture directory via MEMEGET_SEED_DIR
                    if let seedDir = ProcessInfo.processInfo.environment["MEMEGET_SEED_DIR"],
                       !seedDir.isEmpty {
                        let folder = URL(fileURLWithPath: seedDir)
                        if let items = try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil) {
                            let media = items.filter { ["jpg", "jpeg", "png", "webp", "heic", "mp4", "mov"].contains($0.pathExtension.lowercased()) }
                            if !media.isEmpty {
                                _ = await coordinator.ingestFileURLs(media, useStage2And3IfAvailable: false)
                            }
                        }
                    } else {
                        let st = PHPhotoLibrary.authorizationStatus(for: .readWrite)
                        if st == .authorized || st == .limited {
                            coordinator.photoAccessGranted = true
                            coordinator.startCameraRollScan()
                        } else if ProcessInfo.processInfo.environment["MEMEGET_AUTO_SCAN"] == "1" {
                            coordinator.requestPhotoAccessAndScan()
                        }
                    }
                }
        }
    }
}
