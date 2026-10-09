# SMS delivery reports

Delivery callbacks carry a network status report, not a delivery guarantee in
their Android result code. Parse the PDU with its explicit 3gpp/3gpp2 format.
Missing, malformed and non-status PDUs do not update delivery state.

GSM status 0 confirms reception. Statuses 1/2 and non-terminal or reserved
non-error statuses remain unconfirmed. Statuses 0x40–0x7f are terminal failures
(including temporary failures for which the service centre stopped retrying).
CDMA reception is error class 0/status 2; class 2 remains pending and class 3
maps to a generic terminal failure. Raw CDMA status remains in diagnostics.

The existing 1800 ms and seven-day guards apply only to reception successes.
Use the callback arrival time, not the later job execution time. Network errors
are recorded even when they arrive immediately.

Track parts separately per attempt, message ID and stored send timestamp.
All parts must succeed before marking delivery; terminal results cannot be
reversed by pending/duplicate reports. State survives process restart.
New delivery jobs use the `sms_delivery_v1` wire type; old `sms_sent` records
remain readable. Legacy delivery jobs lack a network status and are ignored.
Outstanding reports from sends before this update lack attempt tracking and
are deliberately left unconfirmed. Downgrading with new queued delivery jobs
is not supported by older codecs (their existing quarantine path applies).

Device acceptance checks:

- Vodafone to lifecell: TP-Status 0x45 must show a delivery error, even below
  1800 ms. Message details explain network interworking failure.
- Working route: a successful report after the guard marks delivered.
- Multipart: no delivered mark until every part has a successful report.
- Missing/invalid reports and old attempts must not confirm delivery.

No raw PDU, addresses or message bodies are included in new diagnostics.
