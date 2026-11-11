import { AccessToken } from "npm:livekit-server-sdk@2.19.2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

function json(status: number, body: Record<string, unknown>) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

function getNamedKey(raw: string | undefined, legacy: string | undefined): string | null {
  if (raw) {
    try {
      const parsed = JSON.parse(raw);
      if (typeof parsed.default === "string" && parsed.default.length > 0) return parsed.default;
      const first = Object.values(parsed).find((value) => typeof value === "string" && value.length > 0);
      if (typeof first === "string") return first;
    } catch {
      // Fall back to the legacy single-value environment variable.
    }
  }
  return legacy && legacy.length > 0 ? legacy : null;
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return json(405, { error: "method_not_allowed" });

  try {
    const authorization = req.headers.get("Authorization") ?? "";
    const bearer = authorization.match(/^Bearer\s+(.+)$/i)?.[1];
    if (!bearer) return json(401, { error: "unauthorized" });

    const supabaseUrl = Deno.env.get("SUPABASE_URL");
    const publishableKey = getNamedKey(
      Deno.env.get("SUPABASE_PUBLISHABLE_KEYS"),
      Deno.env.get("SUPABASE_ANON_KEY"),
    );
    const secretKey = getNamedKey(
      Deno.env.get("SUPABASE_SECRET_KEYS"),
      Deno.env.get("SUPABASE_SERVICE_ROLE_KEY"),
    );
    const livekitUrl = Deno.env.get("LIVEKIT_URL");
    const livekitApiKey = Deno.env.get("LIVEKIT_API_KEY");
    const livekitApiSecret = Deno.env.get("LIVEKIT_API_SECRET");

    if (!supabaseUrl || !publishableKey || !secretKey) {
      console.error("Supabase function environment is incomplete");
      return json(500, { error: "server_configuration_error" });
    }
    if (!livekitUrl || !livekitApiKey || !livekitApiSecret) {
      console.error("LiveKit function secrets are incomplete");
      return json(503, { error: "livekit_not_configured" });
    }
    if (!/^wss:\/\//i.test(livekitUrl)) return json(500, { error: "invalid_livekit_url" });

    const authResponse = await fetch(`${supabaseUrl}/auth/v1/user`, {
      headers: { apikey: publishableKey, Authorization: `Bearer ${bearer}` },
    });
    if (!authResponse.ok) return json(401, { error: "unauthorized" });
    const user = await authResponse.json();
    const userId = user?.id;
    const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
    if (typeof userId !== "string" || !uuidPattern.test(userId)) return json(401, { error: "unauthorized" });

    let body: { conversation_id?: unknown; peer_user_id?: unknown };
    try {
      body = await req.json();
    } catch {
      return json(400, { error: "invalid_json" });
    }
    const conversationId = body.conversation_id;
    const peerUserId = body.peer_user_id;
    if (typeof conversationId !== "string" || !uuidPattern.test(conversationId) ||
        typeof peerUserId !== "string" || !uuidPattern.test(peerUserId) ||
        peerUserId === userId) {
      return json(400, { error: "invalid_call_participants" });
    }

    const dbHeaders = { apikey: secretKey, Authorization: `Bearer ${secretKey}` };
    const conversationUrl = new URL(`${supabaseUrl}/rest/v1/conversations`);
    conversationUrl.searchParams.set("select", "id,kind");
    conversationUrl.searchParams.set("id", `eq.${conversationId}`);
    conversationUrl.searchParams.set("kind", "eq.direct");
    const conversationResponse = await fetch(conversationUrl, { headers: dbHeaders });
    if (!conversationResponse.ok) {
      console.error("Could not verify conversation");
      return json(503, { error: "authorization_check_failed" });
    }
    const conversations = await conversationResponse.json();
    if (!Array.isArray(conversations) || conversations.length !== 1) {
      return json(403, { error: "direct_conversation_required" });
    }

    const membersUrl = new URL(`${supabaseUrl}/rest/v1/conversation_members`);
    membersUrl.searchParams.set("select", "user_id");
    membersUrl.searchParams.set("conversation_id", `eq.${conversationId}`);
    membersUrl.searchParams.set("user_id", `in.(${userId},${peerUserId})`);
    const membersResponse = await fetch(membersUrl, { headers: dbHeaders });
    if (!membersResponse.ok) {
      console.error("Could not verify conversation membership");
      return json(503, { error: "authorization_check_failed" });
    }
    const members = await membersResponse.json();
    const memberIds = new Set(Array.isArray(members) ? members.map((row: { user_id?: string }) => row.user_id) : []);
    if (!memberIds.has(userId) || !memberIds.has(peerUserId)) {
      return json(403, { error: "both_participants_must_belong_to_conversation" });
    }

    const roomName = `nx_${conversationId.replaceAll("-", "")}`;
    const accessToken = new AccessToken(livekitApiKey, livekitApiSecret, {
      identity: userId,
      ttl: 600,
    });
    accessToken.addGrant({
      roomJoin: true,
      room: roomName,
      canPublish: true,
      canSubscribe: true,
      canPublishData: true,
    });
    const token = await accessToken.toJwt();

    return json(200, {
      token,
      server_url: livekitUrl,
      room_name: roomName,
      identity: userId,
      expires_in: 600,
    });
  } catch (error) {
    console.error("livekit-token failed", error instanceof Error ? error.message : "unknown_error");
    return json(500, { error: "internal_error" });
  }
});
