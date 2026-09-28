# AURA Architecture & Release Version History

This document tracks all release versions of the AURA AI Android Assistant, including changelogs, tool registries, APK paths, and release verification data.

---

## 📌 Latest Release: v5.3.3 — Persistent Learned Command Rules, Full Tool Integrity & System Polish

- **Release Date / Time**: 2026-09-22 13:45:00 IST
- **Target Platform**: Android 10+ (Tested on Samsung Galaxy S24, Android 14)
- **Local Desktop Path**: `C:\Users\Ansh Kesharwani\Desktop\aura_v5.3.3_learned_rules_stability_20260922.apk`
- **Repository Path**: `apks/aura_v5.3.3_learned_rules_stability_20260922.apk`
- **Status**: Production Verified & Fully Backwards-Compatible

### 🔍 Root Causes Identified & Resolved
1. **User Teaching & Custom Command Procedure Engine (`teach_command_rule` & `LearnedCommandRule`)**:
   - **Problem**: When the user guided AURA (*"Jab main ye bolun toh ye karna, memory me save kar lo"*), AURA either hallucinated text (*"Ok boss kar di hu"*) without saving anything or saved it as casual conversational trivia via `remember_fact`. After a few commands, AURA forgot the custom procedure or took wrong actions.
   - **Solution**:
     - Added first-class tool `teach_command_rule(trigger_pattern, action_instruction, primary_tool, tool_parameters)`.
     - Built `LearnedCommandRule` persistent vault in `AuraMemoryManager` storing triggers, instructions, and target tools.
     - Injected a dedicated `🚨🎯 USER-DEFINED COMMAND RULES (SUPREME EXECUTION PRIORITY #1)` section at the very top of system prompt.
     - Added live prompt directive injection in `GeminiChatService` when incoming prompts match any user-taught rule.
     - Added strict Rule 14 prohibiting fake text-only acknowledgments.
2. **Full Tool Catalog Integrity (Eliminated "Boss, mere paas tool nahi hai")**:
   - **Problem**: `GeminiChatService` was naively pruning 70 tools down to 5-15 based on keyword matching with the immediate prompt. For taught or natural Hindi queries lacking exact keywords, required tools (like `set_volume`, `set_dnd`, `find_and_tap`) were stripped out, forcing Gemini to say "tool nahi hai".
   - **Solution**: Supplied full tool catalog `JarvisToolRegistry.getGeminiToolDeclarations()` (70 tools) on all turns, matching Gemini Live's behavior. Gemini now always has access to all tools.
3. **Experience Database Self-Correction Fix**:
   - Fixed `updateFeedback()` in `ExperienceDatabase.kt` to accurately target and update the most recent record when `taskId` is `"recent_task"`, ensuring user corrections (*"nahi hua"*, *"galat hai"*) are persisted.
4. **Crash & Accessibility Stability Polish**:
   - Fixed `ClassCastException` on Dynamic Island expand/collapse (`WindowManager.LayoutParams` vs `MarginLayoutParams`).
   - Replaced raw `pendingPlan` assignments in `armFacebookPost` and `armAppSearchAndLaunch` with `startPlan()` to ensure timeouts and retry counters are initialized properly.
   - Cleaned `findFocusedEditText` to use a two-pass DFS without double traversal.
   - Upgraded `AuraDynamicIsland.launchApp()` with `JarvisToolRegistry.resolveAppIntent()` and graceful accessibility home search fallback.
   - Added `flagReportViewIds` and lowered `notificationTimeout` to 50ms in `accessibility_service_config.xml`.

---

## 📌 Release v5.3.2 — Dynamic Zero-Hardcode App Resolution & Execution Verification Engine

- **Release Date / Time**: 2026-09-21 21:05:00 IST
- **Target Platform**: Android 10+ (Tested on Samsung Galaxy S24, Android 14)
- **Status**: Production Verified & Fully Backwards-Compatible

### 🔍 Root Causes Identified & Resolved
1. **Zero-Hardcoding Dynamic Package Resolution (`resolveAppPackage`)**:
   - **Problem**: Melodify previously had a faulty hardcoded package name in `JarvisToolRegistry.kt` (`com.ansh.ytmusicpreimum`), causing *"Aisa lagta hai ki Melodify app mein yeh gaana nahi mil raha hai"*.
   - **Solution**: Completely stripped out hardcoded package mappings. Replaced with real-time Android `PackageManager` dynamic scanning via `pm.queryIntentActivities(CATEGORY_LAUNCHER)` matching display labels (`loadLabel()`) and package identifiers, plus `pm.getInstalledApplications(0)`. Any current or future installed music player/app is dynamically resolved on-device with zero code modifications.
2. **ExecutionVerifier False-Negative & Experience Database Self-Healing**:
   - **Problem**: `ExecutionVerifier` only inspected 6 hardware tools; all other 56 tools (read queries like `get_current_time`, action tools like `play_media`, `open_app`) were falling into `else -> verified = false`, returning `verified: false` and polluting `AuraExperienceDB` with false failure logs (`action_dispatched_unverified`).
   - **Solution**: Added context-aware verification `verify(context, toolName, arguments, executionResult)`. Read-only tools and action tools report verified if execution succeeded without errors. Added `verifyWifi()` and built auto-purging (`purgeStaleVerificationFailures()`) to clean historical false negatives from SQLite and SharedPreferences.
