# Changelog

## v1.0.3

### Changed

- Automated Play Store changelog generation and GitHub release notes from `CHANGELOG.md`.
- Derived Android version codes from the version name.

### Fixed

- Restored the invisible 13-space auto-initiate tag instead of showing the `{13}` suffix.
- Avoided contact lookups when Contacts permission is unavailable.
- Used the Android default SMS SIM for recipients without a saved SIM selection on dual-SIM devices.

## v1.0.2

### Changed

- Updated project dependencies and release infrastructure.
- Updated the README with F-Droid installation instructions.

### Fixed

- Fixed identity key generation on first run.
- Fixed a multipart SMS retry storm that could send duplicate key-exchange messages after cellular service was restored.

## v1.0.1

### Added

- Added a GitHub Pages site and configured deployment from the repository root.
- Added `featureGraphics.png`.

### Changed

- Updated Fastlane metadata.
- Updated the application icon size.
- Updated `BUILDING.md`, `.gitignore`, and the privacy policy.
- Clarified the privacy policy for diagnostic log uploads.
- Updated `jackson-core`, `jackson-databind`, and `libphonenumber`.
- Removed unused nested Gradle wrappers.
- Removed debug build suffixes after temporarily adding them during development.

### Fixed

- Fixed Android Auto messaging notifications.

## v1.0.0

### Changed

- Updated library versions.
- Updated `README.md`.
- Marked glossary-affected translations as stale during synchronization.

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

## v0.2.0

### Added

- Added a share-message action to the conversation contextual action bar.
- Added a deployment workflow for the Cloudflare log-upload service.
- Added synchronization of the Cloudflare Worker secret during deployment.

### Changed

- Replaced paste-service log uploads with a Cloudflare R2 backend.
- Updated the log-upload backend to v0.2.0.
- Updated `README.md`.

## v0.1.1

### Added

- Added Android Developer Verification support.
- Included the Android Developer Verification registration file in builds.

### Changed

- Updated `/adi-registration.properties`.
- Updated library versions.
- Aligned AndroidX Core with AGP 8.13.
- Updated `README.md`, including Android Developer Verification documentation.

### Fixed

- Fixed the Android Developer Verification registration file path.
