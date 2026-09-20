# Global target database state

Baseline: `3182490` on `feature/silence-backup-import`.

The target database guard previously inspected SMS subscription references and
recipient default subscriptions. This could leave candidates available when the
target retained a conversation or remote identity but no scoped SMS reference.
Neither `thread` nor `identities` belongs to a single SIM binding.

The existing single SQL statement now includes an EXISTS branch for these tables.
Any row in either blocks every candidate. The analyzer does not read conversation
snippets, recipient IDs or identity key values. It requires both tables to exist;
missing tables reject the inventory rather than look empty. The UNION still reads
all conflict sources in one SQLite statement snapshot and keeps bounded output.
The original 1,024-reference-per-scoped-table limits remain enforced.

This is a conservative refusal rule, not evidence that source and target records
actually conflict. Empty/stale conversation rows also block candidates. Determining
whether existing state can be preserved or merged needs a separate recipient and
identity reconciliation policy. No deletion, cleanup, replacement or import is
implemented. Other target tables and apply-time revalidation remain pending.

## Verification

Three Android fixture tests were added: conversation without SMS, remote identity
without SMS/local keys, and missing global table. Existing synthetic target schemas
now include both global tables. The actual query extracted from Java passed five
local SQLite cases: empty, scoped-only, conversation-only, identity-only and both.

Android build/test APK, JVM suite, lint and diff checks are run for this slice.
No device is connected, so the new Android tests have not been executed and the
updated UI has not been device-tested. No device data was accessed or modified.
