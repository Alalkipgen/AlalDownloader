# Browser guard, share and tabs — `work/browser-guard`

## Status (2026-10-29)

Branched from `work/browser-file-capture` (6fc51ab, in the owner's phone test; it merges first). Owner-requested: a tap or a page jump must never take the download page away; no ad blocking and no host-specific code. CI (`ci.yml`) on the branch is the build/test record; phone test is **NOT RUN**.

- Popups and redirects never take the page away. Pure `BrowserPolicy.popup()` and `BrowserPolicy.guard()` decide (same site = OkHttp `topPrivateDomain()`, else the host). Popups (`onCreateWindow`) never load in the current tab: Block popups on, a same-site popup from a tap opens in a new tab in front and others are blocked; off, all open in new tabs. WebView now always offers popups (`javaScriptCanOpenWindowsAutomatically`) so a blocked one can name its host; at most four wait for an address at once (the oldest is dropped). A popup tab whose first load is a file hands the capture to its opener tab and closes. A tap is consumed only by the navigation it matches, so an ad's jump on the same tap does not spend it. Block redirects (new, default on): a main-frame navigation to another site loads only if app-started (URL bar, Home, history, Back/Forward/Reload, Reopen page, a bar's Open) or a gesture tap on a visible link/form button whose address it is, plus the server redirects after it; script, timer, meta-refresh and overlay-tap jumps are blocked and the page stays. `TapRecorder` (`AlalTap`, per-page key replaced on every commit) sends only the tapped address and opacity/size against the viewport. One bar at a time ("Popup blocked: <host>" / "Blocked a redirect to <host>", Open, Allow on <site>) replaces the old toast; Settings and Browser settings list allowed sites. File arrivals, the refresh tab and Reopen page are never blocked. JUnit covers subdomain and Drive→usercontent loads, a tap's cross-site popup, script/overlay redirects, a tapped link and its redirects, allowed sites and each setting off.
- Share to Downloader. `ACTION_SEND` text/plain was already accepted (`AndroidManifest.xml` intent filter on `MainActivity`; `onCreate` and `onNewIntent` hand the intent to `AlalApp`), which opened Add download with the first http/magnet match; only the link handling changed. Pure `SharedLinks.extract()` returns the distinct http(s) links in order (any Unicode space ends one; trailing punctuation dropped; a closing bracket kept only when the link opened one). One link opens a new tab (user-started), several open Add multiple URLs prefilled for confirmation, none shows a short message; other schemes are ignored. JUnit covers made-up Telegram, Facebook and Chrome texts, several links, punctuation, brackets (the MegaUp `(Hmtv)` name stays whole) and no-link texts.

# Browser file capture — `work/browser-file-capture`

## Status (2026-10-29)

Owner-requested 1DM-style flow: Paste & Go opens the page, the owner completes host steps by hand, and the confirmation sheet opens only when the browser receives the actual file. No host-specific code, resolvers or automation. CI (`ci.yml`) on the branch is the build/test record; phone test is **NOT RUN**.

