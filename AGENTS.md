# OneDown development constraints

## Scope

The user accepts Phases 1–2 as the baseline. Approved Phase 3 adds the built-in WebView browser, confirmed download capture, page-bound blob/data saves and expired-link refresh. Preserve foreground protection, storage recovery and the five-total-attempt retry limit. Source is awaiting build verification on a host with Java and Android tooling.

## Required stack

Kotlin, native Android, minSdk 24, targetSdk 35, Jetpack Compose, Coroutines and Flow, OkHttp 4.x, Room, Hilt, Gradle Kotlin DSL and a version catalog. No RxJava.

## Architecture and future engine requirements

Packages: `core/engine`, `core/data`, `core/service`, `feature/browser`, `feature/downloads`, `ui`, under `/data/user/0/com.vscodroid/files/projects/OneDown/app/src/main/java/com/alal/downloader`.

The engine must remain independent of UI and WebView. Its input is a `DownloadRequest` containing url, fileName, headers and referrerPageUrl; state is exposed via Flow. Persist url, headers as JSON, referrerPageUrl, fileName, totalBytes, per-segment progress, status, ETag and Last-Modified.

Use RandomAccessFile or seekable document channels with explicit offsets for segment writes; never buffer the entire file. Network calls must run off the main thread and file I/O on Dispatchers.IO. Handle network loss, HTTP 403/404/416/5xx, redirect chains, missing Range support, disk full and process death. Segment retries use exponential backoff with at most five attempts.

## Quality gates

Browser rules: never log credentials, URL queries or raw cookies. Debug request diagnostics may record method, hostname and header-presence booleans only. WebView remains outside the engine. Capture cookies for the download URL, UA and referrer; do not automate captchas. Blob bridges must require a confirmed per-page session token, bound chunk size/sequence and one outstanding write. Navigation invalidates the session. Media interception is URL-based candidate detection, not response-MIME sniffing. Link matches identify the intended row, not content identity; validators and range checks govern resume. Room schema is v3 with v1→v2→v3 migrations.

No TODOs or stub functions. Every written function must be complete. Concise KDoc on public classes only; avoid comment noise. Verify edited files and run `./gradlew assembleDebug` from `/data/user/0/com.vscodroid/files/projects/OneDown` after each phase. Fix build errors before declaring a phase complete. If the host cannot execute the build, record the actual blocker and leave the phase awaiting build verification. Static checks are not compilation or runtime tests.