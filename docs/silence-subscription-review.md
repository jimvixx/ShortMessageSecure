# Draft subscription review

The preflight screen now offers a decision for each source binding once the crypto
binding inventory is checked. The user can choose an available SMSecure binding,
leave it unselected, or defer it. Deferral preserves the source and remains
unresolved. Assigned targets are excluded from other source rows; validation also
rejects collisions independently of the UI. There is no automatic numeric match.

Binding labels show logical app IDs, not physical SIM slot numbers. The screen
explains this distinction and the lack of key-conflict verification. Choices are
only a draft and never enable import or change live settings or keys. Incomplete
source crypto inventory does not expose the review controls.

## State ownership and invalidation

`SilenceSubscriptionReview` is a package-private state holder owned by the retained
ViewModel. Its plans are immutable snapshots. The activity renders the current
state and has no access to live restore code. A monotonically changing revision
rejects stale dialog callbacks. Refresh temporarily disables review; unchanged
results preserve choices, while changed device-to-app mappings, status or unresolved
counts clear all decisions. No target choices are available without an available
inventory, but sources can still be deferred after the refresh completes.

Every new backup inspection, including password retry, clears the draft. Activity
rotation retains it through the ViewModel. Leaving the activity or process death
loses it intentionally; no preferences or saved-state payload persists it. Dialogs
are dismissed when the model changes and on activity destruction, and use a secure
window like the parent screen. New inspection and refresh cannot apply stale choices.

The separate preflight result remains the original source analysis; the review is
an explicit in-memory draft and is not passed to an apply pipeline.

## Verification and limitations

Passed: 86 JVM tests (including seven new state-transition tests), 122 migration
instrumentation tests, debug APK, Android test APK, lint and diff whitespace checks.
State tests cover unchanged refresh, stale dialog rejection, changed Android ID
with the same app ID, permission loss, new source, collisions, invalid targets,
deferral, clearing and immutable previous snapshots.

Device smoke on Samsung A53: original export inspection (41 SMS, three identity
pairs, one session), explicit assignment, occupied-target exclusion, deferral and
unchanged refresh. After rotation to landscape and back, assignment and deferral remained intact;
original system rotation settings were restored. After testing, all database,
preference, crypto, device-protected and original-export files matched the backup.
Only the diagnostic log and installation profile marker changed. Staging was empty
and SMSecure was relaunched.

Backup before testing:
`/home/user/Backups/SMSecure/2026-09-19T20-23-34Z-before-silence-review`.
Private data, device-protected data and both exports were double-read verified;
the installed APK was saved and signer compatibility checked before `install -r`.

Still pending: human-friendly target identification, target key/data conflict
analysis, live SIM-change device scenarios, process-death UI testing, actual staged
migration outputs and transactional apply/rollback. Saved mapping equality does not
prove SIM identity. No live import is implemented.
