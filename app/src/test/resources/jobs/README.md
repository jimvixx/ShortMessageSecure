# Legacy job fixtures

These synthetic Java-serialized jobs were produced before adding recovery generations,
using the compiled debug classes based on e85c385 plus the uncommitted route guard and
batch SIM mapping changes. They contain no device or user data.

- sms-send-before-recovery.ser: message 42, group `group`, attempt `attempt`.
- sms-sent-before-recovery.ser: message 42, action `sent-action`, result -1.
- sms-decrypt-before-recovery.ser: message 81, manual override and locked-receive flags enabled.
- sms-receive-before-recovery.ser: internal SIM ID 123 and one synthetic three-byte PDU.

The original computed serialVersionUID values were obtained with `serialver`:
SmsSendJob -1264261020918178580; SmsSentJob -1534569068458165018;
SmsDecryptJob 683540289171725736.
The production classes now retain these values explicitly. The codec test reads these
fixtures with ObjectInputStream and verifies that the missing recovery generation defaults to zero.
