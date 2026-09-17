# Silence crypto verification: third isolated slice

## Boundary

This uncommitted slice extends the Import → Check Silence backup entry. It authenticates and
unlocks the exported master secret, then authenticates and decrypts symmetric SMS bodies from
the read-only staging snapshot. It returns counts only. Plaintext is never displayed, logged,
written to disk, or returned to the caller. No live database, preferences, key cache, sessions,
or prekeys are changed. `isReadyToImport()` remains false; there is no apply action.

The reference is Silence `v0.16.12-unstable`, commit
`a71fea363ab6f58fa7ad4b4774cb149356ff2fc3`. Its legacy PBE algorithm, two salts,
iteration fallback (100), master-secret HMAC-SHA1, and AES-CBC SMS format are handled entirely
inside the migration package. Iteration counts above the reference generator limit of 100000
are rejected before derivation. No core crypto/database/preferences classes were changed.

## New and changed code

- `SilenceLegacyCipher`: legacy master-secret authentication/unlock and local SMS MAC/padding
  verification; bounded Base64 parsing without implicit gzip decoding; owned key-buffer cleanup.
- `SilenceCryptoVerifier`: read-only snapshot traversal, explicit unchecked counts for plain,
  asymmetric, empty, or null bodies, cancellation checks, rejection of conflicting type flags.
- `SilenceCryptoVerificationInfo`: immutable password-required, verified, or rejected metadata.
- Coordinator and preflight result: include verification metadata before disposing all copies.
- Activity, ViewModel, layout, and strings: transient password entry and retry using the selected
  SAF URI. Each retry stages and validates a fresh copy. Wrong passwords and damaged ciphertext
  produce the same non-sensitive failure message.
- `SilenceCryptoVerifierTest`: 14 Android regression tests and three synthetic crypto fixtures.

The password input is not saved in instance state, preferences, or the result. Autofill and
suggestions are disabled; the activity reapplies FLAG_SECURE after the base activity resumes.
The field clears on submission, changing source, and destruction. The ViewModel owns submitted
character arrays and wipes them on completion, cancellation, or rejection. Decrypted byte
buffers and owned keys are wiped after use; Java/JCA/provider internal copies cannot be
reliably erased and no stronger memory-erasure guarantee is claimed.

## Fixture provenance and tests

Fixtures contain synthetic data only. Golden master-secret and SMS vectors were generated
independently with OpenSSL PKCS12KDF/AES and Python HMAC, not with the production verifier.
The disabled-password vector uses the reference literal and omitted iteration setting;
the protected vector uses `fixture-password` and 10000 iterations. Tests cover both vectors,
missing and wrong passwords, damaged master/SMS MACs, valid MAC with invalid padding,
malformed encoding, unchecked records, conflicting flags, excessive KDF work, cancellation,
owned-key clearing, and preservation of snapshot bytes.

- 47 JVM tests passed.
- Debug APK, Android test APK, and lint checks passed.
- All 31 migration Android tests passed on Samsung Galaxy A53 / Android 16.
- Real SAF export: master secret verified and all 41 symmetric SMS bodies verified; 0 unchecked.
- Synthetic protected export through SAF: password prompt, wrong-password rejection, successful
  retry with 1 verified SMS and 0 unchecked, and hidden/cleared password field after success.
  The active activity window had FLAG_SECURE enabled. The synthetic device folder was removed.
- Installed APK and both app-private storage areas were backed up and verified before device
  testing; installation preserved data. Latest verified backup:
  `/home/user/Backups/SMSecure/2026-09-17T20-06-31Z-before-silence-tests`.
- Compared against the initial third-slice baseline: all live database files, crypto state,
  and app preference values were unchanged. Both original Silence exports retained identical
  per-file hashes. Only the diagnostic log, runtime profile marker, and Android framework
  `ActivityThread.IDSCount` changed outside cache. The staging workspace was empty and the
  app was relaunched after verification.

## Remaining work

This does not verify sessions, prekeys, asymmetric SMS, drafts, thread snippets, MMS, or
attachments. A verified count does not establish full import readiness. Preference mapping,
full crypto-state compatibility, normalization, atomic apply/rollback, and final import
confirmation remain separate work. API 24 runtime coverage and other document providers
remain untested. The additional verification code is left uncommitted for review.
