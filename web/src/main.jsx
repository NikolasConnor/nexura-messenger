import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { loadEncryptedRecord } from './crypto/secure-store.js';
import { createPortal } from 'react-dom';
import { createClient } from '@supabase/supabase-js';
import { MessageCircle, Search, Settings, LogOut, Send, Plus, Palette, ShieldCheck, LockKeyhole, Users, ArrowLeft, RefreshCw, UserRound, Mail, Eye, EyeOff, CheckCheck, Copy, Reply, MoreHorizontal, SmilePlus, Pencil, Trash2, Forward, Paperclip, Mic, Square, Play, Pause, Camera, Image as ImageIcon, Video, Heart, MessageSquare, ChevronLeft, X, UserRoundSearch } from 'lucide-react';
import './styles.css';
import { initializeOlm } from './crypto/olm.js';
import { loadEncryptedRecord } from './crypto/secure-store.js';
import { loadMessageReactions, toggleMessageReaction, markConversationRead, updateMyProfile, deleteMessage, editEncryptedDirectMessage } from './api/messenger.js';
import { ensureBrowserE2eeDevice, sendBrowserDirectMessage, sendBrowserGroupMessage, decryptBrowserGroupMessage } from './crypto/transport.js';
import { encryptAndUploadMedia, downloadAndDecryptMedia } from './crypto/media.js';

const SUPABASE_URL = 'https://lyhzawblxcvpgpjrkspc.supabase.co';
const SUPABASE_KEY = 'sb_publishable_G-EJKck-KO2cgoxaMlg9ag_CQZvDUEY';
const supabase = createClient(SUPABASE_URL, SUPABASE_KEY, {
  auth: { persistSession: true, autoRefreshToken: true, detectSessionInUrl: true },
  realtime: { params: { eventsPerSecond: 5 } }
});

async function searchProfiles(query, currentUserId) {
  const clean = query.trim();
  if (clean.length < 2) return [];
  const columns = 'id,display_name,username,avatar_url';
  const [byName, byUsername] = await Promise.all([
    supabase.from('profiles').select(columns).ilike('display_name', '%' + clean + '%').neq('id', currentUserId).limit(20),
    supabase.from('profiles').select(columns).ilike('username', '%' + clean + '%').neq('id', currentUserId).limit(20)
  ]);
  if (byName.error) throw byName.error;
  if (byUsername.error) throw byUsername.error;
  const unique = new Map();
  for (const person of [...(byName.data || []), ...(byUsername.data || [])]) {
    if (person.id) unique.set(person.id, {
      id: person.id,
      title: person.display_name || person.username || 'Пользователь',
      username: person.username || '',
      avatar: person.avatar_url || '🙂'
    });
  }
  return [...unique.values()].slice(0, 30);
}

function initials(name = '?') {
  return name.trim().split(/\s+/).slice(0, 2).map(x => x[0]?.toUpperCase()).join('') || '?';
}
function timeLabel(value) {
  if (!value) return '';
  return new Intl.DateTimeFormat('ru-RU', { hour: '2-digit', minute: '2-digit' }).format(new Date(value));
}
function dateLabel(value) {
  if (!value) return '';
  const date = new Date(value), today = new Date(), yesterday = new Date();
  yesterday.setDate(today.getDate() - 1);
  const same = (a,b) => a.getDate()===b.getDate() && a.getMonth()===b.getMonth() && a.getFullYear()===b.getFullYear();
  if (same(date,today)) return 'Сегодня';
  if (same(date,yesterday)) return 'Вчера';
  return new Intl.DateTimeFormat('ru-RU',{day:'numeric',month:'long',year:date.getFullYear()===today.getFullYear()?undefined:'numeric'}).format(date);
}
function Avatar({ value, name, className = '' }) {
  const src = typeof value === 'string' && value.startsWith('http') ? value : '';
  return <div className={'avatar '+className}>{src ? <img src={src} alt="" loading="lazy"/> : value ? value : initials(name || '?')}</div>;
}
function isEncrypted(content = '') {
  const value = String(content).trim();
  return value.startsWith('{') && (value.includes('"ciphertext"') || value.includes('"protocol"') || value.includes('"nexura"') || value.includes('"kind"'));
}


function parseMediaPayload(content) {
  try { const value=JSON.parse(content); return value && value.schema==='nexura-media-v1'?value:null; } catch { return null; }
}
function formatDuration(seconds) {
  if (!Number.isFinite(seconds)) return '0:00';
  const value=Math.max(0,Math.floor(seconds));
  return Math.floor(value/60)+':'+String(value%60).padStart(2,'0');
}
const sharedVoiceAudio = typeof Audio !== 'undefined' ? new Audio() : null;
let sharedVoiceUrl = '';
const browserVoiceUrls = new Set();
let sharedVoiceMeta = { title:'', subtitle:'', playing:false, current:0, duration:0, speed:1, active:false };
function publishVoiceState(patch={}) { sharedVoiceMeta={...sharedVoiceMeta,...patch}; if(typeof window!=='undefined') window.dispatchEvent(new CustomEvent('nexura:voice-state',{detail:sharedVoiceMeta})); }
function MediaMessage({ payload, supabase, chatTitle }) {
 const [url,setUrl]=useState(''),[error,setError]=useState(''),[busy,setBusy]=useState(true),[playerState,setPlayerState]=useState(sharedVoiceMeta),[speed,setSpeed]=useState(1);
 useEffect(()=>{let alive=true,objectUrl='';downloadAndDecryptMedia(supabase,payload).then(blob=>{if(!alive)return;objectUrl=URL.createObjectURL(blob);browserVoiceUrls.add(objectUrl);setUrl(objectUrl);}).catch(e=>{if(alive)setError(e?.message||'Не удалось открыть файл');}).finally(()=>{if(alive)setBusy(false);});return()=>{alive=false;if(objectUrl){browserVoiceUrls.delete(objectUrl);if(objectUrl!==sharedVoiceUrl)URL.revokeObjectURL(objectUrl);}};},[payload.path,supabase]);
 useEffect(()=>{const sync=e=>setPlayerState(e.detail||sharedVoiceMeta);window.addEventListener('nexura:voice-state',sync);return()=>window.removeEventListener('nexura:voice-state',sync);},[]);
 const isCurrent=!!url&&sharedVoiceUrl===url,playing=isCurrent&&playerState.playing,current=isCurrent?playerState.current:0,duration=isCurrent?playerState.duration:0;
 const toggle=async()=>{if(!sharedVoiceAudio||!url)return;try{if(sharedVoiceUrl!==url){sharedVoiceAudio.pause();if(sharedVoiceUrl&&!browserVoiceUrls.has(sharedVoiceUrl))URL.revokeObjectURL(sharedVoiceUrl);sharedVoiceAudio.src=url;sharedVoiceUrl=url;sharedVoiceAudio.playbackRate=speed;publishVoiceState({title:payload.name||chatTitle||'Голосовое сообщение',subtitle:chatTitle||'Nexura',playing:false,current:0,duration:0,speed,active:true});}if(sharedVoiceAudio.paused)await sharedVoiceAudio.play();else sharedVoiceAudio.pause();}catch{setError('Браузер не смог воспроизвести аудио');}};
 const changeSpeed=()=>{const next=speed===1?1.5:speed===1.5?2:1;setSpeed(next);if(isCurrent&&sharedVoiceAudio){sharedVoiceAudio.playbackRate=next;publishVoiceState({speed:next});}};
 if(busy)return <div className="media-loading">Расшифровываем вложение…</div>;
 if(error)return <div className="media-error">{error}</div>;
 if(payload.media_type==='image')return <img className="media-image-preview" src={url} alt={payload.name||'Изображение'}/>;
 if(payload.media_type==='video')return <video className="media-video-preview" src={url} controls preload="metadata"/>;
 if(payload.media_type==='audio')return <div className="voice-player"><button type="button" className="voice-play" onClick={toggle} aria-label={playing?'Пауза':'Воспроизвести'}>{playing?<Pause size={17}/>:<Play size={17}/>}</button><div className="voice-track"><div className="voice-wave" aria-hidden="true">{Array.from({length:32},(_,i)=><span key={i} style={{height:(7+Math.abs(Math.sin(i*2.17)*Math.cos(i*.43))*19)+'px',opacity:duration&&i/32<=current/duration?1:.55}}/>)}</div><input aria-label="Позиция воспроизведения" type="range" min="0" max={duration||0} step="0.1" value={Math.min(current,duration||0)} onChange={e=>{if(isCurrent&&sharedVoiceAudio)sharedVoiceAudio.currentTime=Number(e.target.value);}}/><div className="voice-time"><span>{formatDuration(current)}</span><span>{formatDuration(duration)}</span></div></div><button type="button" className="voice-speed" onClick={changeSpeed} aria-label="Скорость воспроизведения">{speed}×</button></div>;
 return <a className="file-attachment" href={url} download={payload.name||'attachment'}><span className="file-attachment-icon">↧</span><span className="file-attachment-info"><b>{payload.name}</b><small>{(payload.size/1024/1024).toFixed(2)} МБ · зашифрованный файл</small></span><span className="file-download">Скачать</span></a>;
}
function BrowserAudioDock({state,onToggle,onSeek,onClose}) { if(!state.active)return null;return <div className="audio-dock" role="region" aria-label="Аудиопроигрыватель"><div className="audio-dock-icon"><Mic size={18}/></div><div className="audio-dock-info"><b>{state.title||'Голосовое сообщение'}</b><span>{state.subtitle||'Nexura'}</span><input aria-label="Перемотка аудио" type="range" min="0" max={state.duration||0} step="0.1" value={Math.min(state.current,state.duration||0)} onChange={e=>onSeek(Number(e.target.value))}/><small>{formatDuration(state.current)} / {formatDuration(state.duration)}</small></div><button type="button" className="voice-play" onClick={onToggle} aria-label={state.playing?'Пауза':'Продолжить'}>{state.playing?<Pause size={17}/>:<Play size={17}/>}</button><button type="button" className="audio-dock-close" onClick={onClose} aria-label="Закрыть плеер">×</button></div>;}
function PendingMediaPreview({ file }) {
  const [url,setUrl]=useState('');
  useEffect(()=>{const next=URL.createObjectURL(file);setUrl(next);return()=>URL.revokeObjectURL(next);},[file]);
  if (!url) return null;
  if (file.type.startsWith('image/')) return <img className="media-image-preview pending-image-preview" src={url} alt={file.name || 'Фото'}/>;
  if (file.type.startsWith('video/')) return <video className="media-video-preview pending-video-preview" controls preload="metadata" src={url}/>;
  if (file.type.startsWith('audio/')) return <audio className="pending-audio-preview" controls preload="metadata" src={url}/>;
  return <span className="pending-file-icon">📎</span>;
}

import { loadProfile, saveProfile, uploadProfileBanner, loadProfilePhotos, loadStories, publishSocialMedia, deleteSocialMedia, toggleSocialLike, loadSocialComments, addSocialComment, loadPeopleDirectory } from './api/social.js';

