# Voice & Working Architecture Freeze Rule

## 🔒 PROHIBITED MODIFICATIONS (FROZEN CORE SYSTEMS)

### 1. Voice Stack & Configuration
- **Model**: `models/gemini-2.5-flash-native-audio-latest`
- **Voice**: `"Aoede"` (Sweet, natural female persona)
- **Audio Output**: 24kHz PCM 16-bit mono AudioTrack
- **Rule**: Do not change voice settings, sample rates, or live models.

### 2. Core Working Features
- **Persona & Identity**: Female AI companion, created by Ansh Kesharwani, addressing user as "Boss".
- **REST Function Calling Protocol**: `_task_guidance` inside `functionResponse.response.output._task_guidance`. Never insert separate `text` parts into function response turns.
- **Tools**: All native tools in `JarvisToolRegistry.kt` (including `chat_on_whatsapp`, `type_message`, `get_battery_status`, etc.) are validated and must not be deleted or modified.
- **Accessibility Engine**: `AuraAccessibilityService.kt` screen reading and automated typing must be preserved.
- **Execution Engine**: `ExecutionEngine.kt` fallback and retry policies are locked.
