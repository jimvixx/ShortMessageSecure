# Target conflict explanations

Baseline commit: `b355d65` (database conflict integration).

Target inventory now retains immutable per-logical-binding sets of three reasons:
identity keys, session/prekey files, and database references. The reader evaluates
all three against the original candidate set before filtering, so earlier removal
does not hide later reasons. Unknown/noncandidate IDs do not create blocked entries.
The aggregate occupied count remains unique bindings, not the sum of reasons.

The preflight screen displays counts per reason and explains overlap. It exposes
no key values, message contents, recipient identifiers or file names. Reason counts
do not prove that existing data differs from the source or authorize overwriting.
Review invalidation compares the full conflict map; changed reasons clear decisions
even when available choices and the total blocked count remain the same.

Tests cover overlapping reasons, unchanged original snapshots, deep immutability,
idempotent repeated reasons, defensive input handling and draft invalidation on a
reason-only change. JVM suite, debug build, lint and diff checks validate this slice.
No device installation or device tests were performed for this model/UI-text change.

No block rules were relaxed. Safe merge/replacement policy, remaining database
relationships, cross-subsystem revalidation and actual apply/rollback remain pending.
