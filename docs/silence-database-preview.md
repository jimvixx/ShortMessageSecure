# Silence database preparation: second isolated slice

## Result

The existing **Import → Check Silence backup** flow now performs a trial schema conversion
on a second copy of `messages.db` after structural preflight succeeds. The UI reports that
database preparation was checked on a temporary copy. No apply action exists; the result still
returns `isReadyToImport() == false`.

The first slice is committed as `0c0d3d8` (`feat: add isolated Silence backup preflight`).
The second slice is committed as `d77947c` (`feat: validate Silence database migration in staging`).
No push was performed. The following crypto verification slice is documented in
[silence-crypto-verification.md](silence-crypto-verification.md).

## Conversion boundary

`SilenceDatabaseMigrator` accepts an owned staging snapshot, not an arbitrary application
context or database path. It creates `database-preview/messages.db` inside that snapshot.
Only this second copy is opened read-write. The original snapshot and source SAF documents
remain unchanged. The coordinator closes and removes the prepared database before returning
metadata and then removes the outer snapshot.

Supported conversion: Silence schema 30 → SMSecure schema 35.

- Rebuild `identities` with `key` mapped to `identity_key`, preserving IDs, recipient IDs,
  stored key strings, MACs, and optional legacy `name` values.
- Initialize `verified` to 0 (unknown), never infer trust from a schema conversion.
- Add `thread.pinned_order` with default 0, retaining archive state and existing thread fields.
- Update `user_version` to 35 only within the successful transaction.
- Preserve legacy MMS/part/address tables and data in the trial copy; no MMS or attachment
  import is implemented or promised.

This follows the relevant post-30 steps in current `DatabaseFactory`, with a narrowly scoped
legacy-to-current adapter. It does not instantiate `DatabaseFactory` or a core database helper,
call a notifier, write preferences, unlock keys, or use the standard restore pipeline.

## Validation

`SilenceDatabaseContract` creates an in-memory reference from the current SMSecure DDL
constants. Required columns, declared types, and primary-key positions are checked before
and after conversion. Legacy identity names are mapped explicitly; fields introduced after
schema 30 are excluded only from the source contract. Unknown identity columns are rejected
instead of silently discarded. This is a column contract, not a complete audit of every
legacy constraint, index, recipient relationship, or application invariant.

Reject views, triggers, virtual tables, mixed schemas, unsupported versions, and incompatible
column layouts before any conversion SQL. Run SQLite integrity checks before and after.
Conflicting identity recipients fail the unique constraint and roll back the transaction.

For all original columns in SMS, MMS, parts, MMS addresses, drafts, recipient preferences,
threads, and identities, compute streaming SHA-256 fingerprints before and after conversion.
The fingerprint includes row boundaries, SQLite value types, lengths, and values ordered by
`_id`; it maps the identity column rename explicitly. Values are never logged or persisted
outside the disposable copy. This verifies that stored data, including ciphertext and type
flags, was preserved; it does not authenticate or decrypt any of it.

Cancellation is checked during copy and row fingerprinting. Failure or cancellation removes
the candidate. A prepared object owns its temporary directory and is AutoCloseable. A second
prepare attempt while it exists is rejected without deleting the existing candidate. A new
prepare succeeds after cleanup. Snapshot cleanup remains the final safety net.

The additional database copy and transaction journal require extra cache space beyond the
source staging limit. I/O failures fail the operation and trigger cleanup. No additional
libraries or core database changes were needed. A regression test checks the fixed target
version against current `DatabaseFactory` so schema changes require deliberate review.

## Verification completed

- `:app:testDebugUnitTest`: 47 passed, no failures/errors/skips.
- `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, `:app:lintDebug`: passed.
- Samsung Galaxy A53 / Android 16: all 17 focused Android tests passed (7 preflight + 10
  database preparation tests).
- Real `Download/SilenceExport` selected through the system SAF picker: successful preparation
  with input schema 30, 41 SMS, 0 MMS, and 1 crypto file. The success path is reached only after
  schema conversion and preservation checks complete. The staging workspace was empty afterward.
- Before tests, backed up the installed APK and both private-storage areas, verified two
  identical reads, and compared APK signing certificates. Installed with `adb install -r`.
- After tests, every live database file was byte-identical to the fresh baseline. Crypto state
  and app preferences were unchanged. Both original Silence exports matched their earlier
  per-file hashes. Only the diagnostic log, runtime profile marker, and Android framework
  `ActivityThread.IDS` counter changed outside cache.

The SQL fixture models the legacy SMS/thread/identity/draft/recipient column contract independently
of current DDL and uses synthetic values. Its MMS/part/address tables remain minimal fixtures.
No real exported data or private backup is included in Git. All new Java files carry the requested
2026 Jimvixx GPL header.

## Changed files in this slice

New production classes under `app/src/main/java/org/jimvixx/smsecure/migration/silence/`:

- `SilenceDatabaseMigrator.java`: private-copy conversion, transaction, fingerprints, cleanup.
- `SilenceDatabaseContract.java`: current column contract without opening live helpers.
- `SilenceDatabaseMigrationInfo.java`: immutable result metadata.

Updated production files:

- `SilenceImportCoordinator.java`: runs and closes the candidate after successful preflight.
- `SilencePreflightResult.java`: exposes optional database preparation metadata.
- `app/src/main/res/values/silence_preflight_strings.xml`: clarifies the successful temporary-copy check.

Tests and documentation:

- New `app/src/androidTest/assets/silence/schema-30.sql`.
- New `SilenceDatabaseMigratorTest.java` and `SilenceTestBackup.java` in the migration androidTest package.
- Updated `SilencePreflightAnalyzerTest.java` to use the expanded shared fixture and assert preparation metadata.
- Updated `docs/silence-backup-preflight.md` and added this document.

## Remaining stages

At this checkpoint, master-secret authentication/decryption, preference mapping, crypto/session/prekey compatibility,
thread normalization, coordinated rollback/apply, and a final confirmation UI are not implemented.
API 24 runtime behavior, other SAF providers, and additional legacy versions still need coverage.
The prepared database is deliberately discarded, not retained as an importable backup.
