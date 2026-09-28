# Repository Guidelines & Rules for AURA

## 🔒 STRICT ARCHITECTURE & WORKING CONSTRAINTS (DO NOT VIOLATE)

### 1. FROZEN GEMINI MODEL NAMES
The model list in `GeminiChatService.kt` is **STRICTLY FROZEN** and must **NEVER** be changed, downgraded, or reverted to older model identifiers (such as `gemini-1.5-flash` or `gemini-2.0-flash`).
- Google Gemini v1beta API returns `HTTP 404 NOT_FOUND` for 1.5/2.0 models on this setup.
- The validated, active models tested on physical hardware are:
  ```kotlin
  "gemini-3.6-flash",
  "gemini-3.5-flash",
  "gemini-3.5-flash-lite",
  "gemini-3.1-flash-lite",
  "gemini-flash-latest"
  ```
- **Rule**: Never edit `CANDIDATE_MODELS` in `GeminiChatService.kt` under any circumstances unless explicitly requested by the user.

### 2. FROZEN VOICE CONFIGURATION & PIPELINE
The voice persona, engine, and playback stack in `GeminiLiveClient.kt` are **STRICTLY FROZEN**:
- **Voice Persona**: `"Aoede"` (Natural, sweet female tone). Do NOT change this voice identifier.
- **Live Voice Model**: `models/gemini-2.5-flash-native-audio-latest` for low-latency WebSocket live duplex.
- **Audio Output Pipeline**: 24kHz PCM 16-bit mono AudioTrack stream. Never degrade the sample rate or buffer configuration.
- **Persona & Identity**: AURA is a female AI companion created by Ansh Kesharwani, addressing the user as "Boss". This identity is locked.

### 3. FROZEN WORKING CORE SYSTEMS & AUTOMATION
The following working features and components are production-tested and **STRICTLY PROHIBITED** from being broken, removed, or refactored:
1. **Gemini Function Calling REST Schema**:
   - In `GeminiChatService.kt`: Function response turns must never contain arbitrary `text` parts. All task guidance is injected cleanly into `output._task_guidance`.
2. **Native Tool Registry (`JarvisToolRegistry.kt`)**:
   - All 40+ native device tools (flashlight, battery, alarms, volume, bluetooth, wifi, apps, whatsapp, call screening) must remain intact and functional.
   - `type_message` / `type_text`: Must preserve typing into screen inputs without auto-sending.
   - `chat_on_whatsapp`: Must preserve autonomous reading of WhatsApp messages via accessibility node hierarchy and automatic formulated reply.
3. **Accessibility Automation (`AuraAccessibilityService.kt`)**:
   - `armWhatsappTypeMessage`, click, scroll, and node inspection actions are working and frozen.
4. **Execution & Recovery (`ExecutionEngine.kt` & `ExecutionVerifier.kt`)**:
   - Backoff retry, evidence verification, and fallback tool execution logic must be preserved.

### 4. BUILD & LINT CONSTRAINTS
- Java Target: JDK 17 (`D:\applications\jdk-17`).
- Android lint must keep `abortOnError = false` in `build.gradle.kts` to allow uninterrupted release builds.
