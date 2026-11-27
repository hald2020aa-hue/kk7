# Android port — development checkpoint, not a release

Original version 0.5.2. Desktop source files are retained. The new `mdm-mobile` crate calls `mdm-engine` directly rather than replacing it. The initial Compose dashboard, JNI boundary, foreground service, share-intent and document export paths are implemented in source only.

Decisions: Android API 29–35 target configuration, ARM64 only, private download directory with explicit document export, 4 concurrent jobs maximum, and a push-triggered and manually runnable GitHub Actions debug-build workflow. The browser-connection feature of the Rust engine is not yet enabled for the mobile crate.

Validation: TOML, manifest/resource XML, workflow YAML and upstream source preservation checked. Rust and Android builds and all runtime tests are NOT run: Cargo/Rust, Gradle, Android SDK/NDK absent, external tool download failed DNS. No APK exists. Do not distribute as a finished app.

Remaining release blockers: compile and fix all native and Android errors; HTTPS platform verifier test; transfer/resume/range/no-range/large-file integrity and crash tests; background time limits, process recovery and notifications; safe shared storage export; torrent/magnet; browser and bulk import; yt-dlp/FFmpeg/Python/QuickJS packaging; scheduling, settings and additional desktop features; device/emulator validation; signed release and source/license compliance.

Build package: upstream desktop and scheduled workflows are preserved under `.github/upstream-workflows/` to avoid unrelated builds or scheduled jobs in the Android repository. Only `android-dev.yml` is active. This package is for CI diagnostics, not distribution.
