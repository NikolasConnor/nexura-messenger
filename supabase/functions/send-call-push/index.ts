import "jsr:@supabase/functions-js/edge-runtime.d.ts";

const cors = {
  "Content-Type": "application/json",
  "Access-Control-Allow-Origin": "https://nikolasconnor.github.io",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Vary": "Origin"
};
const enc = (value: string) => btoa(value).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
const pemBytes = (pem: string) => Uint8Array.from(atob(pem.replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g, "")), c => c.charCodeAt(0));

const fromB64Url = (v:string) => Uint8Array.from(atob(v.replaceAll("-","+").replaceAll("_","/")+"=".repeat((4-v.length%4)%4)),c=>c.charCodeAt(0));
const toB64Url = (v:Uint8Array) => { let b=""; for(const x of v)b+=String.fromCharCode(x); return btoa(b).replaceAll("+","-").replaceAll("/","_").replaceAll("=",""); };
const joinBytes = (...parts:Uint8Array[]) => { const out=new Uint8Array(parts.reduce((n,p)=>n+p.length,0)); let i=0; for(const p of parts){out.set(p,i);i+=p.length;} return out; };
const textBytes=(v:string)=>new TextEncoder().encode(v);
const bytesBuffer=(value:Uint8Array):ArrayBuffer=>{const copy=new Uint8Array(value.length);copy.set(value);return copy.buffer;};
async function hmac(keyBytes:Uint8Array,data:Uint8Array){const k=await crypto.subtle.importKey("raw",bytesBuffer(keyBytes),{name:"HMAC",hash:"SHA-256"},false,["sign"]);return new Uint8Array(await crypto.subtle.sign("HMAC",k,bytesBuffer(data)));}
async function hkdfExpand(prk:Uint8Array,info:Uint8Array,length:number){let prev=new Uint8Array(0),out=new Uint8Array(0);for(let i=1;out.length<length;i++){prev=await hmac(prk,joinBytes(prev,info,new Uint8Array([i])));out=joinBytes(out,prev);}return out.slice(0,length);}
async function webPushAuth(endpoint:string){
 const pub=Deno.env.get("WEB_PUSH_VAPID_PUBLIC_KEY")||"",priv=Deno.env.get("WEB_PUSH_VAPID_PRIVATE_KEY")||"";
 if(!pub||!priv)throw new Error("Web Push VAPID keys are not configured");
 const p=fromB64Url(pub),d=fromB64Url(priv);if(p.length!==65||d.length!==32||p[0]!==4)throw new Error("Invalid VAPID key");
 const jwk={kty:"EC",crv:"P-256",x:toB64Url(p.slice(1,33)),y:toB64Url(p.slice(33)),d:toB64Url(d),ext:true,key_ops:["sign"]};
 const key=await crypto.subtle.importKey("jwk",jwk,{name:"ECDSA",namedCurve:"P-256"},false,["sign"]);
 const now=Math.floor(Date.now()/1000),head=enc(JSON.stringify({typ:"JWT",alg:"ES256"}));
 const claims=enc(JSON.stringify({aud:new URL(endpoint).origin,exp:now+43200,sub:Deno.env.get("WEB_PUSH_VAPID_SUBJECT")||"https://nikolasconnor.github.io/nexura-messenger/"}));
 const unsigned=head+"."+claims,signature=new Uint8Array(await crypto.subtle.sign({name:"ECDSA",hash:"SHA-256"},key,bytesBuffer(textBytes(unsigned))));
 return "vapid t="+unsigned+"."+toB64Url(signature)+", k="+pub;
}
async function encryptWebPush(sub:any,payload:string){
 const ua=fromB64Url(sub.p256dh),auth=fromB64Url(sub.auth);
 if(ua.length!==65||ua[0]!==4||auth.length<16)throw new Error("Invalid subscription keys");
 const uaKey=await crypto.subtle.importKey("raw",bytesBuffer(ua),{name:"ECDH",namedCurve:"P-256"},false,[]);
 const pair=await crypto.subtle.generateKey({name:"ECDH",namedCurve:"P-256"},true,["deriveBits"]);
 const as=new Uint8Array(await crypto.subtle.exportKey("raw",pair.publicKey));
 const shared=new Uint8Array(await crypto.subtle.deriveBits({name:"ECDH",public:uaKey},pair.privateKey,256));
 const prkAuth=await hmac(auth,shared);
 const ikm=await hkdfExpand(prkAuth,joinBytes(textBytes("WebPush: info\0"),ua,as),32);
 const salt=crypto.getRandomValues(new Uint8Array(16)),prk=await hmac(salt,ikm);
 const cek=await hkdfExpand(prk,textBytes("Content-Encoding: aes128gcm\0"),16);
 const nonce=await hkdfExpand(prk,textBytes("Content-Encoding: nonce\0"),12);
 const aes=await crypto.subtle.importKey("raw",bytesBuffer(cek),{name:"AES-GCM"},false,["encrypt"]);
 const cipher=new Uint8Array(await crypto.subtle.encrypt({name:"AES-GCM",iv:bytesBuffer(nonce)},aes,bytesBuffer(joinBytes(textBytes(payload),new Uint8Array([2])))));
 const head=new Uint8Array(86);head.set(salt);new DataView(head.buffer).setUint32(16,4096,false);head[20]=65;head.set(as,21);
 return joinBytes(head,cipher);
}
async function sendWebPush(sub:any,payload:Record<string,string>){
 const auth=await webPushAuth(sub.endpoint),body=await encryptWebPush(sub,JSON.stringify(payload));
 const response=await fetch(sub.endpoint,{method:"POST",headers:{Authorization:auth,"Content-Type":"application/octet-stream","Content-Encoding":"aes128gcm",TTL:"35",Urgency:"high"},body});
 if(response.ok)return {ok:true,stale:false};
 await response.text();return {ok:false,stale:response.status===404||response.status===410};
}


