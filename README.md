# Proto Clone

An editable Android reconstruction of **Proto — Economy Simulator 1.99c (Patch 1)**, with installed-version comparison notes and tools for making a separate local copy of the game.

The app simulates autonomous production, trading and transportation across six industries. It includes central banking, interest and reserve rates, currency creation and destruction, taxation, conditional trades, priority orders, graphs, statistics and saved economies.

In the editable build, **Banking** accepts whole percentages from −100% to 100% for both rates. Negative interest credits borrowers’ outstanding debt each payment cycle. A −20% reserve rate lets the bank lend down to a reserve balance of −20% of the money supply.

For help with a struggling economy, open **Control → Economies**, turn on **Economy autopilot**, then press **Play**. It watches for repeated payment failures, frozen borrowers and stalled trade. It can adjust bank rates, fund a farm or worker from the treasury, or create currency to rebuild reserves while the money supply is below $5,000. Return to **Economies** to see its latest decision or switch back to manual control. Each save remembers your choice.

Download the signed Android APK from [GitHub Releases](https://github.com/culpen90/proto-clone/releases/latest). It installs alongside the original Proto app. If you already use a locally signed build of Proto Clone, back up any saves you want to keep before switching. Android requires matching signatures for an in-place update.

## Save an economy or move it to another device

Open **Control → Save/Load State**. **Save** creates a dated checkpoint on this device. The automatic save resumes your current economy when you reopen the app. Each checkpoint includes its own graph history.

1. On the first device, tap **Export file** and choose a location in Files or a cloud drive. On Android 4.1–4.3, choose a destination from the share sheet.
2. Copy the **transfer key** and keep it separately. Every export has its own key. Keep both the key and the `.protoeconomy` file, including before uninstalling the app.
3. On the other device, open **Control → Save/Load State → Import file**, choose the file, enter its key and tap **Open economy**. The imported economy opens paused and becomes the automatic save. To keep the receiving device’s previous economy, tap **Save** before importing.

Exports work offline. AES-256-GCM protects the contents, and the transfer key includes a SHA-256 fingerprint of the exact exported file. Editing the file invalidates that key. Someone with control over the client can still create a different economy and a new key. Server-verified play would require a separate online authority.

New local saves use an installation-specific key. Android 6 and later keep the wrapping key in Android Keystore. Older versions keep a random wrapping key in app-private storage. On upgrade, the app recognizes existing private saves and converts each one when opened, using the graph history available from the older version. Protected files support up to one million graph records and 16 MiB of compressed snapshot data.

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

The application ID is `local.proto.clone`, with launcher label **Proto Clone**. It installs alongside the original `henize.proto_c` application and uses separate private storage. The debug build uses a local Android development signing key.

## Source and installed-version fidelity

The baseline is the official [Henize-Software/proto-economy-simulator](https://github.com/Henize-Software/proto-economy-simulator) source at commit `acd86261d7e396c44c1aa2f79c005eb1d21cf193`.

That public revision identifies itself as **versionCode 51 / 1.99c**. The reference installation identifies itself as **versionCode 53 / 1.99c (Patch 1)**. This reconstruction restores corroborated differences in save-state identifiers, graph serialization, frame pacing, movement, full-screen refresh, information text, artwork encodings and bundled starting economies.

The source reconstruction and a direct APK copy are distinct builds. Manual auditing and passing tests do not prove that recompiled bytecode is identical to the installed release. The separate local-copy workflow below preserves the installed executable and resource files exactly. Neither workflow imports the original app's private saved game.

- [Engine comparison](docs/ENGINE_PARITY.md)
- [UI and feature checklist](docs/UI_PARITY_CHECKLIST.md)
- [Resource hashes](docs/ui-resource-parity.json)
- [Source provenance](docs/PROVENANCE.md)

## Verification

Version 1.2.0 passed **14 engine tests** and **10 Android instrumentation tests** on Android 8 and Android 16 emulators. Save coverage includes legacy migration, independent checkpoint histories, relaunch, all three bundled economies, continued simulation after import, wrong keys, and tampered files. A file exported on one emulator also opened through the document picker on a second emulator and survived a forced restart.

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
