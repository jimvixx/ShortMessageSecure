# Read-only SMSecure subscription candidates

The preceding deferred-decision slice is committed as `041e1e8`.

## Scope

`SilenceTargetSubscriptionReader` reads Android active subscription IDs and a
snapshot of existing SMSecure default preferences. It does not use
`SubscriptionInfoCompat`, allocate logical IDs, edit preferences, request
permissions, or read phone numbers, ICC IDs, or display names from telephony.

`SilenceTargetSubscriptions` is an immutable result with distinct available,
permission-required, and unavailable statuses. Its candidate map uses Android
device IDs as keys and SMSecure logical IDs as values. Only nonnegative integer
values of `app_subscription_id_for_device_subscription_id_<deviceId>` for active
IDs are accepted. Missing and malformed values remain unresolved. All active
entries sharing one logical ID are excluded as ambiguous. Inactive mappings and
allocation counters cannot create candidates. Inputs are not mutated or retained.

The storage direction was checked against the current SMSecurePreferences getter
and setter. This adapter targets current SMSecure settings, not legacy Silence
preferences. No core database, crypto, preferences, or dual-SIM code was changed.

## Remaining boundary

These are saved-mapping candidates, not proof that a SIM owns an identity key.
A stale saved mapping cannot be authenticated by numerical equality. The adapter
is not yet wired into the activity or import coordinator; UI selection, refresh
when subscriptions change, identity-conflict checks and final revalidation remain
pending. A future caller must use logical map values when preparing assignments.
No automatic assignment, historical-slot allocation, live import or key replacement
is implemented. Preflight remains unable to apply a migration.

## Validation

JVM coverage checks mapping direction, malformed and absent records, collisions,
inactive records, immutable snapshots, empty versus unavailable inventory, invalid
IDs, missing/revoked permission and unavailable telephony. Mocked adapter tests
verify that preferences are only read and personal SIM metadata is not requested.

Run: `:app:testDebugUnitTest`, `:app:assembleDebug`, `:app:lintDebug`, and
`git diff --check`. Device installation and device tests were not performed for
this unconnected adapter; live SMSecure data and Silence exports were not accessed.
