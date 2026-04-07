# 🛠️ Building SMSecure

This guide explains how to build SMSecure from source.

SMSecure is a modernized fork of Silence/TextSecure, updated to use a current Android toolchain and development workflow.

---

## 📦 Requirements

Before building, make sure you have:

- Android Studio (recommended)  
- Android SDK installed  
- Java 17 (required by the project)  
- Git  

---

## 🚀 Quick Start (Android Studio)

1. Clone the repository:

```bash
git clone https://github.com/jimvixx/ShortMessageSecure.git
```

2. Open Android Studio

3. Select **"Open"** and choose the project directory

4. Wait for Gradle sync to complete

5. Build and run:
   - Select a device or emulator  
   - Click **Run**

---

## ⚙️ Command Line Build

You can also build SMSecure using Gradle:

```bash
./gradlew assembleDebug
```

APK will be located at:

```
app/build/outputs/apk/debug/
```

---

## 🔧 Configuration

Normally, no manual configuration is required.

If needed, you can create a `local.properties` file:

```
sdk.dir=/path/to/your/android/sdk
```

(Android Studio usually generates this automatically.)

---

## 🧱 Project Details

- Minimum SDK: 24  
- Target SDK: latest (modern Android)  
- Build system: Gradle  
- Language: Java (modernized)  

---

## 🌐 Translations

SMSecure includes a custom localization system based on DeepL.

To work with translations:

- Use the provided `l10n_sync` tool  
- Maintain `strings.xml` as the source of truth  

---

## 🎨 Assets

Vector assets are preferred.

If needed, you can generate raster assets (PNG/WebP) from vector sources using standard tools like:

- Android Studio Vector Asset tool  
- ImageMagick / GIMP  

---

## 🤝 Contributing

Contributions are welcome.

Typical workflow:

1. Fork the repository  
2. Create a feature branch  
3. Make your changes  
4. Submit a Pull Request  

---

## 💡 Notes

- MMS support has been removed in SMSecure  
- Focus is on secure SMS and future extensibility (e.g. P2P transport)  
- Project is actively modernized (Gradle, SDK, dependencies)  

---

## 🔗 Links

➡️ Source Code: https://github.com/jimvixx/ShortMessageSecure

---
