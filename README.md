# LumaGA

<p align="center">
  <img src="docs/logo.png" width="128" alt="LumaGA logo">
</p>

**English** | [简体中文](README.zh-CN.md)

Android port of [MNGA](https://github.com/BugenZhao/MNGA), an NGA (bbs.nga.cn)
client. Built with Jetpack Compose, on top of the same Rust `logic` backend
MNGA uses, linked in as `liblogic.so` over JNI.

## Screenshots

Home | Topic list | Topic detail | Topic detail
--- | --- | --- | ---
![Home](docs/screenshots/mnga_home.jpg) | ![Topic list](docs/screenshots/lumaga_list.jpg) | ![Topic detail](docs/screenshots/lumaga_post.jpg) | ![Topic detail](docs/screenshots/lumaga_post_2.jpg)

## Usage

- Download the latest APK from the [Releases](https://github.com/Duzc01/LumaGA/releases) page:
  - `app-release.apk` — signed release build (recommended)
  - `app-debug.apk` — debug build (built by CI on every push)
- Install and sign in with your NGA account on first launch.
- On Android 12+, choose **Settings → Theme Color → Dynamic Colors** for
  wallpaper-based colors, or select a fixed accent in the same picker.
  Light/dark overrides also control system-bar icons. Classic mode keeps its
  saved fixed accent and original palette.
- Predictive back is enabled on Android 13+. On Android 14+, each screen opens
  in an internal Activity, using Android's cross-Activity predictive preview
  like [LibChecker](https://github.com/LibChecker/LibChecker). Cancelling keeps
  the current screen; committing returns to the previous Activity. This avoids
  relying on app-delivered gesture progress, which was missing on the reported
  HONOR device with single-Activity Navigation Compose. Android 13 and older
  keep Navigation Compose and its existing transitions.
  Back from the home screen is handled by Android (including the back-to-home preview); Android
  12 and older retain double-back-to-exit. On Android 13–14, enable predictive
  back animations in Developer options to see the system preview.
  On a physical phone, verify left/right-edge cancellation and completion
  through Home → Personal Center → Settings → About, toolbar/button back,
  dialog dismissal, and returning to the same topic/scroll position. Tests
  that inject AndroidX back events alone cannot verify the system preview.
- `mnga://` deep links are supported, e.g. `mnga://forum/f/722` opens a forum
  directly; links copied to the clipboard are also detected and opened
  automatically when the app comes to the foreground.

## Layout

- `app/` — Compose UI, ported from the MNGA SwiftUI app.
- `logic/` — Android library module wrapping `liblogic.so` plus the generated
  protobuf Java/Kotlin sources.
- `rust/` — the Rust workspace `liblogic.so` is built from, vendored from MNGA.
  See [rust/README.md](rust/README.md).

## Build

```bash
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`. JDK 17 required.

Gradle does not build the Rust side. `liblogic.so` is committed under
`logic/src/main/jniLibs/`, so a plain Gradle build needs no NDK or Rust
toolchain. After changing anything under `rust/`, rebuild and commit the
libraries:

```bash
rust/build-jni-libs.sh      # liblogic.so for arm64-v8a, x86_64, x86
rust/gen-kotlin-protos.sh   # only when rust/protos/ changed
```

Both need `protoc`; the first also needs `cargo-ndk` and an Android NDK.

## CI

- [ci.yml](.github/workflows/ci.yml) builds the release APK on every push,
  PR targeting `main`, or manual run, and uploads the `app-release` artifact.
  Push and manual builds use the repository secrets `LUMA_KEYSTORE_BASE64`,
  `LUMA_STORE_PASSWORD`, and `LUMA_KEY_PASSWORD` (key alias `luma`) for signing;
  PR builds remain unsigned.
  Once the workflow is on the default branch, open **Actions → CI → Run workflow**,
  select the branch to build (normally `main`), and click **Run workflow**.
  After the run succeeds, download `app-release` from its summary page and
  extract `LumaGA_<version>.apk`. Publishing a GitHub Release is a separate step.
- [rust.yml](.github/workflows/rust.yml) rebuilds `liblogic.so` for all three
  ABIs and runs the Rust unit tests, on changes under `rust/` or on demand.

## Attribution

MNGA ships without a LICENSE file and its README reserves all rights, so this
port — including the Rust sources vendored under `rust/` — is not
redistributable without permission from its author. Vendored sled keeps its own
MIT/Apache-2.0 license files in `rust/logic/sled/`.
