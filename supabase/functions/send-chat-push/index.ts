import "jsr:@supabase/functions-js/edge-runtime.d.ts";

const cors = { "Content-Type": "application/json" };
const enc = (value: string) => btoa(value).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
const pemBytes = (pem: string) => {
  const raw = pem.replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g, "");
  return Uint8Array.from(atob(raw), c => c.charCodeAt(0));
};

async function firebaseAccessToken(service: any): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  const header = enc(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claim = enc(JSON.stringify({
    iss: service.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600
  }));
  const unsigned = header + "." + claim;
  const key = await crypto.subtle.importKey(
    "pkcs8", pemBytes(service.private_key),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]
  );
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(unsigned)
  );
  const assertion = unsigned + "." + enc(String.fromCharCode(...new Uint8Array(signature)));
  const response = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion
    })
  });
  const data = await response.json();
  if (!response.ok || !data.access_token) throw new Error("Firebase OAuth token request failed");
  return data.access_token;
}

function isTransient(status: number): boolean {
  return status === 429 || status === 500 || status === 502 || status === 503 || status === 504;
}

async function sendToFcm(token: string, service: any, accessToken: string, data: Record<string, string>) {
  let lastFailure = "";
  for (let attempt = 0; attempt < 3; attempt++) {
    const response = await fetch(
      "https://fcm.googleapis.com/v1/projects/" + service.project_id + "/messages:send",
      {
        method: "POST",
        headers: { Authorization: "Bearer " + accessToken, "Content-Type": "application/json" },
        body: JSON.stringify({
          message: {
            token,
            data,
            android: { priority: "high", ttl: "604800s" }
          }
        })
      }
    );
    if (response.ok) return { ok: true, stale: false, retryable: false, failure: "" };
    const raw = await response.text();
    let fcmCode = "";
    try {
      const body = JSON.parse(raw);
      const details = Array.isArray(body?.error?.details) ? body.error.details : [];
      fcmCode = details.find((item: any) =>
        item?.["@type"]?.includes("FcmError")
      )?.errorCode ?? body?.error?.status ?? "";
    } catch { /* Use the HTTP class below. */ }
    lastFailure = fcmCode || "FCM_HTTP_" + response.status;
    if (fcmCode === "UNREGISTERED" || fcmCode === "NOT_FOUND") {
      return { ok: false, stale: true, retryable: false, failure: lastFailure };
    }
    if (!isTransient(response.status) || attempt === 2) {
      return { ok: false, stale: false, retryable: isTransient(response.status), failure: lastFailure };
    }
    await new Promise(resolve => setTimeout(resolve, 350 * (attempt + 1)));
  }
  return { ok: false, stale: false, retryable: false, failure: lastFailure || "FCM_UNKNOWN" };
}

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return new Response("Method not allowed", { status: 405 });
  try {
    const url = Deno.env.get("SUPABASE_URL")!;
    const anon = Deno.env.get("SUPABASE_ANON_KEY")!;
    const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
    const serviceJson = Deno.env.get("FIREBASE_SERVICE_ACCOUNT_JSON");
    if (!serviceJson) {
      return Response.json({ error: "Firebase service account secret is not configured" }, { status: 503, headers: cors });
    }

    const auth = req.headers.get("Authorization") ?? "";
    const userResponse = await fetch(url + "/auth/v1/user", {
      headers: { apikey: anon, Authorization: auth }
    });
    if (!userResponse.ok) return Response.json({ error: "Unauthorized" }, { status: 401, headers: cors });
    const user = await userResponse.json();
    const body = await req.json();
    const conversationId = body?.conversation_id;
    const messageId = body?.message_id;
    const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
    if (typeof conversationId !== "string" || !uuidPattern.test(conversationId) ||
        (messageId != null && (typeof messageId !== "string" || !uuidPattern.test(messageId)))) {
      return Response.json({ error: "A valid conversation_id UUID and optional message_id UUID are required" }, { status: 400, headers: cors });
    }

    const headers = { apikey: serviceKey, Authorization: "Bearer " + serviceKey };
    // New clients pass a stable message ID so retries are idempotent and ownership is checked.
    // Keep the field optional for older installed Nexura builds that still call this endpoint.
    if (typeof messageId === "string") {
      const messageResponse = await fetch(
        url + "/rest/v1/messages?select=id,sender_id&conversation_id=eq." +
          encodeURIComponent(conversationId) + "&id=eq." + encodeURIComponent(messageId) + "&limit=1",
        { headers }
      );
      if (!messageResponse.ok) throw new Error("Unable to verify persisted message");
      const persistedMessages = await messageResponse.json();
      if (persistedMessages.length !== 1) {
        return Response.json({ error: "Message not found" }, { status: 404, headers: cors });
      }
      if (persistedMessages[0].sender_id !== user.id) {
        return Response.json({ error: "Only the message sender can request its notification" }, { status: 403, headers: cors });
      }
    }
    const membersResponse = await fetch(
      url + "/rest/v1/conversation_members?select=user_id,muted_until&conversation_id=eq." + encodeURIComponent(conversationId),
      { headers }
    );
    if (!membersResponse.ok) throw new Error("Unable to load conversation members");
    const members = await membersResponse.json();
    if (!members.some((member: any) => member.user_id === user.id)) {
      return Response.json({ error: "Not a conversation member" }, { status: 403, headers: cors });
    }

    const now = Date.now();
    const recipients = members.filter((member: any) =>
      member.user_id !== user.id &&
      (!member.muted_until || Date.parse(member.muted_until) <= now)
    );
    const muted = members.filter((member: any) =>
      member.user_id !== user.id && member.muted_until && Date.parse(member.muted_until) > now
    ).length;
    if (!recipients.length) {
      return Response.json({ sent: 0, targeted: 0, muted, disabled: 0, failed: 0 }, { headers: cors });
    }

    const recipientIds = recipients.map((member: any) => member.user_id);
    const inList = recipientIds.join(",");
    const [tokensResponse, prefsResponse, conversationResponse] = await Promise.all([
      fetch(url + "/rest/v1/device_tokens?select=user_id,token&user_id=in.(" + inList + ")", { headers }),
      fetch(url + "/rest/v1/notification_preferences?select=user_id,push_enabled&user_id=in.(" + inList + ")", { headers }),
      fetch(url + "/rest/v1/conversations?select=kind,title&id=eq." + encodeURIComponent(conversationId) + "&limit=1", { headers })
    ]);
    if (!tokensResponse.ok || !prefsResponse.ok || !conversationResponse.ok) {
      throw new Error("Unable to load notification recipients");
    }
    const tokens = await tokensResponse.json();
    const prefs = await prefsResponse.json();
    const conversationRows = await conversationResponse.json();
    const disabledUsers = new Set(
      prefs.filter((item: any) => item.push_enabled === false).map((item: any) => item.user_id)
    );
    const enabledTokens = tokens.filter((item: any) => !disabledUsers.has(item.user_id));
    const disabled = new Set(recipientIds.filter((id: string) => disabledUsers.has(id))).size;
    if (!enabledTokens.length) {
      return Response.json({
        sent: 0, targeted: 0, muted, disabled, failed: 0,
        no_device_tokens: tokens.length === 0
      }, { headers: cors });
    }

    const conversation = conversationRows[0] ?? {};
    const title = conversation.kind === "group" && typeof conversation.title === "string" && conversation.title.trim()
      ? conversation.title.trim().slice(0, 80)
      : "Nexura";
    const data: Record<string, string> = {
      type: "chat_message",
      title,
      body: "Новое сообщение",
      conversation_id: conversationId
    };
    if (typeof messageId === "string") data.message_id = messageId;

    const service = JSON.parse(serviceJson);
    const accessToken = await firebaseAccessToken(service);
    const results = await Promise.all(enabledTokens.map((item: any) =>
      sendToFcm(item.token, service, accessToken, data)
    ));
    const accepted = results.filter(result => result.ok).length;
    const failed = results.filter(result => !result.ok).length;
    const retryableFailed = results.filter(result => result.retryable).length;
    const staleTokens = enabledTokens
      .filter((_: any, index: number) => results[index].stale)
      .map((item: any) => item.token);

    if (staleTokens.length) {
      const staleList = staleTokens.map((token: string) => encodeURIComponent(token)).join(",");
      await fetch(url + "/rest/v1/device_tokens?token=in.(" + staleList + ")", {
        method: "DELETE", headers
      });
    }

    const failureClasses: Record<string, number> = {};
    for (const result of results) {
      if (!result.ok) failureClasses[result.failure] = (failureClasses[result.failure] ?? 0) + 1;
    }
    return Response.json({
      sent: accepted,
      targeted: enabledTokens.length,
      accepted_by_fcm: accepted,
      failed,
      retryable_failed: retryableFailed,
      muted,
      disabled,
      stale: staleTokens.length,
      no_device_tokens: tokens.length === 0,
      failure_classes: failureClasses
    }, { headers: cors });
  } catch (error) {
    console.error("send-chat-push:", error);
    return Response.json({ error: "Push delivery failed" }, { status: 500, headers: cors });
  }
});
