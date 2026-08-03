// The Android applicationId is load-bearing in a way that is easy to miss.
//
// Android scopes an app's private storage to its package name. Change it and
// the OS does not "rename" anything — it installs what it considers a brand
// new, unrelated app, with an empty sandbox. The SQLite index (every tag,
// caption, transcript, embedding) and every taught exemplar live in that
// sandbox, so a build that ships under a different id presents as total data
// loss: same icon, same name, empty library. That has happened once, and the
// only reason nothing was lost is that the original install was still on the
// device.
//
// So the package id is pinned here as a test, not a convention. It must be
// exactly this, forever. A rename is not a refactor; it is a data migration
// that this app has no way to perform.
import appConfig from '../app.json';

// eslint-disable-next-line @typescript-eslint/no-var-requires
const { addDebugApplicationIdSuffix, debugStringsXml } = require('../plugins/withSeparateDebugApp');

export const ANDROID_PACKAGE = 'com.memeget.app';

describe('android applicationId', () => {
  it('is the one and only package this app ships under', () => {
    expect(appConfig.expo.android.package).toBe(ANDROID_PACKAGE);
  });

  it('is a com.memeget.* id — nothing from any other namespace', () => {
    // Belt and braces: an id that merely *differs* would already fail the check
    // above, but stating the namespace makes the intent obvious to anyone
    // reading a failure, and catches a plausible-looking near-miss.
    const pkg = appConfig.expo.android.package;
    expect(pkg.startsWith('com.memeget.')).toBe(true);
    expect(pkg.split('.').length).toBe(3);
  });

  it('is the id the scheme and slug agree with', () => {
    expect(appConfig.expo.slug).toBe('memeget');
    expect(appConfig.expo.scheme).toBe('memeget');
  });
});

// The other half of the same promise. Pinning the release id stops the app from
// SHIPPING under a new package; this stops a DEV build from colliding with the
// installed one. Both failure modes look identical to a user — same icon, same
// name, empty library — and the second is the easier to cause by accident,
// because it takes only an `install -r` from a laptop.
//
// This tests the config plugin, NOT the generated android/ tree: android/ is
// gitignored, so a test that read app/build.gradle would pass off a stale local
// working copy and prove nothing in a clean checkout.
describe('android debug builds are sandboxed away from the real app', () => {
  const RELEASE_GRADLE = [
    'android {',
    '    signingConfigs {',
    '        debug {',
    '            storeFile file(\'debug.keystore\')',
    '        }',
    '    }',
    '    buildTypes {',
    '        debug {',
    '            signingConfig signingConfigs.debug',
    '        }',
    '        release {',
    '            signingConfig signingConfigs.debug',
    '            minifyEnabled enableMinifyInReleaseBuilds',
    '        }',
    '    }',
    '}',
  ].join('\n');

  const buildTypesOf = (gradle: string) =>
    /\n {4}buildTypes\s*\{([\s\S]*?)\n {4}\}/.exec(gradle)?.[1] ?? '';

  it('is registered in app.json, or it never runs at all', () => {
    const plugins = appConfig.expo.plugins as unknown[];
    const names = plugins.map((p) => (Array.isArray(p) ? p[0] : p));
    expect(names).toContain('./plugins/withSeparateDebugApp');
  });

  it('gives debug builds their own applicationId suffix', () => {
    const out = addDebugApplicationIdSuffix(RELEASE_GRADLE);
    const debugBlock = /\n {8}debug\s*\{([\s\S]*?)\n {8}\}/.exec(buildTypesOf(out))?.[1] ?? '';
    expect(debugBlock).toMatch(/applicationIdSuffix\s+'\.debug'/);
  });

  it('leaves the release id unsuffixed, so shipping is unaffected', () => {
    const out = addDebugApplicationIdSuffix(RELEASE_GRADLE);
    const releaseBlock = /\n {8}release\s*\{([\s\S]*?)\n {8}\}/.exec(buildTypesOf(out))?.[1] ?? '';
    expect(releaseBlock).not.toMatch(/applicationIdSuffix/);
    expect(releaseBlock).toMatch(/minifyEnabled/);
  });

  it('does not mistake the signingConfigs debug block for a build type', () => {
    // Putting the suffix there would be silently meaningless: the dev build
    // would keep the real applicationId and still overwrite the library.
    const out = addDebugApplicationIdSuffix(RELEASE_GRADLE);
    const signing = /signingConfigs\s*\{([\s\S]*?)\n {4}\}/.exec(out)?.[1] ?? '';
    expect(signing).not.toMatch(/applicationIdSuffix/);
    expect(out.match(/applicationIdSuffix/g)).toHaveLength(1);
  });

  it('is idempotent, because prebuild is not always a clean slate', () => {
    const once = addDebugApplicationIdSuffix(RELEASE_GRADLE);
    expect(addDebugApplicationIdSuffix(once)).toBe(once);
  });

  it('refuses rather than silently skipping when the gradle shape is unknown', () => {
    // A no-op here would hand back a dev build that installs over the real app.
    expect(() => addDebugApplicationIdSuffix('android {\n}\n')).toThrow(/buildTypes/);
    expect(() => addDebugApplicationIdSuffix('android {\n    buildTypes {\n    }\n}\n')).toThrow(
      /debug block/
    );
  });

  it('names the dev app distinctly so it cannot be confused on the home screen', () => {
    // Both builds otherwise share an icon and label, and expo-share-intent puts
    // both in the share sheet.
    expect(debugStringsXml()).toMatch(/<string name="app_name">Memeget Dev<\/string>/);
    expect(debugStringsXml()).not.toContain('>Memeget<');
  });
});
