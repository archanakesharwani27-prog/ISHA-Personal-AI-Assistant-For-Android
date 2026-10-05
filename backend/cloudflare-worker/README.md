# ⚡ ISHA Cloud Edge Gateway (Cloudflare Worker)

High-Performance Serverless Edge Proxy for **ISHA Personal AI Assistant for Android**.

Allows users to run ISHA **without needing their own Gemini API key** (Zero-Key Experience like ChatGPT/Claude apps) while keeping the master Google Gemini API key 100% secure in Cloudflare's encrypted secrets.

---

## 🌟 Key Features

1. **Zero-Key Mode for Android Users**:
   - Master Gemini API key is securely encrypted on Cloudflare's edge.
   - Android APK makes requests directly to this gateway.
2. **Gemini Live Audio WebSocket Duplex Pipeline**:
   - Ultra-low latency bidirectional WebSocket proxy for `gemini-2.5-flash-native-audio-latest` with `"Aoede"` persona.
   - Piped in V8 isolate memory directly between phone and Google's servers.
3. **SSE Streaming Chat Proxy**:
   - Direct streaming pass-through for multi-turn chat and function-calling tool execution.
4. **Ephemeral Token Minting**:
   - Built-in `/api/token` endpoint to mint 30-minute short-lived tokens for direct client WebSocket connections.
5. **Global Anycast Edge Latency**:
   - Powered by Cloudflare's 300+ global edge datacenters (Mumbai, Delhi, Bangalore, Chennai, Kolkata).
   - Local ping typically <20ms in India.

---

## 🚀 1-Minute Quick Start & Deployment

### Step 1: Install Dependencies
Open terminal in this directory (`backend/cloudflare-worker`):
```bash
npm install
```

### Step 2: Login to Cloudflare
If you haven't logged in to Wrangler on your machine:
```bash
npx wrangler login
```
*(A browser window will open — simply click "Allow" to authorize).*

### Step 3: Add Your Master Gemini API Key
Run this command to encrypt your Gemini API key inside Cloudflare:
```bash
npx wrangler secret put GEMINI_API_KEY
```
*(Paste your Google Gemini API key when prompted).*

### Step 4: Deploy to Cloudflare Edge
Deploy your worker globally with one command:
```bash
npx wrangler deploy
```

Once deployment completes, Wrangler will display your live Worker URL:
```
Published isha-gateway (x.xx sec)
https://isha-gateway.<your-subdomain>.workers.dev
```

---

## 📱 Connecting to the ISHA Android App

Once you have your Worker URL:
1. Open [IshaGatewayConfig.kt](file:///d:/Projects/aura_shell_backup/android/app/src/main/kotlin/com/aura/assistant/config/IshaGatewayConfig.kt) in Android Studio.
2. Update `DEFAULT_GATEWAY_URL`:
   ```kotlin
   const val DEFAULT_GATEWAY_URL = "https://isha-gateway.<your-subdomain>.workers.dev"
   ```
3. That's it! Any user who installs your APK can immediately chat and talk to Isha with zero setup and no API key required!

---

## 🛠️ API Endpoints Summary

| Endpoint | Method | Description |
|---|---|---|
| `/` or `/health` | `GET` | Health check & Cloudflare PoP diagnostic |
| `/live` or `/ws` | `WebSocket` | Real-time duplex WebSocket for Gemini Live Voice |
| `/api/token` | `POST` | Generates a 30-min ephemeral token from Google |
| `/v1beta/models/*` | `POST` | Streaming and standard Gemini REST proxy |
