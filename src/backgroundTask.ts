import * as BackgroundTask from 'expo-background-task';
import * as TaskManager from 'expo-task-manager';

import { getSetting } from './db';
import { runBackgroundSession } from './indexer';
import { headlessEnricher, loadHeadless, unloadHeadless } from './headlessVision';
import { getPower } from '../modules/memeget-bg';
import {
  powerBlockReason,
  throttlesFromSettings,
  BG_ENABLED_KEY,
  BG_ONLY_CHARGING_KEY,
  BG_PAUSE_HOT_KEY,
  BG_PAUSE_LOW_KEY,
  ENABLED_KEY,
} from './visionCore';

// OS-scheduled background indexing. expo-background-task runs a registered JS
// task via WorkManager (Android) / BGTaskScheduler (iOS) — maintained native
// scheduling, no hand-rolled worker. The task loads the model HEADLESSLY
// (headlessVision.ts) and runs a bounded, resumable session.
//
// IMPORTANT: this whole path runs inference in a background JS context. Whether
// a ~hundreds-of-MB model loads/runs there within the OS's window is a runtime
// property to validate on-device — the code is correct, the behavior is not yet
// measured. Sessions are bounded + resumable (vision_state persists), so a
// killed session just resumes next time.

export const BG_TASK = 'memeget-background-describe';

// Bounded on purpose: WorkManager/BGTask windows are short and Android 14+
// dataSync foreground budgets are capped — do a little, often.
const SESSION_MS = 7 * 60 * 1000;
const SESSION_MAX_ITEMS = 40;

// Flipped by the OS when it's about to reclaim the task; the session honors it.
let expired = false;
try {
  BackgroundTask.addExpirationListener(() => {
    expired = true;
  });
} catch {
  // listener unavailable (e.g. web) — ignore
}

// The work the OS runs. Re-checks every setting/throttle itself; registration
// only gates whether it's scheduled at all.
async function runTask(): Promise<BackgroundTask.BackgroundTaskResult> {
  expired = false;
  try {
    if ((await getSetting(ENABLED_KEY)) !== '1') return BackgroundTask.BackgroundTaskResult.Success;
    if ((await getSetting(BG_ENABLED_KEY)) !== '1') return BackgroundTask.BackgroundTaskResult.Success;

    const throttles = throttlesFromSettings(
      await getSetting(BG_ONLY_CHARGING_KEY),
      await getSetting(BG_PAUSE_HOT_KEY),
      await getSetting(BG_PAUSE_LOW_KEY)
    );
    if (powerBlockReason(getPower(), throttles)) return BackgroundTask.BackgroundTaskResult.Success;

    await loadHeadless();

    const started = Date.now();
    await runBackgroundSession(headlessEnricher(), {
      maxItems: SESSION_MAX_ITEMS,
      shouldStop: () =>
        expired ||
        Date.now() - started > SESSION_MS ||
        powerBlockReason(getPower(), throttles) != null,
    });
    return BackgroundTask.BackgroundTaskResult.Success;
  } catch {
    return BackgroundTask.BackgroundTaskResult.Failed;
  } finally {
    // Drop the model before the process is frozen/reclaimed.
    unloadHeadless();
  }
}

// Defined at module scope so the OS can invoke it after a headless JS launch.
// Wrapped so a missing native module (Expo Go / pre-prebuild) can't crash the
// JS entry point at import time.
try {
  TaskManager.defineTask(BG_TASK, runTask);
} catch {
  // task system unavailable — registration will no-op too
}

export async function registerBackgroundDescribe(): Promise<void> {
  try {
    await BackgroundTask.registerTaskAsync(BG_TASK, { minimumInterval: 30 });
  } catch {
    // not supported (web/Expo Go, or pre-prebuild) — ignore
  }
}

export async function unregisterBackgroundDescribe(): Promise<void> {
  try {
    if (await TaskManager.isTaskRegisteredAsync(BG_TASK)) {
      await BackgroundTask.unregisterTaskAsync(BG_TASK);
    }
  } catch {
    // ignore
  }
}
