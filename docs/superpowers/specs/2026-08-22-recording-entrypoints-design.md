# Recording Entry Points Design

## Goal

Add four background recording entry points:

- App Shortcut: start audio recording.
- App Shortcut: start video recording.
- Quick Settings tile: start audio recording.
- Quick Settings tile: start video recording.

Each entry point must behave like pressing the matching start button on the main recorder screen, while keeping the main UI in the background. When the user later opens the app, the existing Service binding must show the active recording state.

## Architecture

Introduce one application-level `QuickRecordingStarter` with two actions: `AUDIO` and `VIDEO`.

The starter reads the latest `AppSettings` from the existing DataStore. It reuses the existing `AudioRecorderModel.startRecording()` and `VideoRecorderModel.startRecording()` preparation flows so save-folder setup, notification settings, camera selection, dual-camera setup, and video audio configuration stay identical to the main-page flow.

Before starting, the starter binds to the target recorder Service and inspects its state. If the Service is already `RECORDING` or `PAUSED`, it logs the duplicate request and returns without stopping or restarting the Service. If the Service is idle or absent, it starts recording through the existing model flow. A process-level synchronization guard prevents two simultaneous shortcut/tile requests from racing.

The starter must not launch `MainActivity` into the foreground. App Shortcuts use a no-display action Activity only as the Android shortcut target; that Activity forwards the action to the starter and finishes immediately. Tiles call the starter from their `TileService` implementation.

## Android integration

- Register two static launcher shortcuts in `res/xml/shortcuts.xml`.
- Add shortcut labels and descriptions to resources.
- Add two `TileService` declarations to `AndroidManifest.xml`, with labels and icons.
- Add the no-display shortcut action Activity to `AndroidManifest.xml`.
- Use existing app icons where suitable; add dedicated vector drawables only if existing assets cannot represent audio/video actions clearly.
- Preserve current foreground-service declarations and permissions. Do not add a new recording Service.

## State and error handling

- Existing target recording: log an informational duplicate-ignore event and do nothing.
- Missing microphone/camera or runtime permission: log the failure and do not start recording.
- DataStore/settings read failure: log the exception and do not start recording.
- Service binding/start failure: log the exception and update the relevant tile to an unavailable/inactive state when possible.
- Successful start: log action, settings source, and target Service; do not expose sensitive setting values.
- Existing Service error callbacks remain the source of recording failure handling.

## UI restoration

No new restoration UI is required. `Navigation` already binds both recorder models to their Services. When the app is opened after a background start, the existing `onServiceConnected` callbacks copy Service state and recording time into the models, so the recorder screen renders as recording.

## Verification

- Unit-test starter action routing and duplicate-state handling.
- Verify manifest declarations, shortcut XML, and tile metadata with a debug build.
- Run the project's available test suite and assemble task.
- Manually verify: launcher shortcut audio/video, audio/video tile, duplicate tap while recording, app reopen showing recording, and normal main-page start behavior.

## Scope exclusions

- No new stop/pause tiles or shortcuts.
- No change to recording file format, retention, notification content, or settings UI.
- No automatic foreground launch of `MainActivity`.
- No automatic git commit.
