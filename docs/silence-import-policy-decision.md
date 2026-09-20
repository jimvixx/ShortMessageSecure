# Next import policy decision

Baseline: `7251516` commits global target-state blocking.

The importer still performs analysis only. Its target guards conservatively reject
occupied state; they do not decide whether any data may be deleted or merged.
The next implementation phase needs a product choice:

- Empty-target import: retain refusal of existing state and define which app-created
  settings/identity state counts as empty. This still needs handling of historical
  source bindings and safe staging/apply with rollback.
- Full replacement: prepare a complete replacement set, require verified recovery
  material and explicit user confirmation, and coordinate workers, databases,
  preferences and keys through apply and interruption recovery.
- Merge: design recipient/thread/record ID remapping, duplicate detection,
  identity/session conflict resolution and preference precedence before writes.

## Accepted policy (2026-09-21)

The user selected full replacement without merging histories, with an empty app
as the primary path. Provide a source choice where system SMS import is currently
offered after initial setup and preserve the Import / Export entry. Existing data
requires a verified backup and explicit final replacement confirmation. The user
authorized implementation of this workflow, not immediate replacement of device data.

Existing occupancy checks become replacement-impact information in the eventual
workflow. They must not be bypassed to enable apply before complete staging,
verification, backup, coordinated commit and rollback/recovery are implemented.
The exact settings replacement set must be defined before final warning copy is
written; the current preference allowlist alone is not a full replacement plan.

## Independent validation improvement

Target database fixture tables now use the current core CREATE_TABLE definitions
for SMS, recipient preferences, threads and identities. Test inserts name their
columns explicitly. No core helper is instantiated and no live database is opened.
A further WAL fixture verifies global identity state is invisible before commit
and blocks candidates after commit. Existing per-table and global conflict cases
remain in place.

The updated fixtures passed as part of 135 Android migration tests on A53 on
2026-09-21. See silence-replacement-entry.md for backup and verification details.
