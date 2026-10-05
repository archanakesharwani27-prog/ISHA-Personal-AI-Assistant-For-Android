/**
 * ISHA Cloud Edge Gateway — High-Performance Cloudflare Worker
 * 
 * Provides:
 * 1. Zero-Key Android Experience: Secure master API key storage at the edge.
 * 2. Ultra-Low Latency WebSocket Pipeline for Gemini Live Audio (Aoede persona).
 * 3. SSE Streaming Proxy for Multi-Turn Chat & Tool Execution.
 * 4. Ephemeral Token Minting for direct Google Cloud WebSocket connections.
 * 5. Edge PoP routing (<20ms in India & worldwide via Cloudflare Anycast).
 * 
 * Author: Ansh Kesharwani
 * Version: 2.1.0
 */

const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, Authorization, X-Isha-App-Token, Upgrade, Sec-WebSocket-Key, Sec-WebSocket-Version, Sec-WebSocket-Extensions",
  "Access-Control-Max-Age": "86400",
};

export default {
  /**
   * Main fetch entry point for all HTTP and WebSocket requests
   */
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    // 1. Handle CORS Pre-flight Options
    if (request.method === "OPTIONS") {
      return new Response(null, {
        status: 204,
        headers: CORS_HEADERS,
      });
    }

    // 2. Health & Status Check
    if (url.pathname === "/" || url.pathname === "/health" || url.pathname === "/api/status") {
      const hasKey = Boolean(env.GEMINI_API_KEY && env.GEMINI_API_KEY.length > 5);
      const colo = request.cf && request.cf.colo ? request.cf.colo : "EDGE";
      const country = request.cf && request.cf.country ? request.cf.country : "IN";

      return new Response(
        JSON.stringify({
          status: "healthy",
          service: env.SERVICE_NAME || "ISHA Cloud Edge Gateway",
          version: env.SERVICE_VERSION || "2.1.0",
          colo: colo,
          country: country,
          keyConfigured: hasKey,
          authEnforced: env.ENFORCE_AUTH === "true",
          timestamp: new Date().toISOString(),
          capabilities: [
            "gemini-chat-sse-streaming",
            "gemini-live-websocket-duplex",
            "ephemeral-token-minting",
            "edge-caching-and-routing"
          ]
        }, null, 2),
        {
          status: 200,
          headers: {
            "Content-Type": "application/json; charset=utf-8",
            ...CORS_HEADERS,
          },
        }
      );
    }

    // 3. Optional Authentication Guard
    if (env.ENFORCE_AUTH === "true") {
      const appToken = request.headers.get("X-Isha-App-Token") || url.searchParams.get("token");
      const validToken = env.ISHA_APP_SECRET || "isha_mobile_sec_v2_edge";
      if (!appToken || appToken !== validToken) {
        return new Response(
          JSON.stringify({ error: "Unauthorized: Invalid or missing X-Isha-App-Token" }),
          {
            status: 401,
            headers: { "Content-Type": "application/json", ...CORS_HEADERS },
          }
        );
      }
    }

    const masterKey = env.GEMINI_API_KEY;
    if (!masterKey) {
      return new Response(
        JSON.stringify({
          error: "GEMINI_API_KEY secret is not configured in Cloudflare Worker.",
          instruction: "Run 'npx wrangler secret put GEMINI_API_KEY' to configure your Google Gemini API key."
        }),
        {
          status: 500,
          headers: { "Content-Type": "application/json", ...CORS_HEADERS },
        }
      );
    }

    // 4. WebSocket Pipeline for Gemini Live Audio (/live or /ws)
    const isWsUpgrade = request.headers.get("Upgrade")?.toLowerCase() === "websocket";
    if (url.pathname === "/live" || url.pathname === "/ws" || isWsUpgrade) {
      return handleWebSocketProxy(request, env, masterKey);
    }

    // 5. Ephemeral Token Minting Endpoint (/api/token or /v1beta/auth_tokens)
    if (url.pathname === "/api/token" || (url.pathname === "/v1beta/auth_tokens" && request.method === "POST")) {
      return handleEphemeralTokenRequest(request, env, masterKey);
    }

    // 6. REST & Streaming Chat Proxy (/v1beta/models/...)
    if (url.pathname.startsWith("/v1beta/models/")) {
      return handleGeminiRestProxy(request, url, masterKey);
    }

    // 7. Fallback Route Not Found
    return new Response(
      JSON.stringify({ error: `Endpoint not found: ${url.pathname}` }),
      {
        status: 404,
        headers: { "Content-Type": "application/json", ...CORS_HEADERS },
      }
    );
  },
};

/**
 * High-Speed Bidirectional WebSocket Edge Proxy for Gemini Live Audio
 * Bridges the Android client WebSocket to Google's BidiGenerateContent WebSocket.
 */
