# Silence session-state validation

The preceding prekey/signature stage is committed as `5114e97`. This session stage is left
uncommitted for review. It adds one isolated production class, `SilenceSessionVerifier`, and
integrates it into the existing file verifier. No live protocol store is constructed and no
ratchet is advanced, repaired, normalized, or written back. Import readiness remains false.

## Supported checks

- Legacy envelope version 1 contains one SessionStructure; version 2 contains a current state
  and archived states. Every nonempty state is checked. An empty current placeholder is
  accepted when another state is meaningful; an entirely empty record is rejected.
- Protocol versions 2 and 3 are supported. An absent version follows SessionState's legacy
  default of 2. Other explicit values are rejected.
- Local identity keys must match the exact verified source preference slot derived from the
  filename. There is no fallback to another identity, default SIM, or target-device slot.
- Active states require typed local/remote identity keys, a 32-byte root key, and a sender
  chain with matching public/private ratchet keys and a 32-byte chain key with a supported index.
- Receiver chains require public ratchet keys and valid chain-key shapes. Duplicate ratchets,
  unexpected private receiver keys, and unexpected sender message caches are rejected.
- Cached message keys require unique supported indices, 32-byte cipher/MAC keys, and a
  16-byte IV. Key lengths match the bundled SessionState/MessageKeys representation.
- Pending key exchange is supported without an active sender chain. Its sequence and three
  local key pairs (identity, base, ratchet) are checked; identity must match the source slot.
- Pending prekey metadata requires an active chain, a typed base key, and supported IDs.
- Limits follow the current bundled library: 40 archived states, 5 receiver chains, and 2000
  cached message keys per chain. Negative Java indices (including uint32 overflow) are rejected.
- Cancellation is checked before parsing and during state/chain/cache traversal.

Checks are conservative: an archived state bound to a different historical local identity is
rejected instead of silently rebound. Unknown or incomplete states are not repaired. The
source export is always preserved, so unsupported historical cases can be investigated later.

Decoded private buffers used for key-pair checks are wiped in finally blocks. The encrypted
file reader still wipes its owned plaintext/ciphertext buffers. Immutable protobuf/provider
copies cannot be guaranteed erased; no stronger memory-erasure claim is made.

## What success does not establish

These checks validate structure and selected cryptographic relationships. They do not prove
that the remote peer still holds matching ratchet state, authenticate the remote identity
against the identities database, confer trust, verify every root/chain relationship, or
establish successful end-to-end messaging. Target-SIM selection remains explicit future work.
The UI continues to report these limits. No session test sends a message or contacts a peer.

## Changed files

- New `SilenceSessionVerifier.java` under `migration/silence`.
- Updated `SilenceCryptoFileVerifier`, preflight findings, and status strings.
- New synthetic `SilenceSessionTestData.java` and `SilenceSessionVerifierTest.java`.
- Four additional file-verifier tests; existing session fixtures now contain structurally
  valid key material instead of a version-only protobuf.
- Updated the preceding stage document and added this document.

The 17 focused session tests cover protocol/envelope versions, active/pending/archived states,
empty records, missing/wrong identities, root/chain/key-pair failures, duplicate and oversized
chains/caches, malformed pending records, cancellation, and unchanged serialized input.
The integration cases cover authenticated invalid states, wrong source slots, pending-only
sessions, and invalid archives even when the current state passes.

All new Java files use the 2026 Jimvixx GPL header; comments are in English. Dependencies remain
one-way into the existing bundled libsignal schema and key utilities; no core classes changed.

## Validation completed

- 54 JVM tests passed; debug APK/test APK builds, lint, and whitespace checks passed.
- 98 migration Android tests passed on Samsung Galaxy A53 / Android 16, including 21 new tests.
- Real export selected through SAF: 41 verified SMS, 3 matching identity pairs, and 1 session
  passing the new state and local-identity checks. No prekeys were present in that export.
- Before device testing, verified a fresh backup of the installed APK and both private-storage
  areas using identical repeated reads; APK signing certificates matched. Backup:
  `/home/user/Backups/SMSecure/2026-09-17T20-40-42Z-before-silence-tests`.
- Live databases, crypto state, all preferences, and both original Silence exports remained
  unchanged. Only the diagnostic log and runtime profile marker changed outside cache.
  Staging was empty and SMSecure was relaunched after verification.

API 24 runtime and other SAF providers remain untested. The next boundary is authenticating
source identities database records and comparing remote session identities without promoting
trust or modifying live state. SIM mapping, preference migration, and apply/rollback remain later work.
