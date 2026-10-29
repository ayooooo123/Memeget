# Memeget

Private, on-device meme search for images and videos. The maintained apps are native:

- **Android:** [`native/`](native/) — Kotlin, Jetpack Compose, C++/JNI, SQLite. See the [Android build, usage, and verification guide](native/README.md). [Download the latest APK](https://github.com/ayooooo123/Memeget/releases/download/latest/memeget-latest.apk).
- **iOS:** [`ios/`](ios/) — SwiftUI, Objective-C++, Metal, PhotoKit, and the shared C++ meme gate in [`native/core/`](native/core/). Generate the Xcode project with `cd ios && xcodegen generate`; build and test the `Memeget` scheme in Xcode or with `xcodebuild`.

The apps index media locally. Optional cloud assistance requires an explicit opt-in; see each app's settings. Android supports sharing image/video files and post links directly into the collection, including X posts and unwatermarked blank Imgflip templates from `/memegenerator/<id>` links. An Imgflip `/i/<id>` post saves the published image, including any watermark already baked into it.

## Android build

From the repository root:

```sh
cd native
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Requires JDK 17 and the Android SDK/NDK versions listed in [`native/README.md`](native/README.md). `main` builds a signed release APK in [GitHub Actions](.github/workflows/android-apk.yml); tagged `vX.Y.Z` releases are permanent.

## Source layout

- [`native/`](native/): Android app and shared C++ inference/search core.
- [`ios/`](ios/): standalone native iOS app and tests.
- [`modules/memeget-bg/android/src/main/`](modules/memeget-bg/android/src/main/): reusable Android media utilities and fonts compiled directly into `native/`. The historical Kotlin package name remains, but no Expo module or React Native app is built.
- [`tools/`](tools/): standalone dataset, export, and local extraction tools. [`docs/`](docs/) contains research and historical design notes; older documents mentioning Expo describe the retired app, not the maintained build.
- [`signing/debug.keystore`](signing/debug.keystore): pinned Android release signing key; keep it unchanged to preserve in-place updates and users' libraries.

The Expo/React Native application and its build workflow have been retired. Existing installations remain separate packages; removing their source from this repository does not uninstall an app or migrate its private storage. For moving an existing collection into the native Android app, follow [Local use and migration](native/README.md#local-use-and-migration).
