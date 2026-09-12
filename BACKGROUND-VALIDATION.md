# Background download validation

## Implementation and limits

- Engine remains application-scoped; service foreground promotion precedes transfer start.
- CPU lock: Alal:download, ten-minute timeout, renewed every five minutes; Wi-Fi high-performance lock only during active Wi-Fi transfers.
- Existing one-second notification loop retained. Completion Open, expired-link Reopen page, summary Pause all, and Downloads routing added.
- Restore preserves segment checkpoints; default-on auto-resume excludes manual pauses, cancellations and failures. Wi-Fi-only means validated unmetered connectivity, as before.
- Room uses insert-ignore/update instead of replace to preserve row ordering. Table schema remains v3.
- **Deviation:** platform JobScheduler is used instead of WorkManager because project instructions restrict dependencies to existing libraries. Neither expedited mechanism guarantees foreground-service eligibility. Boot/restricted starts can require tapping the recovery notification, particularly on Android 15. Exact automatic boot resume is not guaranteed.
- Android 15 dataSync background time limits still apply. Force-stop, OEM task killers, revoked storage access, and unavailable/expired links cannot be bypassed. Page-bound blob/data downloads still require their browser page.
- Battery exemption is optional; direct exemption requests may require Play policy justification. OEM shortcuts fall back to App info; autostart status is not queryable.
- The persisted active flag is a best-effort interruption indicator, not proof of an OEM kill.

## Automated validation

- Baseline branding CI run 34694596986: completed successfully.
- Local Gradle invocation: blocked by missing Java/JAVA_HOME; direct wrapper also encounters Android /bin/sh execution restrictions.
- Three added JVM tests cover retained offsets/interrupted selection, Wi-Fi/network waiting and manual pause, and no connectivity-driven retries of terminal 403/expired failures.
- Current change CI: pending at commit creation; consult the GitHub Actions run for this commit.

## Real-phone checklist — all NOT RUN (no adb/device access)

1. Start a 1 GB download; watch YouTube for five minutes; compare downloaded bytes before/after.
2. Lock screen for ten minutes; verify bytes advance and locks release after pause/completion.
3. Swipe from recents; verify notification remains and progress advances.
4. Airplane mode for 30 seconds; verify WAITING_FOR_NETWORK, retained offsets, then ordered resume.
5. Reboot with auto-resume enabled; check recovery or explicit user-action notification under platform restrictions.
6. Run `adb shell am kill com.alal.downloader`; reopen and compare offsets. Note am kill normally only kills safe/background processes and may not kill an active foreground service; verify PID actually changed.

Also verify notification Pause/Resume/Cancel/Pause all/Open, OEM fallback activities, denied notifications, auto-resume OFF, metered Wi-Fi, and Android 15 service timeout on supported devices.