function AuthScreen() {
  const [mode, setMode] = useState('login');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  async function submit(event) {
    event.preventDefault(); setBusy(true); setError(''); setNotice('');
    try {
      if (mode === 'register') {
        const { data, error: authError } = await supabase.auth.signUp({
          email: email.trim(), password,
          options: { data: { display_name: displayName.trim() }, emailRedirectTo: window.location.origin + window.location.pathname }
        });
        if (authError) throw authError;
        if (!data.session) setNotice('Аккаунт создан. Подтвердите email по ссылке из письма, затем войдите.');
      } else {
        const { error: authError } = await supabase.auth.signInWithPassword({ email: email.trim(), password });
        if (authError) throw authError;
      }
    } catch (e) { setError(e.message || 'Не удалось выполнить вход'); }
    finally { setBusy(false); }
  }

  return <main className="auth-shell">
    <div className="auth-art">
      <div className="brand"><span className="brand-mark"><MessageCircle size={23}/></span><span>Nexura</span></div>
      <div className="art-copy">
        <div className="eyebrow"><span className="pulse"/> ПРОСТРАНСТВО ДЛЯ ОБЩЕНИЯ</div>
        <h1>Твои разговоры.<br/><span>Твои люди.</span></h1>
        <p>Общайся с близкими с любого устройства. Спокойно, удобно, в своём ритме.</p>
        <div className="art-orbit"><div className="orbit orbit-one"/><div className="orbit orbit-two"/><div className="orbit-core"><MessageCircle size={38}/></div><span className="orbit-dot dot-a"/><span className="orbit-dot dot-b"/><span className="orbit-dot dot-c"/></div>
      </div>
      <div className="art-foot"><ShieldCheck size={15}/> Безопасность — часть основы Nexura</div>
    </div>
    <section className="auth-panel">
      <div className="auth-card">
        <div className="mobile-brand"><span className="brand-mark"><MessageCircle size={21}/></span>Nexura</div>
        <div className="auth-heading"><div className="eyebrow">РАДЫ ТЕБЯ ВИДЕТЬ</div><h2>{mode === 'login' ? 'С возвращением' : 'Создать аккаунт'}</h2><p>{mode === 'login' ? 'Войди, чтобы продолжить общение.' : 'Заполни данные, чтобы присоединиться к Nexura.'}</p></div>
        <form onSubmit={submit} className="auth-form">
          {mode === 'register' && <label>Как тебя называть<input value={displayName} onChange={e=>setDisplayName(e.target.value)} placeholder="Твоё имя" required autoComplete="name"/></label>}
          <label>Email<div className="input-wrap"><Mail size={17}/><input type="email" value={email} onChange={e=>setEmail(e.target.value)} placeholder="name@example.com" required autoComplete="email"/></div></label>
          <label>Пароль<div className="input-wrap"><LockKeyhole size={17}/><input type={showPassword?'text':'password'} value={password} onChange={e=>setPassword(e.target.value)} placeholder="Не менее 6 символов" required minLength={6} autoComplete={mode==='login'?'current-password':'new-password'}/><button type="button" className="icon-button" onClick={()=>setShowPassword(!showPassword)} aria-label="Показать пароль">{showPassword?<EyeOff size={17}/>:<Eye size={17}/>}</button></div></label>
          {error && <div className="alert error">{error}</div>}
          {notice && <div className="alert success">{notice}</div>}
          <button className="primary-btn wide" disabled={busy}>{busy?'Подожди…':mode==='login'?'Войти в Nexura':'Создать аккаунт'}</button>
        </form>
        <div className="auth-switch">{mode==='login'?'Впервые здесь?':'Уже есть аккаунт?'} <button onClick={()=>{setMode(mode==='login'?'register':'login');setError('');setNotice('')}}>{mode==='login'?'Создать аккаунт':'Войти'}</button></div>
        <div className="auth-note"><LockKeyhole size={14}/> Подключение защищено. Используется твой аккаунт Nexura.</div>
      </div>
    </section>
  </main>;
}

