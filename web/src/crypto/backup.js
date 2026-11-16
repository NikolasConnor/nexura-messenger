export function createRecoveryCode() {
  const bytes=crypto.getRandomValues(new Uint8Array(32));
  let binary='';
  for(const byte of bytes)binary+=String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'');
}

function decodeRecoveryCode(code) {
  const clean=String(code||'').trim().replace(/-/g,'+').replace(/_/g,'/');
  if(!/^[A-Za-z0-9+/]{43,44}$/.test(clean))throw new Error('Код восстановления должен содержать 43 символа');
  const binary=atob(clean.padEnd(Math.ceil(clean.length/4)*4,'='));
  const bytes=Uint8Array.from(binary,char=>char.charCodeAt(0));
  if(bytes.length!==32)throw new Error('Неверный код восстановления');
  return bytes;
}

function aad(userId) {
  return new TextEncoder().encode('nexura-web-e2ee-backup-v1|'+userId);
}

export async function encryptBackup(userId,serialized,recoveryCode) {
  const raw=decodeRecoveryCode(recoveryCode);
  const key=await crypto.subtle.importKey('raw',raw,{name:'AES-GCM'},false,['encrypt']);
  const iv=crypto.getRandomValues(new Uint8Array(12));
  const ciphertext=await crypto.subtle.encrypt({name:'AES-GCM',iv,additionalData:aad(userId),tagLength:128},key,new TextEncoder().encode(serialized));
  const encode=bytes=>{let binary='';for(const byte of bytes)binary+=String.fromCharCode(byte);return btoa(binary);};
  return JSON.stringify({version:1,algorithm:'AES-256-GCM',iv:encode(iv),ciphertext:encode(new Uint8Array(ciphertext))});
}

export async function decryptBackup(userId,envelope,recoveryCode) {
  let parsed;
  try{parsed=JSON.parse(envelope);}catch{throw new Error('Облачная копия повреждена');}
  if(parsed?.version!==1||parsed.algorithm!=='AES-256-GCM'||typeof parsed.iv!=='string'||typeof parsed.ciphertext!=='string')throw new Error('Неподдерживаемый формат резервной копии');
  const raw=decodeRecoveryCode(recoveryCode);
  const key=await crypto.subtle.importKey('raw',raw,{name:'AES-GCM'},false,['decrypt']);
  const decode=value=>Uint8Array.from(atob(value),char=>char.charCodeAt(0));
  try{
    const plaintext=await crypto.subtle.decrypt({name:'AES-GCM',iv:decode(parsed.iv),additionalData:aad(userId),tagLength:128},key,decode(parsed.ciphertext));
    return new TextDecoder().decode(plaintext);
  }catch{throw new Error('Код восстановления неверен или резервная копия повреждена');}
}
