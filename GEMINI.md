# Repository Guidelines & Rules for Isha

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
- **Persona & Identity**: Isha is a female AI companion created by Ansh Kesharwani, addressing the user as "Boss". This identity is locked.

### 3. FROZEN WORKING CORE SYSTEMS & AUTOMATION
The following working features and components are production-tested and **STRICTLY PROHIBITED** from being broken, removed, or refactored:
1. **Gemini Function Calling REST Schema**:
   - In `GeminiChatService.kt`: Function response turns must never contain arbitrary `text` parts. All task guidance is injected cleanly into `output._task_guidance`.
2. **Native Tool Registry (`IshaToolRegistry.kt` / `JarvisToolRegistry.kt`)**:
   - All 40+ native device tools (flashlight, battery, alarms, volume, bluetooth, wifi, apps, whatsapp, call screening) must remain intact and functional.
   - `type_message` / `type_text`: Must preserve typing into screen inputs without auto-sending.
   - `chat_on_whatsapp`: Must preserve autonomous reading of WhatsApp messages via accessibility node hierarchy and automatic formulated reply.
   - **Smart Browser-to-Chat Return (`readBrowserScreenAndReturn`)**: When Chrome/browser is opened for search, it extracts readable page text and immediately executes triple-fallback foreground return (`MainActivity.instance`, `GLOBAL_ACTION_BACK`, launch intent) so Isha returns to chat screen with the answer.
   - **Direct Web Search (`performDirectWebSearch`)**: Two-tier robust live search engine (DDG Lite form-based parser + DDG HTML fallback) is locked.
3. **Temporal Intelligence & Time Grounding (`IshaMemoryManager.kt`)**:
   - `⏳ TEMPORAL MATRIX`: Automatic anchoring of Present (Aaj), Past (Yesterday / Day before), and Future (Tomorrow / Day after) timestamps.
   - `PILLAR 9 — MASTERING PRESENT, PAST, AND FUTURE`: Strict stree-ling tenses, live tool grounding for Present, memory/lesson recall for Past, and advance scheduling/proactivity for Future.
4. **Accessibility Automation (`IshaAccessibilityService.kt` & `AuraAccessibilityService.kt`)**:
   - `armWhatsappTypeMessage`, click, scroll, `getScreenTextHierarchy()`, and node inspection actions are working and frozen.
5. **Execution & Recovery (`ExecutionEngine.kt` & `ExecutionVerifier.kt`)**:
   - Backoff retry, evidence verification (`search_internet`, `open_app`, etc.), and fallback tool execution logic must be preserved.

### 4. BUILD & LINT CONSTRAINTS
- Java Target: JDK 17 (`D:\applications\jdk-17`).
- Android lint must keep `abortOnError = false` in `build.gradle.kts` to allow uninterrupted release builds.
- Application ID: `com.isha.assistant` with Activity `com.aura.assistant.MainActivity`. Any process management via ADB must target `com.isha.assistant`.

### 5. 🔒 CODEBASE FREEZE POLICY
The entire active codebase tested on physical hardware (LAVA LEX402) is in a validated, stable production state.
- **Rule**: No edits, deletions, refactoring, or modifications should be performed unless explicitly requested by the user.
