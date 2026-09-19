# AURA 2.0 — Autonomous Personal AI Companion for Android

<p align="center">
  <img src="https://img.shields.io/badge/AURA-2.0.3-00E5FF?style=for-the-badge&logo=android&logoColor=black" alt="AURA 2.0.3" />
  <img src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Gemini-Live%20Multimodal-10A37F?style=for-the-badge&logo=google&logoColor=white" alt="Gemini Live" />
  <img src="https://img.shields.io/badge/Status-Production%20Ready-success?style=for-the-badge" alt="Status" />
  <img src="https://img.shields.io/badge/Training_Corpus-333+_Commands-FF6B35?style=for-the-badge" alt="Training Corpus" />
</p>

---

## 📋 Version History

| Version | Date | What's New |
|---------|------|------------|
| **2.0.3** 🆕 | 2026-09-19 | **AURA Training System** — 333+ command corpus, ExperienceMemory seeding, AuraTrainingEngine |
| 2.0.2 | 2026-09-18 | ConversationalReactionEngine, IntentRouter, improved feminine grammar |
| 2.0.1 | 2026-09-17 | MicOwnershipManager, ExecutionVerifier, RetryPolicy, 52+ tool registry |
| 2.0.0 | 2026-09-13 | Full native Kotlin/Compose rewrite from Flutter, Gemini Live Bidi WebSocket |

---

## 🆕 What's New in v2.0.3 — Training System

> These features are **brand new** — not present in any previous version.

### 🧠 AuraCommandCorpus (`training/AuraCommandCorpus.kt`)
A massive pre-built training dataset with **333+ real-world user command patterns** spanning **55 categories**:
- Hindi, Hinglish, English, typos, slang, compound commands, edge cases
- Every command maps to the correct tool, parameters, and a pre-seeded lesson
- Categories include: calls, SMS, WhatsApp, volume, brightness, WiFi, Bluetooth, alarms, timers, navigation, music, camera, battery, memory, notes, emergency, chitchat, and 37 more

### ⚙️ AuraTrainingEngine (`training/AuraTrainingEngine.kt`)
- **Idempotent seeding**: Seeds `ExperienceMemory` only once per corpus version (v3 → `CORPUS_VERSION = 3`)
- **Background thread**: Non-blocking daemon thread seeded on app start
- **5 extra lesson categories**: COMPOUND, CLARIFY, SAFETY, ANTI_HALLUCINATION, GRAMMAR
- `diagnosticsReport()` — runtime stats about lessons loaded
- `forceSeed()` — for debug/QA

### 📚 Enhanced ExperienceMemory (`memory/ExperienceMemory.kt`)
- Capacity raised: **200 total lessons** (was 3 per tool)
- **5 lessons per tool** (was 3)
- **Prioritised injection**: Warnings/failures first in system prompt (most critical)
- New `recordLessonsBatch()` for efficient bulk seeding
- `stats()` summary for diagnostics
- `MAX_PROMPT_LESSONS = 15` injected per session

---

## 🌟 Overview

**AURA 2.0** is an autonomous personal AI companion designed exclusively for Android. Moving far beyond traditional command bots, AURA combines:

- **Gemini Live Multimodal Intelligence**: Real-time bidirectional voice & vision streaming over WebSocket.
- **ChatGPT-Style Context-Preserving Barge-In**: Interrupt AURA naturally mid-speech without losing conversational context.
- **Background Wake Word Detection**: Offline, high-precision keyword spotting (*"Hey Aura"*) via OpenWakeWord ONNX.
- **Autonomous Tool Engine**: 52+ native Android device tools with multi-step execution, fallback, and verification.
- **Gemini Floating Overlay & Dynamic Island**: Instant access over any running app without interrupting the user's workflow.
- **Self-Learning Experience Memory**: AURA learns from successes and failures and never repeats the same mistake.

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
│                    🆕 TRAINING & SELF-LEARNING LAYER                    │
│  • AuraCommandCorpus (333+ command patterns, 55 categories)             │
│  • AuraTrainingEngine (idempotent seeding, background daemon thread)    │
│  • ExperienceMemory v2 (200 lessons, prioritised prompt injection)      │
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
- Integrated developer diagnostics accessible via **Settings → System & Diagnostics**.
- Real-time observation of WebSocket connectivity, audio packet metrics, barge-in counts, tool invocations, and hardware mic owner.
- Emergency manual **"Force Release Mic"** controls for testing.

### 5. 🧠 Self-Learning Training System 🆕
- **333+ pre-built command patterns** covering every realistic user input style.
- Lessons seeded into `ExperienceMemory` on first boot — AURA starts smart, not blank.
- Compound commands, ambiguity handling, safety rules, anti-hallucination rules, and grammar rules all pre-loaded.

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
│   │   │   ├── AuraMemoryManager.kt            # Persistent user memory + system prompt
│   │   │   └── JarvisToolRegistry.kt           # 52+ tool declarations & dispatcher
│   │   ├── memory/
│   │   │   └── ExperienceMemory.kt             # 🆕 v2: 200 lessons, batch seeding
│   │   ├── training/                           # 🆕 NEW in v2.0.3
│   │   │   ├── AuraCommandCorpus.kt            # 🆕 333+ command patterns, 55 categories
│   │   │   └── AuraTrainingEngine.kt           # 🆕 Idempotent corpus → memory seeder
│   │   ├── brain/
│   │   │   ├── ConversationalReactionEngine.kt # Emotional & contextual response logic
│   │   │   └── IntentRouter.kt                 # Command intent classification
│   │   ├── audio/
│   │   │   ├── MicOwnershipManager.kt          # Hardware mic coordinator
│   │   │   ├── AudioCaptureSession.kt          # 16kHz PCM capture + VAD + AGC
│   │   │   └── AudioPlaybackSession.kt         # 24kHz PCM playback + instant flush
│   │   └── ui/
│   │       ├── screens/
│   │       │   ├── AuraMainScreen.kt           # Main chat interface
│   │       │   ├── ChatGPTVoiceScreen.kt       # Fullscreen voice orb
│   │       │   └── AuraDiagnosticsScreen.kt    # Developer metrics screen
│   │       └── theme/                          # True-dark ChatGPT tokens
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
