# Silence subscription and preference draft

The preceding remote-identity checks are committed as `fbfa7e3`. This stage is uncommitted.
It builds a review-only draft from the staged source and attaches it to the preflight result.
It does not migrate settings, select a SIM, write prepared mappings to disk, or modify live data.

## Source subscription inventory

`SilenceMigrationPlanner` collects canonical source app-subscription IDs from:

- Scoped/unscoped public and private identity preference names.
- Session filenames.
- SMS `subscription_id` values.
- Recipient `default_subscription_id` values.
- Phone-number/ICC metadata preference-name suffixes, without retaining their values.

Each slot retains its set of origins in an immutable `SilenceSubscriptionPlan`. Values outside
the supported integer range, malformed suffixes, and more than 1024 distinct slots reject the
draft. Missing/-1 SMS subscription values are counted separately and never turned into a
crypto slot. Missing/-1 recipient defaults likewise do not invent a binding. A real unscoped
identity/session is represented separately as source slot -1 and requires an explicit decision.

Verified prekeys require exact identity slots, so those slots are covered by the identity
inventory. If the master/SMS, file, or local identity checks have not passed, the draft is
marked as having an unchecked crypto inventory. This flag concerns source-slot coverage only;
it does not approve remote identities, all encrypted fields, or import readiness.

## Explicit target assignments

`withAssignments` accepts proposed mappings and available **SMSecure app-subscription IDs**
from its caller. It does not discover SIMs, infer a match from numeric equality, or interpret
Android device IDs as equivalent to application IDs. It rejects unknown source IDs, negative
or unavailable targets, and many-to-one assignments that would merge distinct source slots.
Partial assignments are allowed as drafts. Input maps, origin sets, and returned maps are
defensively copied and immutable. A completed assignment set never enables import.

No SIM-selection UI is implemented in this slice; the preflight screen displays counts only.
The future selector must obtain a current, read-only target inventory and revalidate it before
apply. Historical/inactive source slots and slot collisions require explicit preservation or
resolution policy; this model never silently drops or merges them.

Importantly, existing `SubscriptionInfoCompat` construction calls `findAppId` and writes
mapping/number/ICC preferences. This planner deliberately does not construct it or call the
core enumeration path. It has no target context, telephony access, or preference-write API.
Source number/ICC values and ambiguous legacy device/app mapping preferences are not reused.

## Preference candidates

`SilencePreferencePlan` only proposes explicitly present boolean values for these five keys,
whose names/types and meaning were compared in the reference Silence and current SMSecure:

- `pref_key_enable_notifications`
- `pref_key_inthread_notifications`
- `pref_show_sent_time`
- `pref_hide_unread_message_divider`
- `pref_system_emoji`

Missing values do not invent defaults. Invalid types/values reject the draft. The returned
map contains only allowlisted names and booleans; other default-preference values are not
retained. Their count is reported as deferred, not discarded from the source. Security,
passphrase/timeouts, key exchange, credentials, registration, network/APN, theme/language,
ringtone URIs, trim/deletion settings, and runtime/version flags remain outside this proposal.
Master-secret preferences continue to be handled separately by crypto validation.

These are review candidates, not instructions to overwrite target choices. Future application
must use explicit user choices and coordinated key/passphrase handling, not copy an XML file.

## Changes and validation scope

- New immutable `SilenceMigrationPlan`, `SilencePreferencePlan`, and `SilenceSubscriptionPlan`.
- New snapshot-only `SilenceMigrationPlanner`.
- Coordinator/result integration and a brief draft summary in the existing preflight screen.
- 11 JVM tests for allowlisting, strict types, deep immutability, explicit mappings, unknown
  slots, collisions, legacy unscoped bindings, and absent/default handling.
- 9 Android tests for source inventory/provenance, unknown SMS IDs, malformed IDs/metadata,
  cancellation, coordinator cleanup/readiness, and source-byte preservation.

All new Java files carry the 2026 Jimvixx GPL header; comments are in English. No core database,
crypto, preference, or SIM classes were changed. No new dependencies or permissions were added.
Target-selection UI, complete preference/key policy, normalization, atomic apply/rollback,
and API 24/other-provider runtime coverage remain unfinished.

## Verification completed

- 65 JVM tests passed; debug APK/test APK builds, lint, and whitespace checks passed.
- 122 migration Android tests passed on Samsung Galaxy A53 / Android 16, including 9 new tests.
- Real SAF export produced 3 source bindings, 41 SMS without a SIM reference, 5 preference
  candidates, and 37 deferred default settings. No targets were assigned or settings applied.
  These are application bindings, not a claim that the source had three physical SIM cards.
- Existing crypto checks continued to pass. The long report remains scrollable and its folder
  selection button is reachable after scrolling.
- Before device tests, verified fresh backups of the installed APK and both private-storage
  areas, and matching APK signing certificates. Backup:
  `/home/user/Backups/SMSecure/2026-09-17T20-57-16Z-before-silence-tests`.
- Live databases, crypto state, all preferences, and both original Silence exports were unchanged.
  Only the diagnostic log and runtime profile marker changed outside cache. Staging was empty;
  SMSecure was relaunched after verification.