async function handleWebSocketProxy(request, env, masterKey) {
  const upgradeHeader = request.headers.get("Upgrade");
  if (!upgradeHeader || upgradeHeader.toLowerCase() !== "websocket") {
    return new Response("Expected WebSocket Upgrade header", {
      status: 426,
      headers: CORS_HEADERS,
    });
  }

  // Google Gemini Live WebSocket endpoint (v1beta)
  const targetWsUrl = `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=${masterKey}`;

  // Create WebSocket pair: clientWs returned to Android app, serverWs handled by this Worker
  const pair = new WebSocketPair();
  const [clientWs, serverWs] = Object.values(pair);

  // Connect upstream to Google Gemini Live
  let upstreamResponse;
  try {
    upstreamResponse = await fetch(targetWsUrl, {
      headers: {
        "Upgrade": "websocket",
      },
    });
  } catch (err) {
    return new Response(`Failed to connect to Gemini Live upstream: ${err.message}`, {
      status: 502,
      headers: CORS_HEADERS,
    });
  }

  const upstreamWs = upstreamResponse.webSocket;
  if (!upstreamWs) {
    return new Response("Upstream did not return a valid WebSocket connection", {
      status: 502,
      headers: CORS_HEADERS,
    });
  }

  serverWs.accept();
  upstreamWs.accept();

  // Forward client (Android) -> upstream (Google Gemini)
  serverWs.addEventListener("message", (event) => {
    try {
      if (upstreamWs.readyState === WebSocket.OPEN) {
        upstreamWs.send(event.data);
      }
    } catch (e) {
      console.error("Client to upstream forward error:", e);
    }
  });

  // Forward upstream (Google Gemini) -> client (Android)
  upstreamWs.addEventListener("message", (event) => {
    try {
      if (serverWs.readyState === WebSocket.OPEN) {
        serverWs.send(event.data);
      }
    } catch (e) {
      console.error("Upstream to client forward error:", e);
    }
  });

  const closeConnections = () => {
    try { serverWs.close(); } catch (_) {}
    try { upstreamWs.close(); } catch (_) {}
  };

  serverWs.addEventListener("close", closeConnections);
  upstreamWs.addEventListener("close", closeConnections);
  serverWs.addEventListener("error", closeConnections);
  upstreamWs.addEventListener("error", closeConnections);

  return new Response(null, {
    status: 101,
    webSocket: clientWs,
  });
}

/**
 * Ephemeral Auth Token Minting
 * Calls Google Generative Language auth_tokens API to generate a temporary token for client.
 */
async function handleEphemeralTokenRequest(request, env, masterKey) {
  const googleTokenUrl = "https://generativelanguage.googleapis.com/v1beta/auth_tokens";

  // Request body: default 30-minute validity, 1-minute new-session expiration
  const now = Date.now();
  const expireTime = new Date(now + 30 * 60 * 1000).toISOString();
  const newSessionExpireTime = new Date(now + 2 * 60 * 1000).toISOString();

  let clientPayload = {};
  if (request.method === "POST") {
    try {
      clientPayload = await request.json();
    } catch (_) {}
  }

  const payload = {
    uses: clientPayload.uses || 1,
    expireTime: clientPayload.expireTime || expireTime,
    newSessionExpireTime: clientPayload.newSessionExpireTime || newSessionExpireTime,
  };

  try {
    const response = await fetch(googleTokenUrl, {
      method: "POST",
      headers: {
        "x-goog-api-key": masterKey,
        "Content-Type": "application/json",
      },
      body: JSON.stringify(payload),
    });

    const data = await response.json();
    return new Response(JSON.stringify(data), {
      status: response.status,
      headers: {
        "Content-Type": "application/json; charset=utf-8",
        ...CORS_HEADERS,
      },
    });
  } catch (err) {
    return new Response(JSON.stringify({ error: err.message }), {
      status: 500,
      headers: { "Content-Type": "application/json", ...CORS_HEADERS },
    });
  }
}

/**
 * REST & SSE Streaming Proxy for Gemini Chat and Function Execution
 */
async function handleGeminiRestProxy(request, url, masterKey) {
  // Construct destination URL with Google Gemini host
  const targetUrl = new URL(`https://generativelanguage.googleapis.com${url.pathname}${url.search}`);
  targetUrl.searchParams.set("key", masterKey);

  // Clone headers and remove mobile-specific internal headers
  const forwardHeaders = new Headers(request.headers);
  forwardHeaders.delete("host");
  forwardHeaders.delete("X-Isha-App-Token");
  forwardHeaders.set("User-Agent", "Isha-Cloud-Edge-Worker/2.1");

  const forwardInit = {
    method: request.method,
    headers: forwardHeaders,
    body: request.method !== "GET" && request.method !== "HEAD" ? request.body : undefined,
  };

  try {
    const upstreamRes = await fetch(targetUrl.toString(), forwardInit);

    const resHeaders = new Headers(upstreamRes.headers);
    // Ensure CORS headers are attached
    for (const [key, value] of Object.entries(CORS_HEADERS)) {
      resHeaders.set(key, value);
    }

    // Direct streaming pass-through: No buffering, lowest possible latency
    return new Response(upstreamRes.body, {
      status: upstreamRes.status,
      statusText: upstreamRes.statusText,
      headers: resHeaders,
    });
  } catch (err) {
    return new Response(
      JSON.stringify({ error: `Upstream request failed: ${err.message}` }),
      {
        status: 502,
        headers: { "Content-Type": "application/json", ...CORS_HEADERS },
      }
    );
  }
}
