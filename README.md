# KoboID Prototype

KoboID is a prototype Android app that turns a phone camera into a document scanner for [KoboCollect](https://www.kobotoolbox.org/) forms.

An enumerator filling in a form taps a button and KoboID opens. They photograph an ID card, passport or other document, and KoboID reads the text on the phone itself. The extracted fields and the photo then go back into the form. Nothing is sent to a server, so it works fully offline.

> This is a prototype. It is meant to show the concept and is not a finished or supported product.

## The Concept

Field teams often have to copy details from identity documents into a form by hand. This is slow and leads to typing mistakes. KoboID speeds this up:

1. **Capture**: take a photo of the document with on-screen alignment guides.
2. **Read**: run OCR (text recognition) on the device.
3. **Structure**: turn the raw text into named fields such as name, date of birth and document number.
4. **Review**: the user checks the result before anything is saved.
5. **Return**: the fields and the photo go back into the KoboCollect form.

## Features

- **Launch from KoboCollect** through an external app intent, or use the app on its own.
- **Native camera** with frames for ID cards, passports and documents. You can still take the picture when the document is not perfectly aligned.
- **On-device OCR** using Google ML Kit. No internet connection is needed.
- **Machine-readable zone (MRZ) parsing** for passports, ID cards and visas (ICAO `TD1`, `TD2`, `TD3`, `MRV-A`, `MRV-B`), with check-digit validation.
- **Belgian eID parsing** as an example of a country-specific card parser.
- **Generic OCR mode**, which returns the raw text only.
- **Custom document presets**: photograph a sample document, draw boxes around the fields you want and name them. The preset can then be reused. Presets can be edited, exported as JSON and deleted.
- **Review screen** showing the structured fields, the raw OCR text and the captured image, with *Retake* and *Send* buttons.
- **Privacy by default**: photos are kept in temporary cache storage unless you choose to keep them.

## Download

To try it, download the APK from the [Releases page](https://github.com/JamesLeonDufour/KoboID_prototype/releases/latest) and install it on an Android phone. You may need to allow installs from unknown sources.

To try it with KoboCollect, upload the example form in [xlsform/](xlsform/) to KoboToolbox and open it in KoboCollect on the same phone.

## How It Works

### Inside a KoboCollect form

KoboCollect launches KoboID with the intent `org.kobo.scanner.SCAN`. The form can say which kind of document to expect (`card_type` / `scanMode`). For MRZ scans it can also pass `mrzStandard` (`TD1`, `TD2` or `TD3`), which changes the camera frame.

In an XLSForm, put the launch call in the `intent` column of a `field-list` group. For example:

```
org.kobo.scanner.SCAN(card_type=MRZ,mrzStandard=TD3)
org.kobo.scanner.SCAN(card_type=BELGIUM_ID)
org.kobo.scanner.SCAN(card_type=GENERIC)
org.kobo.scanner.SCAN(card_type=TEMPLATE:<preset_id>)
```

When the user taps *Send*, KoboID returns:

| Key | Content |
| --- | --- |
| `scan_mode` | Mode used: `MRZ`, `BELGIUM_ID`, `GENERIC` or `TEMPLATE:<id>` |
| `scanned_text` | Raw OCR text |
| `scanned_photo` | URI of the captured image |
| structured fields | e.g. `first_name`, `last_name`, `dob`, `id_number`, `expiry`, `nationality`, `sex`, `document_type`, `issuer` |

An example form that uses these values is in [xlsform/KoboID_xlsform.xlsx](xlsform/KoboID_xlsform.xlsx).

### Using the app on its own

From the home screen you can:

- **Scan MRZ**: choose the document standard, frame the document and see the parsed MRZ fields.
- **New Document**: create a custom preset by capturing a sample and drawing field regions.
- **Settings**: choose whether photos are kept and set the default MRZ standard.

## Building

You need Android Studio or a JDK 21 with the Android SDK.

```bash
cd android
./gradlew assembleDebug        # Windows: .\gradlew.bat assembleDebug
```

The APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`. Install it on a device running Android with a camera.

## Project Layout

- `android/`: the Android app (Kotlin, CameraX, ML Kit)
  - `app/src/main/java/com/koboid/scanner/`: app source code
- `xlsform/`: example KoboToolbox form that launches the scanner

## License

[MIT](LICENSE)