- Navigation never becomes a download because of its URL. `BrowserPolicy.navigation()` decides URL-bar submissions, main-frame `shouldOverrideUrlLoading` and forwarded popups: http(s) always loads, so a host page whose URL ends in `.mp4` opens as a page. Magnet toast, blob:/data: page-bound save, unsupported-scheme toast, long-press **Download link** and refresh-tab matching are unchanged. JUnit covers `.mp4`/signed-query page loads and magnet/blob:/data:/unsupported schemes.
- Approved AGENTS.md exception: the browser offers a file it opened itself. After each main-frame commit (`onPageCommitVisible`, `onPageFinished` fallback, once per navigation) `document.contentType` is read via evaluateJavascript, without an extra request. Video/audio, or non-HTML at a Download-extension URL, goes through the DownloadListener capture path (refresh matching applies). Referer and **Reopen page** use the previous history entry, never the file URL. One `RangeProbe` call with the captured headers gives the Content-Disposition name (`ProbeResult.serverFileName`) and total size; failure or HTML falls back to the URL-based name and unknown size. A URL is offered once per tab; page media pauses while the sheet is open, **Download** stops the media fetch and dismiss leaves the page. Sub-resource media candidates are unchanged. JUnit covers the type × extension × setting decision, the evaluateJavascript result parser, referrer choice, name/size fallback and the probe's server name.
- Redirects never carry the previous host's cookies. `DownloadHttp.execute()` (at most five redirects) applies the pure `RedirectHeaders.forHop()` rule: the original host keeps today's headers; any other host (subdomains included) loses Cookie, Authorization and Proxy-Authorization and gets the browser's cookies for the new URL from `CookieSource` (core/engine, no WebView import), or no Cookie. UA, Accept, Range, If-Range and the https→http rejection are unchanged. `BrowserCookieModule` (feature/browser, Hilt) implements it with CookieManager; the engine, Connect probe and browser probe use it. No cookie values or URL queries are logged. JUnit covers same host, subdomain, other site, no browser cookies and a redirect chain through the engine HTTP path.
- One format for speeds and sizes: `ByteFormat` (core/engine, pure Kotlin, 1024-based, labels B/KB/MB/GB) replaces `DownloadPresentation.bytes()`, the notifications' `/1024 KiB/s` and Android `Formatter` sizes. Speeds below 1 MB/s are whole KB/s ("850 KB/s"); from 1 MB/s they are two-decimal MB/s ("1.25 MB/s"), so "1024 KB/s" never appears. Sizes keep today's precision (B, one decimal KB/MB, two decimals GB, "Unknown" when negative); digits are truncated, not rounded, so a value never shows 1024 of the smaller unit. Used by per-download and summary notifications, list cards, speed strip, details, storage lines, Add-download size, browser sheet/media sizes, page-save progress and the 1 MB import-limit text. The speed-limit setting only changes its label to KB/s; stored value and limits are unchanged. JUnit covers unit boundaries and locale independence.

# Phase 4: Downloads UI and settings — checkpoint

## Phase 4 status (2026-09-12)

Phases 1–3 are user-accepted baseline. Phase 4 source is implemented but **awaiting compilation, JUnit execution and device acceptance**.

- Added Downloads filters/actions/gestures/details, segment progress, ETA, completed-file chooser, bottom navigation, UTF-8 batch import and persistent transfer/theme settings.
- Added shared aggregate limiter, runtime queue capacity, cancel-and-join deletion with retained failure records, notification cleanup, filename sanitation/collision handling and strict RFC 5987 decoding fallback.
- Reconciled DownloadStore test implementations; added 14 JUnit tests for limiter changes/cancellation, filenames, header parsing, batch bounds/duplicates, presentation, queue capacity, deletion ordering/provider failure and empty/mismatched transfers. Tests are **NOT RUN**.
- Act-mode follow-up: retain worker tracking until cancel-and-join returns in pause/cancel/delete, resume and link replacement. Caller cancellation must not free capacity while the old worker is still checkpointing. Added a regression that blocks the cancellation checkpoint, cancels the pause caller and verifies a subsequent download remains queued until the checkpoint finishes; execution remains blocked by missing Java.
- Re-inspected the actual bottom-navigation implementation and confirmed Browser/Downloads/Settings routing and browser pause/resume hooks are present; this is source review, not Compose or device validation. Re-ran both Gradle tasks during this follow-up: each again exited 1 before compilation because Java is missing. No toolchain or Git identity was installed/configured.
- Both `bash ./gradlew assembleDebug --no-daemon` and `bash ./gradlew testDebugUnitTest --no-daemon` exited 1 before compilation: JAVA_HOME unset and java missing. Logs: `/data/user/0/com.vscodroid/files/projects/OneDown/validation/phase4-assemble.log` and `/data/user/0/com.vscodroid/files/projects/OneDown/validation/phase4-tests.log`.
- XML parsing passed. Local tree-sitter Kotlin inspection is inconclusive: its grammar reports diagnostics on existing delegated anonymous objects/trailing-comma syntax. It is not Kotlin compilation; raw diagnostics are preserved in `/data/user/0/com.vscodroid/files/projects/OneDown/validation/phase4-syntax.log`.
- No SDK/adb, APK or generated Room schemas were found. Room stays v3; no Phase 4 entity migration was introduced. SAF, chooser, settings persistence and gesture checks remain **NOT RUN**.
- No Git repository or configured author identity exists; no commit created. Requested commit after validation and repository setup: `Phase 4: downloads UI and settings`.

