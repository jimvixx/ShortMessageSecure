# Full replacement: entry points

The agreed policy is full replacement, not merging. First-run system SMS import
is currently offered through SystemSmsImportReminder after initial setup. That
reminder now opens a source chooser. System SMS invokes the existing flow; Silence
opens the same nonexported analysis activity used by Import / Export through a
package-restricted intent. The reminder has no migration-package Java dependency.

Canceling the chooser or returning from Silence analysis does not start the system
import or mark it completed. The existing dismiss action remains unchanged. The
Silence source is explicitly labelled check-only until apply is implemented.

A persistent notice on the analysis screen explains the replacement policy,
existing-message/key impact and the future backup plus confirmation requirement.
It accurately states that current functionality only checks the source. It is not
a destructive-action confirmation or an import-completion state.

## Next implementation boundaries

- Define the complete prepared replacement set: databases, preferences, identity
  keys, sessions, prekeys and historical subscription bindings.
- Recast target occupancy as replacement impact, while keeping readiness false
  until staging, validation, backup, confirmation and recovery gates exist.
- Implement durable apply/rollback across subsystems and process interruptions.
- Mark onboarding import complete only after an actual successful replacement.
- Validate both entry paths, cancellation and replacement on a backed-up device.

This slice changes entry UI and documents policy only. It does not mutate live
messages, keys or settings and does not activate apply. The previously uncommitted
real-schema fixture improvements are retained.

## Verification

Passed: 98 JVM tests, final debug/test APK builds, lint and diff checks. All 135
migration instrumentation tests passed on A53, including the previously unexecuted
real-schema and global-WAL fixtures. The final generic reminder hide-on-accept
adjustment was build/lint checked after that device run; first-run chooser behavior
still needs an end-to-end UI test. No first-run flags or live data were reset.

A generic `Reminder.hideOnAccept()` hook defaults to the old behavior. Only the
source-chooser reminder opts out, keeping it visible after chooser cancellation.
Other reminders retain their behavior.

Verified backup before device testing:
`/home/user/Backups/SMSecure/2026-09-20T21-09-56Z-before-silence-entry`.
APK signatures matched; installation used install -r. Byte comparison detected
changes in three databases. Per-table row hashes isolated them to android_metadata
(the SQLite locale metadata); every other table was unchanged. Crypto, preferences,
protected storage and both original exports were unchanged. Diagnostic log/profile
marker also changed. No restore was performed; SMSecure was relaunched.

The device has the test APK installed before the final reminder behavior adjustment;
the final APK is built locally. No commit or push was performed in this slice.
