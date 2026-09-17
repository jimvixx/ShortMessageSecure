# Silence backup preflight: first safe slice

## Scope and entry point

Branch: `feature/silence-backup-import`. The debug build was installed
with `adb install -r` after verified backups and matching signing certificates.
Open Import / Export → Import → **Check Silence backup**, then select the export directory itself.
This is an analysis-only feature. The existing SMSecure ZIP restore path is unchanged.

The result is `STRUCTURALLY_VALID` or `REJECTED`. Even a structurally valid result always
returns `isReadyToImport() == false`: no key authentication, decryption, message conversion,
preferences migration, thread normalization, live writes, restore marker, or apply operation exists.

## References checked

- Silence [`v0.16.12-unstable`](https://github.com/SilenceIM/Silence/tree/v0.16.12-unstable),
  commit `a71fea363ab6f58fa7ad4b4774cb149356ff2fc3`.
- Its `EncryptedBackupExporter`, `DatabaseFactory`, database table definitions,
  `MasterSecretUtil`, `SilencePreKeyStore`, and `SilenceSessionStore`.
- Current SMSecure `EncryptedBackupExporter`, `DatabaseFactory` (schema 35),
  `ImportExportFragment`, activity lifecycle, and build configuration (minSdk 24).

The legacy exporter copies an uncompressed directory, including incidental runtime files.
It has no authenticated manifest or reliable application-version field. The analyzer reports
schema 30, not a proven originating app version. Schema 30 / canonical-address schema 1 are
currently the only accepted versions.

## Architecture and boundaries

All migration Java classes live under `org.jimvixx.smsecure.migration.silence`.
The source abstraction exposes read-only directory listings and streams. The SAF adapter
is separate from staging and analysis. The coordinator serializes snapshots and cleanup.
Only generic Base64 decoding and the existing passphrase-protected activity base are reused;
no core database, crypto, or preference class was changed.

UI composition uses a package-restricted internal intent action registered on a non-exported
activity. Existing Java code has no import or class reference into the migration package.
The manifest and Import / Export screen are the only integration points.

The ViewModel retains a running job across recreation and prevents duplicate analyses.
Closing the screen interrupts the worker; it checks interruption during traversal and copying.
Process recreation does not automatically resume a persisted URI or an import operation.
The picker requests a transient read grant only; no grant is persisted.

Snapshots live in private `cache/silence-preflight/silence-preflight-<UUID>` directories,
separate from `restore_staging`. Only `files`, `databases`, and `shared_prefs` are copied;
incidental root entries are ignored. Every analysis uses its own snapshot and deletes it
on success/failure. Leftovers after process death are removed on the next analysis.
Ordinary cache eviction can also remove them. Cleanup failures fail the operation.

## Validation and limits

- Required directories, nonempty messages/canonical-address databases, and both legacy preference files.
- Mixed SMSecure/Silence preferences, traversal/control-character names, duplicate names,
  repeated document IDs/cycles, unreadable input, unsupported versions, and nonempty WAL/journal files are rejected.
- Limits: 512 MiB actually copied, 20,000 entries, 16 nested levels, 255 characters per name,
  and 1 MiB per parsed preference XML. No reliance on provider-reported file lengths.
- Snapshot databases open read-only without the destructive default SQLite corruption handler.
  Integrity checks, ordinary required tables, minimum required columns, SMS thread references,
  and SMS/MMS counts are inspected. This is not a complete semantic validation of every legacy field.
- Preference XML rejects DTDs, external entities, duplicate keys, invalid root/nesting,
  invalid required types, missing initialized master-secret state, and invalid secret field lengths.
  Base64 decoding explicitly disables automatic gzip decompression.
- Counts existing session/prekey/signed-prekey files; rejects empty records and unexpected nested directories.
  Their presence does not establish authenticity, completeness, or decryptability.
- No backup contents, secret values, URI paths, or provider exception text are shown in the UI or logged by this feature.
- The result explicitly states that import is unavailable and SMSecure does not support MMS/attachments.

A backup can change while a provider is being read. The resulting private copy is validated,
but the legacy format cannot prove a coherent, authenticated export. Rejecting sidecars is
intentionally conservative; the implementation never checkpoints or repairs the source.

## Tests and fixtures

`SilenceBackupTest`: JVM tests for staging, cleanup, cancellation, name/path validation,
duplicate entries, nesting/byte/entry limits, required/mixed layouts, journal rejection,
legacy preferences, hostile XML, missing secret state, and immutable results.

`SilencePreflightAnalyzerTest`: Android tests for real SQLite parsing, source digest preservation,
counts, unsupported versions, missing columns, views masquerading as tables, orphan SMS,
corruption, cleanup, and hostile XML on Android. It creates only synthetic databases in the
dedicated temporary subdirectories of the target application cache, never uses SMSecure database helpers, and never opens live data.

The XML fixtures use zero-filled dummy ciphertext/salts. They are intentionally **not decryptable
backups**. Android SQLite fixtures contain only the minimum preflight column contract and synthetic
message values; they are not full application exports. The same XML files are present in JVM
resources and instrumentation assets so neither production assets nor build dependencies change.

## Checks completed

- `:app:testDebugUnitTest`: 47 tests, including 13 new Silence tests; zero failures/errors/skips.
- `:app:assembleDebug`: passed.
- `:app:assembleDebugAndroidTest`: passed. All 7 analyzer tests also passed on Samsung Galaxy A53 / Android 16.
- `:app:lintDebug`: passed, zero errors (238 warnings and 1 hint in the complete application report).
- `git diff --check`: passed. New-file whitespace was also checked separately.
- First-slice implementation started from main commit `fd06c82`.

## Device validation (2026-09-17)

Before device testing, saved and verified the installed APK plus credential-protected and
device-protected private data. Two reads of each initial private-data archive were identical.
Backups are stored outside Git with restricted filesystem permissions. Both Silence exports
were also archived and hashed before analysis. The original baseline backup is retained.

The first instrumentation run exposed a fixture setup bug: the target process cannot write
the test APK package cache. Changed the test to create UUID-named temporary directories inside
the target application cache. These directories are removed in teardown. No live database
helper or application preference API is used by the tests.

After the correction, all 7 `SilencePreflightAnalyzerTest` tests passed on a Samsung Galaxy A53
with Android 16. Launched the new screen through the normal Import / Export menu, selected the
real `Download/SilenceExport` folder using the system document picker, and granted access.
The result was structurally valid: database schema 30, 41 SMS, 0 MMS, and 1 crypto record file.
The two available source export directories were byte-identical. The result survived rotation
into landscape; original rotation settings were restored. The private preflight workspace was empty
after analysis.

After the tests, compared snapshots and original exports:

- Every file under the live `databases/` directory remained byte-identical to the initial backup.
- Crypto files and `SecureSMS-Preferences.xml` remained unchanged.
- Both source Silence exports remained unchanged by per-file SHA-256 comparison.
- Normal application startup/update changed only the diagnostic log, `files/profileInstalled`,
  and the `last_version_code` preference outside cache. These are not import writes.

Actual migration remains unimplemented. Android API 24, other devices/providers, authenticated
crypto/decryption, and all legacy-format variants remain unverified. New UI strings currently
use the English fallback. No real backup data has been added to the repository or test fixtures.

## Exact file inventory

Modified existing files:

- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/org/jimvixx/smsecure/ImportExportFragment.java`
- `app/src/main/res/layout/import_export_fragment.xml`

New production files, under `app/src/main/java/org/jimvixx/smsecure/migration/silence/`:

- `SilenceBackupSource.java`
- `SilenceDirectoryBackupSource.java`
- `SilenceBackupStager.java`
- `SilenceBackupDetector.java`
- `SilencePreferencesReader.java`
- `SilencePreflightAnalyzer.java`
- `SilenceImportCoordinator.java`
- `SilenceBackupInfo.java`
- `SilencePreflightResult.java`
- `SilenceImportPhase.java`
- `SilencePreflightViewModel.java`
- `SilencePreflightActivity.java`

Other new files:

- `app/src/main/res/layout/silence_preflight.xml`
- `app/src/main/res/values/silence_preflight_strings.xml`
- `app/src/test/java/org/jimvixx/smsecure/migration/silence/SilenceBackupTest.java`
- `app/src/test/resources/silence/legacy-default.xml`
- `app/src/test/resources/silence/legacy-secret.xml`
- `app/src/androidTest/java/org/jimvixx/smsecure/migration/silence/SilencePreflightAnalyzerTest.java`
- `app/src/androidTest/assets/silence/legacy-default.xml`
- `app/src/androidTest/assets/silence/legacy-secret.xml`
- `docs/silence-backup-preflight.md`