function App() {
  const [session, setSession] = useState(null);
  const [initializing, setInitializing] = useState(true);
  const [profile, setProfile] = useState(null);
  const [profilePhotos, setProfilePhotos] = useState([]);
  const [profileStories, setProfileStories] = useState([]);
  const [people, setPeople] = useState([]);
  const [peopleQuery, setPeopleQuery] = useState('');
  const [peopleOffset, setPeopleOffset] = useState(0);
  const [peopleHasMore, setPeopleHasMore] = useState(true);
  const [peopleBusy, setPeopleBusy] = useState(false);
  const [viewedProfile, setViewedProfile] = useState(null);
  const [viewedPhotos, setViewedPhotos] = useState([]);
  const [viewedStories, setViewedStories] = useState([]);
  const [socialViewer, setSocialViewer] = useState(null);
  const [socialComments, setSocialComments] = useState([]);
  const [socialCommentDraft, setSocialCommentDraft] = useState('');
  const [socialLiked, setSocialLiked] = useState(false);
  const [socialBusy, setSocialBusy] = useState(false);
  const [socialCaption, setSocialCaption] = useState('');
  const [profileEdit, setProfileEdit] = useState(false);
  const [profileEditDraft, setProfileEditDraft] = useState({display_name:'',username:'',avatar_url:'🙂',bio:'',age:''});
  const [profileEditBusy, setProfileEditBusy] = useState(false);
  const [advancedSettingsOpen, setAdvancedSettingsOpen] = useState(false);
  const [settingsSection, setSettingsSection] = useState('privacy');
  const [privacyDraft, setPrivacyDraft] = useState({showOnlineStatus:true,readReceiptsEnabled:true});
  const [blockedUsers, setBlockedUsers] = useState([]);
  const [e2eeDevices, setE2eeDevices] = useState([]);
  const [browserDeviceId, setBrowserDeviceId] = useState('');
  const [settingsBusy, setSettingsBusy] = useState(false);
  const photoInputRef = useRef(null);
  const storyInputRef = useRef(null);
  const bannerInputRef = useRef(null);
  const [chats, setChats] = useState([]);
  const [selected, setSelected] = useState(null);
  const selectedChatIdRef = useRef(null);
  selectedChatIdRef.current = selected?.id || null;
  const [messages, setMessages] = useState([]);
  const [outgoingMediaStatus, setOutgoingMediaStatus] = useState({});
  const messageLoadSeq = useRef(0);
  const pendingMediaOutboxRef = useRef(new Map());
  const messagesPaneRef = useRef(null);
  const [messageReactions, setMessageReactions] = useState({});
  const [loadingChats, setLoadingChats] = useState(false);
  const [loadingMessages, setLoadingMessages] = useState(false);
  const [search, setSearch] = useState('');
  const [notice, setNotice] = useState('');
  const [mobileChatOpen, setMobileChatOpen] = useState(false);
  const [activeTab, setActiveTab] = useState('chats');
  const [cryptoRuntimeError, setCryptoRuntimeError] = useState('');
  const [contactQuery, setContactQuery] = useState('');
  const [contactResults, setContactResults] = useState([]);
  const [contactBusy, setContactBusy] = useState(false);
  const [groupOpen, setGroupOpen] = useState(false);
  const [groupTitle, setGroupTitle] = useState('');
  const [groupQuery, setGroupQuery] = useState('');
  const [groupResults, setGroupResults] = useState([]);
  const [groupMembers, setGroupMembers] = useState([]);
  const [groupBusy, setGroupBusy] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [profileDraft, setProfileDraft] = useState({ display_name: '', username: '', avatar_url: '🙂', bio: '', age: '' });
  const [profileBusy, setProfileBusy] = useState(false);
  const [draft, setDraft] = useState('');
  const [pendingMedia, setPendingMedia] = useState(null);
  const [recording, setRecording] = useState(false);
  const [recordingSeconds, setRecordingSeconds] = useState(0);
  const fileInputRef = useRef(null);
  const recorderRef = useRef(null);
  const recordingChunksRef = useRef([]);
  const [replyTarget, setReplyTarget] = useState(null);
  const [messageMenu, setMessageMenu] = useState(null);
  const [messageMenuPosition, setMessageMenuPosition] = useState({ top: 0, left: 0 });
  const [chatThemeOpen, setChatThemeOpen] = useState(false);
  const [chatTheme, setChatTheme] = useState(() => { try { return JSON.parse(localStorage.getItem('nexura_chat_theme') || '{}'); } catch { return {}; } });
  const [reactionPickerFor, setReactionPickerFor] = useState(null);
  const [editingMessageId, setEditingMessageId] = useState(null);
  const [pendingAction, setPendingAction] = useState(false);
  const [forwardMessage, setForwardMessage] = useState(null);
  const [forwardingBusy, setForwardingBusy] = useState(false);
  const composerInputRef = useRef(null);
  const [sending, setSending] = useState(false);
  const [e2eeReady, setE2eeReady] = useState(false);
  const [e2eeStatus, setE2eeStatus] = useState('Подготавливаем защищённое устройство…');
  const [browserAudioState, setBrowserAudioState] = useState(sharedVoiceMeta);
  const [notificationPrefs, setNotificationPrefs] = useState({push_enabled:true,sound_enabled:true,vibration_enabled:false});
  const [browserNotificationPermission, setBrowserNotificationPermission] = useState(typeof Notification==='undefined'?'unsupported':Notification.permission);


  useEffect(() => {
    if (!sharedVoiceAudio) return;
    const publish=()=>publishVoiceState({playing:!sharedVoiceAudio.paused&&!sharedVoiceAudio.ended,current:Number.isFinite(sharedVoiceAudio.currentTime)?sharedVoiceAudio.currentTime:0,duration:Number.isFinite(sharedVoiceAudio.duration)?sharedVoiceAudio.duration:0});
    const ended=()=>{publishVoiceState({playing:false,current:0,active:false});if(sharedVoiceUrl){browserVoiceUrls.delete(sharedVoiceUrl);URL.revokeObjectURL(sharedVoiceUrl);}sharedVoiceUrl='';};
    ['timeupdate','loadedmetadata','play','pause'].forEach(name=>sharedVoiceAudio.addEventListener(name,publish));
    sharedVoiceAudio.addEventListener('ended',ended);
    const sync=e=>setBrowserAudioState(e.detail||sharedVoiceMeta);
    window.addEventListener('nexura:voice-state',sync);
    return()=>{['timeupdate','loadedmetadata','play','pause'].forEach(name=>sharedVoiceAudio.removeEventListener(name,publish));sharedVoiceAudio.removeEventListener('ended',ended);window.removeEventListener('nexura:voice-state',sync);};
  }, []);

  useEffect(() => {
    let alive = true;
    supabase.auth.getSession().then(({ data }) => { if (alive) { setSession(data.session); setInitializing(false); } });
    const { data: listener } = supabase.auth.onAuthStateChange((_event, next) => { setSession(next); setInitializing(false); });
    return () => { alive = false; listener.subscription.unsubscribe(); };
  }, []);

  const chatLoadSeq = useRef(0);
  const loadChats = useCallback(async ({ silent = false } = {}) => {
    if (!session?.user?.id) return;
    const requestId = ++chatLoadSeq.current;
    if (!silent) setLoadingChats(true);
    if (!silent) setNotice('');
    try {
      // Fetch the membership list and unread counters together: neither query
      // depends on the other, so the sidebar is not held up by the RPC.
      const [membershipResult, unreadResult] = await Promise.all([
        supabase.from('conversation_members')
          .select('conversation_id, joined_at, conversations(id,kind,title,last_message_at,conversation_members(user_id,profiles(id,display_name,username,avatar_url)))')
          .eq('user_id', session.user.id).order('joined_at', { ascending: false }),
        supabase.rpc('get_my_unread_counts')
      ]);
      if (membershipResult.error) throw membershipResult.error;
      if (requestId !== chatLoadSeq.current) return;
      const unread = {};
      if (!unreadResult.error) for (const row of unreadResult.data || []) unread[row.conversation_id] = Number(row.unread_count) || 0;
      const list = (membershipResult.data || []).map(row => {
        const c = row.conversations;
        if (!c) return null;
        const peer = (c.conversation_members || []).find(m => m.user_id !== session.user.id);
        const p = peer?.profiles;
        return {
          id: c.id, kind: c.kind, peerUserId: peer?.user_id || null,
          title: c.kind === 'direct' ? (p?.display_name || p?.username || 'Пользователь') : (c.title || 'Группа'),
          avatar: p?.avatar_url || null, updated: c.last_message_at, unread: unread[c.id] || 0
        };
      }).filter(Boolean).sort((a, b) => new Date(b.updated || 0) - new Date(a.updated || 0));
      setChats(list);
      setSelected(current => current ? (list.find(c => c.id === current.id) || current) : current);
    } catch (e) {
      if (requestId === chatLoadSeq.current && !silent) setNotice('Не удалось загрузить чаты: ' + (e?.message || 'ошибка'));
    } finally {
      if (requestId === chatLoadSeq.current) setLoadingChats(false);
    }
  }, [session?.user?.id]);

  useEffect(() => {
    if (!session) { setCryptoRuntimeError(''); return; }
    setE2eeReady(false);
    setE2eeStatus('Подготавливаем защищённое устройство…');
    initializeOlm().then(() => ensureBrowserE2eeDevice(supabase, session.user.id))
      .then(result => { setE2eeReady(true); setE2eeStatus('Защита включена · ' + result.availableKeys + ' одноразовых ключей'); })
      .catch(error => { setE2eeReady(false); setCryptoRuntimeError(error?.message || 'Не удалось загрузить криптомодуль'); setE2eeStatus('Защита не готова: ' + (error?.message || 'ошибка')); });
  }, [session?.user?.id]);

  // Keep this browser's published pre-key reserve topped up while the user is
  // signed in. Other users' private keys are never generated or modified here.
  useEffect(() => {
    if (!session?.user?.id || !e2eeReady) return;
    let active = true;
    const refresh = async () => {
      try {
        const result = await ensureBrowserE2eeDevice(supabase, session.user.id);
        if (active) setE2eeStatus('Защита включена · ' + result.availableKeys + ' одноразовых ключей');
      } catch (error) {
        if (active) setE2eeStatus('Не удалось обновить ключи: ' + (error?.message || 'ошибка'));
      }
    };
    const timer = setInterval(refresh, 90_000);
    return () => { active = false; clearInterval(timer); };
  }, [session?.user?.id, e2eeReady]);

  useEffect(() => {
    if (!session) { setChats([]); setSelected(null); setProfile(null); return; }
    loadChats();
    supabase.from('profiles').select('id,display_name,username,avatar_url,bio,age').eq('id', session.user.id).maybeSingle()
      .then(({data}) => setProfile(data));
  }, [session?.user?.id]);

  const decryptMessageRow = useCallback(async (chat, message, cloudPlaintext = null) => {
    if (!isEncrypted(message.content)) return message;
    // A shared encrypted cloud-history copy lets this browser restore messages
    // created for a different active device without weakening Olm device checks.
    if (typeof cloudPlaintext === 'string' && cloudPlaintext.length) {
      return { ...message, content: cloudPlaintext, decrypted: true, encrypted: false, decryptionError: null, restoredFromCloud: true };
    }
    try {
      const plaintext = await decryptBrowserGroupMessage(supabase, session?.user?.id, chat.id, message);
      return { ...message, content: plaintext, decrypted: true, encrypted: false, decryptionError: null };
    } catch (decryptError) {
      return { ...message, encrypted: true, decryptionError: decryptError?.message || 'Не удалось расшифровать сообщение' };
    }
  }, [session?.user?.id]);

  const loadMessages = useCallback(async (chat, { silent = false } = {}) => {
    if (!chat?.id) return;
    const requestId = ++messageLoadSeq.current;
    if (!silent) setLoadingMessages(true);
    if (!silent) setNotice('');
    try {
      // Load the newest page, then reverse it for chronological rendering.
      // The old ascending+limit query could hide all recent messages in long chats.
      const [messageResult, reactionsResult] = await Promise.all([
        supabase.from('messages')
          .select('id,sender_id,content,cloud_content,created_at,message_type,reply_to_id,edited_at,deleted_at')
          .eq('conversation_id', chat.id).is('deleted_at', null)
          .order('created_at', { ascending: false }).order('id', { ascending: false }).limit(50),
        loadMessageReactions(supabase, chat.id).catch(() => ({}))
      ]);
      if (messageResult.error) throw messageResult.error;
      const rawRows = (messageResult.data || []).reverse();
      const missingSharedIds = rawRows
        .filter(message => isEncrypted(message.content) && !(typeof message.cloud_content === 'string' && message.cloud_content.length))
        .map(message => message.id);
      let personalArchives = [];
      if (missingSharedIds.length && session?.user?.id) {
        // Android backfills legacy messages into an account-scoped encrypted archive.
        // The browser must read that archive too, otherwise old messages remain
        // incorrectly bound to whichever device originally received them.
        const { data, error } = await supabase.from('message_cloud_archives')
          .select('message_id,cloud_content')
          .eq('user_id', session.user.id)
          .eq('conversation_id', chat.id)
          .in('message_id', missingSharedIds);
        if (!error && Array.isArray(data)) personalArchives = data;
      }
      const cloudItems = [
        ...rawRows
          .filter(message => isEncrypted(message.content) && typeof message.cloud_content === 'string' && message.cloud_content.length)
          .map(message => ({ id: message.id, cloud_content: message.cloud_content, scope: 'shared' })),
        ...personalArchives
          .filter(item => typeof item.message_id === 'string' && typeof item.cloud_content === 'string' && item.cloud_content.length)
          .map(item => ({ id: item.message_id, cloud_content: item.cloud_content, scope: 'user' }))
      ];
      let cloudPlaintextById = new Map();
      if (cloudItems.length) {
        // Both shared and account-scoped archives remain encrypted at rest.
        try {
          const { data, error } = await supabase.functions.invoke('cloud-history', {
            body: { action: 'decrypt', conversation_id: chat.id, items: cloudItems }
          });
          if (!error && Array.isArray(data?.items)) {
            cloudPlaintextById = new Map(data.items
              .filter(item => typeof item?.id === 'string' && typeof item?.plaintext === 'string')
              .map(item => [item.id, item.plaintext]));
          }
        } catch { /* Keep strict per-device E2EE decryption as the fallback. */ }
      }
      const rows = await Promise.all(rawRows.map(message =>
        decryptMessageRow(chat, message, cloudPlaintextById.get(message.id) || null)
      ));
      if (requestId !== messageLoadSeq.current) return;
      const localPending = [...pendingMediaOutboxRef.current.values()].filter(item => item.conversation_id === chat.id);
      setMessages([...rows.filter(item => !localPending.some(pending => pending.id === item.id)), ...localPending].sort((a,b)=>new Date(a.created_at)-new Date(b.created_at)).slice(-50));
      setMessageReactions(reactionsResult || {});
      markConversationRead(supabase, chat.id).catch(() => {});
    } catch (error) {
      if (requestId === messageLoadSeq.current && !silent) setNotice('Не удалось открыть переписку: ' + (error?.message || 'ошибка'));
    } finally {
      if (requestId === messageLoadSeq.current) setLoadingMessages(false);
    }
  }, [session?.user?.id, decryptMessageRow]);

  useEffect(() => {
    if (!messageMenu && !reactionPickerFor) return;
    const dismiss = event => {
      if (event.type === 'keydown' && event.key === 'Escape') {
        setMessageMenu(null); setReactionPickerFor(null); return;
      }
      if (event.type === 'pointerdown' && !event.target.closest('.message-tools, .message-context-menu-floating')) {
        setMessageMenu(null); setReactionPickerFor(null);
      }
    };
    document.addEventListener('pointerdown', dismiss);
    document.addEventListener('keydown', dismiss);
    return () => {
      document.removeEventListener('pointerdown', dismiss);
      document.removeEventListener('keydown', dismiss);
    };
  }, [messageMenu, reactionPickerFor]);

  useEffect(() => {
    if (!selected) { messageLoadSeq.current++; setMessages([]); setMessageReactions({}); return; }
    const chat = selected;
    setMessages([]);
    setMessageReactions({});
    loadMessages(chat);
    let active = true;
    const channel = supabase.channel('web-chat-' + chat.id)
      .on('postgres_changes', { event: '*', schema: 'public', table: 'messages', filter: 'conversation_id=eq.' + chat.id }, async payload => {
        if (!active) return;
        const eventType = payload.eventType;
        const source = eventType === 'DELETE' ? payload.old : payload.new;
        if (!source?.id) return;
        if (eventType === 'DELETE' || source.deleted_at) {
          setMessages(previous => previous.filter(message => message.id !== source.id));
          setMessageReactions(previous => { const next = { ...previous }; delete next[source.id]; return next; });
          return;
        }
        const message = await decryptMessageRow(chat, source);
        if (!active) return;
        pendingMediaOutboxRef.current.delete(message.id);
        setMessages(previous => {
          const next = [...previous.filter(item => item.id !== message.id), message]
            .sort((a, b) => new Date(a.created_at) - new Date(b.created_at));
          return next.slice(-50);
        });
        if (message.sender_id !== session?.user?.id) markConversationRead(supabase, chat.id).catch(() => {});
      })
      .subscribe();
    return () => { active = false; supabase.removeChannel(channel); };
  }, [selected?.id, loadMessages, decryptMessageRow, session?.user?.id, e2eeReady]);

  useEffect(() => {
    if (!session?.user?.id) return;
    let refreshTimer = null;
    const channel = supabase.channel('web-chat-list-' + session.user.id)
      .on('postgres_changes', { event: '*', schema: 'public', table: 'messages' }, () => {
        if (refreshTimer) clearTimeout(refreshTimer);
        refreshTimer = setTimeout(() => loadChats({ silent: true }), 350);
      })
      .subscribe();
    return () => {
      if (refreshTimer) clearTimeout(refreshTimer);
      supabase.removeChannel(channel);
    };
  }, [session?.user?.id, loadChats]);

  const loadPeople = useCallback(async ({reset = false} = {}) => {
    if (!session?.user?.id || peopleBusy) return;
    const offset = reset ? 0 : peopleOffset;
    setPeopleBusy(true);
    try {
      const rows = await loadPeopleDirectory(supabase, session.user.id, peopleQuery, offset, 40);
      setPeople(previous => reset ? rows : [...previous, ...rows.filter(row => !previous.some(old => old.id === row.id))]);
      setPeopleOffset(offset + 40);
      setPeopleHasMore(rows.length === 40);
    } catch (error) { setNotice('Не удалось загрузить каталог: ' + error.message); }
    finally { setPeopleBusy(false); }
  }, [session?.user?.id, peopleQuery, peopleOffset, peopleBusy]);

  useEffect(() => {
    if (activeTab !== 'people' || !session?.user?.id) return;
    const timer = setTimeout(() => loadPeople({reset:true}), 180);
    return () => clearTimeout(timer);
  }, [activeTab, peopleQuery, session?.user?.id]);

  async function openUserProfile(person) {
    setSocialBusy(true); setNotice('');
    try {
      const {data, error} = await supabase.from('profiles')
        .select('id,display_name,username,avatar_url,bio,age,banner_url')
        .eq('id', person.id).single();
      if (error) throw error;
      const [photos, stories] = await Promise.all([
        loadProfilePhotos(supabase, person.id),
        loadStories(supabase, {userId:person.id, activeOnly:true})
      ]);
      let bannerUrl = data.banner_url || '';
      if (bannerUrl.startsWith('social:')) {
        const {data: signed, error: signError} = await supabase.storage.from('nexura-social-media').createSignedUrl(bannerUrl.slice(7), 7*86400);
        if (!signError) bannerUrl = signed.signedUrl;
      }
      setViewedProfile({...data, banner_display_url:bannerUrl});
      setViewedPhotos(photos); setViewedStories(stories);
    } catch(error) { setNotice('Не удалось открыть профиль: '+error.message); }
    finally { setSocialBusy(false); }
  }

  async function loadOwnSocial() {
    if (!session?.user?.id) return;
    try {
      const [photos, stories] = await Promise.all([
        loadProfilePhotos(supabase, session.user.id),
        loadStories(supabase, {userId:session.user.id, activeOnly:true})
      ]);
      setProfilePhotos(photos); setProfileStories(stories);
    } catch(error) { setNotice('Не удалось загрузить публикации: '+error.message); }
  }

  async function openSocialItem(item, kind) {
    setSocialViewer({...item, kind});
    setSocialCommentDraft(''); setSocialComments([]);
    if (kind === 'photo') {
      try {
        const [{data:likes,error:likeError},{data:comments,error:commentError}] = await Promise.all([
          supabase.from('profile_photo_likes').select('user_id').eq('photo_id',item.id),
          supabase.from('profile_photo_comments').select('id,user_id,content,created_at').eq('photo_id',item.id).order('created_at')
        ]);
        if (likeError) throw likeError;
        if (commentError) throw commentError;
        setSocialLiked((likes||[]).some(row=>row.user_id===session.user.id));
        setSocialComments(comments||[]);
        setSocialViewer(current=>current?.id===item.id?{...current,likeCount:(likes||[]).length}:current);
      } catch(error) { setNotice('Не удалось загрузить реакции: '+error.message); }
    } else {
      const {data:likes,error} = await supabase.from('user_story_likes').select('user_id').eq('story_id',item.id);
      if (!error) {
        setSocialLiked((likes||[]).some(row=>row.user_id===session.user.id));
        setSocialViewer(current=>current?.id===item.id?{...current,likeCount:(likes||[]).length}:current);
      }
    }
  }

  async function toggleCurrentSocialLike() {
    if (!socialViewer || socialBusy) return;
    setSocialBusy(true);
    try {
      const kind = socialViewer.kind;
      const table = kind==='story'?'user_story_likes':'profile_photo_likes';
      const key = kind==='story'?'story_id':'photo_id';
      const {data,error} = await supabase.from(table).select('id').eq(key,socialViewer.id).eq('user_id',session.user.id).maybeSingle();
      if (error) throw error;
      if (data?.id) {
        const {error:removeError}=await supabase.from(table).delete().eq('id',data.id);
        if(removeError)throw removeError;
        setSocialLiked(false); setSocialViewer(v=>({...v,likeCount:Math.max(0,(v.likeCount||0)-1)}));
      } else {
        const {error:addError}=await supabase.from(table).insert({[key]:socialViewer.id,user_id:session.user.id});
        if(addError)throw addError;
        setSocialLiked(true); setSocialViewer(v=>({...v,likeCount:(v.likeCount||0)+1}));
      }
    } catch(error) { setNotice('Не удалось поставить реакцию: '+error.message); }
    finally { setSocialBusy(false); }
  }

  async function submitSocialComment(event) {
    event.preventDefault();
    const clean=socialCommentDraft.trim();
    if(!clean||!socialViewer||socialViewer.kind!=='photo')return;
    setSocialBusy(true);
    try {
      const {data,error}=await supabase.from('profile_photo_comments').insert({photo_id:socialViewer.id,user_id:session.user.id,content:clean}).select('id,user_id,content,created_at').single();
      if(error)throw error;
      setSocialComments(previous=>[...previous,data]);setSocialCommentDraft('');
    } catch(error) { setNotice('Не удалось отправить комментарий: '+error.message); }
    finally { setSocialBusy(false); }
  }

  async function publishProfileMedia(kind, file) {
    if(!file||socialBusy)return;
    setSocialBusy(true);
    try {
      const extension=(file.name.split('.').pop()||'jpg').replace(/[^a-z0-9]/gi,'').toLowerCase()||'jpg';
      const path=session.user.id+'/'+(kind==='story'?'stories':'photos')+'/'+crypto.randomUUID()+'.'+extension;
      const {error:uploadError}=await supabase.storage.from('nexura-social-media').upload(path,file,{upsert:false,contentType:file.type});
      if(uploadError)throw uploadError;
      const now=new Date();
      const row=kind==='story'
        ? {user_id:session.user.id,storage_path:path,media_type:file.type.startsWith('video/')?'video':'image',caption:socialCaption.trim().slice(0,500),created_at:now.toISOString(),expires_at:new Date(now.getTime()+86400000).toISOString()}
        : {user_id:session.user.id,storage_path:path,caption:socialCaption.trim().slice(0,500)};
      const table=kind==='story'?'user_stories':'profile_photos';
      const {data,error}=await supabase.from(table).insert(row).select('id,user_id,storage_path,media_type,caption,created_at,expires_at').single();
      if(error){await supabase.storage.from('nexura-social-media').remove([path]);throw error;}
      const {data:signed,error:signError}=await supabase.storage.from('nexura-social-media').createSignedUrl(path,kind==='story'?86400:7*86400);
      if(signError)throw signError;
      if(kind==='story')setProfileStories(previous=>[{...data,url:signed.signedUrl},...previous]);
      else setProfilePhotos(previous=>[{...data,url:signed.signedUrl},...previous]);
      setSocialCaption('');
      setNotice(kind==='story'?'История опубликована на 24 часа':'Фотография добавлена в профиль');
    } catch(error) { setNotice('Не удалось опубликовать: '+error.message); }
    finally { setSocialBusy(false); }
  }

  async function openAdvancedSettings() {
    setAdvancedSettingsOpen(true); setSettingsSection('privacy'); setSettingsBusy(true);
    try {
      const [profileResult,presenceResult,blocksResult,devicesResult,bundle,notificationsResult] = await Promise.all([
        supabase.from('profiles').select('read_receipts_enabled').eq('id',session.user.id).maybeSingle(),
        supabase.from('user_presence').select('show_online_status').eq('user_id',session.user.id).maybeSingle(),
        supabase.from('user_blocks').select('blocked_id,created_at').eq('blocker_id',session.user.id).order('created_at',{ascending:false}),
        supabase.from('e2ee_devices').select('device_id,created_at,last_seen_at,protocol,bundle_version,curve25519_key,ed25519_key,is_active').eq('user_id',session.user.id).eq('is_active',true).order('created_at',{ascending:false}),
        loadEncryptedRecord(session.user.id,'public-device'),
        supabase.from('notification_preferences').select('push_enabled,sound_enabled,vibration_enabled').eq('user_id',session.user.id).maybeSingle()
      ]);
      if(profileResult.error)throw profileResult.error;
      if(presenceResult.error)throw presenceResult.error;
      if(blocksResult.error)throw blocksResult.error;
      if(devicesResult.error)throw devicesResult.error;
      if(notificationsResult.error)throw notificationsResult.error;
      setNotificationPrefs(notificationsResult.data||{push_enabled:true,sound_enabled:true,vibration_enabled:false});
      setBrowserNotificationPermission(typeof Notification==='undefined'?'unsupported':Notification.permission);
      setPrivacyDraft({showOnlineStatus:presenceResult.data?.show_online_status??true,readReceiptsEnabled:profileResult.data?.read_receipts_enabled??true});
      const ids=(blocksResult.data||[]).map(row=>row.blocked_id);
      if(ids.length){const {data,error}=await supabase.from('profiles').select('id,display_name,username,avatar_url').in('id',ids);if(error)throw error;setBlockedUsers((data||[]).map(p=>({...p,created_at:blocksResult.data.find(b=>b.blocked_id===p.id)?.created_at})));}else setBlockedUsers([]);
      setE2eeDevices(devicesResult.data||[]);
      setBrowserDeviceId(bundle?.device_id||'');
    } catch(error) { setNotice('Не удалось загрузить настройки: '+error.message); }
    finally { setSettingsBusy(false); }
  }

  async function savePrivacySettings(next) {
    setSettingsBusy(true);
    try {
      const {error}=await supabase.rpc('update_my_privacy_settings',{
        p_show_online_status:!!next.showOnlineStatus,
        p_read_receipts_enabled:!!next.readReceiptsEnabled
      });
      if(error)throw error;
      setPrivacyDraft(next); setNotice('Настройки приватности сохранены');
    } catch(error) { setNotice('Не удалось сохранить приватность: '+error.message); }
    finally { setSettingsBusy(false); }
  }

  async function unblockProfile(userId) {
    if(!window.confirm('Разблокировать пользователя?'))return;
    setSettingsBusy(true);
    try {
      const {error}=await supabase.from('user_blocks').delete().eq('blocker_id',session.user.id).eq('blocked_id',userId);
      if(error)throw error;
      setBlockedUsers(previous=>previous.filter(user=>user.id!==userId));
    } catch(error) { setNotice('Не удалось разблокировать: '+error.message); }
    finally { setSettingsBusy(false); }
  }

  async function revokeBrowserDevice(device) {
    if(device.device_id===browserDeviceId){setNotice('Нельзя отозвать устройство, на котором открыты текущая сессия и ключи.');return;}
    if(!window.confirm('Отозвать это E2EE-устройство? Оно потеряет доступ к новым сообщениям.'))return;
    setSettingsBusy(true);
    try {
      const {data,error}=await supabase.rpc('revoke_own_e2ee_device',{p_device_id:device.device_id});
      if(error)throw error;
      if(data!==true)throw new Error('Сервер не подтвердил отзыв устройства');
      setE2eeDevices(previous=>previous.filter(item=>item.device_id!==device.device_id));
      setNotice('Устройство отозвано');
    } catch(error) { setNotice('Не удалось отозвать устройство: '+error.message); }
    finally { setSettingsBusy(false); }
  }

  async function openOwnProfile() {
    setActiveTab('profile'); setViewedProfile(null);
    await loadOwnSocial();
  }

  async function saveNotificationSetting(key,value) {
    setSettingsBusy(true);
    try {
      if(key==='push_enabled'&&value&&typeof Notification!=='undefined'&&Notification.permission==='default') {
        const permission=await Notification.requestPermission();
        setBrowserNotificationPermission(permission);
        if(permission!=='granted') throw new Error('Браузер не разрешил уведомления');
      }
      if(key==='push_enabled'&&value&&typeof Notification!=='undefined'&&Notification.permission==='denied') {
        throw new Error('Уведомления запрещены в настройках браузера');
      }
      const next={...notificationPrefs,[key]:value};
      const {error}=await supabase.from('notification_preferences').upsert({
        user_id:session.user.id,...next,updated_at:new Date().toISOString()
      },{onConflict:'user_id'});
      if(error)throw error;
      setNotificationPrefs(next);
      setBrowserNotificationPermission(typeof Notification==='undefined'?'unsupported':Notification.permission);
      setNotice('Настройки уведомлений сохранены.');
    } catch(error) { setNotice('Не удалось изменить уведомления: '+error.message); }
    finally { setSettingsBusy(false); }
  }

  const visibleChats = useMemo(() => chats.filter(c => c.title.toLowerCase().includes(search.toLowerCase())), [chats, search]);

  useEffect(() => {
    let cancelled = false;
    if (!session || activeTab !== 'contacts' || contactQuery.trim().length < 2) {
      setContactResults([]);
      setContactBusy(false);
      return;
    }
    setContactBusy(true);
    const timer = setTimeout(async () => {
      try {
        const rows = await searchProfiles(contactQuery, session.user.id);
        if (!cancelled) setContactResults(rows);
      } catch (error) {
        if (!cancelled) setNotice('Не удалось найти пользователей: ' + error.message);
      } finally {
        if (!cancelled) setContactBusy(false);
      }
    }, 250);
    return () => { cancelled = true; clearTimeout(timer); };
  }, [session?.user?.id, activeTab, contactQuery]);

  useEffect(() => {
    let cancelled = false;
    if (!groupOpen || !session || groupQuery.trim().length < 2) {
      setGroupResults([]);
      return;
    }
    const timer = setTimeout(async () => {
      try {
        const rows = await searchProfiles(groupQuery, session.user.id);
        if (!cancelled) setGroupResults(rows);
      } catch (error) {
        if (!cancelled) setNotice('Не удалось найти участников: ' + error.message);
      }
    }, 250);
    return () => { cancelled = true; clearTimeout(timer); };
  }, [session?.user?.id, groupOpen, groupQuery]);

  async function createDirectChat(person) {
    setContactBusy(true); setNotice('');
    try {
      const { data, error } = await supabase.rpc('create_direct_conversation', { p_other_user_id: person.id });
      if (error) throw error;
      const id = String(data || '').replace(/^"|"$/g, '');
      if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id)) {
        throw new Error('Сервер вернул некорректный ID переписки');
      }
      const chat = { id, kind: 'direct', peerUserId: person.id, title: person.title, avatar: person.avatar, updated: new Date().toISOString(), unread: 0 };
      setChats(previous => [chat, ...previous.filter(item => item.id !== id)]);
      setSelected(chat); setActiveTab('chats'); setMobileChatOpen(true); setSearch('');
    } catch (error) { setNotice('Не удалось открыть переписку: ' + error.message); }
    finally { setContactBusy(false); }
  }

  async function createGroupChat() {
    const title = groupTitle.trim();
    const memberIds = [...new Set(groupMembers)];
    if (title.length < 2 || title.length > 60) { setNotice('Название группы должно содержать от 2 до 60 символов'); return; }
    if (memberIds.length < 1 || memberIds.length > 49) { setNotice('Выбери от 1 до 49 участников'); return; }
    setGroupBusy(true); setNotice('');
    try {
      const { data, error } = await supabase.rpc('create_group_conversation', { p_title: title, p_member_ids: memberIds });
      if (error) throw error;
      const id = String(data || '').replace(/^"|"$/g, '');
      if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id)) {
        throw new Error('Сервер вернул некорректный ID группы');
      }
      const chat = { id, kind: 'group', peerUserId: null, title, avatar: '👥', updated: new Date().toISOString(), unread: 0 };
      setChats(previous => [chat, ...previous.filter(item => item.id !== id)]);
      setSelected(chat); setActiveTab('chats'); setMobileChatOpen(true);
      setGroupOpen(false); setGroupTitle(''); setGroupQuery(''); setGroupMembers([]); setGroupResults([]);
    } catch (error) { setNotice('Не удалось создать группу: ' + error.message); }
    finally { setGroupBusy(false); }
  }

  async function reactToMessage(message, emoji) {
    const current = messageReactions[message.id] || [];
    const active = current.find(item => item.selectedByMe);
    const existing = current.find(item => item.emoji === emoji);
    const removing = !!existing?.selectedByMe;
    const next = current.map(item => {
      const wasMine = item.selectedByMe;
      const becomesMine = !removing && item.emoji === emoji;
      return {
        ...item,
        count: Math.max(0, item.count - (wasMine && !becomesMine ? 1 : 0) + (!wasMine && becomesMine ? 1 : 0)),
        selectedByMe: becomesMine
      };
    }).filter(item => item.count > 0);
    if (!removing && !existing) next.push({ emoji, count: 1, selectedByMe: true });
    setMessageReactions(previous => ({...previous, [message.id]: next}));
    setReactionPickerFor(null);
    try {
      await toggleMessageReaction(supabase, selected.id, message.id, emoji);
      setMessageReactions(await loadMessageReactions(supabase, selected.id));
    } catch (error) {
      setMessageReactions(await loadMessageReactions(supabase, selected.id).catch(() => current));
      setNotice('Не удалось поставить реакцию: ' + error.message);
    }
  }

  async function copyMessage(message) {
    if (message.encrypted) { setNotice('Нерасшифрованное сообщение нельзя скопировать.'); return; }
    const value = String(message.content || '');
    try {
      if (navigator.clipboard?.writeText && window.isSecureContext) {
        await navigator.clipboard.writeText(value);
      } else {
        const field = document.createElement('textarea');
        field.value = value; field.style.position = 'fixed'; field.style.opacity = '0';
        document.body.appendChild(field); field.select();
        const copied = document.execCommand('copy'); field.remove();
        if (!copied) throw new Error('Копирование недоступно');
      }
      setNotice('Сообщение скопировано');
    } catch { setNotice('Браузер не разрешил копирование'); }
    setMessageMenu(null);
  }

  async function removeOwnMessage(message) {
    if (message.sender_id !== session.user.id || pendingAction) return;
    if (!window.confirm('Удалить это сообщение?')) { setMessageMenu(null); return; }
    setPendingAction(true);
    try {
      await deleteMessage(supabase, message.id);
      setMessages(previous => previous.filter(item => item.id !== message.id));
      setMessageReactions(previous => { const next = {...previous}; delete next[message.id]; return next; });
      setNotice('Сообщение удалено');
    } catch (error) { setNotice('Не удалось удалить сообщение: ' + error.message); }
    finally { setPendingAction(false); setMessageMenu(null); }
  }

  async function forwardToChat(chat) {
    if (!forwardMessage || forwardingBusy) return;
    if (forwardMessage.encrypted) { setNotice('Сначала нужно расшифровать сообщение.'); return; }
    if (chat.kind !== 'direct' || !chat.peerUserId) { setNotice('Пересылка пока доступна в личные чаты.'); return; }
    setForwardingBusy(true);
    try {
      await sendBrowserDirectMessage(supabase, {
        userId: session.user.id, conversationId: chat.id, peerUserId: chat.peerUserId,
        messageId: crypto.randomUUID(), plaintext: String(forwardMessage.content || '')
      });
      setChats(previous => previous.map(item => item.id===chat.id ? {...item,updated:new Date().toISOString()} : item).sort((a,b)=>new Date(b.updated||0)-new Date(a.updated||0)));
      setForwardMessage(null);
      setNotice('Сообщение переслано');
    } catch (error) { setNotice('Не удалось переслать сообщение: ' + (error?.message || 'ошибка')); }
    finally { setForwardingBusy(false); }
  }

  function startEditing(message) {
    if (message.sender_id !== session.user.id || selected?.kind !== 'direct' || message.encrypted) return;
    setEditingMessageId(message.id);
    setDraft(String(message.content || ''));
    setReplyTarget(null);
    setMessageMenu(null);
  }

  async function sendAttachment(fileOverride = pendingMedia, existingMessageId = null) {
    const file = fileOverride;
    if (!file || !selected || (selected.kind === 'direct' && !selected.peerUserId) || !e2eeReady || sending) return;
    setSending(true); setNotice('');
    const conversationId = selected.id;
    const messageId = existingMessageId || crypto.randomUUID();
    const createdAt = new Date().toISOString();
    if (!existingMessageId) {
      const optimistic = {
        id:messageId, sender_id:session.user.id, content:'', created_at:createdAt,
        message_type:'media', encrypted:false, uploading:true, localMediaFile:file,
        reply_to_id:replyTarget?.id || null
      };
      pendingMediaOutboxRef.current.set(messageId, {...optimistic,conversation_id:selected.id});
      setMessages(previous=>[...previous,optimistic]);
    }
    setOutgoingMediaStatus(previous=>({...previous,[messageId]:'sending'}));
    const replyToId = existingMessageId ? (pendingMediaOutboxRef.current.get(existingMessageId)?.reply_to_id || null) : (replyTarget?.id || null);
    try {
      const metadata = await encryptAndUploadMedia(supabase, { file, userId:session.user.id, conversationId, messageId });
      const payload = {userId:session.user.id, conversationId, messageId, plaintext:JSON.stringify(metadata), messageType:'media', replyToId};
      const sent = selected.kind === 'group'
        ? await sendBrowserGroupMessage(supabase, payload)
        : await sendBrowserDirectMessage(supabase, {...payload, peerUserId:selected.peerUserId});
      pendingMediaOutboxRef.current.delete(messageId);
      if (selectedChatIdRef.current === conversationId) setMessages(previous=>[...previous.filter(item=>item.id!==messageId),{...sent,content:JSON.stringify(metadata),media:metadata}].sort((x,y)=>new Date(x.created_at)-new Date(y.created_at)));
      setOutgoingMediaStatus(previous=>({...previous,[messageId]:'sent'}));
      setPendingMedia(null); setReplyTarget(null); loadChats();
    } catch(error) {
      setOutgoingMediaStatus(previous=>({...previous,[messageId]:'failed'}));
      setNotice('Не удалось отправить вложение: '+(error?.message||'ошибка'));
    } finally { setSending(false); }
  }

  function retryMediaMessage(message) {
    const file = message.localMediaFile;
    if (!file) { setNotice('Исходный файл больше недоступен. Выбери его заново.'); return; }
    setOutgoingMediaStatus(previous=>({...previous,[message.id]:'sending'}));
    sendAttachment(file,message.id);
  }

  async function startVoiceRecording() {
    if (!navigator.mediaDevices?.getUserMedia || !window.MediaRecorder) {
      setNotice('Запись голоса недоступна в этом браузере. Разреши доступ к микрофону или выбери аудиофайл.');
      return;
    }
    try {
      const stream=await navigator.mediaDevices.getUserMedia({audio:true});
      const preferred=['audio/mp4','audio/ogg;codecs=opus','audio/webm;codecs=opus','audio/webm'];
      const mimeType=preferred.find(type=>MediaRecorder.isTypeSupported(type));
      const recorder=new MediaRecorder(stream,mimeType?{mimeType}:undefined);
      recordingChunksRef.current=[];
      recorder.ondataavailable=event=>{if(event.data?.size)recordingChunksRef.current.push(event.data);};
      recorder.onstop=()=>{
        const blob=new Blob(recordingChunksRef.current,{type:recorder.mimeType||'audio/webm'});
        const extension=blob.type.includes('mp4')?'m4a':blob.type.includes('ogg')?'ogg':'webm';
        if(blob.size) setPendingMedia(new File([blob],'Голосовое.'+extension,{type:blob.type}));
        stream.getTracks().forEach(track=>track.stop());
        setRecording(false); setRecordingSeconds(0);
      };
      recorderRef.current=recorder; recorder.start(250); setRecording(true); setRecordingSeconds(0);
    } catch(error) { setNotice(error?.name==='NotAllowedError'?'Разреши Nexura доступ к микрофону.':'Не удалось начать запись: '+(error?.message||'ошибка')); }
  }
  function stopVoiceRecording() { if(recorderRef.current?.state==='recording') recorderRef.current.stop(); }
  function cancelVoiceRecording() {
    const recorder=recorderRef.current;
    if(recorder?.state==='recording'){recorder.onstop=null;recorder.stream?.getTracks?.().forEach(track=>track.stop());recorder.stop();}
    recordingChunksRef.current=[];setRecording(false);setRecordingSeconds(0);
  }

  useEffect(()=>{
    if(!recording)return;
    const started=Date.now();
    const timer=setInterval(()=>setRecordingSeconds(Math.floor((Date.now()-started)/1000)),250);
    return ()=>clearInterval(timer);
  },[recording]);

  async function sendMessage(event) {
    event?.preventDefault?.();
    if (!selected || (selected.kind === 'direct' && !selected.peerUserId) || !e2eeReady || sending) return;
    const text = draft.trim();
    if (!text) return;
    setSending(true); setNotice('');
    try {
      if (editingMessageId) {
        const original = messages.find(item => item.id === editingMessageId);
        if (!original) throw new Error('Исходное сообщение не найдено');
        const sent = await editEncryptedDirectMessage(supabase, {
          userId: session.user.id, chatId: selected.id, peerUserId: selected.peerUserId,
          oldMessage: original, newText: text
        });
        setMessages(previous => [...previous.filter(item => item.id !== editingMessageId), {...sent, edited_at: new Date().toISOString()}].sort((a,b)=>new Date(a.created_at)-new Date(b.created_at)));
        setEditingMessageId(null);
      } else {
        const messageId = crypto.randomUUID();
        const payload = {
          userId: session.user.id, conversationId: selected.id, messageId, plaintext: text,
          replyToId: replyTarget?.id || null
        };
        const sent = selected.kind === 'group'
          ? await sendBrowserGroupMessage(supabase, payload)
          : await sendBrowserDirectMessage(supabase, {...payload, peerUserId:selected.peerUserId});
        setMessages(previous => [...previous, sent]);
      }
      setDraft('');
      setReplyTarget(null);
      loadChats();
    } catch (error) {
      setNotice('Не удалось отправить защищённое сообщение: ' + (error?.message || 'ошибка'));
    } finally { setSending(false); }
  }

  function toggleMessageMenu(event, messageId) {
    const rect = event.currentTarget.getBoundingClientRect();
    const menuHeight = 220;
    const top = rect.top > menuHeight + 16 ? rect.top - menuHeight - 8 : Math.min(window.innerHeight - menuHeight - 12, rect.bottom + 8);
    const left = Math.max(12, Math.min(rect.right - 190, window.innerWidth - 202));
    setMessageMenuPosition({ top: Math.max(12, top), left });
    setMessageMenu(current => current === messageId ? null : messageId);
    setReactionPickerFor(null);
  }

  function applyChatTheme(theme) {
    setChatTheme(theme);
    try { localStorage.setItem('nexura_chat_theme', JSON.stringify(theme)); } catch {}
  }

  function openProfileSettings() {
    setProfileDraft({
      display_name: profile?.display_name || session.user.email?.split('@')[0] || '',
      avatar_url: profile?.avatar_url || '🙂',
      bio: profile?.bio || '',
      age: profile?.age == null ? '' : String(profile.age)
    });
    setSettingsOpen(true);
  }

  async function saveProfileSettings(event) {
    event.preventDefault();
    setProfileBusy(true); setNotice('');
    try {
      await updateMyProfile(supabase, profileDraft);
      setProfile(previous => ({ ...previous, ...profileDraft, display_name: profileDraft.display_name.trim() }));
      setSettingsOpen(false);
    } catch (error) {
      setNotice('Не удалось сохранить профиль: ' + (error?.message || 'ошибка'));
    } finally { setProfileBusy(false); }
  }

  async function signOut() { await supabase.auth.signOut(); }
  function openChat(chat) { setChats(previous => previous.map(item => item.id === chat.id ? { ...item, unread: 0 } : item)); setSelected(chat); setMobileChatOpen(true); setActiveTab('chats'); setViewedProfile(null); }
  const selectedHasCipher = messages.some(m => isEncrypted(m.content));
  const activeMenuMessage = messages.find(message => message.id === messageMenu) || null;
  const activeMenuMine = activeMenuMessage?.sender_id === session?.user?.id;

  useEffect(() => {
    const field = composerInputRef.current;
    if (!field) return;
    field.style.height = 'auto';
    field.style.height = Math.min(field.scrollHeight, 144) + 'px';
  }, [draft]);

  useEffect(() => {
    const pane = messagesPaneRef.current;
    if (!pane || !selected) return;
    pane.scrollTo({ top: pane.scrollHeight, behavior: loadingMessages ? 'auto' : 'smooth' });
  }, [selected?.id, messages.length, loadingMessages]);

  if (initializing) return <div className="loading-screen"><div className="brand-mark"><MessageCircle/></div><span>Загружаем Nexura…</span></div>;
  if (!session) return <AuthScreen/>;

  return <div className="messenger">
    <aside className={'sidebar '+(mobileChatOpen?'mobile-hidden':'')}>
      <div className="side-top"><div className="brand"><span className="brand-mark"><MessageCircle size={21}/></span><span>Nexura</span><small>WEB</small></div><div className="side-actions"><button className="icon-button" onClick={()=>setGroupOpen(true)} title="Создать группу"><Plus size={18}/></button><button className="icon-button" onClick={loadChats} title="Обновить"><RefreshCw size={17}/></button></div></div>
      <div className="me-card"><Avatar className="self" value={profile?.avatar_url} name={profile?.display_name || session.user.email}/><div className="me-info"><b>{profile?.display_name || session.user.email?.split('@')[0]}</b><span>Профиль Nexura</span></div><button className="icon-button" onClick={openProfileSettings} title="Редактировать профиль"><Settings size={17}/></button><button className="icon-button" onClick={openAdvancedSettings} title="Настройки приватности и безопасности"><ShieldCheck size={17}/></button><button className="icon-button" onClick={signOut} title="Выйти"><LogOut size={17}/></button></div>
      <div className="side-tabs side-tabs-expanded">
        <button className={activeTab==='chats'?'active':''} onClick={()=>{setActiveTab('chats');setViewedProfile(null);}}><MessageCircle size={16}/> Чаты</button>
        <button className={activeTab==='people'?'active':''} onClick={()=>{setActiveTab('people');setMobileChatOpen(false);setPeopleOffset(0);setPeople([]);}}><Users size={16}/> Люди</button>
        <button className={activeTab==='profile'?'active':''} onClick={openOwnProfile}><UserRound size={16}/> Профиль</button>
        <button onClick={openAdvancedSettings}><Settings size={16}/> Настройки</button>
      </div>
      <div className="list-heading"><span>{activeTab==='chats'?'ТВОИ ЧАТЫ':activeTab==='people'?'КАТАЛОГ ПОЛЬЗОВАТЕЛЕЙ':'ТВОЙ ПРОФИЛЬ'}</span><span>{activeTab==='chats'?chats.length:activeTab==='people'?people.length:0}</span></div>
      <div className="search-box"><Search size={16}/><input value={activeTab==='chats'?search:peopleQuery} onChange={e=>activeTab==='chats'?setSearch(e.target.value):setPeopleQuery(e.target.value)} placeholder={activeTab==='chats'?'Поиск переписки':'Имя или @username'}/></div>
      <div className="chat-list">
        {activeTab==='chats' ? <>
          {loadingChats && chats.length===0 && <div className="chat-skeleton-list" aria-label="Загружаем чаты">{Array.from({length:5},(_,i)=><div className="chat-skeleton" key={i}><i/><span><b/><small/></span><em/></div>)}</div>}
          {loadingChats && chats.length>0 && <div className="list-sync-hint"><span/>Обновляем список</div>}
          {!loadingChats && visibleChats.length===0 && <div className="empty-list"><MessageCircle size={24}/><b>Пока тихо</b><span>Найди человека во вкладке «Контакты» или создай группу кнопкой +.</span></div>}
          {visibleChats.map(chat=><button key={chat.id} className={'chat-item '+(selected?.id===chat.id?'selected':'')} onClick={()=>openChat(chat)}>
            <Avatar value={chat.avatar} name={chat.title}/>
            <div className="chat-meta"><b>{chat.title}</b><span>{chat.kind==='group'?'Группа':'Личная переписка'}</span></div>
            <div className="chat-trailing">{chat.updated && <time>{timeLabel(chat.updated)}</time>}{chat.unread>0&&<i>{chat.unread}</i>}</div>
          </button>)}
        </> : activeTab==='people' ? <>
          {peopleQuery.trim().length>=2 && people.length===0 && !peopleBusy && <div className="empty-list"><UserRoundSearch size={24}/><b>Никого не нашли</b><span>Попробуй другое имя или username.</span></div>}
          {people.length===0 && peopleQuery.trim().length<2 && !peopleBusy && <div className="empty-list"><Users size={24}/><b>Люди Nexura</b><span>Все зарегистрированные пользователи — в одном каталоге.</span></div>}
          {people.map(person=><button key={person.id} className="chat-item" onClick={()=>openUserProfile(person)}>
            <Avatar value={person.avatar_url} name={person.display_name}/>
            <div className="chat-meta"><b>{person.display_name||'Пользователь'}</b><span>{person.username?'@'+person.username:'Пользователь Nexura'}</span></div>
            <div className="contact-open"><UserRound size={16}/></div>
          </button>)}
          {peopleBusy && <div className="list-hint">Загружаем пользователей…</div>}
          {!peopleBusy && peopleHasMore && <button className="load-more-people" onClick={()=>loadPeople()}>{people.length?'Показать ещё':'Загрузить людей'}</button>}
        </> : <div className="empty-list"><><UserRound size={24}/><b>Профиль открыт</b><span>Управляй своими публикациями в основной области.</span></></div>}
      </div>
      <div className="sidebar-foot"><ShieldCheck size={15}/> Nexura · веб-клиент</div>
    </aside>
    <main className={'chat-area '+(mobileChatOpen?'mobile-visible':'')}>
      {notice && <div className="toast">{notice}<button onClick={()=>setNotice('')}>×</button></div>}
      {activeTab==='profile' ? <section className="web-profile-page">
        <div className="web-profile-cover" onClick={()=>bannerInputRef.current?.click()}>
          {profile?.banner_display_url || profile?.banner_url?.startsWith('http') ? (profile.banner_url?.startsWith('social:') ? <video src={profile.banner_display_url} autoPlay muted loop playsInline/> : <img src={profile.banner_url} alt="Обложка профиля"/>) : <div className="cover-placeholder"><ImageIcon size={34}/><b>Добавь обложку профиля</b><span>Фото или видео без звука</span></div>}
          <button className="cover-edit" onClick={event=>{event.stopPropagation();bannerInputRef.current?.click();}}><Camera size={15}/> Изменить обложку</button>
          <input ref={bannerInputRef} type="file" accept="image/*,video/*" className="visually-hidden-file" onChange={async event=>{const file=event.target.files?.[0];if(!file)return;setSocialBusy(true);try{const path=profile?.id+'/'+(file.type.startsWith('video/')?'banners/':'banner-')+crypto.randomUUID()+'.'+((file.name.split('.').pop()||'jpg').replace(/[^a-z0-9]/gi,'')||'jpg');const bucket=file.type.startsWith('video/')?'nexura-social-media':'nexura-avatars';const {error:uploadError}=await supabase.storage.from(bucket).upload(path,file,{upsert:false,contentType:file.type});if(uploadError)throw uploadError;const banner_url=bucket==='nexura-social-media'?'social:'+path:supabase.storage.from(bucket).getPublicUrl(path).data.publicUrl;const {error:updateError}=await supabase.from('profiles').update({banner_url}).eq('id',session.user.id);if(updateError)throw updateError;let banner_display_url=banner_url;if(banner_url.startsWith('social:')){const {data:signed,error}=await supabase.storage.from('nexura-social-media').createSignedUrl(path,7*86400);if(error)throw error;banner_display_url=signed.signedUrl;}setProfile(previous=>({...previous,banner_url,banner_display_url}));setNotice('Обложка профиля обновлена');}catch(error){setNotice('Не удалось обновить обложку: '+error.message);}finally{setSocialBusy(false);event.target.value='';}}}/>
        </div>
        <div className="web-profile-heading"><Avatar className="web-profile-avatar" value={profile?.avatar_url} name={profile?.display_name}/><div className="web-profile-title"><h2>{profile?.display_name||'Твой профиль'}</h2><span>{profile?.username?'@'+profile.username:'Username не задан'}</span>{profile?.bio&&<p>{profile.bio}</p>}{profile?.age&&<small>{profile.age} лет</small>}</div><button className="primary-btn" onClick={()=>{setProfileEditDraft({display_name:profile?.display_name||'',username:profile?.username||'',avatar_url:profile?.avatar_url||'🙂',bio:profile?.bio||'',age:profile?.age==null?'':String(profile.age)});setProfileEdit(true);}}>Изменить профиль</button></div>
        <div className="web-social-section"><div className="web-section-heading"><div><h3>Истории</h3><span>Публикации доступны 24 часа</span></div><button className="social-add-button" onClick={()=>storyInputRef.current?.click()} disabled={socialBusy}><Plus size={16}/> Добавить</button><input ref={storyInputRef} type="file" accept="image/*,video/*" className="visually-hidden-file" onChange={event=>{const file=event.target.files?.[0];if(file)publishProfileMedia('story',file);event.target.value='';}}/></div>
          <div className="web-social-grid stories-grid">{profileStories.map(item=><button key={item.id} className="web-story-card" onClick={()=>openSocialItem(item,'story')}><div className="web-story-media">{item.media_type==='video'?<video src={item.url} muted playsInline/>:<img src={item.url} alt="История"/>}<span><Play size={16}/></span></div><small>{new Date(item.created_at).toLocaleDateString('ru-RU')}</small></button>)}{!profileStories.length&&<div className="social-empty">У тебя пока нет историй. Добавь фото или видео.</div>}</div>
        </div>
        <div className="web-social-section"><div className="web-section-heading"><div><h3>Фотографии</h3><span>Твоя галерея профиля</span></div><button className="social-add-button" onClick={()=>photoInputRef.current?.click()} disabled={socialBusy}><Plus size={16}/> Добавить</button><input ref={photoInputRef} type="file" accept="image/*" className="visually-hidden-file" onChange={event=>{const file=event.target.files?.[0];if(file)publishProfileMedia('photo',file);event.target.value='';}}/></div>
          <div className="web-social-grid">{profilePhotos.map(item=><button key={item.id} className="web-photo-card" onClick={()=>openSocialItem(item,'photo')}><img src={item.url} alt={item.caption||'Фотография профиля'}/></button>)}{!profilePhotos.length&&<div className="social-empty">Здесь будут фотографии твоего профиля.</div>}</div>
        </div>
      </section> : activeTab==='people' ? <div className="people-page"><div className="people-page-heading"><div className="eyebrow">СООБЩЕСТВО NEXURA</div><h2>Люди</h2><p>Находи собеседников, открывай профили и начинай общение.</p></div><div className="people-directory-grid">{people.map(person=><article className="people-person-card" key={person.id}><button className="people-person-main" onClick={()=>openUserProfile(person)}><Avatar value={person.avatar_url} name={person.display_name}/><span><b>{person.display_name||'Пользователь'}</b><small>{person.username?'@'+person.username:'Пользователь Nexura'}</small></span></button><p>{person.bio||'Пока ничего не рассказал о себе.'}</p><div><button className="secondary-btn" onClick={()=>openUserProfile(person)}>Профиль</button><button className="primary-btn" onClick={()=>createDirectChat({id:person.id,title:person.display_name||person.username,avatar:person.avatar_url})}><MessageCircle size={15}/> Написать</button></div></article>)}</div>{peopleBusy&&<div className="list-hint">Загружаем каталог…</div>}{peopleHasMore&&!peopleBusy&&<button className="load-more-people" onClick={()=>loadPeople()}>Показать ещё</button>}</div> : !selected ? <div className="welcome-pane"><div className="welcome-symbol"><MessageCircle size={38}/><span/></div><div className="eyebrow">ТВОЁ ПРОСТРАНСТВО</div><h2>Всё начинается<br/>с разговора.</h2><p>Выбери переписку слева, чтобы продолжить общение.</p><div className="welcome-tags"><span><LockKeyhole size={14}/> Приватность</span><span><Users size={14}/> Свои люди</span></div></div> :
        <><header className="chat-header"><button className="icon-button back-button" onClick={()=>setMobileChatOpen(false)} aria-label="Назад к чатам"><ArrowLeft size={19}/></button><Avatar value={selected.avatar} name={selected.title}/><div className="header-person"><b>{selected.title}</b><span>{selected.kind==='group'?'Групповой чат':'Личная переписка'}</span></div><button className="icon-button chat-theme-trigger" onClick={()=>setChatThemeOpen(true)} title="Оформление чата" aria-label="Оформление чата"><Palette size={17}/></button><div className="header-secure" title={e2eeReady?'Защищённое устройство подключено':'Защита устройства ещё не готова'}><ShieldCheck size={16}/><span>{e2eeReady?'Сквозное шифрование':'Подключение защиты'}</span></div></header>
        <div className="messages-pane" ref={messagesPaneRef} style={chatTheme.id?{'--chat-wallpaper':chatTheme.wall,'--incoming-bubble':chatTheme.bubble,'--outgoing-bubble':chatTheme.mine}:undefined}>
          {loadingMessages && <div className="list-hint">Загружаем историю переписки…</div>}
          {!loadingMessages && messages.length===0 && <div className="empty-messages"><div className="empty-chat-icon"><MessageCircle size={28}/></div><b>Начните общение</b><span>Напиши первое сообщение — оно появится здесь.</span></div>}
          {messages.map((m,index) => {
            const mine=m.sender_id===session.user.id;
            const previous=messages[index-1];
            const nextMessage=messages[index+1];
            const showDate=!previous || dateLabel(previous.created_at)!==dateLabel(m.created_at);
            const groupedWithPrevious=!!previous && previous.sender_id===m.sender_id && !showDate && (new Date(m.created_at)-new Date(previous.created_at)<5*60*1000);
            const groupedWithNext=!!nextMessage && nextMessage.sender_id===m.sender_id && dateLabel(nextMessage.created_at)===dateLabel(m.created_at) && (new Date(nextMessage.created_at)-new Date(m.created_at)<5*60*1000);
            const reactions=messageReactions[m.id] || [];
            const popular=['❤️','👍','😂','🔥','🥰','👏','😮','😢'];
            const replyMessage=messages.find(item=>item.id===m.reply_to_id);
            return <React.Fragment key={m.id}>
              {showDate && <div className="day-divider"><span>{dateLabel(m.created_at)}</span></div>}
              <div id={'message-'+m.id} className={'message-row '+(mine?'mine ':'')+(groupedWithPrevious?'grouped-prev ':'')+(groupedWithNext?'grouped-next':'')} onContextMenu={event=>{event.preventDefault();toggleMessageMenu(event,m.id);}}>
                {!mine && <Avatar className={'message-avatar '+(groupedWithPrevious?'avatar-spacer':'')} value={selected.avatar} name={selected.title}/>}
                <div className="message-shell">
                  <div className="message-bubble">
                    {m.reply_to_id && <button className="reply-reference" onClick={()=>document.getElementById('message-'+m.reply_to_id)?.scrollIntoView({behavior:'smooth',block:'center'})}><span>↪ {replyMessage?.sender_id===session.user.id?'Вы':selected.title}</span><b>{replyMessage?.content || 'Ответ на сообщение'}</b></button>}
                    {m.uploading ? <div className="pending-chat-media"><PendingMediaPreview file={m.localMediaFile}/><div><b>{m.localMediaFile?.name || 'Вложение'}</b><small>{outgoingMediaStatus[m.id]==='failed'?'Не отправлено': 'Отправляется…'}</small>{outgoingMediaStatus[m.id]==='failed' && <button type="button" onClick={()=>retryMediaMessage(m)}>Повторить</button>}</div></div> : m.encrypted ? <div className="encrypted-placeholder"><LockKeyhole size={17}/><span>Не удалось расшифровать</span><small>{m.decryptionError || 'Не удалось открыть защищённое сообщение.'}</small></div> : parseMediaPayload(m.content) ? <MediaMessage payload={parseMediaPayload(m.content)} supabase={supabase} chatTitle={selected.title}/> : <div className="message-content">{m.content}</div>}
                    <div className="message-meta"><time>{timeLabel(m.created_at)}</time>{m.edited_at && <span className="edited-label">изм.</span>}{mine && <span className="media-send-status">{outgoingMediaStatus[m.id]==='sending'?'◷':outgoingMediaStatus[m.id]==='failed'?'!':'✓'}</span>}</div>
                  </div>
                  <div className={"message-tools "+(reactions.length?"has-reactions":"")}>
                    <div className="message-reactions">
                      {reactions.map(reaction=><button key={reaction.emoji+'-'+reaction.count+'-'+Boolean(reaction.selectedByMe)} className={'reaction-chip '+(reaction.selectedByMe?'selected':'')} onClick={()=>reactToMessage(m,reaction.emoji)} title={reaction.selectedByMe?'Убрать реакцию':'Поставить реакцию'}><span className="reaction-emoji">{reaction.emoji}</span><b key={reaction.count} className="reaction-count">{reaction.count}</b></button>)}
                      <button className="reaction-add" onClick={()=>{setReactionPickerFor(reactionPickerFor===m.id?null:m.id);setMessageMenu(null);}} title="Добавить реакцию" aria-label="Добавить реакцию"><SmilePlus size={15}/></button>
                      <button className="message-more" onClick={event=>toggleMessageMenu(event,m.id)} title="Действия с сообщением" aria-label="Действия с сообщением"><MoreHorizontal size={16}/></button>
                    </div>
                    {reactionPickerFor===m.id && <div className="reaction-picker">{popular.map(emoji=><button key={emoji} onClick={()=>reactToMessage(m,emoji)}>{emoji}</button>)}</div>}

                  </div>
                </div>
              </div>
            </React.Fragment>;
          })}
          <div className="security-note"><LockKeyhole size={14}/><span>{e2eeStatus}</span></div>
        </div>
        {(replyTarget || editingMessageId) && <div className="reply-composer"><span>{editingMessageId ? 'Редактирование сообщения' : 'Ответ на: '+String(replyTarget?.content || 'Сообщение').slice(0,90)}</span><button onClick={()=>{setReplyTarget(null);setEditingMessageId(null);setDraft('');}} aria-label="Отменить">×</button></div>}
        {selected.kind === 'direct' || selected.kind === 'group' ? <form className="composer" onSubmit={sendMessage}>
          {pendingMedia && <div className="pending-attachment"><span>📎</span><div><b>{pendingMedia.name}</b><small>{(pendingMedia.size/1024/1024).toFixed(2)} МБ · будет зашифровано перед отправкой</small></div><button type="button" onClick={()=>setPendingMedia(null)} aria-label="Убрать вложение">×</button><PendingMediaPreview file={pendingMedia}/><button type="button" className="primary-btn" onClick={sendAttachment} disabled={!e2eeReady||sending}>{sending?'Отправка…':'Отправить'}</button></div>}
          {recording && <div className="recording-indicator"><span className="recording-dot"/><b>Идёт запись</b><time>{formatDuration(recordingSeconds)}</time><button type="button" onClick={cancelVoiceRecording}>Отмена</button><button type="button" onClick={stopVoiceRecording}>Готово</button></div>}
          <input ref={fileInputRef} type="file" className="visually-hidden-file"  onChange={event=>{const file=event.target.files?.[0];if(file)setPendingMedia(file);event.target.value='';}}/>
          <div className="composer-input-row">
            <button type="button" className="composer-attach" onClick={()=>fileInputRef.current?.click()} disabled={!e2eeReady||sending||recording||!!pendingMedia} title="Прикрепить файл" aria-label="Прикрепить файл"><Paperclip size={18}/></button>
            <button type="button" className={'composer-mic '+(recording?'recording':'')} onClick={recording?stopVoiceRecording:startVoiceRecording} disabled={!e2eeReady||sending||!!pendingMedia} title={recording?'Завершить запись':'Записать голосовое'} aria-label={recording?'Завершить запись':'Записать голосовое'}>{recording?<Square size={16}/>:<Mic size={18}/>}</button>
            <textarea ref={composerInputRef} value={draft} onChange={event=>setDraft(event.target.value)} onKeyDown={event=>{if(event.key==='Enter'&&!event.shiftKey){event.preventDefault();sendMessage(event);}else if(event.key==='Escape'&&(replyTarget||editingMessageId)){setReplyTarget(null);setEditingMessageId(null);setDraft('');}}} placeholder={e2eeReady ? 'Сообщение' : 'Подключаем защищённое соединение…'} maxLength={12000} rows={1} disabled={!e2eeReady || sending}/>
            <button className="send-button" type="submit" disabled={!e2eeReady || sending || !draft.trim()} aria-label="Отправить сообщение"><Send size={18}/></button>
          </div>
          <div className="composer-hint"><LockKeyhole size={11}/><span>{e2eeReady ? 'Сквозное шифрование включено' : 'Ожидаем подключение шифрования'}</span><span className="composer-shortcut">Enter — отправить · Shift + Enter — перенос</span></div>
        </form> : <div className="composer-locked"><div className="lock-circle"><LockKeyhole size={17}/></div><div><b>Групповые сообщения защищены E2EE</b><span>Сообщения шифруются отдельно для каждого активного устройства участников. Открытый текст не отправляется.</span></div></div>}
        </>
      }
    </main>
    {activeMenuMessage && createPortal(<div className="message-context-menu message-context-menu-floating" style={{top:messageMenuPosition.top,left:messageMenuPosition.left}} role="menu" aria-label="Действия с сообщением" onPointerDown={event=>event.stopPropagation()} onClick={event=>event.stopPropagation()}>
      <button role="menuitem" onClick={()=>{setReplyTarget(activeMenuMessage);setEditingMessageId(null);setMessageMenu(null);}}><Reply size={15}/>Ответить</button>
      <button role="menuitem" onClick={()=>{copyMessage(activeMenuMessage);setMessageMenu(null);}}><Copy size={15}/>Копировать</button>
      {!activeMenuMessage.encrypted && <button role="menuitem" onClick={()=>{setForwardMessage(activeMenuMessage);setMessageMenu(null);}}><Forward size={15}/>Переслать</button>}
      {activeMenuMine && selected?.kind==='direct' && !activeMenuMessage.encrypted && <button role="menuitem" onClick={()=>{startEditing(activeMenuMessage);setMessageMenu(null);}}><Pencil size={15}/>Изменить</button>}
      {activeMenuMine && <button role="menuitem" className="danger-action" onClick={()=>{setMessageMenu(null);removeOwnMessage(activeMenuMessage);}} disabled={pendingAction}><Trash2 size={15}/>Удалить</button>}
    </div>, document.body)}
    {forwardMessage && <div className="modal-backdrop" onClick={()=>!forwardingBusy&&setForwardMessage(null)}>
      <section className="group-modal forward-modal" onClick={event=>event.stopPropagation()}>
        <div className="modal-head"><div><div className="eyebrow">ПЕРЕСЫЛКА</div><h3>Выбери чат</h3></div><button className="icon-button" onClick={()=>setForwardMessage(null)} disabled={forwardingBusy} aria-label="Закрыть">×</button></div>
        <div className="forward-preview">{String(forwardMessage.content||'').slice(0,180)}</div>
        <div className="forward-chat-list">{chats.filter(chat=>chat.kind==='direct'&&chat.id!==selected?.id).map(chat=><button key={chat.id} className="chat-item" onClick={()=>forwardToChat(chat)} disabled={forwardingBusy}><Avatar value={chat.avatar} name={chat.title}/><div className="chat-meta"><b>{chat.title}</b><span>Личная переписка</span></div><Forward size={16}/></button>)}
        {chats.filter(chat=>chat.kind==='direct'&&chat.id!==selected?.id).length===0 && <div className="empty-list">Нет других личных чатов для пересылки.</div>}</div>
        {forwardingBusy && <div className="list-hint">Пересылаем с повторным шифрованием…</div>}
      </section>
    </div>}
    {chatThemeOpen && <div className="modal-backdrop" onClick={()=>setChatThemeOpen(false)}><section className="group-modal theme-modal" onClick={event=>event.stopPropagation()}><div className="modal-head"><div><div className="eyebrow">ПЕРСОНАЛИЗАЦИЯ</div><h3>Оформление чата</h3></div><button className="icon-button" onClick={()=>setChatThemeOpen(false)} aria-label="Закрыть">×</button></div><p className="theme-description">Выбери оформление переписки. Настройка сохраняется на этом устройстве.</p><div className="theme-presets">{[{id:'violet',name:'Nexura',wall:'#100d19',bubble:'#49366a',mine:'#49366a'},{id:'midnight',name:'Полночь',wall:'#0b1420',bubble:'#1d3447',mine:'#285879'},{id:'ocean',name:'Океан',wall:'#0b1920',bubble:'#173b42',mine:'#176b70'},{id:'rose',name:'Розовый',wall:'#1b1019',bubble:'#422338',mine:'#713b5b'},{id:'graphite',name:'Графит',wall:'#111318',bubble:'#292d35',mine:'#3f4a60'}].map(theme=><button type="button" key={theme.id} className={'theme-preset '+((chatTheme.id||'violet')===theme.id?'active':'')} onClick={()=>applyChatTheme(theme)}><span className="theme-preview" style={{background:theme.wall}}><i style={{background:theme.bubble}}/><i style={{background:theme.mine}}/></span><b>{theme.name}</b></button>)}</div><div className="theme-modal-foot"><span>Изменения применяются сразу</span><button className="primary-btn" onClick={()=>{applyChatTheme({});setChatThemeOpen(false)}}>Сбросить</button></div></section></div>}
    {settingsOpen && <div className="modal-backdrop" onClick={()=>setSettingsOpen(false)}>
      <section className="group-modal profile-modal" onClick={event=>event.stopPropagation()}>
        <div className="modal-head"><div><div className="eyebrow">ТВОЙ АККАУНТ</div><h3>Профиль</h3></div><button className="icon-button" onClick={()=>setSettingsOpen(false)} aria-label="Закрыть">×</button></div>
        <form className="profile-form" onSubmit={saveProfileSettings}>
          <div className="profile-preview-card"><Avatar value={profileDraft.avatar_url} name={profileDraft.display_name}/><div><b>{profileDraft.display_name || 'Твоё имя'}</b><span>{profile?.username ? '@'+profile.username : session.user.email}</span><small>{[profileDraft.age ? profileDraft.age+' лет' : '', profileDraft.bio].filter(Boolean).join(' · ') || 'Добавь пару слов о себе'}</small></div><ShieldCheck size={17}/></div><div className="profile-avatar-picker"><div><b>Фото и аватар</b><span>Выбери значок, который увидят собеседники</span></div></div>
          <div className="emoji-picker" aria-label="Выбор аватара">
            {['🙂','😎','🤝','🦊','🐼','🐱','🐸','🐻','🌙','⭐','🔥','💜'].map(emoji=><button type="button" key={emoji} className={profileDraft.avatar_url===emoji?'active':''} onClick={()=>setProfileDraft(value=>({...value,avatar_url:emoji}))}>{emoji}</button>)}
          </div>
          <label className="modal-label">Имя<input value={profileDraft.display_name} onChange={event=>setProfileDraft(value=>({...value,display_name:event.target.value}))} placeholder="Как тебя называть" maxLength={40} required/></label>
          <label className="modal-label">Возраст<input type="number" min="1" max="120" value={profileDraft.age} onChange={event=>setProfileDraft(value=>({...value,age:event.target.value}))} placeholder="Необязательно"/></label>
          <label className="modal-label">О себе<textarea value={profileDraft.bio} onChange={event=>setProfileDraft(value=>({...value,bio:event.target.value}))} placeholder="Пара слов о себе" maxLength={160} rows={3}/></label>
          <div className="profile-form-foot"><span>{profileDraft.bio.length}/160</span><button className="primary-btn" type="submit" disabled={profileBusy}>{profileBusy?'Сохраняем…':'Сохранить профиль'}</button></div>
        </form>
      </section>
    </div>}
    {viewedProfile && <div className="modal-backdrop profile-view-backdrop" onClick={()=>setViewedProfile(null)}><section className="web-user-profile-modal" onClick={event=>event.stopPropagation()}>
      <button className="profile-modal-close" onClick={()=>setViewedProfile(null)} aria-label="Закрыть"><X size={19}/></button>
      <div className="web-profile-cover user-cover">{viewedProfile.banner_display_url ? (viewedProfile.banner_url?.startsWith('social:')?<video src={viewedProfile.banner_display_url} autoPlay muted loop playsInline/>:<img src={viewedProfile.banner_display_url} alt="Обложка"/>) : <div className="cover-placeholder"><UserRound size={30}/></div>}</div>
      <div className="web-profile-heading"><Avatar className="web-profile-avatar" value={viewedProfile.avatar_url} name={viewedProfile.display_name}/><div className="web-profile-title"><h2>{viewedProfile.display_name}</h2><span>@{viewedProfile.username||'user'}</span>{viewedProfile.bio&&<p>{viewedProfile.bio}</p>}{viewedProfile.age&&<small>{viewedProfile.age} лет</small>}</div><button className="primary-btn" onClick={()=>createDirectChat({id:viewedProfile.id,title:viewedProfile.display_name,avatar:viewedProfile.avatar_url})}><MessageCircle size={15}/> Написать</button></div>
      <div className="web-social-section"><div className="web-section-heading"><h3>Истории</h3></div><div className="web-social-grid stories-grid">{viewedStories.map(item=><button className="web-story-card" key={item.id} onClick={()=>openSocialItem(item,'story')}><div className="web-story-media">{item.media_type==='video'?<video src={item.url} muted playsInline/>:<img src={item.url} alt="История"/>}<span><Play size={16}/></span></div></button>)}{!viewedStories.length&&<div className="social-empty">Активных историй нет.</div>}</div></div>
      <div className="web-social-section"><div className="web-section-heading"><h3>Фотографии профиля</h3></div><div className="web-social-grid">{viewedPhotos.map(item=><button className="web-photo-card" key={item.id} onClick={()=>openSocialItem(item,'photo')}><img src={item.url} alt={item.caption||'Фото'}/></button>)}{!viewedPhotos.length&&<div className="social-empty">Фотографий пока нет.</div>}</div></div>
    </section></div>}
    {socialViewer && <div className="modal-backdrop social-viewer-backdrop" onClick={()=>setSocialViewer(null)}><section className="social-viewer-modal" onClick={event=>event.stopPropagation()}>
      <button className="profile-modal-close" onClick={()=>setSocialViewer(null)}><X size={19}/></button>
      <div className="social-viewer-media">{socialViewer.media_type==='video'?<video src={socialViewer.url} controls autoPlay muted playsInline/>:<img src={socialViewer.url} alt={socialViewer.caption||'Публикация'}/>}</div>
      <div className="social-viewer-content"><p>{socialViewer.caption||' '}</p><div className="social-viewer-actions"><button onClick={toggleCurrentSocialLike} disabled={socialBusy} className={socialLiked?'liked':''}><Heart size={17} fill={socialLiked?'currentColor':'none'}/> {socialViewer.likeCount||0}</button>{socialViewer.kind==='photo'&&<span><MessageSquare size={16}/> {socialComments.length}</span>}</div>
      {socialViewer.kind==='photo'&&<><div className="social-comments">{socialComments.map(comment=><div key={comment.id}><b>{comment.user_id===session.user.id?'Вы':'Участник Nexura'}</b><span>{comment.content}</span></div>)}{!socialComments.length&&<small>Комментариев пока нет.</small>}</div><form className="social-comment-form" onSubmit={submitSocialComment}><input value={socialCommentDraft} onChange={event=>setSocialCommentDraft(event.target.value)} placeholder="Написать комментарий…" maxLength={1000}/><button className="primary-btn" disabled={socialBusy||!socialCommentDraft.trim()}>Отправить</button></form></>}
      </div>
    </section></div>}
    {advancedSettingsOpen && <div className="modal-backdrop" onClick={()=>setAdvancedSettingsOpen(false)}><section className="group-modal advanced-settings-modal" onClick={event=>event.stopPropagation()}>
      <div className="modal-head"><div><div className="eyebrow">УПРАВЛЕНИЕ АККАУНТОМ</div><h3>Настройки</h3></div><button className="icon-button" onClick={()=>setAdvancedSettingsOpen(false)}><X size={18}/></button></div>
      <div className="settings-section-tabs"><button className={settingsSection==='privacy'?'active':''} onClick={()=>setSettingsSection('privacy')}>Приватность</button><button className={settingsSection==='notifications'?'active':''} onClick={()=>setSettingsSection('notifications')}>Уведомления</button><button className={settingsSection==='devices'?'active':''} onClick={()=>setSettingsSection('devices')}>Устройства</button><button className={settingsSection==='blocked'?'active':''} onClick={()=>setSettingsSection('blocked')}>Блокировки</button></div>
      {settingsBusy&&<div className="list-hint">Обновляем настройки…</div>}
      {settingsSection==='privacy'&&<div className="advanced-settings-content">
        <div className="setting-toggle-row"><span><b>Показывать статус «В сети»</b><small>Другие пользователи смогут видеть, когда ты онлайн.</small></span><input type="checkbox" checked={privacyDraft.showOnlineStatus} disabled={settingsBusy} onChange={e=>savePrivacySettings({...privacyDraft,showOnlineStatus:e.target.checked})}/></div>
        <div className="setting-toggle-row"><span><b>Отчёты о прочтении</b><small>Собеседник увидит, что ты прочитал сообщение.</small></span><input type="checkbox" checked={privacyDraft.readReceiptsEnabled} disabled={settingsBusy} onChange={e=>savePrivacySettings({...privacyDraft,readReceiptsEnabled:e.target.checked})}/></div>
        <div className="settings-info-note"><ShieldCheck size={17}/> Изменения применяются к аккаунту Nexura и синхронизируются между устройствами.</div>
      </div>}
      {settingsSection==='notifications'&&<div className="advanced-settings-content">
        <p className="settings-description">Уведомления в браузере показываются, пока веб-клиент открыт. Получение уведомлений при полностью закрытом браузере требует отдельной настройки PWA Push.</p>
        <div className="settings-permission"><span>Разрешение браузера</span><b>{browserNotificationPermission==='granted'?'Разрешено':browserNotificationPermission==='denied'?'Запрещено':browserNotificationPermission==='unsupported'?'Не поддерживается':'Не запрошено'}</b></div>
        <div className="setting-toggle-row"><span><b>Новые сообщения</b><small>Показывать системные уведомления браузера.</small></span><input type="checkbox" checked={notificationPrefs.push_enabled} disabled={settingsBusy||browserNotificationPermission==='unsupported'} onChange={e=>saveNotificationSetting('push_enabled',e.target.checked)}/></div>
        <div className="setting-toggle-row"><span><b>Звуковой сигнал</b><small>Настройка сохраняется для аккаунта; воспроизведение зависит от разрешений браузера.</small></span><input type="checkbox" checked={notificationPrefs.sound_enabled} disabled={settingsBusy} onChange={e=>saveNotificationSetting('sound_enabled',e.target.checked)}/></div>
      </div>}
      {settingsSection==='devices'&&<div className="advanced-settings-content"><p className="settings-description">Активные устройства, которые могут получать ключи для сквозного шифрования.</p>
        {e2eeDevices.map(device=><div className="device-row" key={device.device_id}><div className="device-icon"><ShieldCheck size={17}/></div><div><b>{device.device_id===browserDeviceId?'Этот браузер':'Устройство Nexura'}</b><small>ID: {device.device_id.slice(0,8)}… · {device.protocol||'E2EE'}</small><small>Отпечаток: {device.curve25519_key?.slice(0,12)||'—'}…</small></div><button disabled={settingsBusy||device.device_id===browserDeviceId} onClick={()=>revokeBrowserDevice(device)}>Отозвать</button></div>)}
        {!e2eeDevices.length&&<div className="social-empty">Активные устройства не найдены.</div>}
        <div className="settings-info-note"><LockKeyhole size={17}/> Отзыв устройства необратим. Для повторного доступа ему потребуется зарегистрировать новую криптографическую идентичность.</div>
      </div>}
      {settingsSection==='blocked'&&<div className="advanced-settings-content">
        {blockedUsers.map(user=><div className="blocked-user-row" key={user.id}><Avatar value={user.avatar_url} name={user.display_name}/><div><b>{user.display_name||'Пользователь'}</b><small>{user.username?'@'+user.username:'Заблокирован'}</small></div><button disabled={settingsBusy} onClick={()=>unblockProfile(user.id)}>Разблокировать</button></div>)}
        {!blockedUsers.length&&<div className="social-empty">Заблокированных пользователей нет.</div>}
      </div>}
    </section></div>}
    {profileEdit && <div className="modal-backdrop" onClick={()=>setProfileEdit(false)}><section className="group-modal profile-modal" onClick={event=>event.stopPropagation()}><div className="modal-head"><div><div className="eyebrow">АККАУНТ NEXURA</div><h3>Изменить профиль</h3></div><button className="icon-button" onClick={()=>setProfileEdit(false)}><X size={18}/></button></div>
      <form className="profile-form" onSubmit={async event=>{event.preventDefault();setProfileEditBusy(true);try{const saved=await saveProfile(supabase,session.user.id,profileEditDraft);setProfile(previous=>({...previous,...saved}));setProfileEdit(false);setNotice('Профиль сохранён');}catch(error){setNotice('Не удалось сохранить профиль: '+error.message);}finally{setProfileEditBusy(false);}}}>
        <div className="emoji-picker">{['🙂','😎','🤝','🦊','🐼','🐱','🐸','🐻','🌙','⭐','🔥','💜','🐺','🦋','🚀','🎧','🌸','🧸','🦄','🍀','⚡','👑','🎮','🌊'].map(emoji=><button type="button" key={emoji} className={profileEditDraft.avatar_url===emoji?'active':''} onClick={()=>setProfileEditDraft(v=>({...v,avatar_url:emoji}))}>{emoji}</button>)}</div>
        <label className="modal-label">Имя<input value={profileEditDraft.display_name} onChange={e=>setProfileEditDraft(v=>({...v,display_name:e.target.value}))} maxLength={40} required/></label>
        <label className="modal-label">Username<input value={profileEditDraft.username} onChange={e=>setProfileEditDraft(v=>({...v,username:e.target.value.toLowerCase().replace(/[^a-z0-9_]/g,'').slice(0,24)}))} placeholder="username" required/></label>
        <label className="modal-label">Возраст<input type="number" min="13" max="120" value={profileEditDraft.age} onChange={e=>setProfileEditDraft(v=>({...v,age:e.target.value}))}/></label>
        <label className="modal-label">О себе<textarea value={profileEditDraft.bio} onChange={e=>setProfileEditDraft(v=>({...v,bio:e.target.value}))} maxLength={160} rows={3}/></label>
        <div className="profile-form-foot"><span>{profileEditDraft.bio.length}/160</span><button className="primary-btn" disabled={profileEditBusy}>{profileEditBusy?'Сохраняем…':'Сохранить'}</button></div>
      </form>
    </section></div>}
    {groupOpen && <div className="modal-backdrop" onClick={()=>setGroupOpen(false)}>
      <section className="group-modal" onClick={event=>event.stopPropagation()}>
        <div className="modal-head"><div><div className="eyebrow">НОВАЯ ПЕРЕПИСКА</div><h3>Создать группу</h3></div><button className="icon-button" onClick={()=>setGroupOpen(false)}>×</button></div>
        <label className="modal-label">Название группы<input value={groupTitle} onChange={event=>setGroupTitle(event.target.value)} placeholder="Например, Друзья" maxLength={60}/></label>
        <label className="modal-label">Добавить участников<input value={groupQuery} onChange={event=>setGroupQuery(event.target.value)} placeholder="Имя или @username"/></label>
        <div className="member-results">
          {groupQuery.trim().length<2 && <div className="list-hint">Введи минимум два символа для поиска людей.</div>}
          {groupResults.map(person=><label className="member-option" key={person.id}>
            <input type="checkbox" checked={groupMembers.includes(person.id)} onChange={event=>setGroupMembers(previous=>event.target.checked?[...previous,person.id]:previous.filter(id=>id!==person.id))}/>
            <div className="avatar">{person.avatar && !person.avatar.startsWith('http') ? person.avatar : initials(person.title)}</div>
            <span><b>{person.title}</b><small>{person.username?'@'+person.username:'Пользователь Nexura'}</small></span>
          </label>)}
        </div>
        <div className="modal-foot"><span>Выбрано: {groupMembers.length}</span><button className="primary-btn" onClick={createGroupChat} disabled={groupBusy}>{groupBusy?'Создаём…':'Создать группу'}</button></div>
      </section>
    </div>}
    <BrowserAudioDock state={browserAudioState}
      onToggle={async()=>{if(!sharedVoiceAudio)return;try{if(sharedVoiceAudio.paused)await sharedVoiceAudio.play();else sharedVoiceAudio.pause();}catch{}}}
      onSeek={value=>{if(sharedVoiceAudio)sharedVoiceAudio.currentTime=value;}}
      onClose={()=>{if(sharedVoiceAudio){sharedVoiceAudio.pause();sharedVoiceAudio.removeAttribute('src');sharedVoiceAudio.load();}if(sharedVoiceUrl){browserVoiceUrls.delete(sharedVoiceUrl);URL.revokeObjectURL(sharedVoiceUrl);}sharedVoiceUrl='';publishVoiceState({active:false,playing:false,current:0,duration:0});}}/>
  </div>;
}

createRoot(document.getElementById('root')).render(<App/>);