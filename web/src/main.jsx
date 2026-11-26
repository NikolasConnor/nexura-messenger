import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { createPortal } from 'react-dom';
import { createClient } from '@supabase/supabase-js';
import { Room, RoomEvent, Track } from 'livekit-client';
import { MessageCircle, Search, Settings, LogOut, Send, Plus, Palette, ShieldCheck, LockKeyhole, Users, ArrowLeft, RefreshCw, UserRound, Mail, Eye, EyeOff, CheckCheck, Copy, Reply, MoreHorizontal, SmilePlus, Pencil, Trash2, Forward, Paperclip, Mic, Square, Play, Pause, Camera, Image as ImageIcon, Video, Heart, MessageSquare, ChevronLeft, X, UserRoundSearch, Phone, PhoneOff, MicOff, Bell, Pin, Star, Clock } from 'lucide-react';
import './styles.css';
import { initializeOlm } from './crypto/olm.js';
import { loadEncryptedRecord, exportEncryptedRecords, importEncryptedRecords, loadEncryptedRecordsByPrefix, removeEncryptedRecord, saveEncryptedRecords } from './crypto/secure-store.js';
import { createRecoveryCode, encryptBackup, decryptBackup } from './crypto/backup.js';
import { loadMessageReactions, toggleMessageReaction, markConversationRead, updateMyProfile, deleteMessage, editEncryptedDirectMessage, getPinnedMessage, setPinnedMessage, sendCloudMessage, editCloudMessage, updateConversationChatMode } from './api/messenger.js';
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
  const raw = String(content || '').trim();
  if (!raw.startsWith('{')) return false;
  try {
    const value = JSON.parse(raw);
    if (!value || typeof value !== 'object' || value.schema === 'nexura-media-v1') return false;
    return value.protocol === 'nexura-olm-group-v1' ||
      value.protocol === 'nexura-olm-v1' ||
      (Array.isArray(value.recipients) && typeof value.sender_device_id === 'string') ||
      typeof value.ciphertext === 'string';
  } catch {
    return false;
  }
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

class AppErrorBoundary extends React.Component {
  constructor(props) { super(props); this.state = { failed: false }; }
  static getDerivedStateFromError() { return { failed: true }; }
  componentDidCatch(error, info) { console.error('Nexura web render failure', error, info); }
  render() {
    if (this.state.failed) return <main className="auth-shell"><section className="auth-panel"><div className="auth-card">
      <div className="brand"><span className="brand-mark"><MessageCircle size={23}/></span><span>Nexura</span></div>
      <h2>Не удалось отобразить этот экран</h2><p>Данные переписки не удалены. Обнови страницу и попробуй снова.</p>
      <button className="primary-btn wide" onClick={()=>window.location.reload()}>Перезагрузить Nexura</button>
    </div></section></main>;
    return this.props.children;
  }
}