### Remaining limitations

Legacy chooser access copies files into narrow cache storage, requiring temporary extra disk space. Provider and database deletion cannot be atomic; a database failure after file deletion may retain a record pointing to a missing file. Provider creation/checkpoint failure can orphan files. Collision enumeration cannot prevent changes by other apps. HTTP mismatch detection covers observable bytes/headers; it cannot detect bytes hidden by HTTP framing. Batch partial-failure UI and provider collisions still need runtime tests. No production-readiness claim is made without compilation and device gates.

# Historical Phase 3 checkpoint

## Phase 3 status (2026-09-12)

Source implemented; **awaiting compilation, JUnit execution and device acceptance**. Phases 1–2 are user-accepted baseline; historical verification records below are unchanged.

- Added Compose/WebView browser with URL/search, DuckDuckGo, tabs, navigation/progress, long-press actions, persistent cookies/settings, desktop UA and same-tab popup forwarding/blocking.
- Added shared capture confirmation with sanitized filename, size, SAF/MediaStore folder and segments; browser headers pass through foreground coordinator. Added explicit queue-front scheduling without preempting active transfers, persisted segment count and confirmed-name policy, and Room v2→v3 migration.
- Added page-bound token-gated blob bridge with bounded chunks, sequence/size validation, one outstanding write, background storage, progress and cancellation/partial cleanup; incremental data decoding supports API 24 without java.util.Base64. Saves are page-local, not restartable engine records.
- Added source-page reopening, tab-scoped refresh matching and durable existing-entry URL/header replacement. Retained representation-validator/range safety checks and restored errors so refresh remains available after process recovery.
- Added optional URL-based media candidates (not MIME sniffing) and default-off foreground clipboard suggestions. Added debug-only method/host/header-presence logging, no raw credentials.
- Added 13 JUnit declarations (42 total): URL classification, filename sanitation, refresh matching, header filtering, data decoding, bounded writes, priority and safe replacement/resume cases. **None executed**: both Gradle tasks exited 1 before compilation because JAVA_HOME is unset and java is absent from PATH. No SDK tools/adb or configured SDK variables detected. No generated v3 Room schema or APK.
- Logs: `/data/user/0/com.vscodroid/files/projects/OneDown/validation/phase3-assemble.log` and `/data/user/0/com.vscodroid/files/projects/OneDown/validation/phase3-tests.log`.
- PASS: XML/TOML parsing, no TODO/NotImplementedError stubs, engine UI independence, SQLite migration SQL/default smoke check. These do not establish compilation or Android correctness.
- PASS: extracted blob JavaScript executed in a Node mock for 0, 1, 49152, 49153 and 200000 bytes; verified binary equality, chunk bound, sequence, acknowledgement callbacks and completion cleanup. This does not test the Android JS bridge or storage provider.
- Direct ZIP, Google Drive cookies, countdown/captcha capture, expired-link offset preservation: **NOT RUN**. Device checklist and limitations are in `/data/user/0/com.vscodroid/files/projects/OneDown/README.md`.
- No Git repository or global author identity exists; no commit created. After successful verification and obtaining a real author identity, requested commit: `Phase 3: built-in browser with download capture`.

### Remaining Phase 3 limitations

Activity recreation resets tabs and interrupts page saves; browser state is not a persistent browsing history. Blob sources confined to cross-origin frames may not be fetchable from the top page. Bridge is installed before navigation (Android exposes new JS interfaces on next page load), but rejects every write without an approved page/session token. Same-page scripts inherently control blob contents. Popups no longer replace the page: same-site tap popups open in a new tab with the popup's URL (window.opener flows may not work) and others are blocked behind a bar; POST form navigations are not seen by the redirect guard. Provider deletion/process-death cleanup is best-effort. Media candidates do not assemble adaptive streams. Existing engine redirect header forwarding can disclose captured credentials to redirect hosts; trust the source. No claim of Phase 3 completion until build and device gates pass.

