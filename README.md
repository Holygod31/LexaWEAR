# LexaWEAR

An Android app for managing clothing care instructions using NFC tags, built with accessibility-first design for visually impaired users.

## About

LexaWEAR lets you write clothing care information to NFC tags and attach them to garments. Users can then scan any tagged item to instantly read its care instructions — all without needing to see the label. The app is fully compatible with Android TalkBack for blind and visually impaired users.

## Features

### Scan Tab
- Scan any LexaWEAR NFC tag to read clothing care instructions
- Displays item name, material, wash temperature, drying, ironing, bleaching and dry cleaning info
- Accessible card layout with TalkBack support
- One-tap shortcut to add scanned item directly to your wardrobe

### Write Tab
- Write clothing care data to any NFC tag
- Dropdown menus for all care categories — no typing required
- Supports: material, wash temperature, drying method, ironing, bleaching, dry cleaning and custom notes
- Compact single-screen layout, no scrolling needed

### Wardrobe Tab
- Scan a tag to add a clothing item to your personal wardrobe
- Each item stores name, category and colour
- Colour-coded dots for quick visual identification
- Tap any item to edit or delete it
- Data stored locally on device — no account or internet required

## Accessibility

LexaWEAR is designed from the ground up for blind and visually impaired users:
- Full TalkBack screen reader support throughout
- All buttons, fields and controls have descriptive content descriptions
- Status messages are announced automatically via accessibility live regions
- Large touch targets on all interactive elements
- High contrast dark mode support

## Tech Stack

- **Language:** Kotlin
- **Min SDK:** API 26 (Android 8.0)
- **Architecture:** Fragment-based navigation with Bottom Navigation
- **NFC:** Android NFC API with NDEF read/write
- **Storage:** SharedPreferences with JSON serialization
- **UI:** Material Components for Android

## Getting Started

### Requirements
- Android Studio Hedgehog or later
- Android device or emulator with NFC support (API 26+)
- NFC tags (NTAG213 or compatible NDEF-writable tags recommended)

### Installation
1. Clone the repository:
   ```bash
   git clone https://github.com/yourusername/LexaWEAR.git
   ```
2. Open the project in Android Studio
3. Sync Gradle and build the project
4. Run on a physical device with NFC enabled (NFC does not work on emulators)

### NFC Tags
LexaWEAR works with any NDEF-compatible NFC tag. NTAG213 stickers are recommended — they are inexpensive, widely available, and have enough storage for all care fields.

## Project Structure

```
app/src/main/
├── java/com/example/lexawear/
│   ├── MainActivity.kt          — Navigation and NFC foreground dispatch
│   ├── CareFragment.kt          — Scan tab: read NFC tags
│   ├── NfcFragment.kt           — Write tab: write NFC tags
│   └── WardrobeFragment.kt      — Wardrobe tab: manage clothing items
├── res/
│   ├── layout/
│   │   ├── activity_main.xml
│   │   ├── fragment_care.xml
│   │   ├── fragment_nfc.xml
│   │   ├── fragment_wardrobe.xml
│   │   └── item_wardrobe.xml
│   ├── menu/
│   │   └── bottom_nav_menu.xml
│   └── values/
│       └── themes.xml
└── AndroidManifest.xml
```

## NFC Data Format

LexaWEAR uses a compact pipe-separated key:value format to maximise storage efficiency on small NFC tags:

```
N:Blue Jacket|M:Cotton|W:30°|D:Air Dry|I:Low Heat|B:Not Allowed|C:No|X:Gentle cycle
```

| Key | Field |
|-----|-------|
| N | Item name |
| M | Material |
| W | Wash temperature |
| D | Drying method |
| I | Ironing |
| B | Bleaching |
| C | Dry cleaning |
| X | Extra notes |

## License

This project was developed as part of the Trinatronics S6 school project.

---

*Built with accessibility in mind. Everyone deserves to know how to care for their clothes.*
