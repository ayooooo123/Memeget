// Pin the Android signing key across builds so app updates always install OVER
// the existing app instead of forcing an uninstall.
//
// Why this matters: the CLIP model (~hundreds of MB) is downloaded once and
// cached in the app's internal storage ({documentDirectory}/react-native-
// executorch/), and the whole search index lives in the app's SQLite db. Android
// preserves both across an update ONLY when the new APK is signed with the SAME
// key as the installed one. If the signing key ever changes, Android refuses the
// update and the user must uninstall first — which wipes the cached model (forcing
// a re-download, breaking the "online only once" promise) and the entire index.
//
// `expo prebuild` regenerates android/app/debug.keystore from the Expo template
// each run, and the release build is signed with it (buildTypes.release ->
// signingConfigs.debug). That's stable today, but it's an implicit dependency on
// the template never changing its keystore (e.g. across an SDK bump). This plugin
// removes the risk: it overwrites the generated keystore with the committed
// signing/debug.keystore on every prebuild, so the signing identity is fixed
// forever. The committed file is the standard Android debug keystore
// (SHA1 5E:8F:16:06:2E:A3:CD:2C:4A:0D:54:78:76:BA:A6:F3:8C:AB:F6:25), byte-for-
// byte the same key current builds already use — so pinning it changes nothing
// about existing installs (no forced reinstall), it just locks it in.
const { withDangerousMod } = require('expo/config-plugins');
const fs = require('fs');
const path = require('path');

module.exports = function withFixedDebugKeystore(config) {
  return withDangerousMod(config, [
    'android',
    async (cfg) => {
      const src = path.join(cfg.modRequest.projectRoot, 'signing', 'debug.keystore');
      const dest = path.join(cfg.modRequest.platformProjectRoot, 'app', 'debug.keystore');
      if (!fs.existsSync(src)) {
        throw new Error(
          `[withFixedDebugKeystore] expected a committed keystore at ${src}. ` +
            `Without it the signing key would drift between builds and updates ` +
            `would wipe the cached model and index.`
        );
      }
      fs.copyFileSync(src, dest);
      return cfg;
    },
  ]);
};
