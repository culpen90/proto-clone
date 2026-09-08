# Verification — 2026-09-08

The results below record the local validation run. Referenced `artifacts/` and `reference/` files remain local and are not included in the public repository.

## Recorded installation

**Proto Clone (`local.proto.clone`) was installed side by side on the Android test device.** The final installed artifact is the exact-copy APK set, versionCode53 / `1.99c (Patch 1)`, not the independently compiled source reconstruction. The original `henize.proto_c` package was verified to retain its original version and update timestamp.

## Evidence

| Check | Result | Evidence |
| --- | --- | --- |
| Final installed APK hashes | All 19 APK hashes on the phone match exact-copy artifacts | `artifacts/installed-verification.json` |
| Installed-version payload integrity | 19 APKs; 297 executable/resource entries and 4,162,856 uncompressed bytes unchanged | `artifacts/exact-clone/parity.json`; `docs/EXACT_CLONE_AUDIT.md` |
| Executable identity | Original and clone `classes.dex` SHA256 `5d6144fc83691f59d6f3c673b38d2160351b6228b48efa249bde9e5712609bdd` | Independent ZIP comparison |
| Packaging | 19 signature and alignment checks passed; regeneration produced identical APKs | `docs/EXACT_CLONE_AUDIT.md` |
| Source build | Debug APK, instrumentation APK, and JVM suite built successfully with JDK 21 | `artifacts/source-build-tests.log` |
| Engine regression tests | 8 passed; 0 failures/errors/skips | `artifacts/unit-tests.json`; `app/build/test-results/testDebugUnitTest/` |
| Exact-copy device tests | **4 passed** in 7.198 seconds | `artifacts/exact-instrumentation.txt` |
| Editable-source device tests | **4 passed** in 7.417 seconds | `artifacts/source-instrumentation.txt` |
| Artwork and starting economies | All 23 app PNG/BMP assets and all 3 raw presets equal installed resources | `docs/ui-resource-parity.json` |
| Current exact-copy process | No fatal exception, ANR, or out-of-memory lines in captured logcat | `artifacts/exact-logcat.txt` |
| Rendering sample | 596 HWUI frames, 13 janky (2.18%); 50th/95th percentile5ms, 99th13ms | `artifacts/exact-gfxinfo.txt` |
| Source lint | 13 errors / 328 warnings remain in inherited code/layouts; check fails | `artifacts/source-lint.log` |

The four device tests use the actual application methods and Android serialization: named save/load restores cash, banking/tax settings, graph records and shared object relationships; finish/relaunch restores the automatic snapshot; preferences persist; and all three bundled economies deserialize, initialize their industries/maps and round-trip through a saved Simulator. The same four tests passed against both executable variants. Tests use isolated clone save slots and restore the clone's graph/preferences. The separate test APK is removed after validation.

The JVM tests exercise movement in eight directions, tile arrival, monetary transfers/tax/refunds, reserve-backed lending/repayment and rejection, commodity production, and consumed-input rejection.

## Phone walkthrough

- Launched through the distinct Proto Clone launcher alias; the exact-copy installation shows the original interface with the alias title.
- Started and paused the economy. Vehicles moved, population changed from20 to21, transaction count advanced from0 to21 and later32+, and prices updated. These are actual simulation results, not static screens.
- Captured and inspected Banking, Trade, Special Ordering, Currency, Map and trader information popups.
- Verified actual Statistics rendering (`exact-12-statistics-rendered.png`) with bank sums, assets, production/current commodity totals and reserves.
- Verified actual Graph rendering (`exact-14-graph-active.png`) with six colored price series and the spread control. Earlier graph/statistics captures were taken before the selected SurfaceView refreshed and still show Map; those earlier images are not used as rendering proof.
- Left the exact copy on its Map screen, paused. The clone has its own economy and does not import the original app's private saved game.

Screenshots are in `artifacts/screenshots/`; comparisons with the original are recorded in `docs/DEVICE_UI_REVIEW.md`. The graph and statistics captures above supplement that earlier paired review.

## Scope of the result

The exact-copy executable/resource identity establishes that the existing game features are carried over. Runtime checks sample the major flows; they do not exhaust every trade condition or long simulation. No claim is made of long-duration performance certification: HWUI does not measure all SurfaceView/game-thread work.

The editable source is a separate reconstruction based on the older official GPL repository with corroborated Patch1 changes. Manual auditing and passing tests do not establish mathematical equivalence of the recompiled program or guarantee compatibility with every pre-existing serialized simulation. It is provided for future development. The exact-copy build is the final installed deliverable.

The remaining lint findings are kept visible rather than suppressed. They include original layout constraints, suspicious indentation, an invalid logging priority, and old-Android RTL declarations. Broadly refactoring these would change the scope from copying the installed game to maintaining it.

## Source publication check

Before public publication, only staged Git files were copied into a new isolated checkout, excluding local SDK configuration, captured APKs and generated artifacts. With JDK 21 and `ANDROID_HOME` pointing to an installed SDK, `./gradlew --no-daemon assembleDebug testDebugUnitTest assembleDebugAndroidTest` passed: all 62 tasks executed and all 8 JVM tests passed without failures, errors or skips. This verifies the repository contains the files needed to build independently. Existing source whitespace and the documented lint findings were retained.