3. **Offline Reflex & Media Playback Grounding**:
   - Added zero-latency local media playback reflex in `OfflineReflexEngine.kt` for instant response.
   - Added Melodify and custom music queries to `AuraCommandCorpus.kt`.

---

## 📌 Release v5.3 — Live System Clock & Grounding Engine

- **Build Date / Time**: 2026-09-21 16:47:27 IST
- **Target Platform**: Android 10+ (Tested on Samsung Galaxy S24, Android 14)
- **Local Desktop Path**: `C:\Users\Ansh Kesharwani\Desktop\aura_v5.3_time_clock_fix_20260921.apk`
- **Repository Path**: `apks/aura_v5.3_time_clock_fix_20260921.apk`
- **APK Size**: 52,191,517 bytes (49.7 MB)
- **SHA-256 Checksum**: `FEF72C38782A519E508F91B60F6231094ADC3C02B9087CFE12B8CC4FABE4A90C`

---

## 📋 Release Summary Table

| Version | Date | Key Capabilities Added | APK Location | Size |
|---|---|---|---|---|
| **v5.3.2** | 2026-09-21 | Dynamic App Resolution Engine (Zero Hardcoding), Verification False-Negative Fix, Experience DB Cleanup | Repository Source & ADB Deploy | Multi |
| **v5.3** | 2026-09-21 | Real-time Device Clock Tool 62, 0s Offline Time Reflex, Message Speak Toggle Tool 61 | `apks/aura_v5.3_time_clock_fix_20260921.apk` | 49.7 MB |
| **v5.2** | 2026-09-21 | Screen Perception & UI Task Grounding (`get_screen_context`, `find_and_tap`) | `Desktop/aura_v5.2_clean_agent_20260921.apk` | 49.8 MB |
| **v5.1** | 2026-09-21 | Direct Google News RSS (`get_latest_news`), MediaStore stats (`get_media_storage_stats`), DuckDuckGo web search | `Desktop/aura_v5.1_clean_agent_20260921.apk` | 49.8 MB |
| **v5.0** | 2026-09-20 | 62 Native Tools, 1053-item training batch, AuraAdbReceiver, Offline Reflex Engine | `Desktop/aura_v5.0_agent_core.apk` | 49.8 MB |
| **v4.0** | 2026-09-20 | Foundation Agent, Multi-turn Chat, Gemini Live Client, Porcupine Wake Word | `Desktop/aura_v4.0_agent_core_20260920.apk` | 49.8 MB |

---

## 🛠️ Complete Tool Index (v5.3)

1. `make_call` — Direct dial contact or raw phone number
2. `send_sms` — Send SMS via telephony
3. `send_whatsapp` — Send WhatsApp text (direct wa.me deep-link + A11y auto-send)
4. `send_whatsapp_media` — Send photos/videos to WhatsApp contacts
5. `toggle_flashlight` — Camera torch ON/OFF
6. `set_volume` — Adjust media, ring, call volume (0-100%)
7. `set_brightness` — Adjust display brightness
8. `toggle_wifi` — WiFi state management
9. `toggle_bluetooth` — Bluetooth adapter control
10. `toggle_hotspot` — Personal hotspot toggle
11. `get_battery_status` — Battery percentage, charging state, temperature
12. `take_screenshot` — Instant screen capture via Accessibility Service
13. `create_alarm` — Set Clock app alarms
14. `set_timer` — Set countdown timers
15. `open_app` — Launch any installed application by name or query
16. `play_media` — Audio playback control
17. `play_youtube` — Search and launch YouTube videos/music
18. `read_recent_sms` — Read incoming SMS inbox
19. `get_clipboard_text` — Inspect system clipboard content
20. `set_clipboard` — Copy text to clipboard
21. `get_media_storage_stats` — Real-time count of photos, videos, audio via MediaStore (NEVER opens Gallery!)
22. `get_storage_space` — Internal storage space diagnosis (Total, Used, Free GB)
23. `get_screen_context` — Inspect active UI node tree hierarchy
24. `read_screen_text` — Extract all readable text from current screen
25. `find_and_tap` — Target on-screen elements and trigger physical Accessibility tap
26. `screen_tap` — Physical coordinate tap
27. `screen_scroll` — Scroll up / down on active view
28. `check_device_health` — Full device diagnostic (RAM, Storage, Battery, Uptime)
29. `check_internet_speed` — Live speed test (download Mbps + ping latency)
30. `get_latest_news` — Direct Google News RSS synthesis (NEVER opens Chrome!)
31. `search_internet` — DuckDuckGo Instant Answer search without browser redirect
32. `clear_notifications` — Clear all or app-specific notifications
33. `toggle_message_announcements` — Turn ON/OFF speaking incoming WhatsApp/SMS aloud
34. `get_current_time` — Live device clock (hour, minute, second, day, date)
35. `accept_call` — Voice-driven call answering
36. `decline_call` — Voice-driven call rejection
37. `emergency_sos` — Instant emergency dispatch
... and all supporting productivity, calendar, contact and memory tools.
