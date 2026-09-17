# SIM inventory on the preflight screen

Baseline commit: `a44928f` (read-only target subscription adapter).

The preflight screen now displays the number of active SIMs with an unambiguous
saved SMSecure binding and the number without one. Permission-required and
unavailable states are distinct and do not block backup inspection. The screen
never requests phone permission or assigns a SIM automatically.

The ViewModel reads the inventory on its background executor, publishes a loading
state instead of retaining old candidates, coalesces concurrent refresh requests,
and discards results after it is cleared. The activity refreshes on resume and
provides an explicit refresh control. No SIM identifiers or phone metadata are
shown. The existing Import entry and analysis-only workflow are unchanged.

## Remaining work before a usable importer

1. Explicit source-to-target review UI, preserving decisions across recreation and
   invalidating them when the backup or target inventory changes.
2. Target identity and existing-data conflict analysis; define handling for
   historical bindings and occupied logical IDs without merging unrelated keys.
3. Prepare actual database, preference and crypto outputs entirely in staging,
   including message references and thread normalization, then verify them together.
4. A coordinated apply/rollback mechanism, including process-death recovery and
   durable backups. Live data writes require an explicit scope expansion beyond
   the original analysis-only slice.
5. End-to-end device testing: encrypted messaging continuity, password-protected
   backups, multiple/historical SIMs, malformed input, storage exhaustion and
   interruption/recovery. Finish user-facing wording and localization.

The validator and draft planner are substantial groundwork. They do not yet form
a working import path. Completing the remaining work is several substantial stages,
not just enabling an Import button.

## Checks

Passed: 79 JVM tests, 122 migration instrumentation tests on Samsung A53 Android
16, debug APK, Android test APK, lint and diff whitespace checks.

Device smoke: the summary reported two saved active bindings and zero unresolved;
manual refresh preserved that result. The original export still passed analysis
(41 SMS, three identity pairs, one session). The SIM summary and both buttons
remained accessible by scrolling after the long report.

Before testing, installed APK and credential/device-protected app data were backed
up and double-read verified at:
`/home/user/Backups/SMSecure/2026-09-17T21-09-57Z-before-silence-tests`.
APK signer digests matched; installation used `adb install -r`.
After testing, databases, crypto, all preferences and both original Silence exports
were byte-identical to their baselines. Only diagnostic log and profile marker
changed. Staging was empty and SMSecure was relaunched.

Permission-loss states are covered by the adapter JVM tests, not by changing
permissions on the user's device. Rotation/process-death and live SIM-change UI
scenarios remain untested. No apply action is present.
