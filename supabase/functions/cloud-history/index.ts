import "jsr:@supabase/functions-js/edge-runtime.d.ts";

const JSON_HEADERS = { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" };
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const encoder = new TextEncoder();
const decoder = new TextDecoder();

function json(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), { status, headers: JSON_HEADERS });
}

function decodeBase64(value: string): Uint8Array {
  const binary = atob(value);
  return Uint8Array.from(binary, (char) => char.charCodeAt(0));
}

function encodeBase64(value: Uint8Array): string {
  let binary = "";
  for (let offset = 0; offset < value.length; offset += 0x8000) {
    binary += String.fromCharCode(...value.subarray(offset, offset + 0x8000));
  }
  return btoa(binary);
}

function aad(conversationId: string, messageId: string): Uint8Array {
  return encoder.encode(`nexura-cloud-history:v1:${conversationId}:${messageId}`);
}

async function getUser(req: Request, baseUrl: string, apiKey: string) {
  const authorization = req.headers.get("Authorization");
  if (!authorization?.startsWith("Bearer ")) return null;
  const response = await fetch(`${baseUrl}/auth/v1/user`, {
    headers: { apikey: apiKey, Authorization: authorization },
  });
  if (!response.ok) return null;
  return await response.json();
}

async function isMember(
  baseUrl: string,
  apiKey: string,
  authorization: string,
  userId: string,
  conversationId: string,
): Promise<boolean> {
  const query = new URLSearchParams({
    select: "user_id",
    user_id: `eq.${userId}`,
    conversation_id: `eq.${conversationId}`,
    limit: "1",
  });
  const response = await fetch(`${baseUrl}/rest/v1/conversation_members?${query}`, {
    headers: { apikey: apiKey, Authorization: authorization },
  });
  if (!response.ok) throw new Error("Unable to verify conversation membership");
  const rows = await response.json();
  return Array.isArray(rows) && rows.length === 1;
}

async function loadAesKey(): Promise<CryptoKey | null> {
  const encoded = Deno.env.get("NEXURA_CLOUD_HISTORY_KEY_V1");
  if (!encoded) return null;
  const raw = decodeBase64(encoded.trim());
  if (raw.length !== 32) throw new Error("NEXURA_CLOUD_HISTORY_KEY_V1 must be a base64-encoded 32-byte key");
  return await crypto.subtle.importKey("raw", raw, { name: "AES-GCM" }, false, ["encrypt", "decrypt"]);
}

async function encrypt(
  key: CryptoKey,
  conversationId: string,
  messageId: string,
  plaintext: string,
): Promise<string> {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ciphertext = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv, additionalData: aad(conversationId, messageId), tagLength: 128 },
    key,
    encoder.encode(plaintext),
  );
  return JSON.stringify({ v: 1, iv: encodeBase64(iv), ciphertext: encodeBase64(new Uint8Array(ciphertext)) });
}

async function decrypt(
  key: CryptoKey,
  conversationId: string,
  messageId: string,
  envelope: string,
): Promise<string> {
  if (envelope.length > 262144) throw new Error("Cloud history envelope is too large");
  const parsed = JSON.parse(envelope);
  if (parsed?.v !== 1 || typeof parsed.iv !== "string" || typeof parsed.ciphertext !== "string") {
    throw new Error("Unsupported cloud history envelope");
  }
  const iv = decodeBase64(parsed.iv);
  const ciphertext = decodeBase64(parsed.ciphertext);
  if (iv.length !== 12 || ciphertext.length < 16 || ciphertext.length > 196608) {
    throw new Error("Invalid cloud history envelope");
  }
  const plaintext = await crypto.subtle.decrypt(
    { name: "AES-GCM", iv, additionalData: aad(conversationId, messageId), tagLength: 128 },
    key,
    ciphertext,
  );
  return decoder.decode(plaintext);
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: JSON_HEADERS });
  if (req.method !== "POST") return json({ error: "Method not allowed" }, 405);

  try {
    const baseUrl = Deno.env.get("SUPABASE_URL");
    const publishableKeys = Deno.env.get("SUPABASE_PUBLISHABLE_KEYS");
    const apiKey = Deno.env.get("SUPABASE_ANON_KEY") ??
      (publishableKeys ? JSON.parse(publishableKeys).default : undefined);
    if (!baseUrl || !apiKey) return json({ error: "Supabase function configuration is incomplete" }, 503);

    const authorization = req.headers.get("Authorization") ?? "";
    const user = await getUser(req, baseUrl, apiKey);
    if (!user?.id || !UUID.test(user.id)) return json({ error: "Unauthorized" }, 401);

    const body = await req.json();
    const action = body?.action;
    const conversationId = body?.conversation_id;
    if (typeof conversationId !== "string" || !UUID.test(conversationId)) {
      return json({ error: "A valid conversation_id is required" }, 400);
    }
    if (!await isMember(baseUrl, apiKey, authorization, user.id, conversationId)) {
      return json({ error: "Not a conversation member" }, 403);
    }

    const key = await loadAesKey();
    if (!key) return json({ error: "Cloud history encryption key is not configured" }, 503);

    if (action === "encrypt") {
      const messageId = body?.message_id;
      const plaintext = body?.plaintext;
      if (typeof messageId !== "string" || !UUID.test(messageId) ||
          typeof plaintext !== "string" || encoder.encode(plaintext).length < 1 ||
          encoder.encode(plaintext).length > 100000) {
        return json({ error: "Invalid message id or plaintext size" }, 400);
      }
      const cloudContent = await encrypt(key, conversationId, messageId, plaintext);
      return json({ cloud_content: cloudContent, version: 1 });
    }

    if (action === "decrypt") {
      const items = body?.items;
      if (!Array.isArray(items) || items.length < 1 || items.length > 100) {
        return json({ error: "A batch of 1 to 100 messages is required" }, 400);
      }
      const output = [];
      for (const item of items) {
        if (typeof item?.id !== "string" || !UUID.test(item.id) ||
            typeof item?.cloud_content !== "string") {
          return json({ error: "Invalid cloud history item" }, 400);
        }
        try {
          output.push({
            id: item.id,
            plaintext: await decrypt(key, conversationId, item.id, item.cloud_content),
          });
        } catch {
          // One damaged or obsolete archive entry must not block the rest of a page.
          output.push({ id: item.id, error: "decrypt_failed" });
        }
      }
      return json({ items: output, version: 1 });
    }

    return json({ error: "Unsupported action" }, 400);
  } catch (error) {
    // Never log message plaintext, envelopes, access tokens, or encryption keys.
    console.error("cloud-history:", error instanceof Error ? error.message : "unknown error");
    return json({ error: "Cloud history operation failed" }, 400);
  }
});
