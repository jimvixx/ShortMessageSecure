# Silence subscription draft decisions

The migration-plan baseline is committed as `cd3dde0`.

This slice extends only the immutable subscription plan. A caller can explicitly
assign a source to an available SMSecure logical subscription or defer the source.
Deferred sources retain all inventory origins and remain unresolved: they do not
count toward complete assignments and are not deleted, merged, or given a new ID.
The two decisions are mutually exclusive. Replacing a draft revalidates target
availability and uniqueness; input collections are copied and outputs immutable.

`withAssignments` replaces the entire decision set and clears previous deferrals.
`withDecisions` permits a partial draft. Neither method performs any I/O.

## Integration boundary

Target discovery and the selection UI are still pending. The existing
`SubscriptionInfoCompat` constructor calls `findAppId`, which writes preferences
and may allocate an app subscription ID. It cannot be used for read-only preflight.
A target adapter needs independently verified logical IDs before UI integration.
Android device subscription IDs must not be substituted for app subscription IDs.
No historical-slot allocation or actual import is implemented.

## Verification

JVM regression cases cover mixed assigned/deferred sources, the legacy unscoped
source, conflicting and unknown decisions, defensive copying, revision of a draft,
sorted input sets, and null source rejection. Debug build and lint are checked.
No device tests or installation were performed for this pure model change; device
SMSecure data and the original exports were not accessed.
