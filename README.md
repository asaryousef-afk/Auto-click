# Smart Touch AI

A real, installable native Android app (Kotlin, Jetpack Compose, MVVM-ish structure,
Coroutines, DataStore). Package: `com.smarttouch.ai`. Min SDK 26, target/compile SDK 34.

Automatically taps a configured screen position **only while a real video is actually
playing** - not just because you're scrolling a feed - using on-device motion analysis.
Nothing is ever uploaded anywhere; the app doesn't even request the INTERNET permission.

## Architecture

```
app/src/main/java/com/smarttouch/ai/
  MainActivity.kt                       - Compose UI: Home, Touch Settings,
                                           Video Detection, Advanced, Debug screens
  ServiceActionReceiver.kt               - relays notification/shortcut actions to the service
  accessibility/
    TouchAccessibilityService.kt         - the engine: gestures, screenshots, overlay, notification
  detection/
    MotionDetector.kt                    - pure frame-difference motion scoring (unit tested)
    VideoActivityTracker.kt              - confirmation-time / no-motion-timeout debouncer (unit tested)
    TouchIntervalCalculator.kt           - preset/custom interval resolution (unit tested)
    Sensitivity.kt                       - LOW / MEDIUM / HIGH / CUSTOM
  state/
    ServiceState.kt                      - IDLE, VIDEO_DETECTING, VIDEO_ACTIVE, TOUCHING,
                                            PAUSED, STOPPED, ERROR
    TouchStateMachine.kt                 - deterministic transitions (unit tested)
  data/
    SettingsRepository.kt                - DataStore-backed persisted settings
  shortcuts/
    ShortcutsInfo.kt                     - see res/xml/shortcuts.xml for the actual shortcuts
app/src/test/java/com/smarttouch/ai/     - JVM unit tests (motion detection, timeouts,
                                            state machine, interval calculation)
```

## How video detection actually works (and its real limits)

On Android 11+ (`API 30+`), the accessibility service periodically calls
`AccessibilityService.takeScreenshot()`, downsamples the result to a small 24x24
luminance grid, and compares it to the previous sample (`MotionDetector`). If the
difference exceeds your chosen **Sensitivity** threshold for long enough
(**Confirmation time**), `VideoActivityTracker` marks video as ACTIVE and the touch
loop starts. If motion stops for longer than your **No-motion timeout**, it goes back
to just watching.

**Read this part honestly:** this is a general on-screen-motion signal, not a
guaranteed "is this actually a video" classifier. A live wallpaper, a loading spinner,
or a very fast scroll animation can also register as motion. That's exactly why
Sensitivity, Confirmation time, and No-motion timeout are all exposed as settings you
can tune per app/situation, rather than the app pretending one fixed algorithm works
perfectly everywhere.

On **Android 8-10 (API 26-29)**, `takeScreenshot()` isn't available to accessibility
services, so video detection cannot run. The app still works as a configurable
interval tapper on those versions (visible as a note in the Video Detection screen) -
this is a real Android platform limitation, not something this app can work around.

## Safety

- The tap position is clamped away from the edges of the screen (a configurable
  margin around the display) so it can't land on the gesture-navigation strip or a
  hardware-adjacent nav area.
- Touching only ever happens while the state machine is in `TOUCHING`, which only
  happens after `VIDEO_ACTIVE`, which only happens after the service is explicitly
  started by you.
- Stopping the service, revoking Accessibility permission, or the app process dying
  all immediately halt tapping - there's no separate "keep tapping anyway" path.
- The persistent notification always has a STOP action available.

## Bixby / Samsung integration - what's real here

Samsung does not expose a public API that lets third-party apps register arbitrary
Bixby *voice* phrases directly. What Smart Touch AI actually implements is the
supported Android mechanism: **static App Shortcuts** (`res/xml/shortcuts.xml`) for
Start / Stop / Pause / Resume. On a Samsung device you can:

1. Long-press the app icon to trigger a shortcut directly, or
2. Open **Settings → Modes and Routines**, create/edit a Routine, add the action
   **"Open app"**, choose Smart Touch AI, and pick one of these shortcuts.

That's the real, documented way third-party apps hook into Routines/Bixby today -
this app doesn't pretend to do more than that.

## Permissions requested (and why)

- **Accessibility service** - required to perform the tap gesture and to take the
  periodic screenshot used for motion detection.
- **Display over other apps (`SYSTEM_ALERT_WINDOW`)** - required only to show the
  draggable floating dot while you're positioning your tap; you can hide it entirely
  once configured (Clean Screen Mode).
- **Notifications** - required (Android 13+) so the persistent status/control
  notification can be shown.
- No `INTERNET` permission is requested at all.

## Build

```
Push to `main` and GitHub Actions (`.github/workflows/build.yml`) will:
1. run the JVM unit tests (motion detection, timeout logic, state machine, interval calc)
2. build a debug APK, uploaded as the `SmartTouchAI-debug-apk` artifact
```

Or open the project directly in Android Studio (File → Open) and run it on a device
or emulator running Android 8.0 (API 26) or newer. Video detection specifically needs
Android 11+ (API 30) to be exercised on-device.

## What's intentionally not included yet

- **Instrumentation/UI tests** (start/stop flow, notification actions, Clean Screen
  Mode end-to-end) are not included in this pass - they need a configured
  emulator/device matrix in CI, which is a meaningfully larger setup than the JVM unit
  tests included here. The JVM unit tests cover all of the pure logic (motion
  detection, sensitivity thresholds, timeout debouncing, the state machine, and
  interval calculation) as requested.
- Pinch-to-resize on the floating dot isn't implemented as a gesture; resizing is
  exposed as an opacity/size setting screen instead (drag-to-reposition, lock, and
  hide are all implemented as real gestures).
- "Start on boot" persists your preference, but Android still requires you to
  manually re-enable the accessibility service after certain restarts - this is an OS
  security restriction that no app can bypass.
