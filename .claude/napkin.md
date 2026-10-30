# Napkin Runbook

## Curation Rules
- Re-prioritize on every read.
- Keep recurring, high-value notes only.
- Max 10 items per category.
- Each item includes date + "Do instead".

## Execution & Validation (Highest Priority)
1. **[2026-10-29] Local toolchain IS installed now: JDK 17 (`C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot`), SDK at `%LOCALAPPDATA%\Android\Sdk`, Gradle 8.9 at `C:\Users\Lauti\.local\gradle-8.9`**
   Do instead: build with `C:\Users\Lauti\.local\gradle-8.9\bin\gradle.bat assembleDebug`; set `JAVA_HOME` and `ANDROID_HOME` in the command. `local.properties` already points to the SDK.
2. **[2026-10-29] `assembleDebug` verified working: BUILD SUCCESSFUL in 7m 35s**
   Do instead: trust a green local build over guesswork; APK lands at `app\build\outputs\apk\debug\app-debug.apk`.
3. **[2026-10-29] Emulator is NOT viable on this PC: Pentium G4560 (2 cores), 8 GB RAM, 12.5 GB free. WHPX hangs QEMU threads**
   Do instead: do not retry the emulator; validate on the user's phone via the APK, or by review plus a local build.
4. **[2026-10-29] CI triggers on push to `main` and uploads `app/build/outputs/apk/debug/app-debug.apk`**
   Do instead: treat the CI artifact as the build truth for anything not built locally.
5. **[2026-10-29] Android config: compileSdk 34, minSdk 26, targetSdk 34, JVM 17, Compose BOM 2024.09.00**
   Do instead: keep new code Java 17 / minSdk 26 compatible and use the existing Compose BOM.

## Shell & Command Reliability
1. **[2026-10-29] Git is not on PATH; `npx github:...` fails with `npm error enoent spawn git`**
   Do instead: download repo ZIPs with `Invoke-WebRequest` and extract with `Expand-Archive`.
2. **[2026-10-29] Windows PowerShell: empty `$env:ANDROID_HOME` makes `Test-Path` throw**
   Do instead: guard with `if ($env:ANDROID_HOME)` or test the literal SDK path instead.

## Architecture
1. **[2026-10-29] State is global in `object Core`, not in the ViewModel**
   Do instead: put shared state in `Core`; the UI, `PlaybackService`, `DownloadService` and `FollowWorker` all read it.
2. **[2026-10-29] Playback lives in `PlaybackService` (media3 `MediaSessionService`); UI uses a `MediaController`**
   Do instead: drive playback through `AppViewModel.withController`; never build a second `ExoPlayer` in the UI.
3. **[2026-10-29] Downloads run in `DownloadService` (foreground, `dataSync`) with a queue in `Core`**
   Do instead: enqueue via `Core.enqueue`; never download from the ViewModel directly.
4. **[2026-10-29] Persistence is JSON files in `filesDir` (`library.json`, `listen.json`, `follows.json`) via `Store`**
   Do instead: extend `Store` with the existing atomic `write` helper; keep `Saved.path` absolute.
5. **[2026-10-29] UI is single-activity Compose with a `Screen` enum (`Home`, `Library`, `Episodes`, `Player`)** + a glass bottom bar (`Inicio`, `Biblioteca`) shown only on Home/Library
   Do instead: add screens to the enum and route them in `OffcastApp`, not new activities. Theme is always dark: black + diffused blue (`drawBehind` radial halos), accent `0xFF3B7BFF → 0xFF1F4FD8`, buttons are translucent glass (`GradientButton`).
6. **[2026-10-29] Networking is raw `HttpURLConnection` through `Net.open` / `Net.text`**
   Do instead: extend `Net` instead of adding OkHttp/Retrofit.

## User Directives
1. **[2026-10-29] Reply in Spanish, terse caveman style active**
   Do instead: answer in Spanish; keep code, paths, numbers and errors verbatim.
2. **[2026-10-29] Confirm before destructive or global environment changes**
   Do instead: preview the plan first.
