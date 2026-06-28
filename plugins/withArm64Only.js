// Ship Android releases for arm64-v8a only.
//
// Every modern Android phone is arm64-v8a. The other three ABIs a default RN
// build packs in (armeabi-v7a, x86, x86_64) exist for old 32-bit devices and
// emulators we don't distribute to. Dropping them roughly halves the APK,
// because the heavyweight native payloads here ship a full copy *per ABI*:
// ExecuTorch's runtime (.so) and ML Kit (via expo-text-extractor) are the bulk
// of the binary, and they're prebuilt blobs inside their dependencies' AARs.
//
// Two knobs are needed, and they do different jobs:
//
//  1. `reactNativeArchitectures=arm64-v8a` (gradle.properties) tells React
//     Native to *compile* only arm64 from source — Hermes, fbjni, the app's
//     codegen. Faster CI builds, but it does NOT touch prebuilt third-party
//     .so files, which are the big ones here.
//
//  2. `ndk { abiFilters 'arm64-v8a' }` in defaultConfig is the authoritative
//     filter on the final merged jniLibs: it strips every non-arm64 .so from
//     the APK, including the prebuilt ExecuTorch / ML Kit blobs. This is what
//     actually shrinks the download.
//
// We deliberately do NOT use `splits.abi` (which emits several per-ABI APKs) —
// the release flow publishes a single sideloadable APK to a GitHub Release, so
// one arm64 universal APK is exactly what we want.
const { withGradleProperties, withAppBuildGradle } = require('expo/config-plugins');

const ABI = 'arm64-v8a';

function setReactNativeArchitectures(config) {
  return withGradleProperties(config, (cfg) => {
    const props = cfg.modResults;
    const existing = props.find(
      (p) => p.type === 'property' && p.key === 'reactNativeArchitectures'
    );
    if (existing) {
      existing.value = ABI;
    } else {
      props.push({ type: 'property', key: 'reactNativeArchitectures', value: ABI });
    }
    return cfg;
  });
}

function setAbiFilters(config) {
  return withAppBuildGradle(config, (cfg) => {
    let src = cfg.modResults.contents;

    // Idempotent: bail if we've already injected the filter.
    if (src.includes(`abiFilters '${ABI}'`) || src.includes(`abiFilters "${ABI}"`)) {
      return cfg;
    }

    const ndkBlock = `        ndk {\n            abiFilters '${ABI}'\n        }\n`;
    // Inject as the first line inside `defaultConfig { ... }`.
    const marker = /defaultConfig\s*\{/;
    if (!marker.test(src)) {
      throw new Error(
        '[withArm64Only] could not find a defaultConfig block in app/build.gradle to add abiFilters to.'
      );
    }
    src = src.replace(marker, (m) => `${m}\n${ndkBlock}`);
    cfg.modResults.contents = src;
    return cfg;
  });
}

module.exports = function withArm64Only(config) {
  config = setReactNativeArchitectures(config);
  config = setAbiFilters(config);
  return config;
};
