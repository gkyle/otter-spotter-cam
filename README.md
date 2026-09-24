# 🐾 Otter Spotter

A high-performance Android camera application and wildlife monitoring tool designed for real-time river otter detection, tracking, and automated throat pattern photography using on-device machine learning (TensorFlow Lite & ONNX Runtime).

---

## 🌟 Highlights

- **100% On-Device AI Inference**: Runs directly on the device using hardware-accelerated **TensorFlow Lite (XNNPACK/NNAPI)** or **ONNX Runtime Mobile**. No internet connection or cloud server required.
- **Smart Camera Trap (Auto Photo)**: Automatically detects when an otter's unique **throat pattern (`throat_portrait`)** is visible within its body (`whole_body`) and captures a full-resolution still photo into `DCIM/Otter Spotter`.
- **Dynamic Optical & Digital Zoom**: Discovers the hardware zoom capabilities of the device's camera lenses (e.g. 1x, 2x, 5x telephoto, 10x, and MAX) and selects native optical zoom presets.
- **Material 3 Design & Controls**:
  - Crisp Material 3 vector icons for flashlight, settings menu, and photo gallery.
  - Interactive **Settings Bottom Sheet** with continuous confidence threshold sliders for **Otter** and **Throat Pattern** sensitivity (5% to 95%).
  - Fast toggles for AI inference, active model engine (TFLite 640 vs. ONNX 256), camera facing (rear/front), bounding box overlays, and debug outlines.
  - Top status pill to toggle Auto Photo mode on/off with instant feedback.
- **Built-in Photo Gallery**:
  - 3-column responsive thumbnail grid with memory caching for fluid 60fps browsing.
  - Fullscreen photo viewer with left/right swiping, photo metadata/timestamps, and Android native sharing.
  - Visual badges distinguishing automatic trap captures (`🎯 Auto`) from manual snapshots.

---

## 📱 Getting Started (Android)

### Prerequisites
- Android Studio or Android SDK with command-line tools.
- JDK 17+.
- Android device running Android 8.0 (API 26) or newer (optimized for modern devices like Google Pixel).

### Build from Source
From the `android/` directory:
```bash
cd android
./gradlew assembleDebug
```
The compiled APK will be located at:
`android/app/build/outputs/apk/debug/app-debug.apk`

### Install via ADB
Connect your Android phone via USB with USB Debugging enabled:
```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```
Or install the prebuilt package:
```bash
adb install -r static/otter-cam.apk
```

---

## 🧭 In-App Controls & Features

### Main Viewfinder
- **Top Bar**:
  - `Otter Spotter` brand label.
  - **Auto Photo Pill**: Tap to toggle between `🎯 Auto Photo` (automatic capture armed) and `● Auto Off`.
  - **Flashlight**: Quick toggle for device torch/flash.
  - **Hamburger Menu (Settings)**: Opens the settings bottom sheet.
- **Telemetry HUD**:
  - Real-time camera FPS and inference latency (ms).
  - Conditional detection count badges: `Otters: X` and `Throat patterns: Y` appear automatically when counts > 0.
- **Viewfinder Overlay**:
  - 10px emerald border flashes around the screen when a throat pattern is spotted.
  - Bounding boxes for otters (cyan) and throat patterns (emerald) can be toggled on/off in settings.
- **Bottom Controls**:
  - **Gallery Button**: Opens the captured photo browser.
  - **Shutter Button**: Circular instant capture button. Automatically turns **emerald green** when a throat pattern is visible, accompanied by subtle haptic feedback.
  - **Zoom Selector Bar**: Dynamic optical and digital zoom buttons (e.g. `1x`, `2x`, `5x`, `10x`, `MAX`).

### Settings Bottom Sheet
Access via the top-right hamburger icon:
- **Otter Confidence Slider**: Tune detection threshold for whole body otters (5% - 95%, step 5%).
- **Throat Pattern Confidence Slider**: Tune sensitivity for throat pattern identification (5% - 95%, step 5%).
- **AI Inference**: Toggle model execution on/off.
- **Active Model Engine**: Switch between `TFLite 640` and `ONNX 256`.
- **Camera Facing**: Switch between rear and front-facing cameras.
- **Detection Bounding Boxes**: Toggle live bounding boxes on the camera feed.
- **Debug Outlines**: Toggle raw candidate bounding boxes for debugging.

---

## 📂 Project Structure

```
otter-cam/
├── android/                 # Native Android application
│   ├── app/
│   │   ├── src/main/assets/ # Bundled TFLite and ONNX models
│   │   ├── src/main/java/   # Kotlin source code (MainActivity, OtterDetector, Gallery, etc.)
│   │   └── src/main/res/    # Layouts, Material 3 vector drawables, colors, styles
│   ├── build.gradle.kts     # Project build configuration
│   └── gradlew              # Gradle wrapper executable
├── models/                  # Trained YOLO weights & exported models (.tflite, .onnx)
└── static/                  # Web/PWA assets and prebuilt APK package
```

---

## 📄 License
This project is open-source and intended for wildlife monitoring and conservation research.
