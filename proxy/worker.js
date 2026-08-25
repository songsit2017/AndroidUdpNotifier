const SYSTEM_PROMPT =
  "คุณคือระบบตอบแชทอัตโนมัติของคนที่กำลังขับรถอยู่ ให้ตอบกลับข้อความสั้นๆ สุภาพ เป็นภาษาไทย ว่ากำลังขับรถอยู่และตอบตามบริบท (ถ้าข้อความสั้นหรือไม่มีบริบท ให้ตอบแค่ว่ากำลังขับรถอยู่เดี๋ยวติดต่อกลับ)";

const MAX_TEXT_LENGTH = 2000;
const RATE_LIMIT_PER_IP_PER_HOUR = 20;
const RATE_LIMIT_TOTAL_PER_DAY = 300;

export default {
  async fetch(request, env) {
    if (request.method !== "POST") {
      return new Response("Method not allowed", { status: 405 });
    }

    const secret = request.headers.get("x-proxy-secret");
    if (!env.PROXY_SHARED_SECRET || secret !== env.PROXY_SHARED_SECRET) {
      return new Response("Unauthorized", { status: 401 });
    }

    let body;
    try {
      body = await request.json();
    } catch {
      return new Response("Bad request", { status: 400 });
    }

    const text = (body && body.text ? String(body.text) : "").slice(0, MAX_TEXT_LENGTH).trim();
    if (!text) {
      return new Response("Bad request", { status: 400 });
    }

    const rateLimitResponse = await checkAndBumpRateLimit(request, env);
    if (rateLimitResponse) return rateLimitResponse;

    const geminiReply = await tryGemini(env, text);
    if (geminiReply) {
      return Response.json({ reply: geminiReply, source: "gemini" });
    }

    const claudeReply = await tryClaude(env, text);
    if (claudeReply) {
      return Response.json({ reply: claudeReply, source: "claude" });
    }

    return new Response("No reply generated", { status: 502 });
  },
};

async function checkAndBumpRateLimit(request, env) {
  if (!env.RATE_LIMIT_KV) return null;

  const ip = request.headers.get("cf-connecting-ip") || "unknown";
  const now = new Date();
  const hourKey = `rl:ip:${ip}:${now.toISOString().slice(0, 13)}`;
  const dayKey = `rl:total:${now.toISOString().slice(0, 10)}`;

  const [ipCount, totalCount] = await Promise.all([
    env.RATE_LIMIT_KV.get(hourKey),
    env.RATE_LIMIT_KV.get(dayKey),
  ]);

  if (Number(ipCount || 0) >= RATE_LIMIT_PER_IP_PER_HOUR) {
    return new Response("Rate limit exceeded", { status: 429 });
  }
  if (Number(totalCount || 0) >= RATE_LIMIT_TOTAL_PER_DAY) {
    return new Response("Daily quota exceeded", { status: 429 });
  }

  await Promise.all([
    env.RATE_LIMIT_KV.put(hourKey, String(Number(ipCount || 0) + 1), { expirationTtl: 3600 }),
    env.RATE_LIMIT_KV.put(dayKey, String(Number(totalCount || 0) + 1), { expirationTtl: 86400 }),
  ]);

  return null;
}

async function tryGemini(env, text) {
  if (!env.GEMINI_API_KEY) return null;
  try {
    const res = await fetch(
      `https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=${env.GEMINI_API_KEY}`,
      {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
          systemInstruction: { parts: [{ text: SYSTEM_PROMPT }] },
          contents: [{ parts: [{ text }] }],
        }),
      }
    );
    if (!res.ok) return null;
    const data = await res.json();
    const generated = data?.candidates?.[0]?.content?.parts?.[0]?.text?.trim();
    return generated || null;
  } catch {
    return null;
  }
}

async function tryClaude(env, text) {
  if (!env.ANTHROPIC_API_KEY) return null;
  try {
    const res = await fetch("https://api.anthropic.com/v1/messages", {
      method: "POST",
      headers: {
        "x-api-key": env.ANTHROPIC_API_KEY,
        "anthropic-version": "2023-06-01",
        "content-type": "application/json",
      },
      body: JSON.stringify({
        model: "claude-3-haiku-20240307",
        max_tokens: 100,
        system: SYSTEM_PROMPT,
        messages: [{ role: "user", content: text }],
      }),
    });
    if (!res.ok) return null;
    const data = await res.json();
    const generated = data?.content?.[0]?.text?.trim();
    return generated || null;
  } catch {
    return null;
  }
}
