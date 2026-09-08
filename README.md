# Proto Clone

An editable Android reconstruction of **Proto — Economy Simulator 1.99c (Patch 1)**, with installed-version comparison notes and tools for making a separate local copy of the game.

The app simulates autonomous production, trading and transportation across six industries. It includes central banking, interest and reserve rates, currency creation and destruction, taxation, conditional trades, priority orders, graphs, statistics and saved economies.

## Build and run

Requirements:

- JDK 21
- Android SDK Platform 34, with `ANDROID_HOME` set or the SDK location configured in Android Studio
- An Android device running API 16 or newer for installation; validation was performed on a Galaxy S25 Ultra

The wrapper downloads Gradle 8.13. The project uses Android Gradle Plugin 8.13.2 and retains the original Android support libraries and target SDK 34.

```sh
./gradlew assembleDebug testDebugUnitTest assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n local.proto.clone/local.proto.clone.Launcher
```

The application ID is `local.proto.clone`, with launcher label **Proto Clone**. It installs alongside the original `henize.proto_c` application and uses separate private storage. The source build uses a local Android development signing key; it is not a store release.

## Source and installed-version fidelity

The baseline is the official [Henize-Software/proto-economy-simulator](https://github.com/Henize-Software/proto-economy-simulator) source at commit `acd86261d7e396c44c1aa2f79c005eb1d21cf193`.

That public revision identifies itself as **versionCode 51 / 1.99c**. The reference installation identifies itself as **versionCode 53 / 1.99c (Patch 1)**. This reconstruction restores corroborated differences in save-state identifiers, graph serialization, frame pacing, movement, full-screen refresh, information text, artwork encodings and bundled starting economies.

The source reconstruction and a direct APK copy are distinct builds. Manual auditing and passing tests do not prove that recompiled bytecode is identical to the installed release. The separate local-copy workflow below preserves the installed executable and resource files exactly. Neither workflow imports the original app's private saved game.

- [Engine comparison](docs/ENGINE_PARITY.md)
- [UI and feature checklist](docs/UI_PARITY_CHECKLIST.md)
- [Resource hashes](docs/ui-resource-parity.json)
- [Source provenance](docs/PROVENANCE.md)

## Verification

The recorded verification run passed **8 engine tests** and **4 Android instrumentation tests**. The four device tests passed against both the editable build and the direct APK copy. They cover named save/load, finish/relaunch restoration, preferences, and loading and serializing all three bundled economies.

After building and installing the source app, run device tests with:

```sh
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class henize.proto.Patch1PersistenceTest \
  local.proto.clone.test/android.test.InstrumentationTestRunner
```

See [verification results](docs/VERIFICATION.md) and the [device UI review](docs/DEVICE_UI_REVIEW.md). These describe the recorded local run; screenshots, device logs and generated APKs are not committed. No hosted CI result is claimed.

`./gradlew lintDebug` currently reports **13 errors and 328 warnings** in inherited code and layouts. These remain visible rather than being suppressed or broadly refactored during the fidelity work. A passing build or test run does not mean lint is clean.

## Optional direct APK copy

`scripts/clone_installed.py` can create a separate local installation from a captured **version 53 / 1.99c (Patch 1)** split APK set. This workflow requires Python 3, Android Build Tools 36.0.0, an existing local Android development keystore, and these local inputs:

- `reference/apk/base.apk` and the 18 configuration splits from the reference installation
- `reference/apk/inventory.json`, an array of objects containing each APK's `file`, `bytes` and `sha256`

**The captured APKs, inventory, generated clone, signing keys, private saves and phone screenshots are not distributed in this repository.** The source build above does not need them. The cloning tool deliberately rejects incomplete or incompatible inputs.

With the required local inputs available:

```sh
python3 scripts/clone_installed.py
./scripts/install-exact.sh
```

The tool writes `artifacts/exact-clone/` and a plain ZIP backup named `artifacts/proto-clone-1.99c-patch1.apks`. The archive is not bundletool format or a universal APK: all 19 APK parts must be installed together. The manifest package name, launcher alias/filter placement and signing metadata change; the game executable, resources and presets are preserved. Android also uses the alias label as the activity title.

The recorded audit verified **297 unchanged payload entries** across 19 APK parts and reproduced identical signed outputs using the same local key. See [the exact-copy audit](docs/EXACT_CLONE_AUDIT.md). Retain the same signing key for updates, and preserve wanted clone saves before switching between executable variants; arbitrary cross-build saved-state compatibility is not guaranteed.

## License and attribution

Proto's original app, artwork and source are credited to **Joshua Henize / Henize Software**. Original source headers specify **GPL-3.0-or-later**; see [LICENSE](LICENSE). Existing notices are retained. This is a community reconstruction, not an official Henize Software release.
