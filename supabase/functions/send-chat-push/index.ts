import "jsr:@supabase/functions-js/edge-runtime.d.ts";

const cors = { "Content-Type": "application/json", "Access-Control-Allow-Origin": "https://nikolasconnor.github.io", "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type", "Access-Control-Allow-Methods": "POST, OPTIONS", "Vary": "Origin" };
const enc = (value: string) => btoa(value).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
const pemBytes = (pem: string) => {
  const raw = pem.replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g, "");
  return Uint8Array.from(atob(raw), c => c.charCodeAt(0));
};


const fromB64Url = (v:string) => Uint8Array.from(atob(v.replaceAll("-","+").replaceAll("_","/")+"=".repeat((4-v.length%4)%4)),c=>c.charCodeAt(0));
const toB64Url = (v:Uint8Array) => { let b=""; for(const x of v)b+=String.fromCharCode(x); return btoa(b).replaceAll("+","-").replaceAll("/","_").replaceAll("=",""); };
const joinBytes = (...parts:Uint8Array[]) => { const out=new Uint8Array(parts.reduce((n,p)=>n+p.length,0)); let i=0; for(const p of parts){out.set(p,i);i+=p.length;} return out; };
const textBytes=(v:string)=>new TextEncoder().encode(v);
async function hmac(keyBytes:Uint8Array,data:Uint8Array){const k=await crypto.subtle.importKey("raw",keyBytes,{name:"HMAC",hash:"SHA-256"},false,["sign"]);return new Uint8Array(await crypto.subtle.sign("HMAC",k,data));}
async function hkdfExpand(prk:Uint8Array,info:Uint8Array,length:number){let prev=new Uint8Array(0),out=new Uint8Array(0);for(let i=1;out.length<length;i++){prev=await hmac(prk,joinBytes(prev,info,new Uint8Array([i])));out=joinBytes(out,prev);}return out.slice(0,length);}
async function webPushAuth(endpoint:string){
 const pub=Deno.env.get("WEB_PUSH_VAPID_PUBLIC_KEY")||"",priv=Deno.env.get("WEB_PUSH_VAPID_PRIVATE_KEY")||"";
 if(!pub||!priv)throw new Error("Web Push VAPID keys are not configured");
 const p=fromB64Url(pub),d=fromB64Url(priv);if(p.length!==65||d.length!==32||p[0]!==4)throw new Error("Invalid VAPID key");
 const jwk={kty:"EC",crv:"P-256",x:toB64Url(p.slice(1,33)),y:toB64Url(p.slice(33)),d:toB64Url(d),ext:true,key_ops:["sign"]};
 const key=await crypto.subtle.importKey("jwk",jwk,{name:"ECDSA",namedCurve:"P-256"},false,["sign"]);
 const now=Math.floor(Date.now()/1000),head=enc(JSON.stringify({typ:"JWT",alg:"ES256"}));
 const claims=enc(JSON.stringify({aud:new URL(endpoint).origin,exp:now+43200,sub:Deno.env.get("WEB_PUSH_VAPID_SUBJECT")||"https://nikolasconnor.github.io/nexura-messenger/"}));
 const unsigned=head+"."+claims,signature=new Uint8Array(await crypto.subtle.sign({name:"ECDSA",hash:"SHA-256"},key,textBytes(unsigned)));
 return "vapid t="+unsigned+"."+toB64Url(signature)+", k="+pub;
}
async function encryptWebPush(sub:any,payload:string){
 const ua=fromB64Url(sub.p256dh),auth=fromB64Url(sub.auth);
 if(ua.length!==65||ua[0]!==4||auth.length<16)throw new Error("Invalid subscription keys");
 const uaKey=await crypto.subtle.importKey("raw",ua,{name:"ECDH",namedCurve:"P-256"},false,[]);
 const pair=await crypto.subtle.generateKey({name:"ECDH",namedCurve:"P-256"},true,["deriveBits"]);
 const as=new Uint8Array(await crypto.subtle.exportKey("raw",pair.publicKey));
 const shared=new Uint8Array(await crypto.subtle.deriveBits({name:"ECDH",public:uaKey},pair.privateKey,256));
 const prkAuth=await hmac(auth,shared);
 const ikm=await hkdfExpand(prkAuth,joinBytes(textBytes("WebPush: info\0"),ua,as),32);
 const salt=crypto.getRandomValues(new Uint8Array(16)),prk=await hmac(salt,ikm);
 const cek=await hkdfExpand(prk,textBytes("Content-Encoding: aes128gcm\0"),16);
 const nonce=await hkdfExpand(prk,textBytes("Content-Encoding: nonce\0"),12);
 const aes=await crypto.subtle.importKey("raw",cek,{name:"AES-GCM"},false,["encrypt"]);
 const cipher=new Uint8Array(await crypto.subtle.encrypt({name:"AES-GCM",iv:nonce},aes,joinBytes(textBytes(payload),new Uint8Array([2]))));
 const head=new Uint8Array(86);head.set(salt);new DataView(head.buffer).setUint32(16,4096,false);head[20]=65;head.set(as,21);
 return joinBytes(head,cipher);
}
async function sendWebPush(sub:any,payload:Record<string,string>){
 const auth=await webPushAuth(sub.endpoint),body=await encryptWebPush(sub,JSON.stringify(payload));
 const response=await fetch(sub.endpoint,{method:"POST",headers:{Authorization:auth,"Content-Type":"application/octet-stream","Content-Encoding":"aes128gcm",TTL:"86400",Urgency:"high"},body});
 if(response.ok)return {ok:true,stale:false};
 await response.text();return {ok:false,stale:response.status===404||response.status===410};
}

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
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return new Response("Method not allowed", { status: 405, headers: cors });
  try {
    const url = Deno.env.get("SUPABASE_URL")!;
    const anon = Deno.env.get("SUPABASE_ANON_KEY")!;
    const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
    const serviceJson = Deno.env.get("FIREBASE_SERVICE_ACCOUNT_JSON");
    const vapidReady = !!(Deno.env.get("WEB_PUSH_VAPID_PUBLIC_KEY") && Deno.env.get("WEB_PUSH_VAPID_PRIVATE_KEY"));
    if (!serviceJson && !vapidReady) {
      return Response.json({ error: "No push delivery provider is configured" }, { status: 503, headers: cors });
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
    const [tokensResponse, prefsResponse, conversationResponse, senderProfileResponse, webPushResponse] = await Promise.all([
      fetch(url + "/rest/v1/device_tokens?select=user_id,token&user_id=in.(" + inList + ")", { headers }),
      fetch(url + "/rest/v1/notification_preferences?select=user_id,push_enabled&user_id=in.(" + inList + ")", { headers }),
      fetch(url + "/rest/v1/conversations?select=kind,title&id=eq." + encodeURIComponent(conversationId) + "&limit=1", { headers }),
      fetch(url + "/rest/v1/profiles?select=display_name,username&id=eq." + encodeURIComponent(user.id) + "&limit=1", { headers }),
      fetch(url + "/rest/v1/web_push_subscriptions?select=user_id,endpoint,p256dh,auth&user_id=in.(" + inList + ")", { headers })
    ]);
    if (!tokensResponse.ok || !prefsResponse.ok || !conversationResponse.ok || !senderProfileResponse.ok || !webPushResponse.ok) throw new Error("Unable to load notification recipients");
    const tokens = await tokensResponse.json(), prefs = await prefsResponse.json();
    const conversationRows = await conversationResponse.json(), senderProfiles = await senderProfileResponse.json();
    const webSubscriptions = await webPushResponse.json();
    const disabledUsers = new Set(
      prefs.filter((item: any) => item.push_enabled === false).map((item: any) => item.user_id)
    );
    const enabledTokens = tokens.filter((item: any) => !disabledUsers.has(item.user_id));
    const enabledWebSubscriptions = webSubscriptions.filter((item:any) => !disabledUsers.has(item.user_id));
    const disabled = new Set(recipientIds.filter((id: string) => disabledUsers.has(id))).size;
    if (!enabledTokens.length && !enabledWebSubscriptions.length) return Response.json({
      sent:0,targeted:0,muted,disabled,failed:0,no_device_tokens:tokens.length===0,
      no_web_push_subscriptions:webSubscriptions.length===0
    },{headers:cors});

    const conversation = conversationRows[0] ?? {};
    const sender = senderProfiles[0] ?? {};
    const senderName = (typeof sender.display_name === "string" && sender.display_name.trim()
      ? sender.display_name.trim()
      : typeof sender.username === "string" && sender.username.trim()
        ? "@" + sender.username.trim()
        : "Пользователь").slice(0, 80);
    const isGroup = conversation.kind === "group";
    const title = isGroup && typeof conversation.title === "string" && conversation.title.trim()
      ? conversation.title.trim().slice(0, 80)
      : senderName;
    const data: Record<string, string> = {
      type: "chat_message",
      title,
      sender_name: senderName,
      conversation_kind: isGroup ? "group" : "direct",
      body: isGroup ? senderName + ": Новое сообщение" : "Новое сообщение",
      conversation_id: conversationId
    };
    if (typeof messageId === "string") data.message_id = messageId;

    const service = serviceJson ? JSON.parse(serviceJson) : null;
    const fcmResults = service && enabledTokens.length ? await (async()=>{
      const accessToken=await firebaseAccessToken(service);
      return await Promise.all(enabledTokens.map((item:any)=>sendToFcm(item.token,service,accessToken,data)));
    })() : [];
    const webResults=vapidReady?await Promise.all(enabledWebSubscriptions.map(async(item:any)=>{
      try{return await sendWebPush(item,{title,body:data.body,conversation_id:conversationId,message_id:typeof messageId==="string"?messageId:""});}
      catch(error){console.error("Web Push delivery failed:",error);return {ok:false,stale:false};}
    })):[];
    const accepted=fcmResults.filter(x=>x.ok).length,failed=fcmResults.filter(x=>!x.ok).length;
    const retryableFailed=fcmResults.filter(x=>x.retryable).length;
    const staleTokens=enabledTokens.filter((_:any,i:number)=>fcmResults[i].stale).map((x:any)=>x.token);
    if(staleTokens.length){const list=staleTokens.map((x:string)=>encodeURIComponent(x)).join(",");await fetch(url+"/rest/v1/device_tokens?token=in.("+list+")",{method:"DELETE",headers});}
    const staleWeb=enabledWebSubscriptions.filter((_:any,i:number)=>webResults[i]?.stale).map((x:any)=>x.endpoint);
    if(staleWeb.length){const list=staleWeb.map((x:string)=>encodeURIComponent(x)).join(",");await fetch(url+"/rest/v1/web_push_subscriptions?endpoint=in.("+list+")",{method:"DELETE",headers});}
    const webAccepted=webResults.filter(x=>x.ok).length;
    return Response.json({
      sent:accepted+webAccepted,targeted:enabledTokens.length+enabledWebSubscriptions.length,
      accepted_by_fcm:accepted,accepted_by_web_push:webAccepted,
      failed:failed+webResults.filter(x=>!x.ok&&!x.stale).length,retryable_failed:retryableFailed,
      muted,disabled,stale:staleTokens.length+staleWeb.length,
      no_device_tokens:tokens.length===0,no_web_push_subscriptions:webSubscriptions.length===0,
      web_push_configured:vapidReady
    },{headers:cors});
  } catch (error) {
    console.error("send-chat-push:", error);
    return Response.json({ error: "Push delivery failed" }, { status: 500, headers: cors });
  }
});
