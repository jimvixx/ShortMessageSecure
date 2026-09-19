# Consistent target binding inventory with WAL

Baseline commit: `acd5b16` (target database snapshot conflict analyzer).

For conflict analysis, copying the entire database is unnecessary. The analyzer
now exposes `occupiedCurrent`, opening an existing database with OPEN_READONLY and
reading both SMS and recipient-default references in one bounded UNION ALL query.
SQLite supplies the statement's read snapshot, including committed WAL content.
No checkpoint, file copy, database helper or schema upgrade is requested.

The earlier finalized-snapshot entry point retains its sidecar rejection and
shares the same query. DISTINCT includes SQLite typeof so malformed real values
cannot be hidden by numeric equality with integer values. Per-table bounds still
reject more than 1,024 distinct references. Message contents are never selected.

## Scope and integration

This method is not yet called by the normal preflight screen or target reader.
Current checks run only against synthetic test databases. Wiring target database
conflicts into the inventory and showing distinct block reasons is the next step.
The method is a logical reference snapshot, not a backup of messages, preferences
or crypto files, and it provides no lock against later changes. Final apply-time
revalidation and cross-subsystem consistency remain required.

SQLite may use its shared-memory coordination files while reading a WAL database;
this is not a claim of zero filesystem activity. No persistent database or WAL
write is requested by the analyzer. Concurrent schema migration is outside this
read path's supported workflow; unavailable or unsupported schema rejects analysis.

## Verification

New Android fixtures test an open WAL writer, committed versus uncommitted data,
unchanged database and WAL bytes after reading, missing-file refusal and bounded
inventories. The source fixtures and the user's live database are distinct.
Passed: 95 JVM tests, 131 Android migration tests on Samsung A53, debug and
Android test builds, lint and diff checks. The WAL fixture confirmed unchanged
main-database and WAL bytes, committed-row visibility and uncommitted-row isolation.

Verified pre-test backup:
`/home/user/Backups/SMSecure/2026-09-19T20-42-17Z-before-silence-review`.
Installed signer matched and updates used install -r. After testing, live databases,
preferences, crypto, protected storage and both exports were unchanged; only the
diagnostic log and installation profile marker changed. SMSecure was relaunched.
