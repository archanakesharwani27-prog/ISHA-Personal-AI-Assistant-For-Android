# ISHA 2.0 — Autonomous Personal AI Companion for Android

<p align="center">
  <img src="https://img.shields.io/badge/ISHA-2.0.0-00E5FF?style=for-the-badge&logo=android&logoColor=black" alt="ISHA 2.0.0" />
  <img src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Gemini-Live%20Multimodal-10A37F?style=for-the-badge&logo=google&logoColor=white" alt="Gemini Live" />
  <img src="https://img.shields.io/badge/Status-Production%20Ready-success?style=for-the-badge" alt="Status" />
  <img src="https://img.shields.io/badge/Developer-Ansh%20Kesharwani-FF6B35?style=for-the-badge" alt="Developer" />
</p>

---

## 🌟 Overview

**ISHA 2.0** is an autonomous personal AI companion designed and created by **Ansh Kesharwani** exclusively for Android. Designed to be a warm, intelligent, and proactive female AI companion, ISHA combines:

- **Gemini Live Multimodal Intelligence**: Real-time bidirectional voice & vision streaming over low-latency WebSocket.
- **Persistent Long-Term Memory**: Remembers user facts, preferences, and custom taught workflows with supreme priority execution.
- **Dynamic Contact Memory with Versioning**: Remembers phone numbers naturally from speech, preserves historical numbers when updated, and identifies old numbers during incoming calls.
- **Truecaller-Style Call Announcer & Gesture Response**: Announces callers (*"Incoming call from [Name]"* / *"Incoming call from [Name] ke purane number se"*) with hands-free voice Accept/Decline and proximity wave gestures.
- **Multi-User Account Isolation**: Complete per-user data sandboxing ensuring private chats never cross-contaminate between different accounts.
- **Autonomous Tool Engine**: 52+ native Android device tools (calling, WhatsApp messaging, typing, system settings, media playback, alarms, timers, SOS).
- **ChatGPT-Style Context-Preserving Barge-In**: Interrupt ISHA naturally mid-speech without losing conversational context.
- **Background Wake Word Detection**: Offline, high-precision keyword spotting (*"Hey ISHA"*) via OpenWakeWord ONNX.

---

## 🏗 System Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           ISHA 2.0 ECOSYSTEM                            │
├────────────────────────────────┬────────────────────────────────────────┤
│         VOICE & PERCEPTION     │          DECISION & CONTROL            │
│  • Gemini Live Bidi WebSocket  │  • Autonomous Tool Engine (52+ Tools)  │
│  • 16kHz PCM In / 24kHz Out    │  • Execution & Verification Engine     │
│  • OpenWakeWord ONNX Engine    │  • Intent Router & Fallback Pipeline   │
│  • ChatGPT-Style Barge-In      │  • Long-Term Memory (IshaMemoryManager)│
├────────────────────────────────┴────────────────────────────────────────┤
│                       HARDWARE ARBITRATION LAYER                        │
│  • MicOwnershipManager (Atomic handshake between WakeWord & GeminiLive) │
│  • AcousticEchoCanceler (AEC) & AutomaticGainControl (AGC)              │
│  • Adaptive Far-Field Gain (1.3x - 3.5x Dynamic RMS)                    │
├─────────────────────────────────────────────────────────────────────────┤
│                    CONTACT MEMORY & CALL INTELLIGENCE                   │
│  • IshaContactMemoryManager (Dual vault JSON + Backup Prefs)            │
│  • Number Versioning (Active + Previous Number History Archive)         │
│  • Pre-Ringing Call Screening (IshaCallScreeningService)                │
│  • Heads-Up Call HUD & Voice/Gesture Accept/Decline                     │
├─────────────────────────────────────────────────────────────────────────┤
│                             USER INTERFACE                              │
│  • Jetpack Compose True-Dark Theme (ChatGPT Aesthetic)                  │
│  • Dynamic Chat Text Scaling (Small, Default, Large)                    │
│  • Voice Persona Switching (Aoede, Puck, Charon, Fenrir, Kore)          │
│  • Multi-User Isolated Chat Sessions                                    │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 🚀 Key Features