async function firebaseAccessToken(service: any): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  const unsigned = enc(JSON.stringify({ alg: "RS256", typ: "JWT" })) + "." + enc(JSON.stringify({
    iss: service.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600
  }));
  const key = await crypto.subtle.importKey("pkcs8", bytesBuffer(pemBytes(service.private_key)),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, bytesBuffer(new TextEncoder().encode(unsigned)));
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

async function sendToFcm(token: string, service: any, accessToken: string, data: Record<string, string>) {
  const response = await fetch("https://fcm.googleapis.com/v1/projects/" + service.project_id + "/messages:send", {
    method: "POST",
    headers: { Authorization: "Bearer " + accessToken, "Content-Type": "application/json" },
    body: JSON.stringify({ message: { token, data, android: { priority: "high", ttl: "35s" } } })
  });
  if (response.ok) return { ok: true, stale: false };
  let stale = false;
  try {
    const error = await response.json();
    const details = Array.isArray(error?.error?.details) ? error.error.details : [];
    const code = details.find((item: any) => item?.["@type"]?.includes("FcmError"))?.errorCode;
    stale = code === "UNREGISTERED" || code === "NOT_FOUND";
  } catch { /* Keep a non-stale failure. */ }
  return { ok: false, stale };
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return new Response("Method not allowed", { status: 405, headers: cors });
  try {
    const url = Deno.env.get("SUPABASE_URL")!;
    const anon = Deno.env.get("SUPABASE_ANON_KEY")!;
    const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
    const serviceJson = Deno.env.get("FIREBASE_SERVICE_ACCOUNT_JSON");
    const vapidReady = !!(Deno.env.get("WEB_PUSH_VAPID_PUBLIC_KEY") && Deno.env.get("WEB_PUSH_VAPID_PRIVATE_KEY"));
    if (!serviceJson && !vapidReady) return Response.json({ error: "No call push provider is configured" }, { status: 503, headers: cors });

    const authorization = req.headers.get("Authorization") ?? "";
    const auth = await fetch(url + "/auth/v1/user", { headers: { apikey: anon, Authorization: authorization } });
    if (!auth.ok) return Response.json({ error: "Unauthorized" }, { status: 401, headers: cors });
    const user = await auth.json();
    const body = await req.json();
    const conversationId = body.conversation_id;
    const recipientId = body.recipient_user_id;
    const callId = body.call_id;
    const sentAt = Number.isFinite(Number(body.sent_at)) ? Number(body.sent_at) : Date.now();
    const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
    if (![conversationId, recipientId, callId].every((x: unknown) => typeof x === "string" && uuid.test(x)) ||
        !Number.isFinite(sentAt) || sentAt <= 0 || Date.now() - sentAt > 35_000 || sentAt > Date.now() + 5_000) {
      return Response.json({ error: "Invalid or expired call payload" }, { status: 400, headers: cors });
    }
    if (user.id === recipientId) return Response.json({ error: "Invalid recipient" }, { status: 400, headers: cors });

    const headers = { apikey: serviceKey, Authorization: "Bearer " + serviceKey };
    const conversationResponse = await fetch(url + "/rest/v1/conversations?select=id,kind&id=eq." + encodeURIComponent(conversationId) + "&limit=1", { headers });
    if (!conversationResponse.ok) throw new Error("Unable to verify conversation");
    const conversations = await conversationResponse.json();
    if (!Array.isArray(conversations) || conversations.length !== 1 || conversations[0].kind !== "direct") {
      return Response.json({ error: "Direct conversation required" }, { status: 403, headers: cors });
    }
    const membersResponse = await fetch(url + "/rest/v1/conversation_members?select=user_id,muted_until&conversation_id=eq." +
      encodeURIComponent(conversationId), { headers });
    if (!membersResponse.ok) throw new Error("Unable to verify conversation membership");
    const members = await membersResponse.json();
    if (!members.some((x: any) => x.user_id === user.id) || !members.some((x: any) => x.user_id === recipientId)) {
      return Response.json({ error: "Caller and recipient must share this conversation" }, { status: 403, headers: cors });
    }
    const recipient = members.find((x: any) => x.user_id === recipientId);
    if (recipient?.muted_until && Date.parse(recipient.muted_until) > Date.now()) {
      return Response.json({ sent: 0, muted: 1, targeted: 0 }, { headers: cors });
    }

    const [tokensResponse, prefsResponse, webResponse, profileResponse] = await Promise.all([
      fetch(url + "/rest/v1/device_tokens?select=token&user_id=eq." + encodeURIComponent(recipientId), { headers }),
      fetch(url + "/rest/v1/notification_preferences?select=push_enabled&user_id=eq." + encodeURIComponent(recipientId) + "&limit=1", { headers }),
      fetch(url + "/rest/v1/web_push_subscriptions?select=endpoint,p256dh,auth&user_id=eq." + encodeURIComponent(recipientId), { headers }),
      fetch(url + "/rest/v1/profiles?select=display_name,username&id=eq." + encodeURIComponent(user.id) + "&limit=1", { headers })
    ]);
    if (!tokensResponse.ok || !prefsResponse.ok || !webResponse.ok || !profileResponse.ok) throw new Error("Unable to load call notification targets");
    const tokens = await tokensResponse.json();
    const prefs = await prefsResponse.json();
    const webSubscriptions = await webResponse.json();
    const profiles = await profileResponse.json();
    if (prefs[0]?.push_enabled === false) return Response.json({ sent: 0, disabled: 1, targeted: 0 }, { headers: cors });

    const caller = profiles[0] ?? {};
    const callerName = (caller.display_name || (caller.username ? "@" + caller.username : "Пользователь")).slice(0, 80);
    const video = body.video === true;
    const title = video ? "Входящий видеозвонок" : "Входящий звонок";
    const notificationBody = callerName + " звонит вам в Nexura";
    const data: Record<string, string> = {
      type: "incoming_call", title, body: notificationBody,
      conversation_id: conversationId, call_id: callId, caller_id: user.id,
      video: video ? "true" : "false", sent_at: String(sentAt)
    };

    const service = serviceJson ? JSON.parse(serviceJson) : null;
    const fcmResults = service && tokens.length
      ? await (async () => {
          const accessToken = await firebaseAccessToken(service);
          return await Promise.all(tokens.map((item: any) => sendToFcm(item.token, service, accessToken, data)));
        })()
      : [];
    const webResults = vapidReady ? await Promise.all(webSubscriptions.map(async (item: any) => {
      try { return await sendWebPush(item, data); }
      catch (error) { console.error("Call Web Push failed", error); return { ok: false, stale: false }; }
    })) : [];

    const staleTokens = tokens.filter((_: any, i: number) => fcmResults[i]?.stale).map((x: any) => x.token);
    if (staleTokens.length) {
      const list = staleTokens.map((x: string) => encodeURIComponent(x)).join(",");
      await fetch(url + "/rest/v1/device_tokens?token=in.(" + list + ")", { method: "DELETE", headers });
    }
    const staleWeb = webSubscriptions.filter((_: any, i: number) => webResults[i]?.stale).map((x: any) => x.endpoint);
    if (staleWeb.length) {
      const list = staleWeb.map((x: string) => encodeURIComponent(x)).join(",");
      await fetch(url + "/rest/v1/web_push_subscriptions?endpoint=in.(" + list + ")", { method: "DELETE", headers });
    }
    const acceptedFcm = fcmResults.filter((x: any) => x.ok).length;
    const acceptedWeb = webResults.filter((x: any) => x.ok).length;
    return Response.json({
      sent: acceptedFcm + acceptedWeb, targeted: tokens.length + webSubscriptions.length,
      accepted_by_fcm: acceptedFcm, accepted_by_web_push: acceptedWeb,
      failed: fcmResults.filter((x: any) => !x.ok && !x.stale).length + webResults.filter((x: any) => !x.ok && !x.stale).length,
      stale: staleTokens.length + staleWeb.length, web_push_configured: vapidReady
    }, { headers: cors });
  } catch (error) {
    console.error("send-call-push", error);
    return Response.json({ error: "Unable to send call notification" }, { status: 500, headers: cors });
  }
});
