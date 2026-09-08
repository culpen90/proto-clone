# Exact installed-APK clone audit

Independently checked on 2026-09-08 using Python ZIP/SHA-256 inspection and Android
Build Tools 36.0.0 `aapt2`, `apksigner`, and `zipalign`. No phone commands were run
for this audit.

**Result:** all 19 delivered APK parts preserve every original executable and
resource byte. The only changed ZIP payload is `AndroidManifest.xml`, plus signing
metadata. The archives themselves differ because they were repackaged and signed.
The source reconstruction under `app/` is a separate deliverable; its compiled
bytecode is not the byte-identical installed-game artifact audited here.

## Independent measurements

| Measurement | Result |
| --- | --- |
| Original / clone APK parts | 19 / 19; exact filename sets match the input inventory |
| Original / clone aggregate APK bytes | 2,338,794 / 2,165,565 |
| Original / clone ZIP members | 392 / 373; no duplicate member names |
| Identical non-manifest, non-signature members | 297, totaling 4,162,856 uncompressed bytes |
| Identical executable bytecode | One `classes.dex`, 2,130,500 bytes |
| Identical resources | 276 `res/` members and 19 `resources.arsc` tables |
| Other identical member | One Gradle app metadata properties file |
| Payload entries by part | Base: 222; xxhdpi: 58; each of 17 language splits: 1 |
| APK signature / alignment checks | All 19 outputs pass v1, v2, v3 signature verification and four-byte ZIP alignment |

Every input SHA-256 and size matches `reference/apk/inventory.json`. Every input
hash, output hash, and unchanged-member hash in `artifacts/exact-clone/parity.json`
was recalculated independently and matches. ZIP member sets were compared in both
directions, so additional or missing payloads are excluded. The `.apks` archive
contains exactly these 19 APKs, each byte-identical to its corresponding output file.

Selected SHA-256 values, measured from files:

| Item | SHA-256 |
| --- | --- |
| Original `base.apk` | `d0f5d62e8e77b51e5243800ba1ceff764b9a8301987d14323bb91cfe4cffd66d` |
| Clone `base.apk` | `0b877c67efc65219e6ab7887897031a8ab2f33b9ac17bd9018d18d832de90441` |
| Original and clone `classes.dex` | `5d6144fc83691f59d6f3c673b38d2160351b6228b48efa249bde9e5712609bdd` |
| Original and clone base `resources.arsc` | `3346cdff07e41bbffe27b66ad88a1b2ebdca89f274f33a32d5d0af6226f2a552` |
| `proto-clone-1.99c-patch1.apks` | `323fc64fb138f5fcb3507a33950ca5b2ffe8d6e6f73b88ad501bdc96e4834b88` |
| `parity.json` | `fc86828d6c79e7fbe7d6fc352c603061a2b832d70cffedabbd03940421773612` |
| Hardened `scripts/clone_installed.py` | `36de13c40e7a5debc8f7f164c8aa129e549616ea061a975e159c052de614f153` |

## Manifest and signature differences

All 19 manifests were decoded independently with `aapt2`. Normalizing only the
intended changes reproduces each original decoded manifest:

- Package changes from `henize.proto_c` to `local.proto.clone` in every part.
- Base keeps the original exported `henize.proto.MainActivity`. Its original
  MAIN/LAUNCHER filter moves intact to one exported `local.proto.clone.Launcher`
  activity alias, labelled **Proto Clone**, targeting that existing activity.
- Version code 53, version name `1.99c (Patch 1)`, minimum SDK 16, target SDK 34,
  permissions, icon/theme references, and all other original attributes remain.
- All 18 split names and `hasCode=false` flags remain. The xxhdpi split retains
  `base__density`, satisfying the base manifest's required density split. The other
  splits are `ar,de,en,es,fr,hi,in,it,ja,ko,my,pt,ru,th,tr,vi,zh`.

Each APK replaces `META-INF/BNDLTOOL.RSA` and `.SF` with `META-INF/ANDROIDD.RSA` and
`.SF`, regenerates `META-INF/MANIFEST.MF`, and removes `stamp-cert-sha256`. APK
signing blocks are regenerated. All clone parts use one local development signer:
`f316b684e87b4df6deb4c9fc987e530e7c3fae9810e6a3371b0cc0ea05f179f1`.
The original base signer is
`4dc138b0c2a116f20721bfa35ba0049d0d462eaacc208a46d224abe2e3d76e76`.
Retained Play distribution metadata does not authenticate the newly signed clone.

## Script review and verification

The review identified missing input completeness checks, possible stale output
inclusion, one-direction member checking, and an implicit single-activity assumption.
The script now verifies the complete input inventory including sizes/hashes,
rejects empty/missing-base/unexpected inputs and stale APK filenames, validates the
supported package/version and single launcher structure, rejects duplicate ZIP
members, and compares complete post-sign member sets. Archive creation uses only
the validated expected filenames.

Preflight accepted the original 19 parts and rejected eight invalid fixtures:
empty inventory, stale output, missing base, extra source, wrong inventory hash,
same input/output directory, wrong manifest package, and unsupported launcher
structure. Temporary regeneration with the hardened script produced **19 APKs
byte-identical to the delivered outputs**, with identical parity records. The
delivered artifacts were not replaced during this check.

## Installation and limits

The `.apks` file is a **plain ZIP of split APKs**, not bundletool format; it has no
`toc.pb`. Extract it and supply all 19 APK paths to `adb install-multiple -r`, or use
the existing `artifacts/exact-clone/*.apk` files. Installing only the base omits its
required density split. Detached `.idsig` files are not needed for ordinary
installation; this audit verifies the APKs' embedded v1/v2/v3 signatures.

The script does not install or launch anything. The distinct package leaves the
original application's private storage separate; it does not copy the original
saved simulation. `-r` updates an existing same-signer `local.proto.clone`, including
the source reconstruction if installed under that package. Package identity,
signer identity, launcher label, and storage location necessarily differ. These
checks establish executable/resource identity, not identical saved state, random
execution, device behavior, or broader compatibility beyond the captured split set.
