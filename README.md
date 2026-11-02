<div align="center">

# 🪪 KoboID Prototype

**Scan identity documents straight into your KoboCollect forms, fully offline and on the phone.**

[![Platform](https://img.shields.io/badge/platform-Android%207.0%2B-3DDC84?logo=android&logoColor=white)](#-built-with-native-android-technology)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![OCR](https://img.shields.io/badge/OCR-on--device-4285F4?logo=google&logoColor=white)](https://developers.google.com/ml-kit/vision/text-recognition/v2)
[![Offline](https://img.shields.io/badge/works-100%25%20offline-2E7D32)](#-works-offline)
[![Release](https://img.shields.io/github/v/release/JamesLeonDufour/KoboID_prototype?label=download)](https://github.com/JamesLeonDufour/KoboID_prototype/releases/latest)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

[**⬇️ Download the APK**](https://github.com/JamesLeonDufour/KoboID_prototype/releases/latest) · [**📋 Example form**](xlsform/) · [**🧩 How it works**](#-how-it-works)

</div>

---

KoboID turns an Android phone's camera into a document scanner for [KoboCollect](https://www.kobotoolbox.org/). An enumerator taps a button in their form and KoboID opens. They photograph an ID card, passport or other document, and KoboID reads the text **on the phone itself**. The extracted fields and the photo go straight back into the form.

No server, no upload, no mobile data needed.

> [!NOTE]
> This is a **prototype**. It is meant to show the concept and is not a finished or supported product.

## ✨ Highlights

|  |  |
| --- | --- |
| 📴 **Works offline** | Text recognition runs entirely on the device. It works in the field with no signal at all. |
| 🔒 **Private by design** | Documents never leave the phone. Photos are kept in temporary storage unless you choose to keep them. |
| 🛂 **Reads passports and ID cards** | Parses the machine-readable zone (MRZ) of passports, ID cards and visas and checks it with check digits. |
| 🧩 **Plugs into KoboCollect** | Launched from any XLSForm, and returns named fields that fill the form automatically. |
| ✏️ **Any document you like** | Draw boxes on a sample document to create your own reusable preset. |
| 👀 **Human in the loop** | Every result is shown for review before it is sent back to the form. |

## 📴 Works Offline

KoboID is built for field work, where coverage is patchy or absent.

- **The OCR model ships inside the app.** KoboID uses the *bundled* version of Google ML Kit text recognition, so the model is installed with the APK. Nothing is downloaded the first time you scan.
- **No network calls.** The app does not talk to any server. Capture, recognition, parsing and review all happen on the phone.
- **Results go back through Android, not the internet.** KoboID hands the data to KoboCollect with a standard Android intent on the same device. KoboCollect then syncs the form the way it normally does, whenever a connection is available.

## 📱 Built with Native Android Technology

KoboID is a native Android app, not a web page wrapped in an app. It talks to the camera and the system directly, which keeps it fast and reliable on modest field phones.

| Layer | Technology | What it does |
| --- | --- | --- |
| Language | **Kotlin** | Modern, safe native Android code |
| Camera | **Android CameraX** | Live camera preview, alignment frames and capture |
| Text recognition | **Google ML Kit (on-device)** | Turns the photo into text, offline |
| Parsing | **Built-in parsers** | MRZ (ICAO 9303), Belgian eID and custom presets |
| Integration | **Android intents** | Launch from KoboCollect and return data and photo |
| Compatibility | **Android 7.0+ (API 24)** | Runs on most phones in use today |

## 🧭 The Concept

Field teams often copy details from identity documents into a form by hand. That is slow and leads to typing mistakes. KoboID does the copying for them:

```
  📷 Capture  ──▶  🔍 Read (OCR)  ──▶  🗂️ Structure  ──▶  👀 Review  ──▶  📤 Return to form
```

1. **Capture**: photograph the document with on-screen alignment guides.
2. **Read**: recognise the text on the device.
3. **Structure**: turn the raw text into named fields such as name, date of birth and document number.
4. **Review**: the user checks the result before anything is saved.
5. **Return**: the fields and the photo go back into the KoboCollect form.

## 🚀 Features

- **Launch from KoboCollect**, or use the app on its own.
- **Guided camera** with frames for ID cards, passports and documents. You can still take the picture when the document is not perfectly aligned.
- **MRZ parsing** for ICAO `TD1`, `TD2`, `TD3`, `MRV-A` and `MRV-B` layouts, with check-digit validation.
- **Belgian eID parsing** as an example of a country-specific card parser.
- **Generic OCR mode**, which returns the raw text only.
- **Custom document presets**: photograph a sample, draw boxes around the fields you want and name them. Presets can be edited, exported as JSON and deleted.
- **Review screen** showing the structured fields, the raw text and the photo, with *Retake* and *Send* buttons.

## ⬇️ Try It

1. Download the APK from the [Releases page](https://github.com/JamesLeonDufour/KoboID_prototype/releases/latest) and install it on an Android phone. You may need to allow installs from unknown sources.
2. Open the app and tap **Scan MRZ** to try it on a passport or ID card.
3. To try it with KoboCollect, upload the example form in [xlsform/](xlsform/) to KoboToolbox and open it in KoboCollect on the same phone.

## 🧩 How It Works

### Inside a KoboCollect form

KoboCollect launches KoboID with the intent `org.kobo.scanner.SCAN`. In an XLSForm, put the launch call in the `intent` column of a `field-list` group:

```
org.kobo.scanner.SCAN(card_type=MRZ,mrzStandard=TD3)
org.kobo.scanner.SCAN(card_type=BELGIUM_ID)
org.kobo.scanner.SCAN(card_type=GENERIC)
org.kobo.scanner.SCAN(card_type=TEMPLATE:<preset_id>)
```

`mrzStandard` (`TD1`, `TD2` or `TD3`) only changes the camera frame. The MRZ layout itself is detected automatically.

When the user taps *Send*, KoboID fills the fields in the group whose names match these keys:

| Key | Content |
| --- | --- |
| `scan_mode` | Mode used: `MRZ`, `BELGIUM_ID`, `GENERIC` or `TEMPLATE:<id>` |
| `scanned_text` | Raw OCR text |
| `scanned_photo` | The captured image |
| structured fields | e.g. `first_name`, `last_name`, `dob`, `id_number`, `expiry`, `nationality`, `sex`, `document_type`, `issuer` |

A ready-made example form with every mode is in [xlsform/KoboID_xlsform.xlsx](xlsform/KoboID_xlsform.xlsx).

### Using the app on its own

- **Scan MRZ**: choose the document standard, frame the document and see the parsed fields.
- **New Document**: create a custom preset by capturing a sample and drawing field regions.
- **Settings**: choose whether photos are kept and set the default MRZ standard.

## 🛠️ Building from Source

You need Android Studio, or JDK 21 with the Android SDK.

```bash
cd android
./gradlew assembleDebug        # Windows: .\gradlew.bat assembleDebug
```

The APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.

## 📁 Project Layout

```
android/                               Android app (Kotlin, CameraX, ML Kit)
└── app/src/main/java/com/koboid/scanner/   App source code
xlsform/                               Example KoboToolbox form
```

## 📄 License

Released under the [MIT License](LICENSE).
