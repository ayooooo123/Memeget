import ExpoModulesCore
import UIKit
import UniformTypeIdentifiers

// iOS counterpart. Battery + thermal reads mirror the Android module. iOS does
// not allow arbitrary sustained background compute, so `startForeground` only
// requests a short background-execution extension (a few minutes at most). True
// background indexing on iOS would use BGProcessingTask while on charger.
public class MemegetBgModule: Module {
  private var bgTask: UIBackgroundTaskIdentifier = .invalid

  public func definition() -> ModuleDefinition {
    Name("MemegetBg")

    Function("getPower") { () -> [String: Any] in
      UIDevice.current.isBatteryMonitoringEnabled = true
      let raw = UIDevice.current.batteryLevel // 0..1, -1 if unknown
      let state = UIDevice.current.batteryState
      let charging = (state == .charging || state == .full)

      let thermal: Int
      switch ProcessInfo.processInfo.thermalState {
      case .nominal: thermal = 0
      case .fair: thermal = 1
      case .serious: thermal = 2
      case .critical: thermal = 3
      @unknown default: thermal = 0
      }

      return [
        "charging": charging,
        "level": Double(raw),
        "thermal": thermal,
        "headroom": -1.0
      ]
    }

    Function("startForeground") { (_ title: String, _ text: String) in
      DispatchQueue.main.async {
        if self.bgTask != .invalid { return }
        self.bgTask = UIApplication.shared.beginBackgroundTask(withName: "memeget.bg-index") {
          UIApplication.shared.endBackgroundTask(self.bgTask)
          self.bgTask = .invalid
        }
      }
    }

    Function("stopForeground") {
      DispatchQueue.main.async {
        if self.bgTask != .invalid {
          UIApplication.shared.endBackgroundTask(self.bgTask)
          self.bgTask = .invalid
        }
      }
    }

    // Parity with the Android module: place a file's bytes (a video) on the
    // general pasteboard under its UTType. iOS pasteboards hold data directly,
    // so no provider/uri indirection is needed. The app currently ships
    // Android-only; this keeps the JS API uniform if an iOS build lands.
    AsyncFunction("copyFileToClipboard") { (uriStr: String, name: String, mimeType: String) in
      guard let url = URL(string: uriStr), url.isFileURL else {
        throw NSError(
          domain: "MemegetBg", code: 1,
          userInfo: [NSLocalizedDescriptionKey: "copyFileToClipboard needs a file:// uri, got \(uriStr)"]
        )
      }
      let data = try Data(contentsOf: url)
      let type = UTType(mimeType: mimeType)?.identifier ?? UTType.mpeg4Movie.identifier
      UIPasteboard.general.setData(data, forPasteboardType: type)
    }
  }
}
