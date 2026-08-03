# Export destinations: physical device pass

Device: **Pixel 9 Pro** (`caiman`, Android 17), wireless ADB, on AC power.
Build: release APK from `feature/video-image-editor-core`, installed with
`adb install -r` over the existing app — versionCode 1152, `lastUpdateTime`
2026-08-03 15:41:44. Library intact after the update: **2185 memes · 1 folder**,
no crash dialog.

## Verified end to end

**Text layers no longer crash.** Adding a text layer in the studio previously
red-boxed with *"Didn't find a correct constructor for class
expo.modules.memegetbg.MemeTextPreviewView"*. On this build the layer is created
(`2 layers`, rotate/resize handles present) with no error. This is the
`@JvmOverloads` fix, and it is also covered by
`MemeTextPreviewViewConstructorTest`, which is RED against the unfixed source
(`NoSuchMethodException: <init> [Context]`) and GREEN 3/3 here —
`text-preview-constructor-pixel.xml`.

**Destination picker renders on device.** Tapping Export presents *Export this
meme — it renders once, so picking a second destination afterwards is instant*
with all three options: `SAVE AS NEW MEME`, `COPY`, `SAVE TO DOWNLOADS`.

**Downloads destination produces a real file.** Exercised with a text layer
applied. Result on disk:

```
/sdcard/Download/17857758597504842761533940907420-variation-20260803-194648.png
349,931 bytes
```

Checked as bytes, not as a toast:

| Property | Observed |
|---|---|
| PNG signature | `89 50 4E 47 0D 0A 1A 0A` — valid |
| Dimensions | 500×740, matching the source exactly |
| Colour type | 6 (RGBA), bit depth 8 |
| Name | `<stem>-variation-<UTC stamp>.png` from `exportOutputSpec` |

Visual inspection confirms a complete render — the full poster, with the added
caption **"MEME TEXT"** burned in as a white banner. Not blank, not letterboxed,
not truncated.

**Crash-safe drafts work.** After the studio was interrupted mid-edit, reopening
offered *"Restore edit draft? Saved 8/3/2026, 3:46:02 PM"* and restoring brought
the layer back. Incidental to this task, but observed.

## Partially verified — stated plainly

**Copy destination.** Tapped; it completed with no exception, no error toast, and
the studio correctly stayed open (only the library destination closes it). Since
`deliverExport` throws when `copyFileToClipboard` returns false, and the error
path renders a `Could not export:` toast, the absence of one is meaningful — but
it is not proof.

What blocked proof:
- `dumpsys clipboard` returns empty on this Android version for access-control
  reasons, so it cannot distinguish "empty" from "not readable".
- `run-as` cannot read the staged file out of a **release** build's cache.
- The success toast is transient and was missed by screenshot sampling at
  0.6 s intervals.

Also note the run that exercised Copy had **0 layers** — the draft did not
restore that time — so it copied the base render rather than an edited one.

**Save-as-new-meme destination.** Not exercised on device. Its code path is the
one that already shipped (`saveSharedFiles` + `onCreated`); the new part is only
that it is reached through the picker.

## Interference worth recording

`com.voicecloner.app` is crash-looping on this phone and repeatedly raises a
*"Allow Voicecloner to record audio?"* runtime-permission dialog, which stole
focus four times mid-run. It was force-stopped rather than answered — that
prompt is the owner's decision, not a test fixture. One attempt to `pm suspend`
it was **reverted immediately**: suspension produced a worse *"App isn't
available … managed by Shell"* system dialog. Final state verified unsuspended.

This is a real obstacle to automated UI verification on this device until that
app is fixed or uninstalled.
