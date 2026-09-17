# Silence prekey consistency and signature checks

The preceding file/identity checks are committed as `c3632ef`. This slice is committed as `5114e97`.
It verifies source records in staging only. No core store or preferences class was changed;
no live keys, sessions, database rows, or SIM mappings are modified.

## Checks

`SilenceKeyPairVerifier` contains the existing X25519 public/private consistency check shared
by identities and prekeys. It derives a public key from the private scalar through the
bundled Curve implementation and compares it with the stored public key. Identity validation
runs first and retains its separate result; a failed identity set prevents prekey approval.

`SilencePreKeyVerifier` uses the authenticated record ID and legacy filename suffix to select
the exact source identity preference. Unscoped records require the unscoped identity. Scoped
records require that exact suffix, with no fallback to another subscription or current SIM.
Unsigned prekeys also require a verified identity for their declared source slot. They have
no identity signature, so slot availability does not cryptographically authenticate ownership.

Signed prekeys additionally require a valid 64-byte signature over the serialized 33-byte
prekey public key. Verification uses the bundled libsignal Curve verifier and the source
identity public key, matching `KeyHelper.generateSignedPreKey`'s signed message format.
No new cryptographic primitive, provider, or library dependency was introduced.

The prekey verifier owns decoded input arrays and wipes public/private/signature buffers on
success, rejection, and cancellation. The shared pair checker wipes derived comparison
buffers; protobuf and provider-owned copies remain subject to best-effort memory cleanup.
No plaintext/key material is stored, displayed, logged, or returned in a result.

The file result remains all-or-nothing. The UI states which checks were performed where
prekeys are present; it does not claim that absent records were tested. Session usability
and target SIM mapping remain unverified and import readiness remains false.

## Changes

- Added `SilenceKeyPairVerifier` and `SilencePreKeyVerifier` inside `migration/silence`.
- Reused the pair check from `SilenceIdentityVerifier`.
- Updated `SilenceCryptoVerifier` to validate identities once before file checks, and pass the
  same snapshot preferences and identity result to the file verifier.
- Extended file parsing to check prekey pairs, exact identity slots, and signed-prekey signatures.
- Updated preflight findings and UI wording.
- Replaced shape-only prekey fixtures with generated valid synthetic pairs/signatures.
- Added `SilencePreKeyVerifierTest` (12 tests) and five file-verifier integration tests.
- All new Java files have the requested 2026 Jimvixx GPL header; comments are in English.

Coverage includes valid scoped/unscoped records, mismatched public/private pairs despite a
valid signature, another signing identity, corrupt/short signatures, missing identity slots,
no scoped/unscoped fallback, rejected/absent identity sets, cancellation, buffer wiping,
unchanged preference maps, corrupted authenticated records, and coordinator cleanup.

## Remaining boundaries

Session local/remote identity consistency, ratchet-state usability, remote identity trust,
explicit target-SIM mapping, preference migration, normalization, and apply/rollback remain
separate work. No validity claim is made about those boundaries. Historical backups with
missing source identity slots fail the prekey check rather than being repaired or guessed.

## Validation completed

- 54 JVM tests passed; debug APK, Android test APK, lint, and whitespace checks passed.
- All 77 migration tests passed on Samsung Galaxy A53 / Android 16, including 17 new tests.
- Real SAF export still passes: 41 SMS, 3 identity pairs, 1 session, and no prekey records.
  Prekey/signature paths were therefore exercised with synthetic records, not real prekeys.
- Before device testing, backed up the installed APK and both private storage areas, verified
  identical repeated reads, and checked matching APK signing certificates. Backup:
  `/home/user/Backups/SMSecure/2026-09-17T20-33-08Z-before-silence-tests`.
- After testing, live databases, crypto state, all preferences, and both original Silence
  exports were unchanged. Only the diagnostic log and runtime profile marker changed outside
  cache. Staging was empty and SMSecure was relaunched after verification.
- API 24 runtime and other SAF providers remain untested. The next implementation boundary
  is session local/remote identity and ratchet-state validation, still without live apply.

The following stage is documented in [silence-session-validation.md](silence-session-validation.md).
