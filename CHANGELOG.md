## 🚀 v1.0.0-alpha — Initial Release (Refactor & Modernization)

This release focuses on a major cleanup and modernization of the codebase, improving stability, security, and maintainability.

### ✨ Highlights

- **Modernized project stack**  
  Updated the entire codebase to align with modern Java, Android SDK, and Gradle standards

- **Removed legacy dependencies**  
  Eliminated outdated and unmaintained libraries:
  `com.amulyakhare.textdrawable`, `org.greenrobot.eventbus`, `org.whispersystems.libpastelog`, `gradle-witness`

- **MMS support removed**  
  Dropped MMS due to low relevance and lack of carrier support

- **Global message search**  
  Search across all conversations

- **Identity verification (MITM protection)**  
  Added secure identity checks via:
  - QR codes  
  - Hex fingerprints  
  - Base64 fingerprints  

- **Localization tooling (DeepL)**  
  Introduced automated translation pipeline for multi-language support

- **Modern notifications**  
  Implemented Android Notification Channels

- **Log collection & sharing**  
  Built-in log capture with upload to paste services (with fallback chain)

### 🧹 Internal improvements

- Removed deprecated APIs and legacy code paths  
- Improved overall app stability and consistency  
- Prepared codebase for future features (e.g., P2P messaging)
