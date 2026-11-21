# BramaV2

**English** | [Polski](README.pl.md)

Gate control over Bluetooth LE: an Android app (with Android Auto support) and ESP32 controller firmware.
The phone is the BLE central; the ESP32 is the peripheral that drives two separate outputs (OPEN and CLOSE)
of the gate operator.

> **Status: prototype.** The project is tested on a single prototype board and three phones. It controls a
> physical device (a moving gate), so verify it yourself before using it in a real installation. Firmware images
> are signed with a development key; production Secure Boot, flash encryption and production keys are not part
> of this repository.

## What it does

- Two commands: `OPEN` (with optional automatic close after the connection is lost) and `CLOSE`.
- The gate position is always unknown: an acknowledgement only means that a pulse was driven on the output.
- Pairing with a QR code or the controller's 6-digit PIN (EC-JPAKE); the session is encrypted and authenticated
  with AES-256-GCM.
- Up to 8 authorized phones at the same time; the app can list phones, remove them and rename the current one.
- Automatic close is performed by the controller, without any involvement of the phone.
- Firmware update over BLE (OTA): signed ECDSA P-256 image, protection against an older `secure_version`,
  rollback to the previous version after a failed boot.
- Android Auto displays the interface of the app running on the phone.

## Repository layout

| Directory | Contents |
|---|---|
| `app/` | Android app (Kotlin, Jetpack Compose): screens and services |
| `protocol/` | Wire protocol: frames, cryptography, EC-JPAKE, test vectors |
| `domain/` | App logic, gate state and repository (no Android dependencies) |
| `ble/` | BLE layer (Android) and a simulated controller for tests |
| `car/` | Android Auto (Car App Library) |
| `firmware/` | ESP32 firmware (ESP-IDF v6.0.1, NimBLE) and host-side tests |
| `hw/` | PCB project in KiCad 10 (schematic, board, interactive BOM) |
| `doc/` | Specification, module designs, plans and implementation status |

The documentation is written in Polish. Good places to start:

- [`doc/PLAN_I_SPECYFIKACJA_BRAMY.md`](doc/PLAN_I_SPECYFIKACJA_BRAMY.md): specification and protocol,
- [`doc/implementation_status.md`](doc/implementation_status.md): what is done and verified (including on hardware),
- [`firmware/README.md`](firmware/README.md): firmware build, tests and configuration.

## Android app

Requirements: Android Studio with a JDK, Android SDK 37 (`compileSdk`), and a device running Android 13 (API 33)
or newer with Bluetooth LE. The QR scanner relies on Google Play services.

```sh
./gradlew :app:assembleDebug        # debug build
./gradlew :app:assembleRelease      # release build (R8, unsigned: the APK has to be signed)
./gradlew test                      # unit tests of all modules
```

The release build has R8 and resource shrinking enabled (about 4 MB). Keep rules for ML Kit are in
`app/proguard-rules.pro`. The repository contains no release signing configuration.

## Firmware

Requirements: ESP-IDF v6.0.1 (`~/.espressif/v6.0.1/esp-idf`) and a development key for signing the image.

```sh
cd firmware
tools/gen_dev_signing_key.sh        # once: key in ~/.config/brama/ (outside the repository)
tools/idf.sh set-target esp32       # once
tools/idf.sh build                  # produces the signed build/brama.bin
```

Host-side logic tests (target `linux`, need the `libbsd-dev` package):

```sh
cd firmware/test/host
../../tools/idf.sh --preview set-target linux
../../tools/idf.sh build && ./build/brama_host_test.elf
```

Details (prototype pins, service mode, UART console, OTA) are in [`firmware/README.md`](firmware/README.md).

**A note on eFuse.** On the board in `hw/`, GPIO12 has a pull-up that selects 1.8 V flash voltage at reset, while
the ESP32-WROOM-32D module has 3.3 V flash. The 3.3 V flash-voltage eFuse is therefore burned on every such board
(an irreversible step, done manually before the first boot; see `firmware/README.md`).

## Hardware

The board design is in `hw/` (KiCad 10, open `hw/esp32_brama.kicad_pro`): ESP32-WROOM-32D, power from an LMR54410
buck converter, two OPEN/CLOSE outputs through CNY17 optocouplers, an interlock input and a UART for diagnostics.
The project libraries are in `hw/lib/`. Notes from the board review are in `doc/implementation_status.md`.

## License

[MIT](LICENSE). The project is developed and tested by a single person; contributions and feedback are welcome.
