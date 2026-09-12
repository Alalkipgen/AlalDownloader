# OneDown

[![Android build](https://github.com/OWNER/REPOSITORY/actions/workflows/android.yml/badge.svg)](https://github.com/OWNER/REPOSITORY/actions/workflows/android.yml)

Replace `OWNER/REPOSITORY` in the badge with your GitHub repository after publishing; no remote is configured in this checkout. Release artifacts use the requested **Alal Downloader** name, but the current installed app name/package remain **OneDown / app.onedown**. Rebranding is separate work.

Native Kotlin Android download manager, minSdk 24 and targetSdk 35, using Compose, Coroutines/Flow, OkHttp 4.12.0, Room and Hilt. Gradle Kotlin DSL and version catalog pin dependencies.

## Features

- Segmented HTTP(S) downloads with pause/resume, validator checks, retries, and durable checkpoints.
- Priority-aware queue, adjustable concurrency and aggregate speed limit.
- Built-in tabbed browser with confirmed download capture and expired-link refresh.
- Downloads list, per-segment progress, batch URL/text import, and persistent settings.
- Foreground notifications and SAF/MediaStore destinations.

Phase 4 is **awaiting compilation and device testing**. See [STATUS.md](STATUS.md) for historical validation results and limitations. Adding CI is not evidence that its first build has passed.

## Screenshots

Screenshots placeholder: add verified Downloads, Browser, and Settings screenshots after a successful device build. Do not expose cookies or signed download URLs.

## Build and test

Requires JDK 17 and the Android SDK command-line tools. Set `JAVA_HOME` to the JDK and `ANDROID_HOME` to your SDK installation, with its `cmdline-tools/latest/bin` on `PATH`. Accept the Android SDK licenses (`sdkmanager --licenses`), then run these commands from the checkout root:

```sh
sdkmanager 'platform-tools' 'platforms;android-35' 'build-tools;35.0.0'
./gradlew lint testDebugUnitTest assembleDebug
./gradlew assembleRelease
```

The checked-in Gradle wrapper downloads Gradle 8.10.2 with SHA-256 verification; a system Gradle or Android Studio is not required. Dependency downloads require network access. `local.properties` is optional, ignored, and not required: Gradle uses `ANDROID_HOME`. Build scripts contain no developer-specific absolute paths. Historical validation notes may retain paths from the original device.

The debug APK is produced in `app/build/outputs/apk/debug/`; the release APK is in `app/build/outputs/apk/release/`. On Windows use `gradlew.bat`. This Android editing host currently has no Java/SDK, so compilation and installation have not been verified here.

## GitHub Actions

The [Android workflow](.github/workflows/android.yml) runs lint, JVM tests, and the debug build on pushes to `main`, pull requests, and `v*` tags. Download artifact **alal-debug** from the successful run. Validation reports are uploaded even if the build fails. Gradle caching is provided by `gradle/actions/setup-gradle`. Superseded runs for the same ref are cancelled.

For an optional API 34 emulator run, use **Actions → Android → Run workflow** and enable **Run API 34 emulator tests**. This invokes `connectedDebugAndroidTest`; there are currently no instrumentation test sources, so it does not yet provide a device acceptance suite.

### Release signing

Generate and securely back up a release keystore **outside the checkout**:

```sh
keytool -genkeypair -v -keystore alal-release.jks -alias alal -keyalg RSA -keysize 2048 -validity 10000
base64 -w 0 alal-release.jks
```

The base64 command is for GNU/Linux; on macOS use `base64 < alal-release.jks | tr -d '\n'`. Base64 is encoding, not encryption. Never paste the output into source, issues, or logs.

Create these repository **Actions secrets** in GitHub Settings → Secrets and variables → Actions:

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | Complete base64-encoded keystore |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | `alal`, or your actual alias |
| `KEY_PASSWORD` | Key password (often the keystore password for PKCS12) |

The release job decodes the keystore into `RUNNER_TEMP`, sets `KEYSTORE_PATH` plus the three signing variables, builds, and removes the temporary keystore on exit. Missing secrets fail the job rather than publishing a debug-signed APK. Secrets are used only in the tag release job, not pull-request builds. Restrict who can push release tags and modify workflows.

For a locally signed release, supply `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` through your environment or secret manager, then run `./gradlew assembleRelease`. Relative keystore paths resolve against the repository root. With **none** of the four variables set, release builds deliberately use the debug signing configuration for local testing; **partial** configuration is rejected. Debug-signed APKs are not production releases and cannot update an installation signed with your release key. Never commit keystores or passwords.

Push a version tag such as `v1.2.3` to create a GitHub Release after the build job passes. The release attaches **Alal-Downloader-v1.2.3.apk** and generates release notes automatically. Version tags should start with a digit after `v` and use letters, digits, dots, plus signs, or hyphens. `versionName` is the tag without its leading `v`; `versionCode` is `GITHUB_RUN_NUMBER`. Local defaults are `0.1.0` and `1`. Keep the same signing key and ensure version codes increase for updates; workflow run numbers are scoped to this workflow.

## License

Project source: [MIT](LICENSE). Dependency and planned font licensing: [THIRD_PARTY.md](THIRD_PARTY.md).

## Debug usage after a successful build

Choose a writable local folder, or use public Download/OneDown on API 29+. On API 24–28, folder selection is required. Use Downloads > Add URLs for one or more HTTP(S) URLs. Existing Phase 1 downloads retain their original paths. Interrupted downloads restore paused after process restart, with invalid segment extents reset; completed, failed and cancelled records retain terminal status.

## Downloads and settings (Phase 4)

- Bottom navigation switches Browser / Downloads / Settings. Downloads has All, Active (including paused/network-waiting), Completed and Failed filters, size/progress/speed/ETA, per-segment bars and explicit action buttons. Swipe right to pause/resume; swipe left to confirm deletion. Long-press opens details; completed-file taps open an Android chooser with read-only URI access. Sensitive header values are redacted, but URLs may contain signed tokens: do not share screenshots of details indiscriminately.
- Deletion waits for active writers to stop. Keep-file is the default; unfinished MediaStore files become visible but may be incomplete. Provider deletion failures retain the entry. Legacy file opening makes a cancellable copy into a narrowly exposed cache directory; this requires temporary extra disk space and cached copies can remain until Android clears cache.
- Add URLs accepts one URL per line or a UTF-8 `.txt` document, at most 1000 entries / 1 MiB. Blank lines and a leading BOM are accepted; duplicates intentionally produce separate downloads. Invalid line numbers are shown. Enqueue stops on the first failure and retains only unsubmitted lines for retry; this is not an atomic batch transaction.
- Settings persist default segments (1–32), simultaneous downloads (1–10), aggregate engine speed in KiB/s (0 unlimited), Wi-Fi-only policy, destination and system/light/dark theme. Raising concurrency drains the queue; lowering it does not interrupt existing jobs. Speed updates apply to engine body reads across all active segments; protocol overhead, probing and browser blob/data saves are excluded.
- Shared filenames preserve extensions within a 180-byte UTF-8 budget, strip unsafe/control characters, number collisions and use the provider's final display name. Collision allocation is serialized within this app; external apps/providers can still race.
- Room remains v3: Phase 4 adds DAO operations, not entity columns. Generated Room schema and upgrade validation still require an Android build host.

### Phase 4 acceptance checks

All runtime checks are **NOT RUN**: test gestures and accessible buttons; screen rotation and browser return; theme/settings restart persistence; duplicate/long/invalid/oversized batch input and partial enqueue failure; chooser grants and missing files; SAF revoked grants and MediaStore collisions; deletion during reads and provider failure; live speed/concurrency changes; empty and mismatched HTTP bodies; resumed SHA-256 integrity. Retry failures remain capped at five total attempts.

The engine package has no Android or UI imports. Room and Android storage implement its persistence and positional-storage boundaries. SAF uses platform DocumentsContract rather than adding the DocumentFile library. Non-seekable providers are unsupported.

An active engine session runs in a dataSync foreground service with notification controls. Network loss moves work to WAITING_FOR_NETWORK; the service remains foreground but releases the wake lock until an allowed network returns. Wi-Fi only means unmetered connectivity. Manual Pause/Cancel never auto-resumes. Android force-stop cannot be survived; reopen and Resume instead. Android 15's six-hour background dataSync budget applies. Interrupted transfers now restore paused and auto-resume by default, subject to platform start restrictions. Boot and restricted-start recovery use a persisted expedited JobScheduler job (not WorkManager). When background foreground-service startup is rejected, a notification asks the user to open Alal. Background settings provide battery-exemption and OEM autostart shortcuts. See BACKGROUND-VALIDATION.md for limitations and actual validation.

## Built-in browser

- Browser opens DuckDuckGo; enter a URL or search, navigate back/forward/reload/home, and open/close tabs. Settings include desktop UA, popup blocking, configurable download extensions, optional media candidates and foreground clipboard suggestions (off by default). Cookies persist; tabs/history are currently Activity-local and reset on recreation.
- Complete host timers/captchas manually. DownloadListener, extension navigation (including URL-bar submissions) and long-press **Download link** open the same confirmation sheet. Review filename, known size, folder and 1–32 segments. HTTP captures include UA, Cookie, Referer and Accept; engine requests start ahead of queued work without preempting active transfers. Up to eight connections per download remain the default cap.
- **Reopen page** appears for a saved source page with HTTP 403/404/410 failure, including restored paused failures. In the new refresh tab, tap the host download button. A DownloadListener event matching filename OR positive known size offers replacement of the existing entry, not a duplicate. Matching validators, size and range support are still required to retain offsets; unverifiable/changed content restarts safely.
- Blob saves use a per-WebView, confirmed-session token and 48 KiB chunks with sequence/size checks and acknowledgement backpressure. Data URLs decode incrementally. Both use SAF/MediaStore on a serialized background writer, bypass engine networking and are not listed as persistent engine transfers. Keep the page open. Navigation/cancel attempts to delete partial output; provider failure or process death can leave an orphan. Cross-origin-frame blobs may be unavailable. Large blob memory use remains controlled by WebView's Blob implementation.
- Media candidates use URL extensions and captured request headers without issuing extra probe requests. They are not response-MIME-confirmed. HLS/DASH choices save manifests only. Clipboard suggestions inspect HTTP(S) text only while resumed/focused, with a bounded history of 100 hashed suggestions.
- JavaScript, DOM/database storage, third-party cookies and compatibility mixed content are enabled for file-host compatibility. TLS errors retain WebView's default rejection. File/content access is disabled. Popup heuristics cannot block all ads; disable blocking for host compatibility if needed. Some login/upload/POST-only download flows are unsupported.
- Debug `OneDownHttp` logs show method/hostname and Cookie/Referer presence only. Headers remain in app-private Room storage. The existing engine forwards caller headers across redirect hosts: use trusted sources, since credentials can reach redirect destinations.

## Phase 3 device acceptance checklist

All entries currently **NOT RUN**. Use disposable destination files and record checksums and redacted header-presence diagnostics.

1. Direct range-capable ZIP: confirm renamed filename/segments, verify multipart requests and checksum. Queue several transfers and confirm captured priority without preemption.
2. Google Drive downloadable file: complete any manual confirmation; capture browser cookies and verify engine Cookie/Referer presence and downloaded contents (not HTML).
3. Countdown/captcha host: solve manually, capture the real link, check header-presence logs and checksum. Test popup-block toggle.
4. Expired signed link: fail with 403/404/410 after partial progress, reopen the page, refresh, verify one row/destination and resumed offsets with unchanged validators. Repeat with changed/missing validators and verify safe restart.
5. Blob/data: compare binary hashes; test empty/large files, cancel/navigation, stale/out-of-order bridge calls, disk full and process death. Test API 24–28 SAF and API 29+ MediaStore.
6. Upgrade actual v2 Room database to v3 and verify old downloads and segment rows survive. SQL smoke checks are not Room validation.

## Manual verification still required

1. Download a known 500 MB file with byte-range support and stable validators. Record progress, swipe the app from recents, and verify notification progress continues.
2. Force-stop with `adb shell am force-stop app.onedown`, then reopen. Verify PAUSED status and retained checkpoints, resume, and compare the finished file's size and SHA-256 with the source. `am kill` may not terminate a foreground-service process; force-stop is a distinct recovery test.
3. Repeat with SAF and MediaStore, revoked tree grants, truncated/deleted partial files, network off/on, metered Wi-Fi-only blocking, notification permission denied, action buttons, disk full and dataSync timeout.
4. Verify a Room v1 installation upgrades without data loss. SQL smoke checks do not replace Room migration validation.