### 1. 📞 Contact Memory with History & Truecaller Announcer
- **Natural Voice Learning**: Tell ISHA *"9087654321 mobile number Rahul ka hai save kar lo"* and ISHA archives it into persistent long-term storage.
- **Automatic History Versioning**: When updated (*"ab 9875432106 Rahul ka hai update kar lo"*), ISHA sets the new number as primary while keeping past numbers archived in history.
- **Smart Call Screening**: Identifies incoming calls before ringing, announcing *"Incoming call from Rahul"* or *"Incoming call from Rahul ke purane number se"*.
- **Touchless Hand & Voice Controls**: Wave over the phone or speak *"Accept"* / *"Decline"* to handle calls hands-free.

### 2. 🔒 Multi-User Privacy Isolation
- Each user account has a completely isolated data sandbox (`files/isha_chats/user_$uid/`).
- Switching accounts or logging in as guest immediately flushes runtime memory and loads only that account's private chat history.

### 3. 🎙 Gemini Live Multimodal Bi-Directional Streaming
- Ultra-low latency voice duplex powered by Google Gemini Live WebSocket.
- Voice persona switching between **Aoede** (default natural female tone), **Puck**, **Charon**, **Fenrir**, and **Kore**.

### 4. 🛠 52+ Native Android Autonomous Tools
- **Messaging & Communication**: Read incoming messages aloud, send WhatsApp messages, type into screen inputs, place calls, look up contacts.
- **Media & Music**: Play songs on YouTube, Spotify, Melodify, JioSaavn, Wynk, control playback.
- **System Control**: Flashlight, Volume, Brightness, Wi-Fi, Bluetooth, Alarms, Timers, Screen lock, Screenshots.

---

## 📁 Repository Structure

```
ISHA/
├── android/
│   ├── app/src/main/kotlin/com/aura/assistant/
│   │   ├── MainActivity.kt                     # Jetpack Compose entry point
│   │   ├── WakeWordService.kt                  # Foreground wake word service
│   │   ├── IshaCallScreeningService.kt         # Pre-ringing incoming call screening
│   │   ├── IshaCallAnnouncerManager.kt         # Floating call HUD & voice announcer
│   │   ├── ai/
│   │   │   ├── GeminiLiveClient.kt             # Gemini Live Bidi WebSocket client
│   │   │   ├── IshaMemoryManager.kt            # Long-term memory vault & system prompt
│   │   │   ├── IshaContactMemoryManager.kt     # Contact memory & number version history
│   │   │   └── IshaToolRegistry.kt             # 52+ native tool dispatcher
│   │   ├── brain/
│   │   │   └── OfflineReflexEngine.kt          # Zero-latency offline execution
│   │   ├── data/
│   │   │   └── IshaChatRepository.kt           # Multi-user partitioned chat repository
│   │   └── ui/
│   │       ├── screens/                        # Main chat, voice orb, diagnostics
│   │       ├── components/                     # Settings sheet, Markdown text scaling
│   │       └── theme/                          # True-dark ChatGPT design system
└── README.md
```

---

## 🔧 Getting Started

### Prerequisites
- Android Studio Ladybug | 2024.2+
- Android SDK 34+ (Android 14 / 15)
- JDK 17
- Google Gemini API Key

### Build & Run
1. Open the `android/` directory in Android Studio.
2. Build via terminal:
   ```bash
   build_isha.bat
   ```
3. Install to device:
   ```bash
   adb install -r android/app/build/outputs/apk/debug/app-debug.apk
   ```

---

## 👤 Developer & Creator
- **Created & Developed By**: **Ansh Kesharwani**
- **Companion**: ISHA (Intelligent System & Helpful Assistant)

---

## 📜 License
This project is licensed under the MIT License — see the LICENSE file for details.

