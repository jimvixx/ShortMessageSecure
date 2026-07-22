# Privacy Policy

Short Message Secure (SMSecure) is designed with privacy as a core principle.

## Data Collection

SMSecure does **not collect, store, or transmit user data to external servers**.

All data (messages, contacts, and related metadata) is processed and stored **locally on the device only**.

---

## Permissions

SMSecure requests only the permissions required for its core functionality:

* **SMS (READ, SEND, RECEIVE, WRITE)**
  Used to send, receive, and manage SMS messages.

* **MMS (RECEIVE_MMS, RECEIVE_WAP_PUSH)** 
  SMSecure declares MMS-related permissions  only to satisfy Android system requirements for default SMS applications. The app does not implement MMS functionality and does not process or store MMS content.

* **Contacts (READ_CONTACTS)**
  Used to display and select recipients.

* **Camera (CAMERA)**
  Used only for QR code scanning (identity verification).

* **Notifications (POST_NOTIFICATIONS)**
  Used to notify about incoming messages.

* **Network (INTERNET, ACCESS_NETWORK_STATE)**
  Used only for optional features (e.g., log sharing or external content when explicitly triggered by the user).

* **Phone State (READ_PHONE_STATE)**
  Used to determine network/service availability.

* **Foreground Service (FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC)**
  Used for short-lived internal tasks such as database migration.

* **Wake Lock (WAKE_LOCK)**  
  Used to keep the device awake during critical operations (e.g., database migration) to ensure tasks complete reliably.

---

## Diagnostic logs

If a user explicitly chooses to submit a diagnostic log for troubleshooting, the log is sanitized before upload to remove known personal identifiers. Submitted logs are encrypted during transmission, used solely for diagnosing application issues, are not shared with third parties, and are automatically deleted within 14 days.

---

## No Tracking

SMSecure:

* does **not include analytics**
* does **not include advertising**
* does **not include tracking libraries**
* does **not use third-party SDKs for data collection**

---

## Data Sharing

SMSecure does **not share any user data with third parties**.

---

## Open Source

SMSecure is fully open source.
The source code is publicly available and can be audited by anyone.

---

## Summary

* ✔ No tracking
* ✔ No analytics
* ✔ No ads
* ✔ No data collection
* ✔ All data stays on device

---
