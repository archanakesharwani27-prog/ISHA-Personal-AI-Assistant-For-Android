# AURA 2.0 — Autonomous Personal AI Companion for Android

<p align="center">
  <img src="https://img.shields.io/badge/AURA-2.0-00E5FF?style=for-the-badge&logo=android&logoColor=black" alt="AURA 2.0" />
  <img src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Gemini-Live%20Multimodal-10A37F?style=for-the-badge&logo=google&logoColor=white" alt="Gemini Live" />
  <img src="https://img.shields.io/badge/Status-Production%20Ready-success?style=for-the-badge" alt="Status" />
</p>

---

## 🌟 Overview

**AURA 2.0** is an autonomous personal AI companion designed exclusively for Android. Moving far beyond traditional command bots, AURA combines:

- **Gemini Live Multimodal Intelligence**: Real-time bidirectional voice & vision streaming over WebSocket.
- **ChatGPT-Style Context-Preserving Barge-In**: Interrupt AURA naturally mid-speech without losing conversational context.
- **Background Wake Word Detection**: Offline, high-precision keyword spotting (*"Hey Aura"*) via OpenWakeWord ONNX.
- **Autonomous Tool Engine**: 52+ native Android device tools with multi-step execution, fallback, and verification.
- **Gemini Floating Overlay & Dynamic Island**: Instant access over any running app without interrupting the user's workflow.

---

## 🏗 System Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           AURA 2.0 ECOSYSTEM                           │
├────────────────────────────────┬────────────────────────────────────────┤
│         VOICE & PERCEPTION     │          DECISION & CONTROL            │
│  • Gemini Live Bidi WebSocket  │  • Autonomous Tool Engine (52+ Tools)  │
│  • 16kHz PCM In / 24kHz Out    │  • Execution & Verification Engine     │
│  • OpenWakeWord ONNX Engine    │  • Intent Router & Fallback Pipeline   │
│  • ChatGPT-Style Barge-In      │  • Long-Term Memory (AuraMemoryManager)│
├────────────────────────────────┴────────────────────────────────────────┤
│                       HARDWARE ARBITRATION LAYER                        │
│  • MicOwnershipManager (Atomic handshake between WakeWord & GeminiLive) │
│  • AcousticEchoCanceler (AEC) & AutomaticGainControl (AGC)              │
│  • Adaptive Far-Field Gain (1.3x - 3.5x Dynamic RMS)                    │
├─────────────────────────────────────────────────────────────────────────┤
│                             USER INTERFACE                              │
│  • Jetpack Compose True-Dark Theme (ChatGPT Aesthetic)                  │
│  • AuraDynamicIsland Floating Overlay Card                              │
│  • Live Telemetry Diagnostics Screen                                    │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 🚀 Key Features

### 1. 🎙 ChatGPT-Style Context-Preserving Barge-In
- When the user interrupts AURA mid-speech, the in-progress exchange is **not discarded**.
- AURA automatically captures the partial turn (`InterruptedContext`), saves it to a rolling turn buffer, and silently merges it with the user's new command.
- **Result**: Seamless, natural conversational continuity just like human dialogue.

### 2. ⚡ Microphone Ownership Arbitration (`MicOwnershipManager`)
- Eliminates race conditions and double-open crashes between background keyword spotting and live voice sessions.
- `WakeWordService` immediately yields microphone ownership before broadcasting wake events.
- `GeminiLiveClient` acquires exclusive 16kHz capture and releases cleanly upon session end.

### 3. 🛠 52+ Native Android Autonomous Tools
AURA executes native device actions directly via Android APIs:
- **Communication**: WhatsApp auto-send, Direct SMS, Phone calls, Contacts lookup.
- **Media & Music**: YouTube Music search/playback, System media transport controls (play/pause/next/like).
- **Productivity**: Google Calendar events, Alarms, Timers, Notes.
- **Device Control**: Flashlight, Volume, Brightness, Wi-Fi/Bluetooth settings, App launching.
- **Navigation & Vision**: Google Maps routing, Real-time camera & screen frame analysis.

### 4. 📊 Live Telemetry & Diagnostics
- Integrated developer diagnostics accessible via **Settings -> System & Diagnostics**.
- Real-time observation of WebSocket connectivity, audio packet metrics, barge-in counts, tool invocations, and hardware mic owner.
- Emergency manual **"Force Release Mic"** controls for testing.

---

## 📁 Repository Structure

```
aura_shell_backup/
├── 2.0.md                                      # Comprehensive AURA 2.0 Specification
├── android/
│   ├── app/src/main/kotlin/com/aura/assistant/
│   │   ├── MainActivity.kt                     # Compose entry point & Lifecycle
│   │   ├── WakeWordService.kt                  # Foreground OpenWakeWord service
│   │   ├── ai/
│   │   │   ├── GeminiLiveClient.kt             # Gemini Live Bidi WebSocket client
│   │   │   ├── AuraMemoryManager.kt            # Persistent user memory system
│   │   │   └── tools/                          # Native tool registry & implementations
│   │   ├── audio/
│   │   │   ├── MicOwnershipManager.kt          # Hardware mic coordinator
│   │   │   ├── AudioCaptureSession.kt          # 16kHz PCM capture + VAD + AGC
│   │   │   ├── AudioPlaybackSession.kt         # 24kHz PCM playback + instant flush
│   │   │   ├── AuraVadDetector.kt              # Voice activity detection
│   │   │   └── AuraDiagnostics.kt              # System telemetry flows
│   │   ├── ui/
│   │   │   ├── screens/
│   │   │   │   ├── AuraMainScreen.kt           # Main chat interface
│   │   │   │   ├── ChatGPTVoiceScreen.kt       # Fullscreen voice orb
│   │   │   │   └── AuraDiagnosticsScreen.kt    # Developer metrics screen
│   │   │   ├── components/                     # Compose UI components
│   │   │   └── theme/                          # True-dark ChatGPT tokens
│   │   └── service/
│   │       ├── AuraAccessibilityService.kt     # UI automation & WhatsApp auto-send
│   │       └── AuraNotificationListenerService.kt # Media session listener
└── README.md
```

---

## 🔧 Getting Started

### Prerequisites
- Android Studio Ladybug | 2024.2+
- Android SDK 34+ (Android 14 / 15 target)
- JDK 17+
- Gemini API Key with access to Google GenerativeService (v1beta Bidi WebSocket)

### Build & Run
1. Open the `android/` directory in Android Studio.
2. Add your Gemini API key in `Secrets.kt` or `local.properties`.
3. Sync Gradle and run on a physical Android device or emulator with microphone support:
   ```bash
   ./gradlew installDebug
   ```
4. Grant **Microphone**, **Accessibility**, and **Notification Listener** permissions when prompted for full autonomous functionality.

---

## 📜 License

This project is licensed under the MIT License — see the LICENSE file for details.
