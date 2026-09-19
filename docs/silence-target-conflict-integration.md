# Target database conflicts connected to preflight

Baseline commit: `5d6bffc` (consistent read-only WAL inventory).

The target subscription reader now combines identity-key, crypto-file and database
occupancy before publishing candidates. Database references are read through the
single-statement read-only analyzer, not through DatabaseFactory. No messages,
preferences, keys or schema are written. The existing background executor performs
the check on screen entry and explicit refresh.

A candidate used by SMS or a recipient default is excluded. Unknown-subscription
SMS conservatively blocks all candidates. The occupied count is the union of all
block reasons, so one target is not counted twice. A missing, invalid, unsupported
or unreadable database makes the whole inventory unavailable; it is never treated
as empty. Existing review invalidation rules clear stale assignments on changed
results, and deferral remains possible after an unavailable result.

The UI now describes the combined block count and retains the warning about other
tables and later changes. It does not present this check as a full merge analysis.
Adapter tests cover successful empty inventory, database-only occupancy and an
IOException producing unavailable status with no candidates. SQLite fixtures cover
the real SQL behavior, including WAL visibility, in the instrumentation suite.

## Remaining limits

This is conservative refusal of occupied targets, not an implemented merge or
replacement policy. Separate subsystem reads do not form one atomic snapshot and
can become stale; apply-time revalidation/coordination remains mandatory. Other
database tables and recipient/identity relationships still require review.
No import/apply pipeline is enabled.

Passed: 95 JVM tests, 131 Android migration tests, debug and test builds, lint
and diff checks. On the Samsung A53, preflight showed zero available candidates,
zero ambiguous mappings and two blocked bindings; refresh produced the same result.
Backup selection remained accessible.

Verified backup before installation and tests:
`/home/user/Backups/SMSecure/2026-09-19T20-44-52Z-before-silence-review`.
APK signatures matched; installation preserved data. Post-test databases,
preferences, crypto, protected storage and both exports matched the backup.
Only the diagnostic log and profile marker changed. SMSecure was relaunched.
