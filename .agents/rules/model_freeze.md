# Model Names Freeze Rule

## Rule: Gemini Models Are Frozen
Under no circumstances should any AI agent modify the `CANDIDATE_MODELS` list in:
`android/app/src/main/kotlin/com/aura/assistant/ai/GeminiChatService.kt`

The approved models are:
- `gemini-3.6-flash`
- `gemini-3.5-flash`
- `gemini-3.5-flash-lite`
- `gemini-3.1-flash-lite`
- `gemini-flash-latest`

Any attempt to change these to older 1.5 or 2.0 identifiers results in `HTTP 404 NOT_FOUND` errors on the user's Google API key.
