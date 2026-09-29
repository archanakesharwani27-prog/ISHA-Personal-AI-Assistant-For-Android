# AURA Assistant — Living Memory & Architecture Guide

> **Purpose**: This file is the single source of truth for AURA (Android AI Assistant). Any AI model (Claude, Gemini 3.8 Flash, GPT-4o) resuming work on this codebase MUST read this file first.

---

## 🎯 Project Overview
- **Project Name**: AURA (Android Personal AI Assistant)
- **Root Path**: `d:\Projects\aura_shell_backup\`
- **Android Root**: `d:\Projects\aura_shell_backup\android\`
- **JDK Location**: `D:\applications\jdk-17` (Crucial for builds: `set JAVA_HOME=D:\applications\jdk-17`)
- **ADB Location**: `D:\android_01\platform-tools\adb.exe`
- **Main Languages**: Kotlin (Android Native Jetpack Compose), minimal legacy Flutter bindings
- **AI Brain**: Google Gemini Live WebSocket (Streaming Voice) + Gemini 1.5/2.5 Flash (Streaming Text & Tool Calling) + On-Device Offline Reflex Engine
- **Active Name**: ISHA 2.0 (renamed from AURA/Jarvis on 27 Sep 2026 21:43 IST)
- **Official App ID**: `com.isha.assistant` (Updated from `com.aura.assistant`)
- **Multi-Device Orchestration**: `IshaDeviceRegistry`, `IshaCrossDeviceBridge`, `IshaCloudSyncBridge` (Phone A ⟷ Phone B realtime commands & cloud memory sync)

---

## 🗄️ Pre-Rename (AURA / Jarvis) Snapshot & Backups
The entire complete codebase prior to the ISHA rename is permanently preserved across 3 layers:
1. **GitHub Backup Branch**: `backup/aura-pre-rename` (Pushed to remote `origin`)
2. **GitHub Git Tag**: `aura-pre-rename-v2.0`
3. **Pre-Rename Commit**: `aa9b4f43e66b21e2bc3f14d6af7ac2634013eb3e` (Date: 27 September 2026, 15:16:17 IST)
4. **Local Standalone Zip Archive**: `D:\Projects\aura_pre_rename_backup_20260928.zip` (37.5 MB, contains all 317 full source files including original `JarvisToolRegistry.kt`, `AuraNotificationListenerService.kt`, `AuraMainScreen.kt`, and `build_aura.bat`).
5. **New Public GitHub Repo**: `https://github.com/archanakesharwani27-prog/ISHA-Personal-AI-Assistant-For-Android.git` (Remote: `isha`).
   - *Rule*: Do NOT push old commits or hardcoded secrets. When generating the next build, initialize a fresh clean commit with sanitized/placeholder secrets and push to `main`.
- To checkout or inspect pre-rename state at any time:
  ```bash
  git checkout backup/aura-pre-rename
  ```

---

## 🏛️ System Architecture & File Directory