# Historical Phase 2 checkpoint

## Phase 2 status (2026-09-12)

Implementation is present but awaiting compilation, executed tests and device verification. User accepted Phase 1 as the baseline; its previous validation history is retained below.

- Added Hilt foreground dataSync service, partial wake lock, notification actions, one-second notification refreshes, runtime notification permission and required manifest declarations.
- Added a serialized foreground/engine coordinator, WAITING_FOR_NETWORK, connectivity callbacks and persistent unmetered-only policy. Waiting sessions retain foreground protection without a wake lock so network regain can resume legally in the background.
- Added engine-independent positional storage, SAF DocumentsContract/ParcelFileDescriptor writes, persisted folder selection, API 29+ pending MediaStore Downloads, and legacy file destinations. No new library dependencies. API 24–28 requires folder selection. Non-seekable SAF providers are rejected.
- Added Room v1→v2 migration and per-download destination identifiers. Restoration pauses interrupted work and resets only segment checkpoints not covered by the destination length; missing files reset all segments.
- Added twelve recovery/storage/network JUnit tests: 29 test declarations total. None executed on this host. Latest cases cover revoked destination access, shutdown preserving manual cancellation, and persistence before truncation/publication.
- Both Bash Gradle attempts (assembleDebug and testDebugUnitTest) exited 1 before compilation: JAVA_HOME unset and java absent from PATH. Android SDK and adb remain unavailable. No APK or generated Room schema exists.
- Static XML/TOML parsing, absence of TODO stubs and engine Android-independence checks passed. A SQLite smoke test executed the migration statements extracted from source and confirmed legacy parent and segment rows survive; this is not Room schema/migration verification.
- Follow-up review guarded API 26 notification APIs, refreshed stopped-service action notifications without recreating a foreground summary, propagated foreground-start rejection to waiting commands, and stopped the service on network-monitor failure. Commands fail promptly while teardown is in progress rather than holding the command mutex while waiting for shutdown. Removed an old-service summary cancellation that could affect a newer owner. These lifecycle changes still require Android instrumentation.
- 500 MB recents-swipe, force-stop/reopen, checksum, permission, provider, disk-full and timeout tests remain unperformed. See the manual checklist in `/data/user/0/com.vscodroid/files/projects/OneDown/README.md`.
- No Git repository or configured author identity; no commit created. Requested commit after successful validation: `Phase 2: foreground service, notifications, SAF storage`.

### Remaining limitations

Android process termination/force-stop interrupts downloads; reopening restores PAUSED rather than automatically restarting. dataSync background execution is subject to Android 15's six-hour budget and manufacturer restrictions. Cancel retains partial data (including pending MediaStore items). A process kill between provider document creation and saving its URI can leave an orphan document; provider and Room operations cannot share a transaction. Storage-provider seek/sync semantics and Room code generation require real Android validation. Service/notification lifecycle and concurrency behavior have source review only, not instrumentation coverage.

## Historical Phase 1 checkpoint

## Status
Source implementation is present but NOT build-verified or runtime-tested. No APK or successful unit-test report exists. Do not mark this phase complete.

Implemented: request/state/error models; HEAD/ranged GET probe and header parsers; segment planner; bounded-retry RandomAccessFile segment transfers; task orchestration with durable checkpoints, pause/resume validation and range fallback; application-scoped queue; Room entities/DAO/database/repository; Hilt wiring; minimal URL/Add/progress/Pause/Resume/Cancel UI. Seventeen JUnit tests cover segment planning, header parsing and transfer regressions; none has been executed on this host.

