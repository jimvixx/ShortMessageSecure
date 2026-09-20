# Prepared replacement integrity manifest

Baseline: `a5869ac` commits the replacement-source entry UI and accepted policy.

`SilenceReplacementManifest` inventories a separately prepared directory. It records
immutable relative-path maps of SHA-256 digests and byte sizes, and can recapture
and compare that inventory before a future apply. It has no Context, database,
preference or live-storage access and does not write any files.

Required output: messages.db, canonical_address.db, current-package default
preferences and SecureSMS-Preferences. Optional record files are limited to the
current session/prekey/signed-prekey directories with canonical numeric names and
optional logical-subscription suffixes. Other files/directories, legacy-package
preferences, sidecars, temporary records, symlinks below the trusted root, empty
files and out-of-range identifiers are rejected. Limits: 20,000 files, 512 MiB total.
Hashing streams bytes and wipes the temporary read buffer.

This contract describes prepared output, not raw Silence input. Producers must
still perform schema conversion, crypto/session filename transformation, complete
preference preparation and semantic verification before capturing it. Metadata
outside this initial allowlist needs an explicit contract extension if required.
Do not copy arbitrary source files to satisfy the manifest.

## Security and remaining integration

A digest is change detection, not authentication of the source or proof of valid
SQLite/crypto content. The caller must exclusively own staging during capture,
verification and future apply; this class does not lock files or eliminate races.
No manifest is persisted or logged. Relative paths and digests must remain private
because record names may identify recipients.

The manifest does not describe cleanup of superseded live files, rollback backups,
job queues or all replacement side effects. Those require a separate transaction
plan and recovery journal. Source backup, staged output and rollback data must have
distinct ownership and lifetimes. Nothing is connected to an apply action yet.

## Verification

JVM fixtures cover immutable inventories, unchanged verification, same-length
content edits, added/removed records, required files, legacy preferences, sidecars,
unexpected files, links and oversize rejection. Debug build, unit suite, lint and
diff checks are run. No device installation or device tests are needed for this
pure file-inventory slice; no live app data was accessed.
