# Persistent job compatibility

New jobs use the `SMSecureJob:` envelope followed by Base64 of the explicit
binary format in `StableJobCodec`. Encryption, when requested by the job,
wraps the entire envelope using the existing master cipher.

Version 1 uses Java DataInput/DataOutput primitives (big-endian; strings use
modified UTF-8 with a two-byte length). The first value is an int version,
followed by a UTF type identifier:

| Type | Payload, in order |
| --- | --- |
| sms_send | long message ID, nullable UTF group, nullable UTF send-attempt ID |
| sms_receive | int internal subscription ID, int PDU count, repeated int length + raw bytes |
| sms_decrypt | long message ID, boolean manual override, boolean received while locked |
| sms_sent | long message ID, nullable UTF callback action, int callback result |
| generate_keys | no payload |

A nullable UTF string is a boolean presence flag followed by UTF when present.
Type names, version numbers and payload order are permanent wire identifiers.
Do not replace them with Java class names or enums. Future changes must retain
readers for earlier versions and the fixed byte fixtures in unit tests.
Job constructors restore execution requirements, retry limits and wake locks.
The send-attempt ID is restored rather than regenerated. Received subscription
IDs are already internal IDs and must not be mapped from device SIM IDs again.

## Upgrade and recovery

Queue database version 2 adds `queue_unreadable`. On a read/encoding failure,
the original payload and encryption flag are copied there and removed from the
active queue in one transaction. Failed copies roll back. Quarantined records
are not automatically executed or retried. They can contain private SMS data
and must remain app-private; do not include payloads in diagnostic logs.

Readable Java-serialized legacy records are rewritten under their existing live
queue ID before being returned for execution. This is only a best-effort reader:
published releases used obfuscated Java names and incompatible descriptors.
Incompatible records from those releases are preserved, not automatically
repaired. No class-name guessing, descriptor replacement or unconditional resend
is attempted. Jobs already deleted by an older version cannot be restored here.

## Verification

`./gradlew :app:testDebugUnitTest :app:assembleRelease`

Unit tests cover fixed wire fixtures, job fields, preserved send-attempt/SIM IDs,
readable legacy envelopes, malformed/unknown formats, quarantine ordering,
copy failure and encrypted-payload preservation. SQLite interactions in those
tests are mocked; they do not prove an on-device database upgrade.

Before release, validate an upgrade on a disposable installation with queued
synthetic jobs, including incompatible legacy entries and a process restart.
Check the retained quarantine bytes and avoid real SMS transmission. A primary
device requires a verified backup first. The new format is independent of R8,
but future releases still need the wire fixtures and upgrade checks.
