# Existing target crypto-file guard

Baseline commit: `f26a6c6` (existing identity-key guard).

The target reader now inspects directory entries in `sessions-v2`, `prekeys` and
`signed_prekeys` without opening record contents or calling core store helpers.
The core helpers create directories; the isolated guard does not create, repair,
rename or delete anything. Canonicalizing the trusted files root permits Android
storage aliases; linked or unexpected entries below that root are rejected.

A current record name `<record>.<appSubscription>` blocks that logical target.
Temporary `.tmp` records also block it. Legacy unscoped names, unknown metadata,
malformed names or overflowing identifiers conservatively block every candidate.
Unexpected entry types, unreadable directories and inventories above 20,000 entries
make target inventory unavailable. No record content or filenames are exposed in UI.

The existing blocked count now includes the union of identity and crypto-file
occupancy. Refresh removes blocked options and invalidates earlier draft decisions.
Matching source/target crypto material is not accepted as permission to overwrite.

## Verification

JVM fixtures cover absent directories with no creation, session/prekey ownership,
temporary files, unrelated slots, unscoped/malformed/overflowing names, unexpected
subdirectories and files in place of directories. Adapter tests continue to verify
presence-only identity checks. Debug build, JVM tests, lint and diff checks are run.
No device tests or installations are performed for this slice.

## Remaining boundaries

This is a conservative presence check, not verification of target crypto validity.
Messages, recipient preferences, database state and other files still need their
own conflict analysis. Concurrent changes require revalidation before any future
apply; this snapshot is not a lock or an authorization to import. No actual import,
merge, overwrite or cleanup is implemented.
