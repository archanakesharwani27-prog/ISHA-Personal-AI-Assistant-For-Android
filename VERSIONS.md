# AURA Architecture & Release Version History

This document tracks all release versions of the AURA AI Android Assistant, including changelogs, tool registries, APK paths, and release verification data.

---

## 📌 Latest Release: v5.3 — Live System Clock & Grounding Engine

- **Build Date / Time**: 2026-09-21 16:47:27 IST
- **Target Platform**: Android 10+ (Tested on Samsung Galaxy S24, Android 14)
- **Local Desktop Path**: `C:\Users\Ansh Kesharwani\Desktop\aura_v5.3_time_clock_fix_20260921.apk`
- **Repository Path**: `apks/aura_v5.3_time_clock_fix_20260921.apk`
- **APK Size**: 52,191,517 bytes (49.7 MB)
- **SHA-256 Checksum**: `FEF72C38782A519E508F91B60F6231094ADC3C02B9087CFE12B8CC4FABE4A90C`

### 🔍 Root Cause of Time Discrepancy & Solution
1. **The Bug**:
   When Gemini Live voice chat connects (e.g. 15–20 minutes prior), a static timestamp (`$now`) was injected into the initial system prompt. During ongoing continuous voice conversations, Gemini held onto this stale timestamp from the session start and lacked a dedicated real-time tool to check the device clock. As a result, asking *"Time kya hai"* produced stale or inaccurate time.
2. **The Fix (5 Core Components)**:
   - **Naya Dedicated Tool (`get_current_time`) [Tool 62]**:
     Fetches live device clock via Android system time (`Date()`, `SimpleDateFormat("hh:mm a")`, `SimpleDateFormat("h bajkar m minute")`). Returns exact 12-hour, 24-hour, and spoken Hindi time.
   - **Offline Reflex Engine (Rule 13)**:
     Queries like *"Time kya hai"*, *"Time batao"*, *"Kitne baje hain"*, *"Aaj ki date kya hai"* are resolved with 0-second latency directly from the phone's live clock.
   - **Gemini Live Prompt & Corpus Rule 13**:
     Strictly forbids using session start timestamps for answering time queries; enforces executing `get_current_time`.
   - **Notification Voice Announcement Toggle (`toggle_message_announcements`) [Tool 61]**:
     Provides voice and UI controls to enable or silence spoken reading of incoming WhatsApp/SMS/OTP notifications.
   - **Clean Audio & TTS Routing**:
     Harmonized native Android TTS and dynamic island state without voice mismatches.

---

## 📋 Release Summary Table

| Version | Date | Key Capabilities Added | APK Location | Size |
|---|---|---|---|---|
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
