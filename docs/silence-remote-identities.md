# Silence remote identity records and session comparisons

The preceding session stage is committed as `3a142f8`. This stage is committed as `fbfa7e3`.
All reads target the disposable snapshot. No core identity store is instantiated and no trust,
verified flag, session, preference, or live database is changed. Import readiness remains false.

## Record authentication

`SilenceRemoteIdentityIndex` reads the legacy `identities` table with the original `key` column.
Each row must have a positive integer recipient ID, a unique nonempty canonical address
reference, a typed 33-byte Curve25519 public key, and a valid 20-byte HMAC-SHA1.

The MAC covers decimal recipient ID concatenated with the exact stored Base64 key string,
matching Silence v0.16.12-unstable IdentityDatabase/MasterCipher. Whitespace in that stored
string is authenticated verbatim; it is not normalized before MAC verification. UTF-8 matches
the reference's Android encoding for this ASCII content. Comparison uses MessageDigest.isEqual.

A package-private method in `SilenceLegacyCipher` verifies the MAC without the legacy helper's
logging. Temporary owned buffers are wiped. The index retains public keys only, never private
key material, and is discarded after producing aggregate results. No identities or MACs are
printed, displayed, persisted in reports, or returned in the public result model.

Duplicate recipients, missing address references, invalid key/MAC shapes, malformed encoding,
failed MACs, and database errors reject the complete index. Partial rows are discarded and
session comparisons are disabled. Input is capped at 100000 identities, 1024 characters per
encoded key, and 128 characters per encoded MAC. Cancellation propagates separately.

## Session comparison

After a session state passes the existing structural/local-identity checks, its remote public
key (if present) is compared with the authenticated row for that source recipient. Current
states produce separate matched, missing, and different counts. Archived states have separate
matched/unmatched counts; historical key changes can legitimately differ from the latest row.
Pending exchanges without a remote key do not contribute a comparison.

`SilenceRemoteIdentityInfo` is immutable and contains only status and counts. Results attach
to the file result only after all files pass, so a later invalid session cannot expose partial
comparison totals as a completed scan. A rejected identity index remains a separate failure
from successfully readable crypto files. A missing or different remote key is reported, never
silently trusted, rewritten, or substituted. Archived differences are not current mismatches.

MAC validity and consistency with a saved row do not establish a human peer's identity or
current peer ratchet state. No trust promotion, message exchange, or network request occurs.
The preview database's default unknown verification status is preserved.

## Changes and tests

- Added `SilenceRemoteIdentityIndex` and immutable `SilenceRemoteIdentityInfo` under migration/silence.
- Added a scoped legacy identity-MAC verifier; integrated comparison into validated session traversal.
- Extended the file result and preflight UI to report aggregate source integrity and comparisons.
- Added `SilenceRemoteIdentityVerifierTest` with 15 device tests and synthetic MAC/key data.
- Updated the preceding stage document and added this document.
- All new Java files carry the requested 2026 Jimvixx GPL header; comments are in English.

Tests cover valid rows, damaged/short MACs, changed recipients and keys, exact-string MAC
semantics, malformed key shapes, orphan/duplicate recipients, separate current/archive counts,
failed-index suppression, an empty identities table, actual session traversal, cancellation,
and coordinator/source preservation without import readiness.

Target-SIM mapping, preference migration, thread normalization, user confirmation, and atomic
apply/rollback remain separate work. API 24 runtime and other SAF providers remain untested.

## Validation completed

- 54 JVM tests passed; debug APK/test APK builds, lint, and whitespace checks passed.
- 113 migration Android tests passed on Samsung Galaxy A53 / Android 16, including 15 new tests.
- Real SAF export retained successful checks for 41 SMS, 3 local identity pairs, and 1 session.
  It contains zero remote identity rows and produced zero remote-key comparisons. Positive
  MAC/matching and mismatch cases were therefore validated with synthetic fixtures only.
- Before device testing, verified backups of the installed APK and both private-storage areas
  using identical repeated reads and matching APK signing certificates. Backup:
  `/home/user/Backups/SMSecure/2026-09-17T20-48-48Z-before-silence-tests`.
- Live databases, crypto state, all preferences, and both original Silence exports were unchanged.
  Only the diagnostic log and runtime profile marker changed outside cache. Staging was empty;
  SMSecure was relaunched after verification.

The next safe boundary is an explicit source-to-target subscription and preference plan,
without applying it to live data. Missing/different peer identities must remain visible in
any future readiness/confirmation logic; MAC validity must never promote trust automatically.

The following draft-planning stage is documented in [silence-migration-plan.md](silence-migration-plan.md).
