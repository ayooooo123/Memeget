// Ref-counted holder for the Android keep-alive foreground service (see
// modules/memeget-bg/KeepAliveService.kt): while ANY unit of heavy work runs —
// indexing, re-tagging, describing, transcribing, embedding/poster backfills —
// the process must survive backgrounding and the screen turning off, or the
// work silently freezes until the user next opens the app (which was exactly
// the complaint: everything only progressed while staring at the app).
//
// Multiple workers overlap constantly (an index run while the poster backfill
// drains while a describe is queued), so raw startKeepAlive/stopKeepAlive
// calls from each would fight — the first one to finish would kill the service
// out from under the others. Acquire/release with a live count fixes that, and
// the notification always names the most recent work so the user can see why
// Memeget is running.
//
// No-ops (via the underlying wrappers) when the native module isn't built in.
// Android caps dataSync foreground services at ~6h/day — plenty for any real
// session; the OS reclaims the service after that and work resumes on the
// next app open.
import { PermissionsAndroid, Platform } from 'react-native';

import { startKeepAlive, stopKeepAlive } from '../modules/memeget-bg';

const holders = new Map<number, string>();
let seq = 0;

// Android 13+ suppresses the foreground-service notification unless the user
// grants POST_NOTIFICATIONS at runtime — nothing ever asked, so the service
// ran invisibly and "the background process is missing" as far as anyone
// could tell. Ask once, on the first acquire (i.e. the first time there is
// actual work worth showing). The service runs either way; this only makes it
// visible.
let permissionAsked = false;
function ensureNotificationPermission(): void {
  if (permissionAsked || Platform.OS !== 'android' || Number(Platform.Version) < 33) return;
  permissionAsked = true;
  PermissionsAndroid.request(
    'android.permission.POST_NOTIFICATIONS' as Parameters<typeof PermissionsAndroid.request>[0]
  ).catch(() => {});
}

function refresh(): void {
  if (holders.size === 0) {
    stopKeepAlive();
    return;
  }
  const labels = [...holders.values()];
  const text =
    labels[labels.length - 1] + (labels.length > 1 ? ` (+${labels.length - 1} more)` : '');
  startKeepAlive('Memeget is working', text);
}

// Hold the service for one unit of work. Returns the release function; calling
// it more than once is safe. ALWAYS release in a finally — a leaked hold keeps
// the notification (and wake lock) up until the app dies.
export function acquireKeepAlive(label: string): () => void {
  ensureNotificationPermission();
  const id = ++seq;
  holders.set(id, label);
  refresh();
  return () => {
    if (holders.delete(id)) refresh();
  };
}
