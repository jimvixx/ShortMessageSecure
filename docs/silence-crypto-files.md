# Silence crypto-file readability: fourth isolated slice

The preceding master-secret/SMS slice is committed as `7ed270f`. This slice is committed together with identity/source-binding checks as `c3632ef`. No push was performed.

## Scope and architecture

After the exported master secret and symmetric SMS bodies are checked, the same short-lived
legacy cipher authenticates encrypted records in `files/sessions-v2`, `files/prekeys`, and
`files/signed_prekeys`. Only the disposable snapshot is read. No live store is instantiated,
no recipient lookup is performed, and no preference, identity, session, or prekey is installed.

`SilenceCryptoFileVerifier` owns strict file-envelope parsing. `SilenceLegacyCipher` now exposes
a package-private byte-array decrypt method whose caller must wipe the plaintext. The file
verifier wipes its owned ciphertext and plaintext buffers in a finally block. Protobuf parsing
creates immutable library-owned copies which cannot be reliably wiped; this is a best-effort
memory cleanup, not a claim of guaranteed erasure.

`SilenceCryptoFileInfo` is immutable and exposes either READABLE with counts or REJECTED.
It is attached as optional metadata to `SilenceCryptoVerificationInfo`. A file failure does
not erase the successful master-secret/SMS result, and the UI reports both outcomes separately.
If unlocking or SMS verification fails, file verification is not run. No partial success
counts are reported when any file fails. Import readiness remains false in every case.

## Checks and limits

- Read big-endian 32-bit version and ciphertext length exactly; reject truncated headers.
- Sessions accept envelope versions 1 and 2; prekey files accept version 1 only.
- Reject ciphertext lengths below 52 bytes or above 1 MiB before allocating a buffer.
- Require exact file length, full reads, and no trailing bytes.
- Authenticate HMAC-SHA1 before AES-CBC decryption and padding validation.
- Parse the original session-state protobuf for version 1 and record protobuf for version 2.
  Version 2 requires a current or archived state. Empty plaintext is rejected.
- Parse prekey protobufs with the current bundled library schema and check required fields,
  public/private key sizes, public key type byte, and signed-prekey signature size.
- Reject unexpected filenames rather than silently ignoring files; check cancellation between
  files and before decryption. Missing directories produce zero readable records.

READABLE means authenticated envelope plus protobuf readability and the stated field checks.
It does **not** establish a usable session, consistent key pair, valid signed-prekey signature,
identity trust, or correct recipient/SIM mapping. Test fixtures intentionally use synthetic
protobuf fields and do not imply those stronger properties. The app communicates this limit.

## Important filename difference

Reference: Silence `v0.16.12-unstable` at
`a71fea363ab6f58fa7ad4b4774cb149356ff2fc3`, `SilencePreKeyStore` and `SilenceSessionStore`.

Silence constructs a prekey filename by concatenating the decimal key ID and subscription ID
without a delimiter. Current `SMSecurePreKeyStore` uses a dot. Sessions use the recipient ID
and optional dot-separated subscription ID. Lexical filename checks deliberately do not split,
rename, resolve, or assign anything. A future mapping stage must inspect record IDs and source
preferences and explicitly resolve target subscriptions before any apply operation.

No core database, crypto-store, or preferences class was changed. The only added dependency
is from the migration package into the existing bundled libsignal protobuf schema. No new
library dependencies were introduced.

## Changes

- Added `SilenceCryptoFileVerifier.java` and `SilenceCryptoFileInfo.java` under the migration package.
- Extended `SilenceLegacyCipher`, `SilenceCryptoVerifier`, and `SilenceCryptoVerificationInfo`.
- Updated preflight result findings, the existing preflight activity, and its string resources.
- Added `SilenceCryptoFileVerifierTest.java` with 13 device tests using generated synthetic fixtures.
- Updated the previous slice document and added this document.
- All new Java files use the 2026 Jimvixx GPL header; comments are in English.

## Validation

- 47 JVM unit tests passed; debug build, test APK build, lint, and whitespace checks passed.
- 44 Android migration tests passed on Samsung Galaxy A53 / Android 16, including 13 new tests.
- Covered both session formats, both prekey types, absent directories, unknown versions/files,
  damaged MAC, oversized and negative lengths, truncation/trailing bytes, malformed protobuf,
  missing fields/signature, bad key length, cancellation, source-byte preservation, and
  separate reporting of file failure after successful SMS verification.
- Before device testing, backed up the installed APK and both private storage areas, verified
  identical repeated reads, and confirmed matching APK signing certificates. Backup:
  `/home/user/Backups/SMSecure/2026-09-17T20-16-06Z-before-silence-tests`.

- Real export selected through SAF: 41 SMS checked, 0 unchecked, and 1 authenticated/decoded
  session file; no prekeys or signed prekeys present in that export.
- All live database files, crypto state, app settings, and both original Silence exports
  remained unchanged. Only the diagnostic log, runtime profile marker, and Android framework
  IDS counter changed outside cache. Staging was empty afterward and the app was relaunched.

API 24 runtime, other document providers, genuine historical version-1 sessions, protocol/key
consistency, signatures, identity and subscription mapping, and apply/rollback remain outside
this slice. Synthetic compatibility tests do not replace a corpus of historical real sessions.

The following identity/source-binding stage is described in
[silence-identity-validation.md](silence-identity-validation.md).
