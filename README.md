# Game Stability (Android)

A lightweight Android companion that reports public device diagnostics and Android thermal status. It does **not** claim to boost FPS directly, force-close other apps, overclock CPU/GPU, or read hidden temperature sensors.

## Requirements
- Android Studio with Android SDK Platform 34 installed (or an equivalent Android SDK setup)
- JDK 17
- Internet access on first Gradle sync/build to resolve Gradle and Maven dependencies

## Open and build
1. Extract this folder.
2. Open the `GameStability` folder in Android Studio.
3. Let Gradle sync and install any requested SDK platform/build tools.
4. Run the `app` configuration on a device/emulator, or use `./gradlew :app:assembleDebug` (Windows: `gradlew.bat :app:assembleDebug`).
5. Expected debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

The bootstrap script downloads Gradle 8.2 when no Gradle installation is found. The Android SDK is not bundled.

## Current features
- Device model, Android version, screen resolution, reported refresh modes, memory/storage availability and battery percentage.
- Public thermal status monitoring on Android 10+; thermal headroom where available.
- Practical, user-controlled suggestions and shortcuts to Android settings.
- Three gaming advice profiles: cooler device, stable FPS, and low-graphics guidance.
- Shortcuts to display and battery-saver settings.
- GitHub Actions workflow to run unit tests and build a debug APK without a local computer.
- No special permissions, root, ADB, analytics or network calls from the app itself.

## Build with GitHub Actions (no computer required)
1. Create a GitHub repository and upload the contents of this project folder (`GameStability`) to its root.
2. Open the repository's **Actions** tab and enable Actions if GitHub asks.
3. Run **Android CI** with **Run workflow**, or push/commit to `main` or `master`.
4. Wait for the workflow to finish successfully.
5. Open the successful workflow run, scroll to **Artifacts**, and download `GameStability-debug-apk`.
6. Extract the artifact ZIP to obtain `app-debug.apk`, then install it on the phone. Android may ask you to allow installation from the browser/file manager you used.

The workflow installs JDK 17, Android SDK Platform 34, Build Tools 34.0.0 and Gradle 8.2 on GitHub's runner. It invokes the installed Gradle directly because this source package does not include `gradle-wrapper.jar`.

## Limitations
- Android does not expose a general public API for one ordinary app to read another game's FPS or control its CPU/GPU usage.
- The graphics profile is guidance only. The app cannot directly change another game's resolution, shadows, anti-aliasing, or effects unless that game provides its own supported integration.
- Lowering display brightness may reduce screen power in some conditions, but it does not lower a game's rendering resolution and does not guarantee higher FPS.
- Reported refresh rate is not the same as a game's FPS.
- Thermal status is a system category, not a temperature reading in degrees Celsius.
- OPPO/ColorOS may expose different refresh modes and game-mode settings by model/software version.
