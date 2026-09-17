# Silence identity and source-binding validation

This stage continues the uncommitted crypto-file checks on `feature/silence-backup-import`.
No commit or push was requested for this continuation. The last commit remains `7ed270f`.
All work stays in the migration package; no live database, identity, preference, session,
prekey, recipient, or device subscription is modified.

## Identity key pairs

`SilenceIdentityVerifier` reads the already parsed snapshot of `SecureSMS-Preferences.xml`.
It recognizes the reference public/private curve25519 preference names, either unscoped or
with a canonical nonnegative decimal subscription suffix. Both entries must exist and be
strings. Malformed suffixes and incomplete pairs are rejected rather than silently skipped.

For every pair, it authenticates/decrypts the encrypted private key using the exported master
secret, requires a 32-byte scalar and a 33-byte typed public key, derives the X25519 public key
using the standard base point through the bundled Curve implementation, and compares it with
the exported public key using `MessageDigest.isEqual`.

Owned decoded, decrypted, and derived buffers are wiped in finally blocks. Java/provider
internal copies remain subject to the same best-effort memory-cleanup limitation as earlier
stages. No key material is returned, logged, displayed, or written to disk.

`SilenceIdentityInfo` exposes VERIFIED with a count, ABSENT, or REJECTED. Absence is explicitly
not treated as a verified identity. Failure is reported separately from SMS/file checks;
any bad pair rejects the identity result without partial success counts. Cancellation
propagates instead of becoming a validation failure. No full-import readiness is claimed.

## Source identifiers

`SilenceSourceBinding` is a pure parser with no Android context or live-store dependency.

- Session filenames must contain a canonical positive recipient ID and optional canonical
  nonnegative subscription ID. Overflow, extra separators, leading zeros, and empty fields
  are rejected. Missing suffix represents the legacy unscoped slot, not a target default SIM.
- For prekeys and signed prekeys, the ID inside the authenticated protobuf determines the
  filename prefix. Only the remaining decimal suffix is interpreted as the source subscription.
  This checks a real record-name disagreement without guessing where to split the number.
- A session recipient must exist exactly once with a nonempty address in the snapshot's
  `canonical_address.db`, opened read-only with a non-destructive corruption handler.

An ID decoded from the source is not assigned to an installed SIM. The checks do not yet
cross-match a file's source subscription with a verified identity slot or session key.
Historical filename collisions may already have overwritten data inside Silence; this checker
cannot reconstruct records missing from the export.

## Changes

New production classes, all under `migration/silence`:
`SilenceIdentityVerifier`, `SilenceIdentityInfo`, and `SilenceSourceBinding`.

Extended the crypto result/verifier and existing preflight activity for separate identity
reporting. Extended file validation for source recipient references and embedded prekey IDs.
Reused the bounded Base64 decoder within the package. Updated findings and UI strings.
No core stores, preferences, crypto helpers, or new libraries were changed/added.

Added `SilenceIdentityVerifierTest` (12 device tests), `SilenceSourceBindingTest` (7 JVM tests),
and four file-verifier device tests. Fixtures are synthetic. The identity tests cover multiple
scoped/unscoped pairs, missing members, mismatched pairs, invalid types/encoding/length,
a damaged MAC, malformed slots, cancellation, and unchanged source preference maps.

All new Java files carry the 2026 Jimvixx GPL header; comments are in English.

## Remaining boundaries

Signed-prekey signatures, prekey pair consistency, session identity/ratchet consistency,
source-slot cross-checks, explicit target-SIM selection, preference mapping, normalization,
and atomic apply/rollback remain separate steps. API 24 runtime and other SAF providers
remain untested. Import is still unavailable and `isReadyToImport()` remains false.

## Verification completed

- 54 JVM tests passed; debug APK/test APK builds and lint passed; `git diff --check` passed.
- 60 Android migration tests passed on Samsung Galaxy A53 / Android 16 (16 new tests in this stage).
- Real export through SAF: 3 matching identity key pairs, 41 verified SMS, 0 unchecked SMS,
  and 1 authenticated/decoded session file with a valid source-recipient reference.
- Before device tests, verified a fresh backup of the installed APK and both app-private
  storage areas, and checked that the APK signing certificates matched. Backup:
  `/home/user/Backups/SMSecure/2026-09-17T20-24-53Z-before-silence-tests`.
- After tests, all live database files, crypto state, app preference values, and both original
  Silence exports were unchanged. Only the diagnostic log, runtime profile marker, and
  Android framework IDS counter changed outside cache. Staging was empty; the app was relaunched.

The final rebuild also includes a wording-only refinement to the result findings and comments
made after the installed device-test build; the tested verification logic is unchanged.
