# Target database snapshot conflicts

Baseline commit: `cc78667` (existing crypto-file guard).

`SilenceTargetDatabaseConflicts` analyzes a caller-owned, quiescent `messages.db`
snapshot at schema 35 with SQLite OPEN_READONLY. It never opens DatabaseFactory,
creates missing databases, upgrades schemas, or queries message bodies/addresses.
It returns only an immutable set of occupied candidate logical subscription IDs.

Distinct references in `sms.subscription_id` and
`recipient_preferences.default_subscription_id` are checked. SMS with NULL/-1
subscription references block all candidates; NULL/-1 recipient defaults do not
reserve a specific binding. Wrong types, out-of-range IDs, missing tables/columns,
unsupported schemas and more than 1,024 distinct references reject the analysis.
WAL, SHM or journal sidecars beside the supplied snapshot are rejected.

## Integration boundary

This analyzer is deliberately NOT connected to the live target reader or UI yet.
The caller must supply a consistent, finalized snapshot; absence of sidecars alone
does not prove that a file copied from a live WAL database is current. Safe target
snapshot acquisition, including concurrent changes, is the next integration step.
No live database is opened by this new code during normal app use.

An unoccupied subscription is not proof that the database can be replaced or merged.
Other tables, recipient/identity relationships and final apply-time revalidation
remain pending. No live import or schema migration is implemented here.

## Tests

Six Android SQLite fixture cases cover unchanged empty snapshots, combined scoped
references, unknown SMS versus unset recipient defaults, invalid types/ranges,
missing tables/wrong schema and unsettled WAL snapshots. Fixtures are created and
deleted only in the test cache. Test code remains compatible with minSdk 24.

Passed: 95 JVM tests, debug and Android test builds, lint, diff checks, and
128 migration instrumentation tests on Samsung A53. The first device run exposed
persistent journaling in fixtures; fixtures now explicitly finalize using DELETE
journal mode. Production rejection of unsettled snapshots was retained.

Before each device run, private and device-protected SMSecure data, installed APK,
and both source exports were backed up and verified. Final-run backup:
`/home/user/Backups/SMSecure/2026-09-19T20-38-42Z-before-silence-review`.
After the final run, databases, preferences, crypto, protected storage and exports
were unchanged. Only the diagnostic log changed. SMSecure was relaunched.
The snapshot analyzer itself remains disconnected from live data and the UI.