## Latest correctness review (2026-09-12)
- A ranged probe receiving HTTP 200 now disables segmentation even if Accept-Ranges advertises bytes. Negative HEAD lengths and unexpected successful probe status codes are not trusted.
- Full-file transfers no longer return early merely because an existing byte count equals the expected length. Reset checkpoints are saved before truncating and syncing the file.
- Task updates save before publishing. A checkpoint failure cannot publish a successful transition first.
- HTTP 200 range fallback invalidates segment checkpoints before re-probing, so an interrupted fallback cannot reuse known-invalid ranges.
- Added three in-process OkHttp/JUnit regression tests without adding dependencies. These are source-reviewed, not executed.
- Re-read edited Kotlin files and reran static checks: TOML parsed, four XML files parsed, seven engine files remained independent of Android/UI imports, and checkpoint-order assertions passed.
- Retried assembleDebug directly after these edits: exit 126 (wrapper interpreter permission denied). Bash assembleDebug and testDebugUnitTest each exited 1 (Java missing), before compilation or test discovery.
- Environment inspection found no java, javac, sdkmanager, standalone Gradle, proot or package-manager command in PATH; JAVA_HOME/ANDROID_HOME/ANDROID_SDK_ROOT are unset. Host is Android API 36 ARM64 with approximately 4.9 GiB free. No toolchain was installed and no files in the unrelated project were changed.
- Upstream VSCodroid documentation identifies a supported Java 17 installation route: Command Palette > VSCodroid: Manage Toolchains, or long-press the app icon > Manage toolchains. Open a new terminal after installation. This requires app UI interaction unavailable to the current tools. Java installation alone does not establish Android build support: the SDK remains missing, and upstream documents restrictions on absolute-path toolchain execution and JDK helper spawning. Source: https://github.com/rmyndharis/VSCodroid/blob/main/docs/USER_GUIDE.md.

## Validation actually performed
- Structural checks passed for version-catalog references, XML parsing, absence of Kotlin TODO stubs, and engine independence from Android/UI imports.
- Coroutines 1.9.0 continuation resume signature checked against upstream source.
- Both `./gradlew assembleDebug` and `./gradlew testDebugUnitTest` attempted from `/data/user/0/com.vscodroid/files/projects/OneDown`: exit 126, `/bin/sh: bad interpreter: Permission denied`.
- Both tasks retried using Bash and the absolute wrapper path: exit 1, `JAVA_HOME is not set and no java command could be found in your PATH`.
- No JDK or Android SDK found in inspected locations. Android ARM64 requires compatible native build tooling, not ordinary Linux x86-64 SDK tools.
- Kotlin compilation, Room/Hilt code generation, actual tests and device behavior remain unverified.

## Behavioral decisions and limitations
- Five total attempts; waits 1, 2, 4, 8 seconds. The originally listed 16-second retry would require a sixth attempt.
- Files live in a per-download UUID directory below app-specific external Downloads storage, preventing filename collisions.
- Cancel stops and retains partial data; Resume is available. Per the requested restore policy, every non-completed record, including cancelled records, restores as PAUSED.
- No foreground service: process death stops execution. Restart restores checkpoints but does not auto-start transfers.
- Headers are persisted unencrypted in the app-private Room database and forwarded on every redirect hop, including cross-origin hops, as requested. Supply only trusted URLs; credentials can be disclosed to redirect destinations. HTTPS-to-HTTP redirects are rejected. No header logging is added.
- Caller Range/If-Range headers are rejected because the engine owns them. Non-identity encoded file responses fail rather than risk corrupt range offsets.
- Expired-link classification uses HTTP 410; ambiguous HTTP 403 remains Forbidden, not guessed as expiration.
- HTTP cleartext is enabled to support user-entered HTTP download URLs.
- Room schema export is configured; schema JSON cannot be generated until the annotation processor runs.

## Historical Phase 1 resume instructions (superseded by Phase 2 above)
1. Read `/data/user/0/com.vscodroid/files/projects/OneDown/AGENTS.md`. No `.clinerules` was found during inspection.
2. Provide a supported JDK 17 / Android SDK 35 / Build Tools 35.0.0 host or compatible Android toolchain.
3. Run assembleDebug and testDebugUnitTest, fix all compiler/test failures, and exercise pause/resume, process death, range fallback, HTTP failures and disk full on disposable files.
4. Git repository is not initialized and no commit was created. No Git user identity was configured. After successful validation, initialize only this project, use the user's actual identity, and commit with `Phase 1: core download engine`.
