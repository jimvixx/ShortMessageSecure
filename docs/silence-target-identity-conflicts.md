# Existing target identity guard

Baseline: `796465d` commits the draft subscription review UI.

Before publishing target candidates, the isolated reader checks presence of the
public and private identity preference entries for each candidate logical app ID.
It uses the existing generic IdentityKeyUtil key-name helpers and
MasterSecretUtil preference filename. It never reads key values, decrypts them,
generates keys, repairs incomplete pairs or writes preferences.

Either entry blocks the candidate, including empty, malformed or partial stored
material. This conservative rule also blocks matching identities: equality has
not been verified and cannot authorize replacement. The immutable inventory keeps
an occupied count separate from missing/ambiguous mappings. The screen reports
that count and explicitly says that other data conflicts remain unchecked.

On refresh, a newly blocked candidate invalidates the draft decisions. Occupied
candidates cannot be assigned through the model and may only be left unresolved
or deferred. All changes remain within the migration package and its UI resources;
no core crypto/preferences classes were modified.

## Validation and limits

JVM adapter tests cover public-only and private-only occupancy and verify that
initial reads use only preference presence checks. State tests cover invalidation,
rejected assignment, deferral, immutable inventories and stable blocked counts.
Debug build, JVM suite, lint and whitespace checks are run for this slice.
No device installation or device tests were performed for this slice.

This is not a complete conflict analyzer. Messages, recipients, sessions, prekeys,
unscoped legacy state and other target data still require independent checks.
An unoccupied identity slot is not proof that a target is safe to import into.
Final revalidation immediately before any future apply is also required. Current
preflight remains analysis-only, and no replacement or merge is implemented.