```
android/app/src/main/kotlin/com/aura/assistant/
├── MainActivity.kt                      ← App entry, system broadcast receivers, permission manager
├── WakeWordService.kt                   ← Background wake word & shake detection service
├── AuraAccessibilityService.kt          ← Full UI automation engine, screen vision, gestures, node clicks (77KB+)
├── AuraBootReceiver.kt                  ← Auto-starts background services on device reboot
├── AuraNotificationListenerService.kt   ← Intercepts and parses incoming notifications
├── JarvisFileManager.kt                 ← Local file operations, gallery storage, cleaner
│
├── ai/
│   ├── GeminiChatService.kt             ← Streaming chat API with tool calling & 5-model fallback chain
│   ├── GeminiLiveClient.kt              ← Bidirectional audio WebSocket client (16kHz PCM <-> 24kHz PCM)
│   ├── JarvisToolRegistry.kt            ← 62 native Android tools registered in Gemini schema (125KB+)
│   └── AuraMemoryManager.kt             ← System prompt builder with Claude Sonnet + GPT-4o 7-Pillar Fusion
│
├── brain/
│   ├── IntentRouter.kt                  ← Fast intent classifier
│   ├── OfflineReflexEngine.kt           ← Zero-latency (<20ms) offline command execution (Torch, Volume, etc.)
│   └── ConversationalReactionEngine.kt  ← Fast conversational reactions & fillers
│
├── compat/
│   └── DeviceCompatibilityHelper.kt     ← OEM Autostart & Battery Optimization manager (Xiaomi, Vivo, Samsung)
│
├── training/
│   ├── AuraAdbReceiver.kt               ← Static headless broadcast receiver (ADB_INJECT, RUN_1000_TRAINING, RUN_TESTS)
│   ├── AuraCommandCorpus.kt             ← 650+ real-world command training patterns across 55 categories
│   ├── AuraCommandTrainer.kt            ← Routing guide generator
│   ├── AuraToolTestSuite.kt             ← On-device test harness with 12 suites (114 tests)
│   └── AuraTrainingEngine.kt            ← 1053+ task live on-device training batch runner & ExperienceMemory seeder
│
├── memory/
│   └── ExperienceMemory.kt              ← Persistent lessons & self-correction storage on-device
│
└── ui/
    ├── contract/AuraAssistantViewModel.kt ← Main ViewModel managing live state, offline reflex, chat history
    ├── components/                      ← GlowingVoiceOrb, AuraHeaderHud, QuickToolsBottomSheet
    └── screens/AuraMainScreen.kt        ← Jetpack Compose UI
```

---

## ⚡ Offline Reflex Capabilities (No Internet Required)
Normal mobile commands execute on-device in <20ms without invoking Gemini API:
- `toggle_flashlight`: On / off / jalao / band karo
- `set_volume`: 0-100%, mute, unmute, max
- `set_brightness`: 0-100%, full, dim
- `toggle_wifi`, `toggle_bluetooth`, `toggle_hotspot`
- `get_battery_status`: Level & charging state
- `create_alarm`, `set_timer`
- `open_app`: Camera, Settings, YouTube, WhatsApp, Chrome, Gallery, Calculator, Clock, Calendar, Maps
- `take_screenshot`

---

## 🔒 Crucial Rules & Constraints
1. **User Identity & Key Contacts**:
   - Primary User: "Boss" / "Ansh ji"
   - Approved Contacts: `Mom`, `Srishti kesarwani`, `Anmol didi ji`
   - Strict rule: NEVER route to Papa or unverified contacts without explicit user disambiguation.
2. **Grammar Law**:
   - Strict Stree-Ling (Feminine Hindi grammar): "karti hoon", "kar doongi", "samajh gayi", "dekh rahi hoon".
   - Forbidden: "karta hoon", "kar dunga", "samajh gaya".
3. **Anti-Hallucination Law**:
   - Never say "Maine kar diya" or "ho gaya" unless the underlying tool returns success (`status = 0` or `"success"`).
   - If execution fails or requires permission, transparently explain and provide alternative.

---

## 🛠️ Build & Verification Quick Reference
```cmd
:: Build Command
cd /d d:\Projects\aura_shell_backup\android
set JAVA_HOME=D:\applications\jdk-17
gradlew.bat compileDebugKotlin --quiet

:: Install APK
D:\android_01\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk

:: Test Headless ADB Command
D:\android_01\platform-tools\adb.exe shell am broadcast -a com.aura.assistant.ADB_INJECT -p com.aura.assistant --es text "torch on karo"

:: Run Full Test Suite (12 suites, 114 tests)
D:\android_01\platform-tools\adb.exe shell am broadcast -a com.aura.assistant.RUN_TESTS -p com.aura.assistant

:: Run 1000+ Task On-Device Training Batch (1053 tasks, 100% pass rate)
D:\android_01\platform-tools\adb.exe shell am broadcast -a com.aura.assistant.RUN_1000_TRAINING -p com.aura.assistant
```