function AuthScreen() {
  const [mode, setMode] = useState('login');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  async function loginWithTelegram() {
    setBusy(true); setError(''); setNotice('');
    try {
      const { error: authError } = await supabase.auth.signInWithOAuth({
        provider: 'custom:telegram',
        options: {
          redirectTo: window.location.origin + window.location.pathname,
          scopes: 'openid profile'
        }
      });
      if (authError) throw authError;
    } catch (e) {
      setBusy(false);
      setError(e?.message || 'Не удалось начать вход через Telegram');
    }
  }

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
        <div className="auth-divider"><span>или</span></div>
        <button type="button" className="telegram-login-btn wide" disabled={busy} onClick={loginWithTelegram}>
          <span className="telegram-login-mark" aria-hidden="true">➤</span>
          {busy ? 'Подключаем Telegram…' : 'Войти через Telegram'}
        </button>
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
  const [storiesFeed, setStoriesFeed] = useState([]);
  const [storiesFeedLoading, setStoriesFeedLoading] = useState(false);
  const [storiesFeedError, setStoriesFeedError] = useState('');
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
  const [hasOlderMessages,setHasOlderMessages] = useState(false);
  const [loadingOlderMessages,setLoadingOlderMessages] = useState(false);
  const olderLoadingRef = useRef(false);
  const messagesRef = useRef([]);
  const [peerTyping,setPeerTyping] = useState(false);
  const [peerPresence,setPeerPresence] = useState(null);
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
  const [contactMatches,setContactMatches] = useState([]);
  const [contactSyncBusy,setContactSyncBusy] = useState(false);
  const [contactBusy, setContactBusy] = useState(false);
  const [groupOpen, setGroupOpen] = useState(false);
  const [groupTitle, setGroupTitle] = useState('');
  const [groupQuery, setGroupQuery] = useState('');
  const [groupResults, setGroupResults] = useState([]);
  const [groupMembers, setGroupMembers] = useState([]);
  const [groupBusy, setGroupBusy] = useState(false);

  const [groupManagementOpen, setGroupManagementOpen] = useState(false);
  const [groupMemberRows, setGroupMemberRows] = useState([]);
  const [groupManageAllowed, setGroupManageAllowed] = useState(false);
  const [groupNameDraft, setGroupNameDraft] = useState('');
  const [groupMemberQuery, setGroupMemberQuery] = useState('');
  const [groupMemberResults, setGroupMemberResults] = useState([]);
  const [groupManageBusy, setGroupManageBusy] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [profileDraft, setProfileDraft] = useState({ display_name: '', username: '', avatar_url: '🙂', bio: '', age: '' });
  const [profileBusy, setProfileBusy] = useState(false);
  const [draft, setDraft] = useState('');
  const [pendingMedia, setPendingMedia] = useState([]);
  const [sendingMediaBatch, setSendingMediaBatch] = useState(false);
  const [recording, setRecording] = useState(false);
  const [recordingSeconds, setRecordingSeconds] = useState(0);
  const fileInputRef = useRef(null);
  const recorderRef = useRef(null);
  const recordingChunksRef = useRef([]);
  const [replyTarget, setReplyTarget] = useState(null);
  const [messageMenu, setMessageMenu] = useState(null);
  const [messageMenuPosition, setMessageMenuPosition] = useState({ top: 0, left: 0 });
  const [chatToolsOpen,setChatToolsOpen] = useState(false);
  const [chatSearchOpen,setChatSearchOpen] = useState(false);
  const [chatSearchQuery,setChatSearchQuery] = useState('');
  const [chatGalleryOpen,setChatGalleryOpen] = useState(false);
  const [pinnedMessageId,setPinnedMessageId] = useState(null);
  const [chatMutedUntil,setChatMutedUntil] = useState(null);
  const [scheduleOpen,setScheduleOpen] = useState(false);
  const [scheduleAt,setScheduleAt] = useState('');
  const [scheduleBusy,setScheduleBusy] = useState(false);
  const [scheduledCount,setScheduledCount] = useState(0);
  const [scheduledModalOpen,setScheduledModalOpen] = useState(false);
  const [scheduledItems,setScheduledItems] = useState([]);
  const scheduledSendingRef = useRef(new Set());

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
  const [callUi, setCallUi] = useState(null);
  const [incomingCall, setIncomingCall] = useState(null);
  const callRef = useRef({});
  const [notificationPrefs, setNotificationPrefs] = useState({push_enabled:true,sound_enabled:true,vibration_enabled:false});
  const [browserNotificationPermission, setBrowserNotificationPermission] = useState(typeof Notification==='undefined'?'unsupported':Notification.permission);
  const [e2eeRecoveryRequired,setE2eeRecoveryRequired] = useState(false);
  const [webBackupExists,setWebBackupExists] = useState(false);
  const [backupBusy,setBackupBusy] = useState(false);
  const [recoveryCode,setRecoveryCode] = useState('');
  const [restoreCode,setRestoreCode] = useState('');



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

  useEffect(()=>{
    if(!session?.user?.id)return;
    let active=true;
    supabase.from('notification_preferences')
      .select('push_enabled,sound_enabled,vibration_enabled')
      .eq('user_id',session.user.id).maybeSingle()
      .then(({data,error})=>{if(active&&!error&&data)setNotificationPrefs(data);});
    return()=>{active=false;};
  },[session?.user?.id]);

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
      // Keep the private Saved Messages chat available in every client.
      const {error:savedError}=await supabase.rpc('get_or_create_saved_conversation');
      if(savedError)throw savedError;
      // Fetch the membership list and unread counters together.
      const [membershipResult, unreadResult] = await Promise.all([
        supabase.from('conversation_members')
          .select('conversation_id, joined_at, conversations(id,kind,title,chat_mode,last_message_at,conversation_members(user_id,profiles(id,display_name,username,avatar_url)))')
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
          id: c.id, kind: c.kind, chatMode:c.chat_mode||'secret', peerUserId: peer?.user_id || null,
          title: c.kind === 'direct' ? (p?.display_name || p?.username || 'Пользователь') : (c.title || 'Группа'),
          avatar: c.title==='⭐ Избранное'?'⭐':(p?.avatar_url || null), updated: c.last_message_at, unread: unread[c.id] || 0
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
    initializeOlm().then(async()=>{
      const localDevice=await loadEncryptedRecord(session.user.id,'public-device').catch(()=>null);
      if(!localDevice){
        const {data,error}=await supabase.from('e2ee_web_state_backups').select('user_id').eq('user_id',session.user.id).maybeSingle();
        if(error)throw error;
        if(data){setE2eeRecoveryRequired(true);setE2eeStatus('Найдена облачная резервная копия · требуется восстановление');setCryptoRuntimeError('Восстанови E2EE-ключи из резервной копии в настройках безопасности.');return null;}
      }
      return ensureBrowserE2eeDevice(supabase,session.user.id);
    }).then(result => { if(!result)return;setE2eeRecoveryRequired(false);setE2eeReady(true);setE2eeStatus('Защита включена · ' + result.availableKeys + ' одноразовых ключей'); })
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
    if(message.content==='NEXURA_CLOUD_V1'){
      if(typeof cloudPlaintext==='string'&&cloudPlaintext.length)return {...message,content:cloudPlaintext,decrypted:true,encrypted:false,decryptionError:null,cloudMode:true};
      return {...message,encrypted:true,decryptionError:'Не удалось открыть облачное сообщение. Проверь подключение к облачной истории.',cloudMode:true};
    }
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

  const decryptMessageBatch = useCallback(async(chat,rawRows)=>{
    const missingSharedIds=rawRows.filter(message=>isEncrypted(message.content)&&!(typeof message.cloud_content==='string'&&message.cloud_content.length)).map(message=>message.id);
    let personalArchives=[];
    if(missingSharedIds.length&&session?.user?.id){
      const {data,error}=await supabase.from('message_cloud_archives').select('message_id,cloud_content')
        .eq('user_id',session.user.id).eq('conversation_id',chat.id).in('message_id',missingSharedIds);
      if(!error&&Array.isArray(data))personalArchives=data;
    }
    const cloudItems=[
      ...rawRows.filter(message=>(isEncrypted(message.content)||message.content==='NEXURA_CLOUD_V1')&&typeof message.cloud_content==='string'&&message.cloud_content.length)
        .map(message=>({id:message.id,cloud_content:message.cloud_content,scope:'shared'})),
      ...personalArchives.filter(item=>typeof item.message_id==='string'&&typeof item.cloud_content==='string'&&item.cloud_content.length)
        .map(item=>({id:item.message_id,cloud_content:item.cloud_content,scope:'user'}))
    ];
    let cloudPlaintextById=new Map();
    if(cloudItems.length){
      try{
        const {data,error}=await supabase.functions.invoke('cloud-history',{body:{action:'decrypt',conversation_id:chat.id,items:cloudItems}});
        if(!error&&Array.isArray(data?.items))cloudPlaintextById=new Map(data.items.filter(item=>typeof item?.id==='string'&&typeof item?.plaintext==='string').map(item=>[item.id,item.plaintext]));
      }catch{/* strict E2EE device checks remain the fallback */}
    }
    return Promise.all(rawRows.map(message=>decryptMessageRow(chat,message,cloudPlaintextById.get(message.id)||null)));
  },[session?.user?.id,decryptMessageRow]);

  useEffect(()=>{messagesRef.current=messages;},[messages]);

  const loadMessages = useCallback(async (chat, { silent = false, before = null } = {}) => {
    if (!chat?.id) return;
    const requestId = ++messageLoadSeq.current;
    if (!silent) setLoadingMessages(true);
    if (!silent) setNotice('');
    try {
      // Load the newest page, then reverse it for chronological rendering.
      // The old ascending+limit query could hide all recent messages in long chats.
      let messageQuery=supabase.from('messages')
        .select('id,sender_id,content,cloud_content,created_at,message_type,reply_to_id,edited_at,deleted_at')
        .eq('conversation_id',chat.id).is('deleted_at',null);
      if(before?.created_at&&before?.id)messageQuery=messageQuery.or(
        'created_at.lt.'+before.created_at+',and(created_at.eq.'+before.created_at+',id.lt.'+before.id+')'
      );
      else if(before?.created_at)messageQuery=messageQuery.lt('created_at',before.created_at);
      const [messageResult,reactionsResult]=await Promise.all([
        messageQuery.order('created_at',{ascending:false}).order('id',{ascending:false}).limit(50),
        before?Promise.resolve({data:null}):loadMessageReactions(supabase,chat.id).catch(()=>({}))
      ]);
      if (messageResult.error) throw messageResult.error;
      const rawRows = (messageResult.data || []).reverse();
      const rows = await decryptMessageBatch(chat,rawRows);
      if (requestId !== messageLoadSeq.current) return;
      const localPending = [...pendingMediaOutboxRef.current.values()].filter(item => item.conversation_id === chat.id);
      if(before){
        setMessages(previous=>{
          const combined=[...rows,...previous,...localPending];
          return [...new Map(combined.map(item=>[item.id,item])).values()].sort((a,b)=>new Date(a.created_at)-new Date(b.created_at));
        });
        setHasOlderMessages(rawRows.length===50);
      }else{
        if(!silent||rawRows.length===50)setHasOlderMessages(rawRows.length===50);
        setMessages(previous=>{
          const base=silent&&selectedChatIdRef.current===chat.id?previous:[];
          const combined=[...base,...rows,...localPending];
          return [...new Map(combined.map(item=>[item.id,item])).values()].sort((a,b)=>new Date(a.created_at)-new Date(b.created_at)).slice(-500);
        });
      }
      if(!before)setMessageReactions(reactionsResult || {});
      markConversationRead(supabase, chat.id).catch(() => {});
    } catch (error) {
      if (requestId === messageLoadSeq.current && !silent) setNotice('Не удалось открыть переписку: ' + (error?.message || 'ошибка'));
    } finally {
      if (requestId === messageLoadSeq.current) setLoadingMessages(false);
    }
  }, [session?.user?.id, decryptMessageBatch]);

  const loadOlderMessages = useCallback(async()=>{
    const chat=selected;
    const oldest=messagesRef.current[0];
    if(!chat?.id||!oldest||!hasOlderMessages||olderLoadingRef.current)return;
    olderLoadingRef.current=true;setLoadingOlderMessages(true);
    const pane=messagesPaneRef.current;
    const previousHeight=pane?.scrollHeight||0;
    const previousTop=pane?.scrollTop||0;
    try{
      await loadMessages(chat,{silent:true,before:oldest});
      requestAnimationFrame(()=>requestAnimationFrame(()=>{
        if(pane)pane.scrollTop=previousTop+(pane.scrollHeight-previousHeight);
      }));
    }finally{olderLoadingRef.current=false;setLoadingOlderMessages(false);}
  },[selected?.id,hasOlderMessages,loadMessages]);

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
    setHasOlderMessages(false);
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
          return next.slice(-500);
        });
        if (message.sender_id !== session?.user?.id) markConversationRead(supabase, chat.id).catch(() => {});
      })
      .subscribe();
    return () => { active = false; supabase.removeChannel(channel); };
  }, [selected?.id, loadMessages, decryptMessageBatch, session?.user?.id, e2eeReady]);

  useEffect(() => {
    if (!session?.user?.id) return;
    let refreshTimer = null;
    const channel = supabase.channel('web-chat-list-' + session.user.id)
      .on('postgres_changes', { event: '*', schema: 'public', table: 'messages' }, payload => {
        if (refreshTimer) clearTimeout(refreshTimer);
        refreshTimer = setTimeout(() => loadChats({ silent: true }), 350);
        const message=payload.new;
        if(message?.sender_id===session.user.id||!message?.conversation_id)return;
        if(!notificationPrefs.push_enabled||typeof Notification==='undefined'||Notification.permission!=='granted'||!document.hidden)return;
        const chat=chats.find(item=>item.id===message.conversation_id);
        const notification=new Notification('Nexura', {
          body:chat?'Новое сообщение от '+chat.title:'Новое сообщение',
          tag:'nexura-chat-'+message.conversation_id,
          silent:!notificationPrefs.sound_enabled
        });
        notification.onclick=()=>{
          window.focus();
          const target=chats.find(item=>item.id===message.conversation_id);
          if(target){setActiveTab('chats');setSelected(target);setMobileChatOpen(true);}
          notification.close();
        };
      })
      .subscribe();
    return () => {
      if (refreshTimer) clearTimeout(refreshTimer);
      supabase.removeChannel(channel);
    };
  }, [session?.user?.id, loadChats, chats, notificationPrefs]);

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
      const {data:blockRow,error:blockError}=await supabase.from('user_blocks').select('blocked_id').eq('blocker_id',session.user.id).eq('blocked_id',person.id).maybeSingle();
      if(blockError)throw blockError;
      let bannerUrl = data.banner_url || '';
      if (bannerUrl.startsWith('social:')) {
        const {data: signed, error: signError} = await supabase.storage.from('nexura-social-media').createSignedUrl(bannerUrl.slice(7), 7*86400);
        if (!signError) bannerUrl = signed.signedUrl;
      }
      setViewedProfile({...data, banner_display_url:bannerUrl, isBlocked:!!blockRow});
      setViewedPhotos(photos); setViewedStories(stories);
    } catch(error) { setNotice('Не удалось открыть профиль: '+error.message); }
    finally { setSocialBusy(false); }
  }

  async function loadStoriesFeed() {
    if (!session?.user?.id) return;
    setStoriesFeedLoading(true);
    setStoriesFeedError('');
    try {
      const stories=await loadStories(supabase,{activeOnly:true});
      const ids=[...new Set(stories.map(story=>story.user_id))];
      if(!ids.length){setStoriesFeed([]);return;}
      const {data:profiles,error}=await supabase.from('profiles').select('id,display_name,username,avatar_url').in('id',ids);
      if(error)throw error;
      const byId=new Map((profiles||[]).map(profile=>[profile.id,profile]));
      setStoriesFeed(stories.map(story=>({...story,profile:byId.get(story.user_id)||{display_name:'Пользователь'}})));
    } catch(error) {
      console.error('Nexura stories feed:', error);
      setStoriesFeedError(error?.message || 'Не удалось загрузить истории');
      setNotice('Не удалось загрузить истории: '+(error?.message || 'ошибка'));
    } finally { setStoriesFeedLoading(false); }
  }

  useEffect(()=>{
    if(activeTab!=='people'||!session?.user?.id)return;
    void loadStoriesFeed();
    let timer=null;
    const channel=supabase.channel('web-stories-feed-'+session.user.id)
      .on('postgres_changes',{event:'*',schema:'public',table:'user_stories'},()=>{
        if(timer)clearTimeout(timer);
        timer=setTimeout(()=>void loadStoriesFeed(),250);
      }).subscribe();
    return()=>{if(timer)clearTimeout(timer);supabase.removeChannel(channel);};
  },[activeTab,session?.user?.id]);

  async function deleteCurrentSocialItem() {
    if(!socialViewer||socialViewer.user_id!==session.user.id||socialBusy)return;
    if(!window.confirm(socialViewer.kind==='story'?'Удалить эту историю?':'Удалить фотографию из профиля?'))return;
    setSocialBusy(true);
    try{
      const table=socialViewer.kind==='story'?'user_stories':'profile_photos';
      const {error}=await supabase.from(table).delete().eq('id',socialViewer.id).eq('user_id',session.user.id);
      if(error)throw error;
      const {error:storageError}=await supabase.storage.from('nexura-social-media').remove([socialViewer.storage_path]);
      if(storageError)throw storageError;
      if(socialViewer.kind==='story'){setProfileStories(previous=>previous.filter(item=>item.id!==socialViewer.id));setStoriesFeed(previous=>previous.filter(item=>item.id!==socialViewer.id));}
      else setProfilePhotos(previous=>previous.filter(item=>item.id!==socialViewer.id));
      setSocialViewer(null);setNotice('Публикация удалена');
    }catch(error){setNotice('Не удалось удалить публикацию: '+error.message);}
    finally{setSocialBusy(false);}
  }

  async function loadOwnSocial() {
    if (!session?.user?.id) return;
    try {
      const [photos, activeStories, archivedStories] = await Promise.all([
        loadProfilePhotos(supabase, session.user.id),
        loadStories(supabase, {userId:session.user.id, activeOnly:true}),
        loadStories(supabase, {userId:session.user.id, activeOnly:false})
      ]);
      const stories=[...activeStories,...archivedStories].sort((a,b)=>new Date(b.created_at)-new Date(a.created_at));
      setProfilePhotos(photos); setProfileStories(stories);
    } catch(error) { setNotice('Не удалось загрузить публикации: '+error.message); }
  }

  async function toggleViewedUserBlock() {
    if(!viewedProfile||socialBusy)return;
    const shouldBlock=!viewedProfile.isBlocked;
    if(!window.confirm(shouldBlock?'Заблокировать этого пользователя? Он не сможет отправлять тебе сообщения.':'Разблокировать пользователя?'))return;
    setSocialBusy(true);
    try{
      const result=shouldBlock
        ? await supabase.from('user_blocks').upsert({blocker_id:session.user.id,blocked_id:viewedProfile.id},{onConflict:'blocker_id,blocked_id',ignoreDuplicates:true})
        : await supabase.from('user_blocks').delete().eq('blocker_id',session.user.id).eq('blocked_id',viewedProfile.id);
      if(result.error)throw result.error;
      setViewedProfile(previous=>({...previous,isBlocked:shouldBlock}));
      setNotice(shouldBlock?'Пользователь заблокирован':'Пользователь разблокирован');
    }catch(error){setNotice('Не удалось изменить блокировку: '+error.message);}
    finally{setSocialBusy(false);}
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
    if(file.size<1||file.size>50*1024*1024){setNotice('Файл должен быть не пустым и не больше 50 МБ.');return;}
    if(kind==='story'&&!file.type.startsWith('image/')&&!file.type.startsWith('video/')){setNotice('История должна быть фото или видео.');return;}
    if(kind==='photo'&&!file.type.startsWith('image/')){setNotice('В галерею можно добавить только изображение.');return;}
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
      if(kind==='story') {
        const published={...data,url:signed.signedUrl,profile:{display_name:profile?.display_name||'Вы',username:profile?.username||'',avatar_url:profile?.avatar_url||'🙂'}};
        setProfileStories(previous=>[published,...previous.filter(item=>item.id!==data.id)]);
        setStoriesFeed(previous=>[published,...previous.filter(item=>item.id!==data.id)].sort((a,b)=>new Date(b.created_at)-new Date(a.created_at)));
      } else setProfilePhotos(previous=>[{...data,url:signed.signedUrl},...previous]);
      setSocialCaption('');
      setNotice(kind==='story'?'История опубликована на 24 часа':'Фотография добавлена в профиль');
    } catch(error) { setNotice('Не удалось опубликовать: '+error.message); }
    finally { setSocialBusy(false); }
  }

  async function openAdvancedSettings() {
    setAdvancedSettingsOpen(true); setSettingsSection('privacy'); setSettingsBusy(true);
    try {
      const [profileResult,presenceResult,blocksResult,devicesResult,bundle,notificationsResult,webBackupResult] = await Promise.all([
        supabase.from('profiles').select('read_receipts_enabled').eq('id',session.user.id).maybeSingle(),
        supabase.from('user_presence').select('show_online_status').eq('user_id',session.user.id).maybeSingle(),
        supabase.from('user_blocks').select('blocked_id,created_at').eq('blocker_id',session.user.id).order('created_at',{ascending:false}),
        supabase.from('e2ee_devices').select('device_id,created_at,protocol,bundle_version,curve25519_key,ed25519_key,is_active').eq('user_id',session.user.id).eq('is_active',true).order('created_at',{ascending:false}),
        loadEncryptedRecord(session.user.id,'public-device'),
        supabase.from('notification_preferences').select('push_enabled,sound_enabled,vibration_enabled').eq('user_id',session.user.id).maybeSingle(),
        supabase.from('e2ee_web_state_backups').select('updated_at').eq('user_id',session.user.id).maybeSingle()
      ]);
      if(profileResult.error)throw profileResult.error;
      if(presenceResult.error)throw presenceResult.error;
      if(blocksResult.error)throw blocksResult.error;
      if(devicesResult.error)throw devicesResult.error;
      if(notificationsResult.error)throw notificationsResult.error;
      if(webBackupResult.error)throw webBackupResult.error;
      setWebBackupExists(!!webBackupResult.data);
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

  async function createWebE2eeBackup() {
    if(!e2eeReady||backupBusy)return;
    setBackupBusy(true);
    try {
      const recovery=createRecoveryCode();
      const snapshot=await exportEncryptedRecords(session.user.id);
      const encrypted=await encryptBackup(session.user.id,snapshot,recovery);
      const {error}=await supabase.from('e2ee_web_state_backups').upsert({
        user_id:session.user.id,format_version:1,encrypted_payload:encrypted,updated_at:new Date().toISOString()
      },{onConflict:'user_id'});
      if(error)throw error;
      setRecoveryCode(recovery);setWebBackupExists(true);
      setNotice('Резервная копия создана. Сохрани код восстановления отдельно от этого устройства.');
    } catch(error) { setNotice('Не удалось создать резервную копию: '+error.message); }
    finally { setBackupBusy(false); }
  }

  async function restoreWebE2eeBackup() {
    if(backupBusy)return;
    setBackupBusy(true);
    try {
      if(await loadEncryptedRecord(session.user.id,'public-device').catch(()=>null)) {
        throw new Error('На этом браузере уже есть криптографическая идентичность. Восстановление разрешено только на новом браузере без локального E2EE-устройства.');
      }
      const {data,error}=await supabase.from('e2ee_web_state_backups')
        .select('format_version,encrypted_payload').eq('user_id',session.user.id).maybeSingle();
      if(error)throw error;
      if(!data?.encrypted_payload)throw new Error('Облачная резервная копия не найдена');
      const snapshot=await decryptBackup(session.user.id,data.encrypted_payload,restoreCode);
      const count=await importEncryptedRecords(session.user.id,snapshot);
      setE2eeRecoveryRequired(false);setE2eeReady(false);setCryptoRuntimeError('');
      setNotice('Ключи восстановлены ('+count+' записей). Перезапускаем веб-клиент…');
      window.location.reload();
    } catch(error) { setNotice('Не удалось восстановить копию: '+error.message); }
    finally { setBackupBusy(false); }
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

  const visibleMessages = useMemo(() => {
    const query=chatSearchQuery.trim().toLocaleLowerCase();
    return query ? messages.filter(message=>String(message.content||'').toLocaleLowerCase().includes(query)) : messages;
  },[messages,chatSearchQuery]);

  useEffect(()=>{
    if(!selected?.id||!session?.user?.id)return;
    let cancelled=false;
    Promise.all([
      getPinnedMessage(supabase,selected.id),
      supabase.from('conversation_members').select('muted_until').eq('conversation_id',selected.id).eq('user_id',session.user.id).maybeSingle()
    ]).then(([pin, mute])=>{
      if(cancelled)return;
      setPinnedMessageId(pin||null);
      if(!mute.error)setChatMutedUntil(mute.data?.muted_until||null);
    }).catch(error=>{if(!cancelled)setNotice('Не удалось загрузить настройки чата: '+error.message);});
    return()=>{cancelled=true;};
  },[selected?.id,session?.user?.id]);

  async function changeChatMute(durationMs) {
    if(!selected)return;
    const muted_until=durationMs==null?null:new Date(Date.now()+durationMs).toISOString();
    try {
      const {error}=await supabase.from('conversation_members').update({muted_until})
        .eq('conversation_id',selected.id).eq('user_id',session.user.id);
      if(error)throw error;
      setChatMutedUntil(muted_until);setChatToolsOpen(false);
      setNotice(muted_until?'Уведомления чата отключены.':'Уведомления чата включены.');
    } catch(error) { setNotice('Не удалось изменить уведомления: '+error.message); }
  }

  async function togglePinnedChatMessage(message) {
    if(!selected||!message)return;
    try {
      await setPinnedMessage(supabase,selected.id,pinnedMessageId===message.id?null:message.id);
      setPinnedMessageId(pinnedMessageId===message.id?null:message.id);
      setNotice(pinnedMessageId===message.id?'Сообщение откреплено.':'Сообщение закреплено.');
    } catch(error) { setNotice('Не удалось изменить закрепление: '+error.message); }
    finally { setMessageMenu(null); }
  }

  const pinnedMessage=useMemo(()=>messages.find(message=>message.id===pinnedMessageId)||null,[messages,pinnedMessageId]);
  const chatGalleryItems=useMemo(()=>messages.filter(message=>parseMediaPayload(message.content)||/https?:\/\//i.test(String(message.content||''))),[messages]);

  async function toggleConversationMode() {
    if(!selected)return;
    const next=selected.chatMode==='cloud'?'secret':'cloud';
    if(next==='secret'&&!e2eeReady){setNotice('Сначала подключи защищённое E2EE-устройство.');return;}
    const warning=next==='cloud'?'Облачный режим хранит текст сообщений на сервере без сквозного шифрования. Продолжить?':'Включить секретный режим? Новые сообщения будут шифроваться отдельно для устройств участников.';
    if(!window.confirm(warning))return;
    try {
      await updateConversationChatMode(supabase,selected.id,next);
      setSelected(previous=>previous?{...previous,chatMode:next}:previous);
      setChats(previous=>previous.map(chat=>chat.id===selected.id?{...chat,chatMode:next}:chat));
      setChatToolsOpen(false);setNotice(next==='secret'?'Секретный режим включён.':'Облачный режим включён. Текст новых сообщений не будет E2EE.');
    } catch(error) { setNotice('Не удалось изменить режим чата: '+error.message); }
  }
  function openScheduleDialog() {
    const date=new Date(Date.now()+5*60*1000);
    setScheduleAt(new Date(date.getTime()-date.getTimezoneOffset()*60000).toISOString().slice(0,16));
    setScheduleOpen(true);
  }
  async function scheduleCurrentMessage() {
    const text=draft.trim(),sendAt=new Date(scheduleAt).getTime();
    if(!selected||!text||!Number.isFinite(sendAt)||sendAt<=Date.now()||sendAt>Date.now()+30*24*60*60*1000){setNotice('Выбери время в пределах следующих 30 дней.');return;}
    if(selected.chatMode!=='cloud'&&!e2eeReady){setNotice('Защищённое устройство ещё не готово.');return;}
    setScheduleBusy(true);
    try {
      const id=crypto.randomUUID();
      await saveEncryptedRecords(session.user.id,[['scheduled:'+id,{id,conversationId:selected.id,kind:selected.kind,peerUserId:selected.peerUserId,chatTitle:selected.title,chatMode:selected.chatMode||'secret',text,replyToId:replyTarget?.id||null,sendAt}]]);
      setDraft('');setReplyTarget(null);setScheduleOpen(false);setScheduledCount(value=>value+1);
      setNotice('Сообщение запланировано. Веб-клиент отправит его, когда Nexura будет открыта.');
    } catch(error) { setNotice('Не удалось запланировать сообщение: '+error.message); }
    finally { setScheduleBusy(false); }
  }
  async function openScheduledQueue() {
    try {
      const rows=await loadEncryptedRecordsByPrefix(session.user.id,'scheduled:');
      setScheduledItems(rows.map(row=>row.value).filter(Boolean).sort((a,b)=>a.sendAt-b.sendAt));
      setScheduledModalOpen(true);
    } catch(error) { setNotice('Не удалось открыть очередь отложенных сообщений: '+error.message); }
  }
  async function cancelScheduledMessage(item) {
    try {
      await removeEncryptedRecord(session.user.id,'scheduled:'+item.id);
      setScheduledItems(previous=>previous.filter(row=>row.id!==item.id));
      setScheduledCount(value=>Math.max(0,value-1));
      setNotice('Отложенное сообщение отменено.');
    } catch(error) { setNotice('Не удалось отменить сообщение: '+error.message); }
  }

  const processScheduledMessages = useCallback(async()=>{
    if(!session?.user?.id)return;
    const pending=await loadEncryptedRecordsByPrefix(session.user.id,'scheduled:');
    setScheduledCount(pending.length);
    for(const record of pending) {
      const item=record.value;
      if(!item?.id||item.sendAt>Date.now()||scheduledSendingRef.current.has(item.id))continue;
      const chat=chats.find(candidate=>candidate.id===item.conversationId);
      if(!chat||chat.chatMode!==(item.chatMode||'secret'))continue;
      if(chat.chatMode!=='cloud'&&!e2eeReady)continue;
      scheduledSendingRef.current.add(item.id);
      try {
        const payload={userId:session.user.id,conversationId:item.conversationId,messageId:item.id,plaintext:item.text,replyToId:item.replyToId||null};
        if(chat.chatMode==='cloud')await sendCloudMessage(supabase,payload);
        else if(item.kind==='group')await sendBrowserGroupMessage(supabase,payload);
        else await sendBrowserDirectMessage(supabase,{...payload,peerUserId:item.peerUserId});
        await removeEncryptedRecord(session.user.id,record.recordId);
        setScheduledCount(value=>Math.max(0,value-1));setNotice('Отложенное сообщение отправлено в чат «'+(chat.title||item.chatTitle)+'».');
        loadChats({silent:true});
      } catch(error) { setNotice('Не удалось отправить отложенное сообщение: '+error.message); }
      finally { scheduledSendingRef.current.delete(item.id); }
    }
  },[session?.user?.id,chats,e2eeReady,loadChats]);
  useEffect(()=>{
    if(!session?.user?.id)return;
    let active=true;
    const tick=()=>{if(active)processScheduledMessages().catch(error=>setNotice('Ошибка очереди отложенных сообщений: '+error.message));};
    tick();const timer=setInterval(tick,10000);
    return()=>{active=false;clearInterval(timer);};
  },[session?.user?.id,processScheduledMessages]);

  async function syncBrowserContacts() {
    if(!navigator.contacts?.select||!window.isSecureContext) {
      setNotice('Импорт контактов поддерживается только в некоторых мобильных браузерах. Используй каталог людей или отправь приглашение.');
      return;
    }
    setContactSyncBusy(true);
    try {
      const picked=await navigator.contacts.select(['name','tel'],{multiple:true});
      const phones=[...new Set(picked.flatMap(item=>item.tel||[]).map(value=>String(value).replace(/[^+0-9]/g,'')).filter(value=>value.length>=7&&value.length<=20))].slice(0,500);
      if(!phones.length){setNotice('В выбранных контактах не найдено телефонных номеров.');return;}
      const {data,error}=await supabase.rpc('match_contact_users',{p_phone_numbers:phones});
      if(error)throw error;
      setContactMatches(Array.isArray(data)?data:[]);
      setNotice(Array.isArray(data)&&data.length?'Найдено контактов в Nexura: '+data.length:'Среди выбранных контактов пока нет пользователей Nexura.');
    } catch(error) {
      if(error?.name!=='AbortError')setNotice('Не удалось синхронизировать контакты: '+error.message);
    } finally { setContactSyncBusy(false); }
  }

  async function shareNexuraInvite() {
    const url=window.location.origin+window.location.pathname;
    const payload={title:'Nexura Messenger',text:'Давай общаться в Nexura Messenger',url};
    try {
      if(navigator.share)await navigator.share(payload);
      else if(navigator.clipboard?.writeText&&window.isSecureContext){await navigator.clipboard.writeText(url);setNotice('Ссылка на Nexura скопирована.');}
      else setNotice('Ссылка: '+url);
    } catch(error) { if(error?.name!=='AbortError')setNotice('Не удалось поделиться ссылкой: '+error.message); }
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

  async function openGroupManagement() {
    if(!selected||selected.kind!=='group')return;
    setGroupManagementOpen(true);setGroupNameDraft(selected.title);setGroupMemberQuery('');setGroupMemberResults([]);
    setGroupManageBusy(true);
    try{
      const [{data:members,error:membersError},{data:own,error:ownError}]=await Promise.all([
        supabase.from('conversation_members').select('user_id,member_role,joined_at,profiles(id,display_name,username,avatar_url)').eq('conversation_id',selected.id).order('joined_at'),
        supabase.from('conversation_members').select('member_role').eq('conversation_id',selected.id).eq('user_id',session.user.id).maybeSingle()
      ]);
      if(membersError)throw membersError;if(ownError)throw ownError;
      setGroupMemberRows(members||[]);setGroupManageAllowed(['owner','admin'].includes(own?.member_role));
    }catch(error){setNotice('Не удалось загрузить участников: '+error.message);}
    finally{setGroupManageBusy(false);}
  }

  useEffect(()=>{
    let cancelled=false;
    if(!groupManagementOpen||groupMemberQuery.trim().length<2){setGroupMemberResults([]);return;}
    const timer=setTimeout(async()=>{
      try{const rows=await loadPeopleDirectory(supabase,session.user.id,groupMemberQuery,0,15);if(!cancelled)setGroupMemberResults(rows.filter(p=>!groupMemberRows.some(m=>m.user_id===p.id)));}
      catch(error){if(!cancelled)setNotice('Не удалось найти участников: '+error.message);}
    },200);
    return()=>{cancelled=true;clearTimeout(timer);};
  },[groupManagementOpen,groupMemberQuery,groupMemberRows,session?.user?.id]);

  async function renameCurrentGroup(){
    const title=groupNameDraft.trim();
    if(title.length<2||title.length>60){setNotice('Название группы должно содержать от 2 до 60 символов');return;}
    setGroupManageBusy(true);
    try{
      const {error}=await supabase.from('conversations').update({title}).eq('id',selected.id);
      if(error)throw error;
      setSelected(previous=>({...previous,title}));
      setChats(previous=>previous.map(chat=>chat.id===selected.id?{...chat,title}:chat));
      setNotice('Название группы изменено');
    }catch(error){setNotice('Не удалось изменить название: '+error.message);}
    finally{setGroupManageBusy(false);}
  }

  async function addCurrentGroupMember(person){
    setGroupManageBusy(true);
    try{
      const {error}=await supabase.from('conversation_members').insert({conversation_id:selected.id,user_id:person.id,member_role:'member'});
      if(error)throw error;
      await openGroupManagement();setNotice('Участник добавлен');
    }catch(error){setNotice('Не удалось добавить участника: '+error.message);setGroupManageBusy(false);}
  }

  async function changeGroupMemberRole(userId,role){
    setGroupManageBusy(true);
    try{
      const {error}=await supabase.from('conversation_members').update({member_role:role}).eq('conversation_id',selected.id).eq('user_id',userId);
      if(error)throw error;
      setGroupMemberRows(previous=>previous.map(row=>row.user_id===userId?{...row,member_role:role}:row));
    }catch(error){setNotice('Не удалось изменить роль: '+error.message);}
    finally{setGroupManageBusy(false);}
  }

  async function removeCurrentGroupMember(userId){
    const isSelf=userId===session.user.id;
    if(!window.confirm(isSelf?'Выйти из этой группы?':'Удалить участника из группы?'))return;
    setGroupManageBusy(true);
    try{
      const {error}=await supabase.from('conversation_members').delete().eq('conversation_id',selected.id).eq('user_id',userId);
      if(error)throw error;
      if(isSelf){setGroupManagementOpen(false);setSelected(null);setChats(previous=>previous.filter(chat=>chat.id!==selected.id));await loadChats();}
      else setGroupMemberRows(previous=>previous.filter(row=>row.user_id!==userId));
    }catch(error){setNotice('Не удалось выполнить действие: '+error.message);}
    finally{setGroupManageBusy(false);}
  }

  async function subscribeCallChannel(channel) {
    return new Promise((resolve,reject)=>{
      let settled=false;
      const timer=setTimeout(()=>{if(!settled){settled=true;reject(new Error('Сервер сигнализации не ответил'));}},15000);
      channel.subscribe(status=>{
        if(status==='SUBSCRIBED'&&!settled){settled=true;clearTimeout(timer);resolve();}
        else if(['CHANNEL_ERROR','TIMED_OUT','CLOSED'].includes(status)&&!settled){settled=true;clearTimeout(timer);reject(new Error('Не удалось подключиться к серверу звонков: '+status));}
      });
    });
  }

  async function ensureCallChannels(peerUserId) {
    let current=callRef.current;
    if(current.peerChannel&&current.peerUserId===peerUserId)return;
    if(current.peerChannel)await supabase.removeChannel(current.peerChannel);
    let own=current.ownChannel;
    if(!own){
      own=supabase.channel('user:'+session.user.id,{config:{private:true,broadcast:{ack:true,self:false}}});
      own.on('broadcast',{event:'call_signal'},({payload})=>{void receiveCallSignal(payload);});
      callRef.current={...callRef.current,ownChannel:own};
      await subscribeCallChannel(own);
    }
    const peer=supabase.channel('user:'+peerUserId,{config:{private:true,broadcast:{ack:true,self:false}}});
    callRef.current={...callRef.current,ownChannel:own,peerChannel:peer,peerUserId};
    await subscribeCallChannel(peer);
  }

  useEffect(()=>{
    if(!session?.user?.id)return;
    let alive=true;
    const own=supabase.channel('user:'+session.user.id,{config:{private:true,broadcast:{ack:true,self:false}}});
    own.on('broadcast',{event:'call_signal'},({payload})=>{if(alive)void receiveCallSignal(payload);});
    own.subscribe(status=>{
      if(status==='SUBSCRIBED'&&alive)callRef.current={...callRef.current,ownChannel:own};
    });
    return()=>{
      alive=false;
      const current=callRef.current;
      if(current.peerChannel)supabase.removeChannel(current.peerChannel);
      supabase.removeChannel(own);
      if(current.room){current.ending=true;current.room.disconnect().catch(()=>{});}
      (current.audioElements||[]).forEach(element=>{try{element.pause();element.remove();}catch{}});
      callRef.current={};
    };
  },[session?.user?.id]);

  async function sendCallSignal(signal) {
    const channel=callRef.current.peerChannel;
    if(!channel)throw new Error('Канал звонка не подключён');
    const result=await channel.send({type:'broadcast',event:'call_signal',payload:signal});
    if(result!=='ok')throw new Error('Сервер сигнализации не подтвердил доставку');
  }

  async function connectCallRoom({callId,peerUserId,conversationId,sendAnswer=false}) {
    const current=callRef.current;
    if(current.callId!==callId||current.room)return;
    const {data,error}=await supabase.functions.invoke('livekit-token',{
      body:{conversation_id:conversationId,peer_user_id:peerUserId}
    });
    if(error)throw error;
    if(!data?.token||!String(data.server_url||'').startsWith('wss://'))throw new Error(data?.error||'LiveKit не выдал данные для подключения');
    const room=new Room({adaptiveStream:true,dynacast:true});
    callRef.current={...callRef.current,room,ending:false};
    room.on(RoomEvent.TrackSubscribed,(track)=>{
      if(track.kind!==Track.Kind.Audio)return;
      const element=track.attach();
      element.autoplay=true;element.setAttribute('playsinline','');
      document.body.appendChild(element);
      callRef.current.audioElements=[...(callRef.current.audioElements||[]),element];
    });
    room.on(RoomEvent.TrackUnsubscribed,(track)=>{
      track.detach().forEach(element=>{element.remove();});
      callRef.current.audioElements=(callRef.current.audioElements||[]).filter(element=>element.isConnected);
    });
    room.on(RoomEvent.ParticipantConnected,()=>{
      setCallUi(previous=>previous?{...previous,connected:true,status:'На связи',connectedAt:previous.connectedAt||Date.now()}:previous);
    });
    room.on(RoomEvent.ParticipantDisconnected,()=>{
      if(!room.remoteParticipants.size)setCallUi(previous=>previous?{...previous,connected:false,status:'Ожидаем собеседника…'}:previous);
    });
    room.on(RoomEvent.Disconnected,()=>{
      if(!callRef.current.ending)setCallUi(previous=>previous?{...previous,connected:false,status:'Связь прервана'}:previous);
    });
    await room.connect(data.server_url,data.token);
    if(callRef.current.callId!==callId){await room.disconnect();return;}
    await room.localParticipant.setMicrophoneEnabled(true);
    setCallUi(previous=>previous?{...previous,status:room.remoteParticipants.size?'На связи':'Ожидаем собеседника…',connected:room.remoteParticipants.size>0,connectedAt:room.remoteParticipants.size?(previous.connectedAt||Date.now()):previous.connectedAt}:previous);
    if(sendAnswer)await sendCallSignal({type:'answer',call_id:callId,from:session.user.id,to:peerUserId});
  }

  async function receiveCallSignal(signal) {
    if(!signal||signal.to!==session.user.id||!signal.from||signal.from===session.user.id)return;
    const type=signal.type, id=signal.call_id;
    if(!id)return;
    if(type==='offer'){
      if(callRef.current.callId||incomingCall){
        await ensureCallChannels(signal.from).catch(()=>{});
        await sendCallSignal({type:'busy',call_id:id,from:session.user.id,to:signal.from}).catch(()=>{});
        return;
      }
      try{
        await ensureCallChannels(signal.from);
        let conversationId=selected?.peerUserId===signal.from?selected.id:null;
        if(!conversationId){
          const {data,error}=await supabase.rpc('create_direct_conversation',{p_other_user_id:signal.from});
          if(error)throw error;
          conversationId=String(data||'').replace(/^"|"$/g,'');
        }
        const {data:peer,error:peerError}=await supabase.from('profiles').select('display_name,username').eq('id',signal.from).maybeSingle();
        if(peerError)throw peerError;
        setIncomingCall({callId:id,peerUserId:signal.from,peerName:peer?.display_name||peer?.username||'Входящий вызов',conversationId});
        await sendCallSignal({type:'ringing',call_id:id,from:session.user.id,to:signal.from});
      }catch(error){setNotice('Не удалось принять сигнал звонка: '+error.message);}
      return;
    }
    if(id!==callRef.current.callId)return;
    if(type==='ringing'){setCallUi(previous=>previous?{...previous,status:'Вызов…'}:previous);return;}
    if(type==='answer'){
      clearTimeout(callRef.current.timeout);
      setCallUi(previous=>previous?{...previous,status:'Соединяем…'}:previous);
      try{await connectCallRoom({callId:id,peerUserId:callRef.current.peerUserId,conversationId:callRef.current.conversationId,sendAnswer:false});}
      catch(error){setNotice('Не удалось подключить звонок: '+error.message);await endCall(false);}
      return;
    }
    if(['end','decline','busy'].includes(type)){
      const message=type==='busy'?'Абонент занят':type==='decline'?'Звонок отклонён':'Звонок завершён';
      await finishCallLocal();
      setNotice(message);
    }
  }

  async function finishCallLocal() {
    const current=callRef.current;
    clearTimeout(current.timeout);
    current.ending=true;
    if(current.room){try{await current.room.disconnect();}catch{}}
    (current.audioElements||[]).forEach(element=>{try{element.pause();element.srcObject=null;element.remove();}catch{}});
    if(current.peerChannel)await supabase.removeChannel(current.peerChannel);
    callRef.current=current.ownChannel?{ownChannel:current.ownChannel}:{};
    setCallUi(null);setIncomingCall(null);
  }

  async function startAudioCall() {
    if(!selected||selected.kind!=='direct'||!selected.peerUserId||callRef.current.callId||incomingCall)return;
    try{
      if(!navigator.mediaDevices?.getUserMedia)throw new Error('Браузер не поддерживает аудиозвонки');
      const permissionStream=await navigator.mediaDevices.getUserMedia({audio:true});
      permissionStream.getTracks().forEach(track=>track.stop());
      await ensureCallChannels(selected.peerUserId);
      const callId=crypto.randomUUID();
      callRef.current={...callRef.current,callId,peerUserId:selected.peerUserId,conversationId:selected.id,timeout:null};
      setCallUi({callId,peerName:selected.title,direction:'outgoing',status:'Вызов…',connected:false,muted:false});
      await sendCallSignal({type:'offer',call_id:callId,from:session.user.id,to:selected.peerUserId,video:false,sent_at:Date.now()});
      callRef.current.timeout=setTimeout(async()=>{
        if(callRef.current.callId!==callId)return;
        await sendCallSignal({type:'end',call_id:callId,from:session.user.id,to:selected.peerUserId}).catch(()=>{});
        await finishCallLocal();setNotice('Нет ответа');
      },35000);
    }catch(error){await finishCallLocal();setNotice('Не удалось начать звонок: '+error.message);}
  }

  async function acceptIncomingCall() {
    const incoming=incomingCall;
    if(!incoming)return;
    if(!incoming.conversationId){setNotice('Открой личный чат с этим пользователем и повтори приём звонка.');return;}
    try{
      if(!navigator.mediaDevices?.getUserMedia)throw new Error('Браузер не поддерживает аудиозвонки');
      const permissionStream=await navigator.mediaDevices.getUserMedia({audio:true});
      permissionStream.getTracks().forEach(track=>track.stop());
    }catch(error){setNotice('Для звонка разреши доступ к микрофону: '+error.message);return;}
    callRef.current={...callRef.current,callId:incoming.callId,peerUserId:incoming.peerUserId,conversationId:incoming.conversationId};
    setIncomingCall(null);
    setCallUi({callId:incoming.callId,peerName:incoming.peerName,direction:'incoming',status:'Соединяем…',connected:false,muted:false});
    try{await connectCallRoom({callId:incoming.callId,peerUserId:incoming.peerUserId,conversationId:incoming.conversationId,sendAnswer:true});}
    catch(error){setNotice('Не удалось подключиться к звонку: '+error.message);await endCall(false);}
  }

  async function declineIncomingCall() {
    const incoming=incomingCall;if(!incoming)return;
    await ensureCallChannels(incoming.peerUserId).catch(()=>{});
    await sendCallSignal({type:'decline',call_id:incoming.callId,from:session.user.id,to:incoming.peerUserId}).catch(()=>{});
    await finishCallLocal();
  }

  async function endCall(notifyPeer=true) {
    const current=callRef.current;
    if(notifyPeer&&current.callId&&current.peerUserId){
      await sendCallSignal({type:'end',call_id:current.callId,from:session.user.id,to:current.peerUserId}).catch(()=>{});
    }
    await finishCallLocal();
  }

  async function toggleCallMute() {
    const room=callRef.current.room;if(!room)return;
    const muted=!callUi?.muted;
    try{await room.localParticipant.setMicrophoneEnabled(!muted);setCallUi(previous=>previous?{...previous,muted}:previous);}
    catch(error){setNotice('Не удалось изменить микрофон: '+error.message);}
  }

  async function openSavedMessages() {
    try{
      const {data,error}=await supabase.rpc('get_or_create_saved_conversation');
      if(error)throw error;
      const idValue=String(data||'').replace(/^"|"$/g,'');
      if(!idValue)throw new Error('Сервер не вернул чат Избранного');
      const {data:row,error:readError}=await supabase.from('conversations')
        .select('id,kind,title,chat_mode,last_message_at').eq('id',idValue).single();
      if(readError)throw readError;
      const chat={id:row.id,kind:row.kind,chatMode:row.chat_mode||'cloud',title:'⭐ Избранное',avatar:'⭐',peerUserId:null,updated:row.last_message_at,unread:0};
      setChats(previous=>[chat,...previous.filter(item=>item.id!==chat.id)]);
      openChat(chat);
    }catch(error){setNotice('Не удалось открыть Избранное: '+error.message);}
  }

  async function saveMessageToSaved(message) {
    if(!message||message.encrypted||message.decryptionError){setNotice('Это сообщение ещё не расшифровано и его нельзя сохранить.');return;}
    try{
      const {data,error}=await supabase.rpc('get_or_create_saved_conversation');
      if(error)throw error;
      const savedId=String(data||'').replace(/^"|"$/g,'');
      if(!savedId)throw new Error('Не удалось открыть Избранное');
      const text=String(message.content||'');
      const payload={userId:session.user.id,conversationId:savedId,messageId:crypto.randomUUID(),plaintext:text,messageType:message.message_type||'text'};
      await sendCloudMessage(supabase,payload);
      setMessageMenu(null);setNotice('Сообщение сохранено в ⭐ Избранное');
      loadChats({silent:true});
    }catch(error){setNotice('Не удалось сохранить сообщение: '+error.message);}
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

  async function sendAttachment(fileOverride = null, existingMessageId = null) {
    const file = fileOverride || pendingMedia[0] || null;
    if (!file || !selected || (selected.kind === 'direct' && !selected.peerUserId) || (!e2eeReady&&selected.chatMode!=='cloud') || sending) return;
    setSending(true); setNotice('');
    const conversationId = selected.id;
    const messageId = existingMessageId || crypto.randomUUID();
    const createdAt = new Date().toISOString();
    let uploadedPath = null;
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
      uploadedPath = metadata.path;
      const payload = {userId:session.user.id, conversationId, messageId, plaintext:JSON.stringify(metadata), messageType:'media', replyToId};
      const sent = selected.chatMode==='cloud'
        ? await sendCloudMessage(supabase,payload)
        : selected.kind === 'group'
          ? await sendBrowserGroupMessage(supabase,payload)
          : await sendBrowserDirectMessage(supabase,{...payload,peerUserId:selected.peerUserId});
      pendingMediaOutboxRef.current.delete(messageId);
      if (selectedChatIdRef.current === conversationId) setMessages(previous=>[...previous.filter(item=>item.id!==messageId),{...sent,content:JSON.stringify(metadata),media:metadata}].sort((x,y)=>new Date(x.created_at)-new Date(y.created_at)));
      setOutgoingMediaStatus(previous=>({...previous,[messageId]:'sent'}));
      setPendingMedia(previous=>previous.filter(item=>item!==file)); setReplyTarget(null); loadChats();
      return true;
    } catch(error) {
      if (uploadedPath) {
        try {
          const {data: savedMessage} = await supabase.from('messages').select('id')
            .eq('id', messageId).eq('conversation_id', conversationId).maybeSingle();
          if (!savedMessage) await supabase.storage.from('nexura-chat-media').remove([uploadedPath]);
        } catch { /* Keep the original send error visible; cleanup is best-effort. */ }
      }
      setOutgoingMediaStatus(previous=>({...previous,[messageId]:'failed'}));
      setNotice('Не удалось отправить вложение: '+(error?.message||'ошибка'));
      return false;
    } finally { setSending(false); }
  }

  async function sendPendingAttachments() {
    if (!pendingMedia.length || sending || sendingMediaBatch) return;
    setSendingMediaBatch(true);
    const batch = [...pendingMedia];
    try {
      for (const file of batch) {
        const sent = await sendAttachment(file);
        if (!sent) break;
      }
    } finally {
      setSendingMediaBatch(false);
    }
  }

  function addPendingMediaFiles(event) {
    const incoming = Array.from(event.target.files || []);
    event.target.value = '';
    if (!incoming.length) return;
    const valid = incoming.filter(file => file.size > 0 && file.size <= 24 * 1024 * 1024);
    if (valid.length !== incoming.length) setNotice('Каждый файл должен быть не пустым и не больше 24 МБ.');
    const room = Math.max(0, 10 - pendingMedia.length);
    if (valid.length > room) setNotice('За одно сообщение можно прикрепить не более 10 файлов.');
    if (room) setPendingMedia(previous => [...previous, ...valid].slice(0, 10));
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
        if(blob.size) setPendingMedia(previous=>previous.length>=10?previous:[...previous,new File([blob],'Голосовое.'+extension,{type:blob.type})]);
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
    if (!selected || (selected.kind === 'direct' && !selected.peerUserId) || (!e2eeReady&&selected.chatMode!=='cloud') || sending) return;
    const text = draft.trim();
    if (!text) return;
    setSending(true); setNotice('');
    try {
      if (editingMessageId) {
        const original = messages.find(item => item.id === editingMessageId);
        if (!original) throw new Error('Исходное сообщение не найдено');
        const sent = original.encrypted
          ? await editEncryptedDirectMessage(supabase,{userId:session.user.id,chatId:selected.id,peerUserId:selected.peerUserId,oldMessage:original,newText:text})
          : await editCloudMessage(supabase,original.id,text);
        setMessages(previous => [...previous.filter(item => item.id !== editingMessageId), {...sent, edited_at: new Date().toISOString()}].sort((a,b)=>new Date(a.created_at)-new Date(b.created_at)));
        setEditingMessageId(null);
      } else {
        const messageId = crypto.randomUUID();
        const payload = {
          userId: session.user.id, conversationId: selected.id, messageId, plaintext: text,
          replyToId: replyTarget?.id || null
        };
        const sent = selected.chatMode==='cloud'
          ? await sendCloudMessage(supabase,payload)
          : selected.kind === 'group'
            ? await sendBrowserGroupMessage(supabase,payload)
            : await sendBrowserDirectMessage(supabase,{...payload,peerUserId:selected.peerUserId});
        setMessages(previous => [...previous, sent]);
      }
      setDraft('');
      setReplyTarget(null);
      loadChats();
    } catch (error) {
      setNotice('Не удалось отправить сообщение: ' + (error?.message || 'ошибка'));
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

  useEffect(()=>{
    if(!session?.user?.id)return;
    let active=true;
    const heartbeat=async()=>{
      try{
        const {data,error}=await supabase.from('user_presence').select('user_id').eq('user_id',session.user.id).maybeSingle();
        if(error)throw error;
        const payload={last_seen_at:new Date().toISOString()};
        const result=data
          ? await supabase.from('user_presence').update(payload).eq('user_id',session.user.id)
          : await supabase.from('user_presence').insert({user_id:session.user.id,...payload});
        if(result.error)throw result.error;
      }catch(error){if(active)console.debug('Nexura presence heartbeat unavailable',error?.message);}
    };
    void heartbeat();
    const timer=setInterval(()=>{if(document.visibilityState==='visible')void heartbeat();},60000);
    const onFocus=()=>{if(document.visibilityState==='visible')void heartbeat();};
    document.addEventListener('visibilitychange',onFocus);
    return()=>{active=false;clearInterval(timer);document.removeEventListener('visibilitychange',onFocus);};
  },[session?.user?.id]);

  useEffect(()=>{
    if(!selected?.id||!session?.user?.id){setPeerTyping(false);setPeerPresence(null);return;}
    let active=true;
    const peerId=selected.kind==='direct'?selected.peerUserId:null;
    const refresh=async()=>{
      if(!peerId){setPeerTyping(false);setPeerPresence(null);return;}
      try{
        const [{data:typing,error:typingError},{data:presence,error:presenceError}]=await Promise.all([
          supabase.from('conversation_typing').select('user_id,updated_at').eq('conversation_id',selected.id).neq('user_id',session.user.id).gt('updated_at',new Date(Date.now()-9000).toISOString()).limit(1),
          supabase.rpc('get_user_presence',{p_user_id:peerId})
        ]);
        if(typingError)throw typingError;
        if(presenceError)throw presenceError;
        if(!active)return;
        setPeerTyping(!!typing?.length);
        const row=Array.isArray(presence)?presence[0]:presence;
        setPeerPresence(row||null);
      }catch(error){if(active)console.debug('Nexura peer status unavailable',error?.message);}
    };
    void refresh();
    const timer=setInterval(refresh,3500);
    const channel=supabase.channel('web-typing-'+selected.id)
      .on('postgres_changes',{event:'*',schema:'public',table:'conversation_typing',filter:'conversation_id=eq.'+selected.id},()=>void refresh())
      .subscribe();
    return()=>{active=false;clearInterval(timer);supabase.removeChannel(channel);};
  },[selected?.id,selected?.peerUserId,selected?.kind,session?.user?.id]);

  useEffect(()=>{
    if(!selected?.id||!session?.user?.id)return;
    let cancelled=false;
    const syncTyping=async()=>{
      try{
        if(draft.trim()){
          const {error}=await supabase.from('conversation_typing').upsert({
            conversation_id:selected.id,user_id:session.user.id,updated_at:new Date().toISOString()
          },{onConflict:'conversation_id,user_id'});
          if(error)throw error;
        }else{
          const {error}=await supabase.from('conversation_typing').delete().eq('conversation_id',selected.id).eq('user_id',session.user.id);
          if(error)throw error;
        }
      }catch(error){if(!cancelled)console.debug('Nexura typing sync unavailable',error?.message);}
    };
    const timer=setTimeout(()=>void syncTyping(),draft.trim()?450:0);
    return()=>{cancelled=true;clearTimeout(timer);};
  },[draft,selected?.id,session?.user?.id]);

  useEffect(()=>{
    return()=>{
      if(selectedChatIdRef.current&&session?.user?.id){
        void supabase.from('conversation_typing').delete().eq('conversation_id',selectedChatIdRef.current).eq('user_id',session.user.id);
      }
    };
  },[session?.user?.id]);

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
    <aside className={'sidebar '+(mobileChatOpen||activeTab!=='chats'?'mobile-hidden':'')}>
      <div className="side-top"><div className="brand"><span className="brand-mark"><MessageCircle size={21}/></span><span>Nexura</span><small>WEB</small></div><div className="side-actions"><button className="icon-button" onClick={()=>setGroupOpen(true)} title="Создать группу"><Plus size={18}/></button><button className="icon-button" onClick={loadChats} title="Обновить"><RefreshCw size={17}/></button></div></div>
      <div className="me-card"><Avatar className="self" value={profile?.avatar_url} name={profile?.display_name || session.user.email}/><div className="me-info"><b>{profile?.display_name || session.user.email?.split('@')[0]}</b><span>Профиль Nexura</span></div><button className="icon-button" onClick={openProfileSettings} title="Редактировать профиль"><Settings size={17}/></button><button className="icon-button" onClick={openAdvancedSettings} title="Настройки приватности и безопасности"><ShieldCheck size={17}/></button><button className="icon-button" onClick={signOut} title="Выйти"><LogOut size={17}/></button></div>
      <div className="side-tabs side-tabs-expanded">
        <button className={activeTab==='chats'?'active':''} onClick={()=>{setActiveTab('chats');setViewedProfile(null);}}><MessageCircle size={16}/> Чаты</button>
        <button className={activeTab==='people'?'active':''} onClick={()=>{setActiveTab('people');setMobileChatOpen(false);setPeopleOffset(0);setPeople([]);}}><Users size={16}/> Контакты</button>
        <button className={activeTab==='profile'?'active':''} onClick={openOwnProfile}><UserRound size={16}/> Профиль</button>
        <button onClick={openAdvancedSettings}><Settings size={16}/> Настройки</button>
      </div>
      <div className="list-heading"><span>{activeTab==='chats'?'ТВОИ ЧАТЫ':activeTab==='people'?'КОНТАКТЫ':'ТВОЙ ПРОФИЛЬ'}</span><span>{activeTab==='chats'?chats.length:activeTab==='people'?people.length:0}</span></div>
      <div className="search-box"><Search size={16}/><input value={activeTab==='chats'?search:peopleQuery} onChange={e=>activeTab==='chats'?setSearch(e.target.value):setPeopleQuery(e.target.value)} placeholder={activeTab==='chats'?'Поиск переписки':'Имя или @username'}/></div>
      {activeTab==='chats'&&<button className="saved-messages-shortcut" onClick={openSavedMessages}><Star size={16} fill="currentColor"/> Избранное</button>}
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
    <main className={'chat-area '+(mobileChatOpen||activeTab==='people'||activeTab==='profile'?'mobile-visible':'')}>
      {notice && <div className="toast">{notice}<button onClick={()=>setNotice('')}>×</button></div>}
      {activeTab==='profile' ? <section className="web-profile-page"><button className="web-mobile-back" onClick={()=>{setActiveTab('chats');setMobileChatOpen(false);}}><ArrowLeft size={16}/> Назад к чатам</button>
        <div className="web-profile-cover" onClick={()=>bannerInputRef.current?.click()}>
          {profile?.banner_display_url || profile?.banner_url?.startsWith('http') ? (profile.banner_url?.startsWith('social:') ? <video src={profile.banner_display_url} autoPlay muted loop playsInline/> : <img src={profile.banner_url} alt="Обложка профиля"/>) : <div className="cover-placeholder"><ImageIcon size={34}/><b>Добавь обложку профиля</b><span>Фото или видео без звука</span></div>}
          <button className="cover-edit" onClick={event=>{event.stopPropagation();bannerInputRef.current?.click();}}><Camera size={15}/> Изменить обложку</button>
          <input ref={bannerInputRef} type="file" accept="image/*,video/*" className="visually-hidden-file" onChange={async event=>{const file=event.target.files?.[0];if(!file)return;setSocialBusy(true);try{const path=profile?.id+'/'+(file.type.startsWith('video/')?'banners/':'banner-')+crypto.randomUUID()+'.'+((file.name.split('.').pop()||'jpg').replace(/[^a-z0-9]/gi,'')||'jpg');const bucket=file.type.startsWith('video/')?'nexura-social-media':'nexura-avatars';const {error:uploadError}=await supabase.storage.from(bucket).upload(path,file,{upsert:false,contentType:file.type});if(uploadError)throw uploadError;const banner_url=bucket==='nexura-social-media'?'social:'+path:supabase.storage.from(bucket).getPublicUrl(path).data.publicUrl;const {error:updateError}=await supabase.from('profiles').update({banner_url}).eq('id',session.user.id);if(updateError)throw updateError;let banner_display_url=banner_url;if(banner_url.startsWith('social:')){const {data:signed,error}=await supabase.storage.from('nexura-social-media').createSignedUrl(path,7*86400);if(error)throw error;banner_display_url=signed.signedUrl;}setProfile(previous=>({...previous,banner_url,banner_display_url}));setNotice('Обложка профиля обновлена');}catch(error){setNotice('Не удалось обновить обложку: '+error.message);}finally{setSocialBusy(false);event.target.value='';}}}/>
        </div>
        <div className="web-profile-heading"><Avatar className="web-profile-avatar" value={profile?.avatar_url} name={profile?.display_name}/><div className="web-profile-title"><h2>{profile?.display_name||'Твой профиль'}</h2><span>{profile?.username?'@'+profile.username:'Username не задан'}</span>{profile?.bio&&<p>{profile.bio}</p>}{profile?.age&&<small>{profile.age} лет</small>}</div><button className="primary-btn" onClick={()=>{setProfileEditDraft({display_name:profile?.display_name||'',username:profile?.username||'',avatar_url:profile?.avatar_url||'🙂',bio:profile?.bio||'',age:profile?.age==null?'':String(profile.age)});setProfileEdit(true);}}>Изменить профиль</button></div>
        <label className="social-caption-field">Подпись к следующей публикации<input value={socialCaption} onChange={event=>setSocialCaption(event.target.value)} placeholder="Добавь описание (необязательно)" maxLength={500}/></label>
        <div className="web-social-section"><div className="web-section-heading"><div><h3>Истории</h3><span>Активные истории — 24 часа, старые остаются в архиве</span></div><button className="social-add-button" onClick={()=>storyInputRef.current?.click()} disabled={socialBusy}><Plus size={16}/> Добавить</button><input ref={storyInputRef} type="file" accept="image/*,video/*" className="visually-hidden-file" onChange={event=>{const file=event.target.files?.[0];if(file)publishProfileMedia('story',file);event.target.value='';}}/></div>
          <div className="web-social-grid stories-grid">{profileStories.map(item=><button key={item.id} className="web-story-card" onClick={()=>openSocialItem(item,'story')}><div className="web-story-media">{item.media_type==='video'?<video src={item.url} muted playsInline/>:<img src={item.url} alt="История"/>}<span><Play size={16}/></span></div><small>{(item.expires_at&&new Date(item.expires_at)<=new Date()?'Архив · ':'')+new Date(item.created_at).toLocaleDateString('ru-RU')}</small></button>)}{!profileStories.length&&<div className="social-empty">У тебя пока нет историй. Добавь фото или видео.</div>}</div>
        </div>
        <div className="web-social-section"><div className="web-section-heading"><div><h3>Фотографии</h3><span>Твоя галерея профиля</span></div><button className="social-add-button" onClick={()=>photoInputRef.current?.click()} disabled={socialBusy}><Plus size={16}/> Добавить</button><input ref={photoInputRef} type="file" accept="image/*" className="visually-hidden-file" onChange={event=>{const file=event.target.files?.[0];if(file)publishProfileMedia('photo',file);event.target.value='';}}/></div>
          <div className="web-social-grid">{profilePhotos.map(item=><button key={item.id} className="web-photo-card" onClick={()=>openSocialItem(item,'photo')}><img src={item.url} alt={item.caption||'Фотография профиля'}/></button>)}{!profilePhotos.length&&<div className="social-empty">Здесь будут фотографии твоего профиля.</div>}</div>
        </div>
      </section> : activeTab==='people' ? <div className="people-page"><button className="web-mobile-back" onClick={()=>{setActiveTab('chats');setMobileChatOpen(false);}}><ArrowLeft size={16}/> Назад к чатам</button><div className="people-page-heading"><div className="eyebrow">СООБЩЕСТВО NEXURA</div><h2>Люди</h2><p>Находи собеседников, открывай профили и начинай общение.</p><div className="people-tools"><button className="secondary-btn" onClick={syncBrowserContacts} disabled={contactSyncBusy}>{contactSyncBusy?'Проверяем…':'Синхронизировать контакты'}</button><button className="primary-btn" onClick={shareNexuraInvite}>Пригласить друзей</button><small>Браузер попросит выбрать контакты вручную. На сервер отправляются только выбранные номера для поиска совпадений.</small></div></div>
      {contactMatches.length>0&&<section className="contact-matches"><div className="web-section-heading"><h3>Знакомые в Nexura</h3><span>{contactMatches.length}</span></div><div className="contact-match-grid">{contactMatches.map(person=><article key={person.user_id} className="contact-match-item"><Avatar value={person.avatar_url} name={person.display_name}/><span><b>{person.display_name||'Пользователь'}</b><small>{person.phone||''}</small></span><button className="primary-btn" onClick={()=>createDirectChat({id:person.user_id,title:person.display_name||'Пользователь',avatar:person.avatar_url})}><MessageCircle size={14}/> Написать</button></article>)}</div></section>}
      <section className="stories-feed-section"><div className="web-section-heading"><div><h3>Истории</h3><span>{storiesFeed.length ? `${storiesFeed.length} активных публикаций` : 'Свежие публикации участников'}</span></div><div className="stories-feed-actions"><button className="social-add-button" onClick={()=>void loadStoriesFeed()} disabled={storiesFeedLoading} title="Обновить истории"><RefreshCw size={15}/> Обновить</button><button className="social-add-button" onClick={openOwnProfile}><Plus size={15}/> Моя история</button></div></div><div className="stories-feed-row">{storiesFeed.map(item=><button className="stories-feed-card" key={item.id} onClick={()=>openSocialItem(item,'story')} aria-label={`Открыть историю: ${item.profile?.display_name||item.profile?.username||'Участник'}`}><div className="stories-feed-media">{item.media_type==='video'?<video src={item.url} muted autoPlay loop playsInline preload="metadata"/>:<img src={item.url} alt="История" loading="lazy"/>}</div><div className="stories-feed-author"><Avatar value={item.profile?.avatar_url} name={item.profile?.display_name}/><span>{item.profile?.display_name||item.profile?.username||'Участник'}</span></div></button>)}{storiesFeedLoading&&!storiesFeed.length&&<div className="social-empty stories-feed-empty">Загружаем истории…</div>}{!storiesFeedLoading&&!storiesFeed.length&&<div className="stories-feed-empty"><div className="social-empty">{storiesFeedError ? `Не удалось загрузить истории: ${storiesFeedError}` : 'Пока нет активных историй. Будь первым — опубликуй свою в профиле.'}</div><button className="social-add-button" onClick={()=>void loadStoriesFeed()} disabled={storiesFeedLoading}><RefreshCw size={15}/> Повторить загрузку</button></div>}</div></section><div className="people-directory-grid">{people.map(person=><article className="people-person-card" key={person.id}><button className="people-person-main" onClick={()=>openUserProfile(person)}><Avatar value={person.avatar_url} name={person.display_name}/><span><b>{person.display_name||'Пользователь'}</b><small>{person.username?'@'+person.username:'Пользователь Nexura'}</small></span></button><p>{person.bio||'Пока ничего не рассказал о себе.'}</p><div><button className="secondary-btn" onClick={()=>openUserProfile(person)}>Профиль</button><button className="primary-btn" onClick={()=>createDirectChat({id:person.id,title:person.display_name||person.username,avatar:person.avatar_url})}><MessageCircle size={15}/> Написать</button></div></article>)}</div>{peopleBusy&&<div className="list-hint">Загружаем каталог…</div>}{peopleHasMore&&!peopleBusy&&<button className="load-more-people" onClick={()=>loadPeople()}>Показать ещё</button>}</div> : !selected ? <div className="welcome-pane"><div className="welcome-symbol"><MessageCircle size={38}/><span/></div><div className="eyebrow">ТВОЁ ПРОСТРАНСТВО</div><h2>Всё начинается<br/>с разговора.</h2><p>Выбери переписку слева, чтобы продолжить общение.</p><div className="welcome-tags"><span><LockKeyhole size={14}/> Приватность</span><span><Users size={14}/> Свои люди</span></div></div> :
        <><header className="chat-header"><button className="icon-button back-button" onClick={()=>setMobileChatOpen(false)} aria-label="Назад к чатам"><ArrowLeft size={19}/></button><Avatar value={selected.avatar} name={selected.title}/><div className="header-person"><b>{selected.title}</b><span>{selected.kind==='group'?'Групповой чат':peerTyping?'печатает…':peerPresence?.is_visible&&peerPresence?.is_online?'в сети':peerPresence?.is_visible&&peerPresence?.last_seen_at?'был(а) в сети '+new Date(peerPresence.last_seen_at).toLocaleTimeString('ru-RU',{hour:'2-digit',minute:'2-digit'}):'Личная переписка'}</span></div>{selected.kind==='direct'&&<button className="icon-button" onClick={startAudioCall} disabled={!!callUi||!!incomingCall} title="Позвонить" aria-label="Позвонить"><Phone size={17}/></button>}{selected.kind==='group'&&<button className="icon-button" onClick={openGroupManagement} title="Участники и настройки группы" aria-label="Участники группы"><Users size={17}/></button>}<button className="icon-button chat-theme-trigger" onClick={()=>setChatThemeOpen(true)} title="Оформление чата" aria-label="Оформление чата"><Palette size={17}/></button><button className="icon-button" onClick={toggleConversationMode} title="Режим шифрования" aria-label="Режим шифрования"><LockKeyhole size={17}/></button><div className="chat-tools-wrap"><button className="icon-button" onClick={()=>setChatToolsOpen(value=>!value)} title="Действия чата" aria-label="Действия чата"><MoreHorizontal size={18}/></button>{chatToolsOpen&&<div className="chat-tools-menu">
          <button onClick={()=>{setChatSearchOpen(value=>!value);setChatSearchQuery('');setChatToolsOpen(false);}}><Search size={15}/>Поиск по переписке</button>
          <button onClick={()=>{setChatGalleryOpen(true);setChatToolsOpen(false);}}><ImageIcon size={15}/>Медиа и ссылки</button>
          <button onClick={()=>changeChatMute(chatMutedUntil&&new Date(chatMutedUntil)>new Date()?null:60*60*1000)}><Bell size={15}/>{chatMutedUntil&&new Date(chatMutedUntil)>new Date()?'Включить уведомления':'Отключить на час'}</button>
          <button onClick={()=>changeChatMute(8*60*60*1000)}><Bell size={15}/>Отключить на 8 часов</button>
          <button onClick={()=>changeChatMute(48*60*60*1000)}><Bell size={15}/>Отключить на 2 дня</button>
        </div>}</div><div className="header-secure" title={selected.chatMode==='cloud'?'Облачный режим: без сквозного шифрования':e2eeReady?'Защищённое устройство подключено':'Защита устройства ещё не готова'}><ShieldCheck size={16}/><span>{selected.chatMode==='cloud'?'Облачный режим':e2eeReady?'Сквозное шифрование':'Подключение защиты'}</span></div></header>
        <div className="messages-pane" ref={messagesPaneRef} onScroll={event=>{if(event.currentTarget.scrollTop<55)loadOlderMessages();}} style={chatTheme.id?{'--chat-wallpaper':chatTheme.wall,'--incoming-bubble':chatTheme.bubble,'--outgoing-bubble':chatTheme.mine}:undefined}>
          {hasOlderMessages&&<button className="load-older-messages" onClick={loadOlderMessages} disabled={loadingOlderMessages}>{loadingOlderMessages?'Загружаем…':'Загрузить предыдущие сообщения'}</button>}
          {chatSearchOpen&&<div className="chat-search-bar"><Search size={15}/><input autoFocus value={chatSearchQuery} onChange={e=>setChatSearchQuery(e.target.value)} placeholder="Поиск по загруженной истории…"/><button onClick={()=>{setChatSearchOpen(false);setChatSearchQuery('');}}><X size={15}/></button><small>{visibleMessages.length}</small></div>}
          {pinnedMessage&&<button className="pinned-message-banner" onClick={()=>document.getElementById('message-'+pinnedMessage.id)?.scrollIntoView({behavior:'smooth',block:'center'})}><span>⌖ Закреплено</span><b>{String(pinnedMessage.content||'Медиа').slice(0,100)}</b><X size={14} onClick={event=>{event.stopPropagation();togglePinnedChatMessage(pinnedMessage);}}/></button>}
          {loadingMessages && <div className="list-hint">Загружаем историю переписки…</div>}
          {!loadingMessages && messages.length===0 && <div className="empty-messages"><div className="empty-chat-icon"><MessageCircle size={28}/></div><b>Начните общение</b><span>Напиши первое сообщение — оно появится здесь.</span></div>}
          {visibleMessages.map((m,index) => {
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
          <div className="security-note"><LockKeyhole size={14}/><span>{selected.chatMode==='cloud'?'Облачный режим: текст хранится без сквозного шифрования':e2eeStatus}</span></div>
        </div>
        {(replyTarget || editingMessageId) && <div className="reply-composer"><span>{editingMessageId ? 'Редактирование сообщения' : 'Ответ на: '+String(replyTarget?.content || 'Сообщение').slice(0,90)}</span><button onClick={()=>{setReplyTarget(null);setEditingMessageId(null);setDraft('');}} aria-label="Отменить">×</button></div>}
        {selected.kind === 'direct' || selected.kind === 'group' ? <form className="composer" onSubmit={sendMessage}>
          {pendingMedia.length>0 && <div className="pending-attachments">
            <div className="pending-attachments-list">{pendingMedia.map((file,index)=><div className="pending-attachment" key={file.name+'-'+file.size+'-'+file.lastModified+'-'+index}>
              <span>📎</span><div><b>{file.name||'Вложение'}</b><small>{(file.size/1024/1024).toFixed(2)} МБ · будет зашифровано</small></div>
              <button type="button" onClick={()=>setPendingMedia(previous=>previous.filter((_,i)=>i!==index))} aria-label="Убрать вложение">×</button>
              <PendingMediaPreview file={file}/>
            </div>)}</div>
            <div className="pending-attachments-footer"><small>{pendingMedia.length}/10 вложений</small>
              <button type="button" className="primary-btn" onClick={sendPendingAttachments} disabled={(!e2eeReady&&selected.chatMode!=='cloud')||sending||sendingMediaBatch}>{sending||sendingMediaBatch?'Отправка…':'Отправить '+pendingMedia.length}</button>
            </div>
          </div>}
          {recording && <div className="recording-indicator"><span className="recording-dot"/><b>Идёт запись</b><time>{formatDuration(recordingSeconds)}</time><button type="button" onClick={cancelVoiceRecording}>Отмена</button><button type="button" onClick={stopVoiceRecording}>Готово</button></div>}
          <input ref={fileInputRef} type="file" multiple className="visually-hidden-file" onChange={addPendingMediaFiles}/>
          <div className="composer-input-row">
            <button type="button" className="composer-attach" onClick={()=>fileInputRef.current?.click()} disabled={(!e2eeReady&&selected.chatMode!=='cloud')||sending||sendingMediaBatch||recording||pendingMedia.length>=10} title="Прикрепить до 10 файлов" aria-label="Прикрепить файл"><Paperclip size={18}/></button>
            <button type="button" className={'composer-mic '+(recording?'recording':'')} onClick={recording?stopVoiceRecording:startVoiceRecording} disabled={(!e2eeReady&&selected.chatMode!=='cloud')||sending||sendingMediaBatch||pendingMedia.length>0} title={recording?'Завершить запись':'Записать голосовое'} aria-label={recording?'Завершить запись':'Записать голосовое'}>{recording?<Square size={16}/>:<Mic size={18}/>}</button>
            <textarea ref={composerInputRef} value={draft} onChange={event=>setDraft(event.target.value)} onKeyDown={event=>{if(event.key==='Enter'&&!event.shiftKey){event.preventDefault();sendMessage(event);}else if(event.key==='Escape'&&(replyTarget||editingMessageId)){setReplyTarget(null);setEditingMessageId(null);setDraft('');}}} placeholder={e2eeReady ? 'Сообщение' : 'Подключаем защищённое соединение…'} maxLength={12000} rows={1} disabled={(!e2eeReady&&selected.chatMode!=='cloud') || sending}/>
            <button type="button" className="composer-schedule" onClick={openScheduleDialog} disabled={!draft.trim()||sending} title={scheduledCount?'Отложить отправку · в очереди: '+scheduledCount:'Отложить отправку'} aria-label="Отложить отправку"><Clock size={17}/></button>
            <button className="send-button" type="submit" disabled={(!e2eeReady&&selected.chatMode!=='cloud') || sending || !draft.trim()} aria-label="Отправить сообщение"><Send size={18}/></button>
          </div>
          <div className="composer-hint"><LockKeyhole size={11}/><span>{selected.chatMode==='cloud'?'Облачный режим · без E2EE':e2eeReady ? 'Сквозное шифрование включено' : 'Ожидаем подключение шифрования'}</span><span className="composer-shortcut">Enter — отправить · Shift + Enter — перенос</span></div>
        </form> : <div className="composer-locked"><div className="lock-circle"><LockKeyhole size={17}/></div><div><b>Групповые сообщения защищены E2EE</b><span>Сообщения шифруются отдельно для каждого активного устройства участников. Открытый текст не отправляется.</span></div></div>}
        </>
      }
    </main>
    {activeMenuMessage && createPortal(<div className="message-context-menu message-context-menu-floating" style={{top:messageMenuPosition.top,left:messageMenuPosition.left}} role="menu" aria-label="Действия с сообщением" onPointerDown={event=>event.stopPropagation()} onClick={event=>event.stopPropagation()}>
      <button role="menuitem" onClick={()=>{setReplyTarget(activeMenuMessage);setEditingMessageId(null);setMessageMenu(null);}}><Reply size={15}/>Ответить</button>
      <button role="menuitem" onClick={()=>{copyMessage(activeMenuMessage);setMessageMenu(null);}}><Copy size={15}/>Копировать</button>{!activeMenuMessage.encrypted&&!activeMenuMessage.decryptionError&&<button role="menuitem" onClick={()=>saveMessageToSaved(activeMenuMessage)}><Star size={15}/>Сохранить в Избранное</button>}<button role="menuitem" onClick={()=>togglePinnedChatMessage(activeMenuMessage)}><Pin size={15}/>{pinnedMessageId===activeMenuMessage.id?'Открепить':'Закрепить в чате'}</button>
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
    {scheduledModalOpen&&<div className="modal-backdrop" onClick={()=>setScheduledModalOpen(false)}><section className="group-modal schedule-modal" onClick={event=>event.stopPropagation()}>
      <div className="modal-head"><div><div className="eyebrow">ЛОКАЛЬНАЯ ОЧЕРЕДЬ</div><h3>Отложенные сообщения</h3></div><button className="icon-button" onClick={()=>setScheduledModalOpen(false)}><X size={18}/></button></div>
      <p className="settings-description">Очередь хранится в зашифрованном хранилище этого браузера. Отправка выполняется, когда Nexura открыта.</p>
      <div className="scheduled-list">{scheduledItems.map(item=><article className="scheduled-item" key={item.id}><div><b>{item.chatTitle||'Чат'}</b><time>{new Date(item.sendAt).toLocaleString('ru-RU')}</time><p>{item.text}</p><small>{item.chatMode==='cloud'?'Облачный режим':'Сквозное шифрование'}</small></div><button className="danger-text" onClick={()=>cancelScheduledMessage(item)}>Отменить</button></article>)}{!scheduledItems.length&&<div className="social-empty">Отложенных сообщений нет.</div>}</div>
    </section></div>}
    {scheduleOpen&&<div className="modal-backdrop" onClick={()=>!scheduleBusy&&setScheduleOpen(false)}><section className="group-modal schedule-modal" onClick={event=>event.stopPropagation()}>
      <div className="modal-head"><div><div className="eyebrow">ОТЛОЖЕННАЯ ОТПРАВКА</div><h3>Запланировать сообщение</h3></div><button className="icon-button" onClick={()=>setScheduleOpen(false)} disabled={scheduleBusy}><X size={18}/></button></div>
      <div className="schedule-preview">{draft}</div><label className="modal-label">Дата и время<input type="datetime-local" value={scheduleAt} onChange={event=>setScheduleAt(event.target.value)}/></label>
      <p className="settings-description">Сообщение отправится, когда веб-клиент открыт. Если браузер будет закрыт, оно уйдёт при следующем открытии Nexura.</p>
      <div className="modal-foot"><button className="secondary-btn" onClick={()=>setScheduleOpen(false)} disabled={scheduleBusy}>Отмена</button><button className="primary-btn" onClick={scheduleCurrentMessage} disabled={scheduleBusy}>{scheduleBusy?'Сохраняем…':'Запланировать'}</button></div>
    </section></div>}
    {chatGalleryOpen&&<div className="modal-backdrop" onClick={()=>setChatGalleryOpen(false)}><section className="group-modal chat-gallery-modal" onClick={event=>event.stopPropagation()}>
      <div className="modal-head"><div><div className="eyebrow">МАТЕРИАЛЫ ПЕРЕПИСКИ</div><h3>Медиа и ссылки</h3></div><button className="icon-button" onClick={()=>setChatGalleryOpen(false)}><X size={18}/></button></div>
      <div className="chat-gallery-list">{chatGalleryItems.map(item=><div className="chat-gallery-item" key={item.id}>{parseMediaPayload(item.content)?<MediaMessage payload={parseMediaPayload(item.content)} supabase={supabase} chatTitle={selected?.title}/>:<a href={String(item.content).match(/https?:\/\/[^\s<>]+/i)?.[0]} target="_blank" rel="noreferrer">{String(item.content).slice(0,180)}</a>}<small>{timeLabel(item.created_at)}</small></div>)}{!chatGalleryItems.length&&<div className="social-empty">В загруженной истории пока нет медиа и ссылок.</div>}</div>
      <p className="settings-description">Показаны материалы из уже загруженных сообщений.</p>
    </section></div>}
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
    {incomingCall&&<div className="modal-backdrop call-backdrop"><section className="call-modal incoming-call-modal"><div className="call-avatar"><Phone size={27}/></div><div className="eyebrow">ВХОДЯЩИЙ ЗВОНОК</div><h3>{incomingCall.peerName}</h3><p>Вам звонят в Nexura</p><div className="call-controls"><button className="call-decline" onClick={declineIncomingCall}><PhoneOff size={19}/> Отклонить</button><button className="call-accept" onClick={acceptIncomingCall}><Phone size={19}/> Принять</button></div></section></div>}
    {callUi&&<div className="call-floating-panel"><div className="call-floating-info"><span className="call-live-dot"/><div><b>{callUi.peerName}</b><small>{callUi.status}</small></div></div><div className="call-floating-actions"><button onClick={toggleCallMute} title={callUi.muted?'Включить микрофон':'Выключить микрофон'}>{callUi.muted?<MicOff size={17}/>:<Mic size={17}/>}</button><button className="call-hangup" onClick={()=>endCall(true)} title="Завершить звонок"><PhoneOff size={17}/></button></div></div>}
    {viewedProfile && <div className="modal-backdrop profile-view-backdrop" onClick={()=>setViewedProfile(null)}><section className="web-user-profile-modal" onClick={event=>event.stopPropagation()}>
      <button className="profile-modal-close" onClick={()=>setViewedProfile(null)} aria-label="Закрыть"><X size={19}/></button>
      <div className="web-profile-cover user-cover">{viewedProfile.banner_display_url ? (viewedProfile.banner_url?.startsWith('social:')?<video src={viewedProfile.banner_display_url} autoPlay muted loop playsInline/>:<img src={viewedProfile.banner_display_url} alt="Обложка"/>) : <div className="cover-placeholder"><UserRound size={30}/></div>}</div>
      <div className="web-profile-heading"><Avatar className="web-profile-avatar" value={viewedProfile.avatar_url} name={viewedProfile.display_name}/><div className="web-profile-title"><h2>{viewedProfile.display_name}</h2><span>@{viewedProfile.username||'user'}</span>{viewedProfile.bio&&<p>{viewedProfile.bio}</p>}{viewedProfile.age&&<small>{viewedProfile.age} лет</small>}</div><button className="primary-btn" onClick={()=>createDirectChat({id:viewedProfile.id,title:viewedProfile.display_name,avatar:viewedProfile.avatar_url})}><MessageCircle size={15}/> Написать</button><button className="secondary-btn block-user-button" onClick={toggleViewedUserBlock} disabled={socialBusy}>{viewedProfile.isBlocked?'Разблокировать':'Заблокировать'}</button></div>
      <div className="web-social-section"><div className="web-section-heading"><h3>Истории</h3></div><div className="web-social-grid stories-grid">{viewedStories.map(item=><button className="web-story-card" key={item.id} onClick={()=>openSocialItem(item,'story')}><div className="web-story-media">{item.media_type==='video'?<video src={item.url} muted playsInline/>:<img src={item.url} alt="История"/>}<span><Play size={16}/></span></div></button>)}{!viewedStories.length&&<div className="social-empty">Активных историй нет.</div>}</div></div>
      <div className="web-social-section"><div className="web-section-heading"><h3>Фотографии профиля</h3></div><div className="web-social-grid">{viewedPhotos.map(item=><button className="web-photo-card" key={item.id} onClick={()=>openSocialItem(item,'photo')}><img src={item.url} alt={item.caption||'Фото'}/></button>)}{!viewedPhotos.length&&<div className="social-empty">Фотографий пока нет.</div>}</div></div>
    </section></div>}
    {socialViewer && <div className="modal-backdrop social-viewer-backdrop" onClick={()=>setSocialViewer(null)}><section className="social-viewer-modal" onClick={event=>event.stopPropagation()}>
      <button className="profile-modal-close" onClick={()=>setSocialViewer(null)}><X size={19}/></button>{socialViewer.user_id===session.user.id&&<button className="social-delete-button" onClick={deleteCurrentSocialItem} disabled={socialBusy} title="Удалить публикацию"><Trash2 size={17}/></button>}
      <div className="social-viewer-media">{socialViewer.media_type==='video'?<video src={socialViewer.url} controls autoPlay muted playsInline/>:<img src={socialViewer.url} alt={socialViewer.caption||'Публикация'}/>}</div>
      <div className="social-viewer-content"><p>{socialViewer.caption||' '}</p><div className="social-viewer-actions"><button onClick={toggleCurrentSocialLike} disabled={socialBusy||(socialViewer.kind==='story'&&socialViewer.expires_at&&new Date(socialViewer.expires_at)<=new Date())} className={socialLiked?'liked':''} title={socialViewer.kind==='story'&&socialViewer.expires_at&&new Date(socialViewer.expires_at)<=new Date()?'История из архива — реакции закрыты':''}><Heart size={17} fill={socialLiked?'currentColor':'none'}/> {socialViewer.likeCount||0}</button>{socialViewer.kind==='photo'&&<span><MessageSquare size={16}/> {socialComments.length}</span>}</div>
      {socialViewer.kind==='photo'&&<><div className="social-comments">{socialComments.map(comment=><div key={comment.id}><b>{comment.user_id===session.user.id?'Вы':'Участник Nexura'}</b><span>{comment.content}</span></div>)}{!socialComments.length&&<small>Комментариев пока нет.</small>}</div><form className="social-comment-form" onSubmit={submitSocialComment}><input value={socialCommentDraft} onChange={event=>setSocialCommentDraft(event.target.value)} placeholder="Написать комментарий…" maxLength={1000}/><button className="primary-btn" disabled={socialBusy||!socialCommentDraft.trim()}>Отправить</button></form></>}
      </div>
    </section></div>}
    {groupManagementOpen && <div className="modal-backdrop" onClick={()=>setGroupManagementOpen(false)}><section className="group-modal group-management-modal" onClick={event=>event.stopPropagation()}>
      <div className="modal-head"><div><div className="eyebrow">УПРАВЛЕНИЕ ГРУППОЙ</div><h3>Участники</h3></div><button className="icon-button" onClick={()=>setGroupManagementOpen(false)}><X size={18}/></button></div>
      {groupManageAllowed&&<div className="group-rename-row"><input value={groupNameDraft} onChange={event=>setGroupNameDraft(event.target.value)} maxLength={60}/><button className="primary-btn" onClick={renameCurrentGroup} disabled={groupManageBusy}>Сохранить</button></div>}
      <div className="group-member-list">{groupMemberRows.map(row=>{const p=row.profiles||{};return <div className="group-member-manage-row" key={row.user_id}><Avatar value={p.avatar_url} name={p.display_name}/><div><b>{p.display_name||p.username||'Пользователь'}</b><small>{row.member_role==='owner'?'Создатель':row.member_role==='admin'?'Администратор':'Участник'}</small></div>{groupManageAllowed&&row.user_id!==session.user.id&&<div className="group-member-actions">{row.member_role!=='admin'?<button disabled={groupManageBusy} onClick={()=>changeGroupMemberRole(row.user_id,'admin')}>Назначить админом</button>:<button disabled={groupManageBusy} onClick={()=>changeGroupMemberRole(row.user_id,'member')}>Снять админа</button>}<button className="danger-text" disabled={groupManageBusy} onClick={()=>removeCurrentGroupMember(row.user_id)}>Удалить</button></div>}</div>;})}</div>
      {groupManageAllowed&&<div className="group-add-area"><b>Добавить участника</b><input value={groupMemberQuery} onChange={event=>setGroupMemberQuery(event.target.value)} placeholder="Имя или @username"/><div className="group-add-results">{groupMemberResults.map(person=><button key={person.id} onClick={()=>addCurrentGroupMember(person)} disabled={groupManageBusy}><Avatar value={person.avatar_url} name={person.display_name}/><span>{person.display_name||person.username}</span><Plus size={16}/></button>)}{groupMemberQuery.trim().length>=2&&!groupMemberResults.length&&<small>Новых пользователей не найдено.</small>}</div></div>}
      <button className="leave-group-button" onClick={()=>removeCurrentGroupMember(session.user.id)} disabled={groupManageBusy}>Покинуть группу</button>
    </section></div>}
    {advancedSettingsOpen && <div className="modal-backdrop" onClick={()=>setAdvancedSettingsOpen(false)}><section className="group-modal advanced-settings-modal" onClick={event=>event.stopPropagation()}>
      <div className="modal-head"><div><div className="eyebrow">УПРАВЛЕНИЕ АККАУНТОМ</div><h3>Настройки</h3></div><button className="icon-button" onClick={()=>setAdvancedSettingsOpen(false)}><X size={18}/></button></div>
      <div className="settings-section-tabs"><button className={settingsSection==='privacy'?'active':''} onClick={()=>setSettingsSection('privacy')}>Приватность</button><button className={settingsSection==='notifications'?'active':''} onClick={()=>setSettingsSection('notifications')}>Уведомления</button><button className={settingsSection==='devices'?'active':''} onClick={()=>setSettingsSection('devices')}>Устройства</button><button className={settingsSection==='blocked'?'active':''} onClick={()=>setSettingsSection('blocked')}>Блокировки</button><button className={settingsSection==='backup'?'active':''} onClick={()=>setSettingsSection('backup')}>Резервная копия</button></div>
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
      {settingsSection==='backup'&&<div className="advanced-settings-content">
        <div className="settings-info-note"><LockKeyhole size={17}/> Резервная копия содержит только зашифрованное состояние браузерного E2EE. Код восстановления не отправляется на сервер.</div>
        <div className="settings-description">{webBackupExists?'В облаке есть резервная копия веб-устройства.':'Резервной копии веб-устройства пока нет.'}</div>
        <button className="primary-btn" onClick={createWebE2eeBackup} disabled={backupBusy||!e2eeReady}>{backupBusy?'Создаём…':webBackupExists?'Обновить резервную копию':'Создать резервную копию'}</button>
        {recoveryCode&&<div className="backup-recovery-box"><b>Новый код восстановления</b><code>{recoveryCode}</code><button className="secondary-btn" onClick={async()=>{try{if(!navigator.clipboard?.writeText||!window.isSecureContext)throw new Error('Буфер обмена недоступен');await navigator.clipboard.writeText(recoveryCode);setNotice('Код скопирован');}catch(error){setNotice('Не удалось скопировать код: '+error.message);}}}>Скопировать код</button><small>Запиши код на бумаге или сохрани в менеджере паролей. Если потеряешь его, расшифровать копию будет невозможно.</small></div>}
        <div className="backup-restore-box"><b>Восстановить на новом браузере</b><p>Введи код от резервной копии. Восстановление доступно только если на этом браузере ещё не создано E2EE-устройство.</p><input value={restoreCode} onChange={event=>setRestoreCode(event.target.value.trim())} placeholder="Код восстановления" autoComplete="off"/><button className="secondary-btn" onClick={restoreWebE2eeBackup} disabled={backupBusy||!restoreCode.trim()||!e2eeRecoveryRequired}>{backupBusy?'Восстанавливаем…':'Восстановить ключи'}</button></div>
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

createRoot(document.getElementById('root')).render(<AppErrorBoundary><App/></AppErrorBoundary>);