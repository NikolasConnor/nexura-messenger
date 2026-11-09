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
  const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(unsigned));
  const assertion = unsigned + "." + enc(String.fromCharCode(...new Uint8Array(signature)));
  const response = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion })
  });
  const data = await response.json();
  if (!response.ok || !data.access_token) throw new Error("Firebase OAuth token request failed");
  return data.access_token;
}

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return new Response("Method not allowed", { status: 405 });
  try {
    const url = Deno.env.get("SUPABASE_URL")!;
    const anon = Deno.env.get("SUPABASE_ANON_KEY")!;
    const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
    const serviceJson = Deno.env.get("FIREBASE_SERVICE_ACCOUNT_JSON");
    if (!serviceJson) return Response.json({ error: "Firebase service account secret is not configured" }, { status: 503, headers: cors });

    const auth = req.headers.get("Authorization") ?? "";
    const userResponse = await fetch(url + "/auth/v1/user", { headers: { apikey: anon, Authorization: auth } });
    if (!userResponse.ok) return Response.json({ error: "Unauthorized" }, { status: 401, headers: cors });
    const user = await userResponse.json();
    const { conversation_id } = await req.json();
    if (typeof conversation_id !== "string") return Response.json({ error: "conversation_id is required" }, { status: 400, headers: cors });

    const headers = { apikey: serviceKey, Authorization: "Bearer " + serviceKey };
    const membersResponse = await fetch(url + "/rest/v1/conversation_members?select=user_id&conversation_id=eq." + encodeURIComponent(conversation_id), { headers });
    if (!membersResponse.ok) throw new Error("Unable to load conversation members");
    const members = await membersResponse.json();
    const ids = members.map((m: any) => m.user_id);
    if (!ids.includes(user.id)) return Response.json({ error: "Not a conversation member" }, { status: 403, headers: cors });
    const recipients = ids.filter((id: string) => id !== user.id);
    if (!recipients.length) return Response.json({ sent: 0 }, { headers: cors });

    const [tokensResponse] = await Promise.all([
      fetch(url + "/rest/v1/device_tokens?select=token&user_id=in.(" + recipients.join(",") + ")", { headers }),
    ]);
    const tokens = await tokensResponse.json();
    if (!tokens.length) return Response.json({ sent: 0 }, { headers: cors });

    const service = JSON.parse(serviceJson);
    const accessToken = await firebaseAccessToken(service);
    const title = "Nexura";
    let sent = 0;
    const stale: string[] = [];
    for (const item of tokens) {
      const response = await fetch("https://fcm.googleapis.com/v1/projects/" + service.project_id + "/messages:send", {
        method: "POST",
        headers: { Authorization: "Bearer " + accessToken, "Content-Type": "application/json" },
        body: JSON.stringify({ message: { token: item.token, data: { title, body: "Новое сообщение", conversation_id } , android: { priority: "high" } } })
      });
      if (response.ok) sent++;
      else {
        const failure = await response.text();
        if (failure.includes("UNREGISTERED") || failure.includes("INVALID_ARGUMENT")) stale.push(item.token);
      }
    }
    if (stale.length) {
      await fetch(url + "/rest/v1/device_tokens?token=in.(" + stale.map(encodeURIComponent).join(",") + ")", { method: "DELETE", headers });
    }
    return Response.json({ sent, stale: stale.length }, { headers: cors });
  } catch (error) {
    console.error("send-chat-push:", error);
    return Response.json({ error: "Push delivery failed" }, { status: 500, headers: cors });
  }
});
