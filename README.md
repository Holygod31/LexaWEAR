# LexaWEAR

**Android-exclusive** wardrobe management app for blind and visually impaired users, built around NFC tags and Android's TalkBack accessibility system.

---

## The idea

Managing a wardrobe without sight means relying on others to identify colours, read care labels, and keep clothing organised. LexaWEAR replaces that dependency with a small NFC tag on each garment. Tap the phone against the tag — the app reads it and announces everything: colour, type, size, material, wash temperature, ironing instructions. No sight required at any step.

The app is designed TalkBack-first. Every button, every screen transition, every result is announced through Android's native accessibility layer. There is no visual-only information — if TalkBack can't describe it, it doesn't belong in the app.

---

## How it works

### NFC tags

Each garment gets an NTAG213 or NTAG215 NFC sticker, permanently heat-pressed into the fabric. The tag stores a compact, language-neutral string:

```
N:Blue Jacket|T:JK|CL:2196F3|P:P|S:M|F:C|SE:W|M:wool|W:30|D:A|I:1|B:0|C:0|X:notes
```

All values are language-neutral codes — `JK` means jacket in English and *veste* in French. The app decodes them at display time in the user's selected language. This means a tag written in one language is fully readable in another without any migration.

### The four screens

| Screen | Purpose |
|--------|---------|
| **Write** | 14-step wizard to encode a new garment onto a tag. Each step is a set of clearly labelled buttons; TalkBack announces the current step and options before any input is required. |
| **Scan** | Reads an existing tag and announces each field in sequence. Items can be saved to the wardrobe from here. |
| **Wardrobe** | Local catalogue of all saved garments. Scrollable list; each item carries a full TalkBack description including type, colour, season and care instructions. |
| **Filter** | Narrows the wardrobe by clothing type, colour, season, and formality. Filters are applied client-side; no re-read of storage on each selection. |

### Camera / computer vision

The camera screen provides an assisted route for garments that don't yet have a tag. It uses a two-tier detection pipeline:

1. **TFLite clothing classifier** — `clothing_classifier.tflite` in `assets/`. When present, runs a MobileNetV2 model trained on iMaterialist/DeepFashion2 to identify clothing type. Results below a 0.60 confidence threshold are discarded; the user is prompted to select manually.
2. **ML Kit fallback** — when the TFLite model is absent or returns low confidence, Google ML Kit's image labeller takes over for type detection.
3. **Colour detection** — independent of both models. A k-means cluster analysis on the centre crop of the frame identifies the dominant colour, which is then matched to the nearest entry in `ColorPalette`.

> **Current state:** `clothing_classifier.tflite` is not yet trained. The pipeline falls back to ML Kit automatically — no code change needed once the model file is added. See [`docs/clothing_classifier_training.md`](docs/clothing_classifier_training.md) for the training guide.

Care symbol recognition (`care_symbols.tflite`) is a planned extension. ML Kit OCR covers text-based labels in the current version. See [`docs/care_symbol_classifier_training.md`](docs/care_symbol_classifier_training.md).

---

## Architecture

```
MainActivity
│
├── NfcFragment          (Write — tag wizard)
├── CareFragment         (Scan — tag reader + care display)
├── WardrobeFragment     (Wardrobe — item list)
├── FilterFragment       (Filter — wardrobe narrowing)
│
├── CameraFragment       (Camera UI)
│
├── VisionAnalyzer       (TFLite + ML Kit + k-means colour)
├── CareSymbolClassifier (stub — awaiting care_symbols.tflite)
├── TutorialManager      (15-step first-run overlay)
└── ColorPalette         (single source of truth for all colours)
```

**Key architectural rules — do not break these:**

- Root layout is `FrameLayout` (`R.id.root_layout`). Do not revert to `LinearLayout` — the tutorial overlay depends on this.
- `isTutorialNavigating` must be set before and cleared after any programmatic tab switch in `TutorialManager`.
- `CareSymbolClassifier.LABELS` order must match the trained model's output order exactly.
- `CLOTHING_LABELS` in `VisionAnalyzer` must match `labels.txt` from the training script exactly.
- All lookup tables in fragments use `get()` properties — never static initialisers. They access Android string resources and must be initialised after the fragment attaches.
- `ColorPalette.kt` is the single source of truth for colours. Do not add colour definitions anywhere else.
- NFC tags always store language-neutral codes. Names are decoded at display time only.

---

## Tech stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin |
| Min SDK | API 26 (Android 8.0) |
| NFC | Android NFC Adapter API — NDEF text records |
| Camera | CameraX |
| Type classification | TensorFlow Lite (MobileNetV2) + Google ML Kit fallback |
| OCR | Google ML Kit |
| Colour detection | k-means clustering → `ColorPalette` lookup |
| Accessibility | Android Accessibility API (TalkBack) |
| Storage | JSON in internal app storage — no cloud, no database |
| Languages | English, French |

---

## Getting started

Clone the repo and open in Android Studio. Connect an Android device (API 26+), build and run. That's it — no SDK setup, no API keys, no server.

The app is **Android-exclusive**. NFC hardware and the TalkBack accessibility framework are the two hard requirements; both are Android-native and have no iOS equivalent at this time.

To test the full NFC flow you need NTAG213 or NTAG215 stickers. Standard NFC stickers from any electronics supplier work fine.

---

## Roadmap

- [ ] Train `clothing_classifier.tflite` — iMaterialist Fashion dataset (Apache 2.0). Training script and guide in `tools/` and `docs/`.
- [ ] Train `care_symbols.tflite` — photograph real garment labels, 50–100 images per ISO 3758 symbol class. Lower priority; OCR covers most text labels.

---

## Repository structure

```
LexaWEAR/
├── app/
│   └── src/main/
│       ├── java/com/example/lexawear/
│       │   ├── MainActivity.kt
│       │   ├── NfcFragment.kt
│       │   ├── CareFragment.kt
│       │   ├── WardrobeFragment.kt
│       │   ├── FilterFragment.kt
│       │   ├── CameraFragment.kt
│       │   ├── VisionAnalyzer.kt
│       │   ├── CareSymbolClassifier.kt
│       │   ├── TutorialManager.kt
│       │   └── ColorPalette.kt
│       ├── assets/
│       │   ├── clothing_classifier.tflite   ← add after training
│       │   └── care_symbols.tflite          ← add after training
│       └── res/
│           ├── layout/
│           ├── values/strings.xml
│           └── values-fr/strings.xml
├── tools/
│   ├── train_clothing_classifier.py
│   └── train_care_symbols.py
└── docs/
    ├── clothing_classifier_training.md
    └── care_symbol_classifier_training.md
```

---

## Project

Developed as part of the Mechatronics Trinational programme (DHBW Lörrach · FHNW Muttenz · IUT Mulhouse), 2026.  
Package: `com.example.lexawear`