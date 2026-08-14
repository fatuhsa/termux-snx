# AGENTS.md

Android terminal emulator + Linux environment app (the Termux app). This repo is a fork of `termux/termux-app` (`upstream` remote); `origin` is `fatuhsa/termux-snx`. Default branch is `master`. Keep upstream mergeable when touching shared code.

## Build setup

- **Java 17 required** (Gradle 9.2.1 wrapper + AGP 8.13.2). Newer JDKs break the build. Verify with `./gradlew --version`.
- NDK `29.0.14206865`, compileSdk 36, minSdk 21, targetSdk 28 — all set in `gradle.properties`, not in module files.
- AndroidX is used; `android.useAndroidX=true`.
- Build is pure Gradle (no Kotlin DSL, no Kotlin — all Java 1.8 source + C/C++ via ndkBuild).

## Modules

- `:app` — the application (`com.termux`), main entrypoints: `TermuxApplication`, `TermuxActivity`, `TermuxService`, `TermuxInstaller`. Debug APKs are split per ABI + universal and named `termux-app_<variant>-debug_<abi>.apk`.
- `:termux-shared` — shared constants/utils for the app and plugin apps. **All shared constants and utils must live here**, never hardcoded in the app (`TermuxConstants` is the canonical constants class). App/plugin-specific classes go under `com.termux.shared.termux`; general classes outside it. Published to Jitpack as `com.termux:termux-shared`.
- `:terminal-emulator` (`com.termux.emulator`) and `:terminal-view` — terminal emulation/widget libraries, also published to Jitpack. Termux's terminal handling is based on the (inactive) Android-Terminal-Emulator project.
- `site/`, `fastlane/`, `docs/` — website assets, Play metadata, docs; not part of the app build.

## Commands

```sh
./gradlew assembleDebug            # full APK build (see bootstrap gotcha below)
./gradlew test                     # all unit tests (what CI runs)
./gradlew :terminal-emulator:test --tests "com.termux.terminal.ScreenBufferTest"  # single test
./gradlew lint                     # lint (note: build fails on -Werror C flags, not lint)
```

- CI builds with the env vars below; a plain local `assembleDebug` uses defaults.
- `./gradlew clean` **deletes the downloaded bootstrap zips** (`app/src/main/cpp/bootstrap-*.zip`), so the next build re-downloads them (needs network).

## Bootstrap gotcha (important)

`:app`'s build downloads bootstrap zip packages from `github.com/termux/termux-packages/releases` into `app/src/main/cpp/` and packages them into the APK. The variant is selected by `TERMUX_PACKAGE_VARIANT` (`apt-android-7` default, or `apt-android-5` for Android 5/6 support). The variant must match `com.termux.shared.termux.TermuxBootstrap.PackageVariant` or the app crashes at startup. Never change the bootstrap variant without rebuilding the APK, and don't hand-install a mismatched bootstrap.

## Env vars read by `app/build.gradle`

- `TERMUX_PACKAGE_VARIANT` — bootstrap variant (see above)
- `TERMUX_APP_VERSION_NAME` — overrides `versionName`; must pass the semver regex or build fails
- `TERMUX_APK_VERSION_TAG` — embedded in APK filenames
- `TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS` / `TERMUX_SPLIT_APKS_FOR_RELEASE_BUILDS` — ABI split control (default: 1 for debug, 0 for release — F-Droid does not support split APKs)
- `JITPACK_NDK_VERSION` — NDK override for Jitpack builds

## Conventions (enforced upstream, PRs rejected otherwise)

- **Commit messages must be Conventional Commits** with exact types `Added`, `Changed`, `Deprecated`, `Removed`, `Fixed`, `Security` (capital first letter, present tense, space after colon, optional scope like `Fixed(terminal): ...`). Changelogs are generated from these; any other type breaks tooling.
- `versionName` must be semver 2.0.0 (`major.minor.patch`, patch required). It is validated by `validateVersionName()` in `app/build.gradle:160` and by the release workflow.
- Don't bump dependency versions casually: `desugar_jdk_libs` was reverted to 1.1.5 deliberately (commit 1937595c), `commons-io` is pinned to 2.5 because newer versions break Android < 8 (missing `java.nio.file.Path`).
- Update changelogs when contributing; hardcoded paths in app code will not be accepted.

## Testing quirks

- Unit tests only (JUnit4 + Robolectric for the app module); no instrumentation tests in CI.
- `terminal-emulator` tests are plain JVM tests of terminal emulation (no Android deps).
- `:terminal-view` sets `unitTests.returnDefaultValues = true`.

## Misc gotchas

- Debug builds are signed with the in-repo `app/testkey_untrusted.jks` (alias `alias`, passwords visible in `app/build.gradle` — this is intentional, it is a public test key).
- Release builds: `minifyEnabled true` with `shrinkResources false` for reproducible builds; debug splits are `enable`d only when a task name contains `Debug`/`Release`.
- All APKs (app + plugins) share `sharedUserId` `com.termux` and must be signed with the same key to coexist on a device.
- C code compiles with `-Werror` (app and terminal-emulator), so C warnings fail the build.