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
