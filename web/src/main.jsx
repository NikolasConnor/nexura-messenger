import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { createRoot, createPortal } from 'react-dom/client';
import { createClient } from '@supabase/supabase-js';
import { MessageCircle, Search, Settings, LogOut, Send, Plus, Palette, ShieldCheck, LockKeyhole, Users, ArrowLeft, RefreshCw, UserRound, Mail, Eye, EyeOff, CheckCheck, Copy, Reply, MoreHorizontal, SmilePlus, Pencil, Trash2, Forward } from 'lucide-react';
import './styles.css';
import { initializeOlm } from './crypto/olm.js';
import { loadMessageReactions, toggleMessageReaction, markConversationRead, updateMyProfile, deleteMessage, editEncryptedDirectMessage } from './api/messenger.js';
import { ensureBrowserE2eeDevice, sendBrowserDirectMessage, decryptBrowserGroupMessage } from './crypto/transport.js';

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
  const [chats, setChats] = useState([]);
  const [selected, setSelected] = useState(null);
  const [messages, setMessages] = useState([]);
  const messageLoadSeq = useRef(0);
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
  const [profileDraft, setProfileDraft] = useState({ display_name: '', avatar_url: '🙂', bio: '' });
  const [profileBusy, setProfileBusy] = useState(false);
  const [draft, setDraft] = useState('');
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

  useEffect(() => {
    let alive = true;
    supabase.auth.getSession().then(({ data }) => { if (alive) { setSession(data.session); setInitializing(false); } });
    const { data: listener } = supabase.auth.onAuthStateChange((_event, next) => { setSession(next); setInitializing(false); });
    return () => { alive = false; listener.subscription.unsubscribe(); };
  }, []);

  const loadChats = useCallback(async () => {
    if (!session?.user?.id) return;
    setLoadingChats(true); setNotice('');
    try {
      const { data, error } = await supabase.from('conversation_members')
        .select('conversation_id, joined_at, conversations(id,kind,title,last_message_at,conversation_members(user_id,profiles(id,display_name,username,avatar_url)))')
        .eq('user_id', session.user.id).order('joined_at', { ascending: false });
      if (error) throw error;
      const ids = (data || []).map(x => x.conversation_id).filter(Boolean);
      let unread = {};
      if (ids.length) {
        const { data: counts, error: countError } = await supabase.rpc('get_my_unread_counts');
        if (!countError) for (const row of counts || []) unread[row.conversation_id] = row.unread_count || 0;
      }
      const list = (data || []).map(row => {
        const c = row.conversations;
        if (!c) return null;
        const peer = (c.conversation_members || []).find(m => m.user_id !== session.user.id);
        const p = peer?.profiles;
        return {
          id: c.id, kind: c.kind, peerUserId: peer?.user_id || null, title: c.kind === 'direct' ? (p?.display_name || p?.username || 'Пользователь') : (c.title || 'Группа'),
          avatar: p?.avatar_url || null, updated: c.last_message_at, unread: unread[c.id] || 0
        };
      }).filter(Boolean).sort((a, b) => new Date(b.updated || 0) - new Date(a.updated || 0));
      setChats(list);
      if (selected) {
        const fresh = list.find(c => c.id === selected.id);
        if (fresh) setSelected(fresh);
      }
    } catch (e) { setNotice('Не удалось загрузить чаты: ' + e.message); }
    finally { setLoadingChats(false); }
  }, [session?.user?.id, selected?.id]);

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
    supabase.from('profiles').select('id,display_name,username,avatar_url,bio').eq('id', session.user.id).maybeSingle()
      .then(({data}) => setProfile(data));
  }, [session?.user?.id]);

  const loadMessages = useCallback(async (chat) => {
    if (!chat?.id) return;
    const requestId = ++messageLoadSeq.current;
    setLoadingMessages(true);
    setNotice('');
    try {
      const { data, error } = await supabase.from('messages')
        .select('id,sender_id,content,created_at,message_type,reply_to_id,edited_at,deleted_at')
        .eq('conversation_id', chat.id).is('deleted_at', null)
        .order('created_at', { ascending: true }).limit(100);
      if (error) throw error;
      const rows = await Promise.all((data || []).map(async message => {
        if (!isEncrypted(message.content)) return message;
        try {
          const plaintext = await decryptBrowserGroupMessage(supabase, session?.user?.id, chat.id, message);
          return { ...message, content: plaintext, decrypted: true };
        } catch (decryptError) {
          return { ...message, encrypted: true, decryptionError: decryptError?.message || 'Не удалось расшифровать сообщение' };
        }
      }));
      if (requestId !== messageLoadSeq.current) return;
      setMessages(rows);
      const reactions = await loadMessageReactions(supabase, chat.id).catch(() => ({}));
      if (requestId !== messageLoadSeq.current) return;
      setMessageReactions(reactions);
      markConversationRead(supabase, chat.id).catch(() => {});
    } catch (error) {
      if (requestId === messageLoadSeq.current) setNotice('Не удалось открыть переписку: ' + (error?.message || 'ошибка'));
    } finally {
      if (requestId === messageLoadSeq.current) setLoadingMessages(false);
    }
  }, [session?.user?.id]);

  useEffect(() => {
    if (!messageMenu && !reactionPickerFor) return;
    const dismiss = event => {
      if (event.type === 'keydown' && event.key === 'Escape') {
        setMessageMenu(null); setReactionPickerFor(null); return;
      }
      if (event.type === 'pointerdown' && !event.target.closest('.message-tools')) {
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
    loadMessages(selected);
    const channel = supabase.channel('web-chat-' + selected.id)
      .on('postgres_changes', { event: '*', schema: 'public', table: 'messages', filter: 'conversation_id=eq.' + selected.id }, () => loadMessages(selected))
      .subscribe();
    return () => { supabase.removeChannel(channel); };
  }, [selected?.id, loadMessages, e2eeReady]);

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

  async function sendMessage(event) {
    event?.preventDefault?.();
    if (!selected || selected.kind !== 'direct' || !selected.peerUserId || !e2eeReady || sending) return;
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
        const sent = await sendBrowserDirectMessage(supabase, {
          userId: session.user.id, conversationId: selected.id,
          peerUserId: selected.peerUserId, messageId, plaintext: text,
          replyToId: replyTarget?.id || null
        });
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
      bio: profile?.bio || ''
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
  function openChat(chat) { setSelected(chat); setMobileChatOpen(true); setActiveTab('chats'); }
  const selectedHasCipher = messages.some(m => isEncrypted(m.content));

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
      <div className="me-card"><Avatar className="self" value={profile?.avatar_url} name={profile?.display_name || session.user.email}/><div className="me-info"><b>{profile?.display_name || session.user.email?.split('@')[0]}</b><span>Профиль Nexura</span></div><button className="icon-button" onClick={openProfileSettings} title="Настройки профиля"><Settings size={17}/></button><button className="icon-button" onClick={signOut} title="Выйти"><LogOut size={17}/></button></div>
      <div className="side-tabs"><button className={activeTab==='chats'?'active':''} onClick={()=>setActiveTab('chats')}><MessageCircle size={17}/> Чаты</button><button className={activeTab==='contacts'?'active':''} onClick={()=>setActiveTab('contacts')}><Users size={17}/> Контакты</button></div>
      <div className="list-heading"><span>{activeTab==='chats'?'ТВОИ ЧАТЫ':'ПОИСК ЛЮДЕЙ'}</span><span>{activeTab==='chats'?chats.length:contactResults.length}</span></div>
      <div className="search-box"><Search size={16}/><input value={activeTab==='chats'?search:contactQuery} onChange={e=>activeTab==='chats'?setSearch(e.target.value):setContactQuery(e.target.value)} placeholder={activeTab==='chats'?'Поиск переписки':'Имя или @username'}/></div>
      <div className="chat-list">
        {activeTab==='chats' ? <>
          {loadingChats && <div className="list-hint">Загружаем чаты…</div>}
          {!loadingChats && visibleChats.length===0 && <div className="empty-list"><MessageCircle size={24}/><b>Пока тихо</b><span>Найди человека во вкладке «Контакты» или создай группу кнопкой +.</span></div>}
          {visibleChats.map(chat=><button key={chat.id} className={'chat-item '+(selected?.id===chat.id?'selected':'')} onClick={()=>openChat(chat)}>
            <Avatar value={chat.avatar} name={chat.title}/>
            <div className="chat-meta"><b>{chat.title}</b><span>{chat.kind==='group'?'Группа':'Личная переписка'}</span></div>
            <div className="chat-trailing">{chat.updated && <time>{timeLabel(chat.updated)}</time>}{chat.unread>0&&<i>{chat.unread}</i>}</div>
          </button>)}
        </> : <>
          {contactQuery.trim().length<2 && <div className="empty-list"><Users size={24}/><b>Найди собеседника</b><span>Введи минимум два символа имени или имени пользователя.</span></div>}
          {contactBusy && <div className="list-hint">Ищем пользователей…</div>}
          {!contactBusy && contactQuery.trim().length>=2 && contactResults.length===0 && <div className="empty-list"><UserRound size={24}/><b>Никого не нашли</b><span>Попробуй другое имя или username.</span></div>}
          {contactResults.map(person=><button key={person.id} className="chat-item" onClick={()=>createDirectChat(person)} disabled={contactBusy}>
            <Avatar value={person.avatar} name={person.title}/>
            <div className="chat-meta"><b>{person.title}</b><span>{person.username?'@'+person.username:'Пользователь Nexura'}</span></div>
            <div className="contact-open"><MessageCircle size={16}/></div>
          </button>)}
        </>}
      </div>
      <div className="sidebar-foot"><ShieldCheck size={15}/> Nexura · ранняя веб-версия</div>
    </aside>
    <main className={'chat-area '+(mobileChatOpen?'mobile-visible':'')}>
      {notice && <div className="toast">{notice}<button onClick={()=>setNotice('')}>×</button></div>}
      {!selected ? <div className="welcome-pane"><div className="welcome-symbol"><MessageCircle size={38}/><span/></div><div className="eyebrow">ТВОЁ ПРОСТРАНСТВО</div><h2>Всё начинается<br/>с разговора.</h2><p>Выбери переписку слева, чтобы продолжить общение.</p><div className="welcome-tags"><span><LockKeyhole size={14}/> Приватность</span><span><Users size={14}/> Свои люди</span></div></div> :
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
                    {m.encrypted ? <div className="encrypted-placeholder"><LockKeyhole size={17}/><span>Не удалось расшифровать</span><small>{m.decryptionError || 'Не удалось открыть защищённое сообщение.'}</small></div> : <div className="message-content">{m.content}</div>}
                    <div className="message-meta"><time>{timeLabel(m.created_at)}</time>{m.edited_at && <span className="edited-label">изм.</span>}{mine && <CheckCheck size={13}/>}</div>
                  </div>
                  <div className={"message-tools "+(reactions.length?"has-reactions":"")}>
                    <div className="message-reactions">
                      {reactions.map(reaction=><button key={reaction.emoji+'-'+reaction.count+'-'+Boolean(reaction.selectedByMe)} className={'reaction-chip '+(reaction.selectedByMe?'selected':'')} onClick={()=>reactToMessage(m,reaction.emoji)} title={reaction.selectedByMe?'Убрать реакцию':'Поставить реакцию'}><span className="reaction-emoji">{reaction.emoji}</span><b key={reaction.count} className="reaction-count">{reaction.count}</b></button>)}
                      <button className="reaction-add" onClick={()=>{setReactionPickerFor(reactionPickerFor===m.id?null:m.id);setMessageMenu(null);}} title="Добавить реакцию" aria-label="Добавить реакцию"><SmilePlus size={15}/></button>
                      <button className="message-more" onClick={event=>toggleMessageMenu(event,m.id)} title="Действия с сообщением" aria-label="Действия с сообщением"><MoreHorizontal size={16}/></button>
                    </div>
                    {reactionPickerFor===m.id && <div className="reaction-picker">{popular.map(emoji=><button key={emoji} onClick={()=>reactToMessage(m,emoji)}>{emoji}</button>)}</div>}
                    {messageMenu===m.id && createPortal(<div className="message-context-menu message-context-menu-floating" style={{top:messageMenuPosition.top,left:messageMenuPosition.left}} role="menu" onMouseDown={event=>event.stopPropagation()}>
                      <button role="menuitem" onClick={()=>{setReplyTarget(m);setEditingMessageId(null);setMessageMenu(null);}}><Reply size={15}/>Ответить</button>
                      <button role="menuitem" onClick={()=>copyMessage(m)}><Copy size={15}/>Копировать</button>
                      {!m.encrypted && <button role="menuitem" onClick={()=>{setForwardMessage(m);setMessageMenu(null);}}><Forward size={15}/>Переслать</button>}
                      {mine && selected.kind==='direct' && !m.encrypted && <button role="menuitem" onClick={()=>startEditing(m)}><Pencil size={15}/>Изменить</button>}
                      {mine && <button role="menuitem" className="danger-action" onClick={()=>removeOwnMessage(m)} disabled={pendingAction}><Trash2 size={15}/>Удалить</button>}
                    </div>, document.body)}
                  </div>
                </div>
              </div>
            </React.Fragment>;
          })}
          <div className="security-note"><LockKeyhole size={14}/><span>{e2eeStatus}</span></div>
        </div>
        {(replyTarget || editingMessageId) && <div className="reply-composer"><span>{editingMessageId ? 'Редактирование сообщения' : 'Ответ на: '+String(replyTarget?.content || 'Сообщение').slice(0,90)}</span><button onClick={()=>{setReplyTarget(null);setEditingMessageId(null);setDraft('');}} aria-label="Отменить">×</button></div>}
        {selected.kind === 'direct' ? <form className="composer" onSubmit={sendMessage}>
          <div className="composer-input-row">
            <textarea ref={composerInputRef} value={draft} onChange={event=>setDraft(event.target.value)} onKeyDown={event=>{if(event.key==='Enter'&&!event.shiftKey){event.preventDefault();sendMessage(event);}else if(event.key==='Escape'&&(replyTarget||editingMessageId)){setReplyTarget(null);setEditingMessageId(null);setDraft('');}}} placeholder={e2eeReady ? 'Сообщение' : 'Подключаем защищённое соединение…'} maxLength={12000} rows={1} disabled={!e2eeReady || sending}/>
            <button className="send-button" type="submit" disabled={!e2eeReady || sending || !draft.trim()} aria-label="Отправить сообщение"><Send size={18}/></button>
          </div>
          <div className="composer-hint"><LockKeyhole size={11}/><span>{e2eeReady ? 'Сквозное шифрование включено' : 'Ожидаем подключение шифрования'}</span><span className="composer-shortcut">Enter — отправить · Shift + Enter — перенос</span></div>
        </form> : <div className="composer-locked"><div className="lock-circle"><LockKeyhole size={17}/></div><div><b>Отправка в группах пока отключена</b><span>Сначала проверяем совместимость личных переписок с Android. Открытый текст не отправляется.</span></div></div>}
        </>
      }
    </main>
    {forwardMessage && <div className="modal-backdrop" onClick={()=>!forwardingBusy&&setForwardMessage(null)}>
      <section className="group-modal forward-modal" onClick={event=>event.stopPropagation()}>
        <div className="modal-head"><div><div className="eyebrow">ПЕРЕСЫЛКА</div><h3>Выбери чат</h3></div><button className="icon-button" onClick={()=>setForwardMessage(null)} disabled={forwardingBusy} aria-label="Закрыть">×</button></div>
        <div className="forward-preview">{String(forwardMessage.content||'').slice(0,180)}</div>
        <div className="forward-chat-list">{chats.filter(chat=>chat.kind==='direct'&&chat.id!==selected?.id).map(chat=><button key={chat.id} className="chat-item" onClick={()=>forwardToChat(chat)} disabled={forwardingBusy}><Avatar value={chat.avatar} name={chat.title}/><div className="chat-meta"><b>{chat.title}</b><span>Личная переписка</span></div><Forward size={16}/></button>)}
        {chats.filter(chat=>chat.kind==='direct'&&chat.id!==selected?.id).length===0 && <div className="empty-list">Нет других личных чатов для пересылки.</div>}</div>
        {forwardingBusy && <div className="list-hint">Пересылаем с повторным шифрованием…</div>}
      </section>
    </div>}
    {chatThemeOpen && <div className="modal-backdrop" onClick={()=>setChatThemeOpen(false)}><section className="group-modal theme-modal" onClick={event=>event.stopPropagation()}><div className="modal-head"><div><div className="eyebrow">ПЕРСОНАЛИЗАЦИЯ</div><h3>Оформление чата</h3></div><button className="icon-button" onClick={()=>setChatThemeOpen(false)} aria-label="Закрыть">×</button></div><p className="theme-description">Выбери оформление переписки. Настройка сохраняется на этом устройстве.</p><div className="theme-presets">{[{id:'violet',name:'Nexura',wall:'#100d19',bubble:'#49366a',mine:'#49366a'},{id:'midnight',name:'Полночь',wall:'#0b1420',bubble:'#1d3447',mine:'#285879'},{id:'ocean',name:'Океан',wall:'#0b1920',bubble:'#173b42',mine:'#176b70'},{id:'rose',name:'Розовый',wall:'#1b1019',bubble:'#422338',mine:'#713b5b'},{id:'light',name:'Светлый',wall:'#e9e7ef',bubble:'#ffffff',mine:'#ded3f8'}].map(theme=><button type="button" key={theme.id} className={'theme-preset '+((chatTheme.id||'violet')===theme.id?'active':'')} onClick={()=>applyChatTheme(theme)}><span className="theme-preview" style={{background:theme.wall}}><i style={{background:theme.bubble}}/><i style={{background:theme.mine}}/></span><b>{theme.name}</b></button>)}</div><div className="theme-modal-foot"><span>Изменения применяются сразу</span><button className="primary-btn" onClick={()=>{applyChatTheme({});setChatThemeOpen(false)}}>Сбросить</button></div></section></div>}
    {settingsOpen && <div className="modal-backdrop" onClick={()=>setSettingsOpen(false)}>
      <section className="group-modal profile-modal" onClick={event=>event.stopPropagation()}>
        <div className="modal-head"><div><div className="eyebrow">ТВОЙ АККАУНТ</div><h3>Профиль</h3></div><button className="icon-button" onClick={()=>setSettingsOpen(false)} aria-label="Закрыть">×</button></div>
        <form className="profile-form" onSubmit={saveProfileSettings}>
          <div className="profile-preview-card"><Avatar value={profileDraft.avatar_url} name={profileDraft.display_name}/><div><b>{profileDraft.display_name || 'Твоё имя'}</b><span>{profile?.username ? '@'+profile.username : session.user.email}</span><small>{profileDraft.bio || 'Добавь пару слов о себе'}</small></div><ShieldCheck size={17}/></div><div className="profile-avatar-picker"><div><b>Фото и аватар</b><span>Выбери значок, который увидят собеседники</span></div></div>
          <div className="emoji-picker" aria-label="Выбор аватара">
            {['🙂','😎','🤝','🦊','🐼','🐱','🐸','🐻','🌙','⭐','🔥','💜'].map(emoji=><button type="button" key={emoji} className={profileDraft.avatar_url===emoji?'active':''} onClick={()=>setProfileDraft(value=>({...value,avatar_url:emoji}))}>{emoji}</button>)}
          </div>
          <label className="modal-label">Имя<input value={profileDraft.display_name} onChange={event=>setProfileDraft(value=>({...value,display_name:event.target.value}))} placeholder="Как тебя называть" maxLength={40} required/></label>
          <label className="modal-label">О себе<textarea value={profileDraft.bio} onChange={event=>setProfileDraft(value=>({...value,bio:event.target.value}))} placeholder="Пара слов о себе" maxLength={160} rows={3}/></label>
          <div className="profile-form-foot"><span>{profileDraft.bio.length}/160</span><button className="primary-btn" type="submit" disabled={profileBusy}>{profileBusy?'Сохраняем…':'Сохранить профиль'}</button></div>
        </form>
      </section>
    </div>}
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
  </div>;
}

createRoot(document.getElementById('root')).render(<App/>);