# Passport Flasher

Android app for flashing firmware to the FoloToy AI Passport (ESP32-C3) via USB OTG.

## Features

- Connect to Passport device via USB OTG (VID 0x303A / PID 0x1001)
- Chip detection (ESP32-C3) and flash size identification
- Select firmware files from device storage (single merge firmware or multiple partition bins)
- Intelligent address inference for common partition files (`bootloader.bin` → 0x0, `partition-table.bin` → 0x8000, `app.bin` → 0x10000, etc.)
- Flash firmware with compressed (deflate) or uncompressed mode
- MD5 hash verification after write
- Erase entire flash
- Real-time progress and log output
- Baud rate selection (115200–2000000)

## Architecture

The app is based on the [esptool-js](https://github.com/espressif/esptool-js) protocol (Apache-2.0), ported to Kotlin with Android USB Host API.

```
app/
├── esptool/          # ESP protocol stack
│   ├── SlipCodec.kt        # SLIP frame encoding/decoding
│   ├── Esp32C3Rom.kt       # ESP32-C3 chip constants
│   ├── StubData.kt         # Stub loader binary (base64)
│   ├── EspLoader.kt        # Core protocol: sync, flash, stub, etc.
│   └── FirmwareImage.kt    # Firmware file handling
├── usb/              # USB transport layer
│   ├── UsbTransport.kt     # Bulk read/write, CDC control requests
│   └── UsbManagerHelper.kt # USB device discovery & permission
├── ui/               # UI layer
│   └── FlasherViewModel.kt
└── MainActivity.kt         # Main activity with binding
```

## Build

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

## Requirements

- Android 8.0+ (API 26)
- Device with USB OTG support
- FoloToy AI Passport device

## License

This project incorporates code derived from [esptool-js](https://github.com/espressif/esptool-js) (Apache-2.0).