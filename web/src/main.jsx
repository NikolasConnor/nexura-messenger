import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { createClient } from '@supabase/supabase-js';
import { MessageCircle, Search, Settings, LogOut, Send, Plus, ShieldCheck, LockKeyhole, Users, ArrowLeft, RefreshCw, UserRound, Mail, Eye, EyeOff, CheckCheck } from 'lucide-react';
import './styles.css';
import { initializeOlm } from './crypto/olm.js';
import { loadMessageReactions, toggleMessageReaction, markConversationRead } from './api/messenger.js';
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
  return <div className={'avatar '+className}>{src ? <img src={src} alt="" loading="lazy"/> : value && value !== '🙂' ? value : initials(name || '?')}</div>;
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
  const [draft, setDraft] = useState('');
  const [replyTarget, setReplyTarget] = useState(null);
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
      }).filter(Boolean);
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
    setLoadingMessages(true); setNotice('');
    const { data, error } = await supabase.from('messages')
      .select('id,sender_id,content,created_at,message_type,reply_to_id,edited_at,deleted_at')
      .eq('conversation_id', chat.id).is('deleted_at', null)
      .order('created_at', { ascending: true }).limit(100);
    if (error) setNotice('Не удалось открыть переписку: ' + error.message);
    else {
      const rows = await Promise.all((data || []).map(async message => {
        if (!isEncrypted(message.content)) return message;
        try {
          const plaintext = await decryptBrowserGroupMessage(supabase, session?.user?.id, chat.id, message);
          return { ...message, content: plaintext, decrypted: true };
        } catch (decryptError) {
          return { ...message, encrypted: true, decryptionError: decryptError?.message || 'Не удалось расшифровать сообщение' };
        }
      }));
      setMessages(rows);
      const reactions = await loadMessageReactions(supabase, chat.id).catch(() => ({}));
      setMessageReactions(reactions);
      markConversationRead(supabase, chat.id).catch(() => {});
    }
    setLoadingMessages(false);
  }, [session?.user?.id]);

  useEffect(() => {
    if (!selected) { setMessages([]); return; }
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

  async function sendMessage(event) {
    event?.preventDefault?.();
    if (!selected || selected.kind !== 'direct' || !selected.peerUserId || !e2eeReady || sending) return;
    const text = draft.trim();
    if (!text) return;
    setSending(true); setNotice('');
    const messageId = crypto.randomUUID();
    try {
      const sent = await sendBrowserDirectMessage(supabase, {
        userId: session.user.id, conversationId: selected.id,
        peerUserId: selected.peerUserId, messageId, plaintext: text,
        replyToId: replyTarget?.id || null
      });
      setMessages(previous => [...previous, sent]);
      setDraft('');
      setReplyTarget(null);
      loadChats();
    } catch (error) {
      setNotice('Не удалось отправить защищённое сообщение: ' + (error?.message || 'ошибка'));
    } finally { setSending(false); }
  }

  async function signOut() { await supabase.auth.signOut(); }
  function openChat(chat) { setSelected(chat); setMobileChatOpen(true); setActiveTab('chats'); }
  const selectedHasCipher = messages.some(m => isEncrypted(m.content));

  if (initializing) return <div className="loading-screen"><div className="brand-mark"><MessageCircle/></div><span>Загружаем Nexura…</span></div>;
  if (!session) return <AuthScreen/>;

  return <div className="messenger">
    <aside className={'sidebar '+(mobileChatOpen?'mobile-hidden':'')}>
      <div className="side-top"><div className="brand"><span className="brand-mark"><MessageCircle size={21}/></span><span>Nexura</span><small>WEB</small></div><div className="side-actions"><button className="icon-button" onClick={()=>setGroupOpen(true)} title="Создать группу"><Plus size={18}/></button><button className="icon-button" onClick={loadChats} title="Обновить"><RefreshCw size={17}/></button></div></div>
      <div className="me-card"><Avatar className="self" value={profile?.avatar_url} name={profile?.display_name || session.user.email}/><div className="me-info"><b>{profile?.display_name || session.user.email?.split('@')[0]}</b><span>В сети</span></div><button className="icon-button" onClick={signOut} title="Выйти"><LogOut size={17}/></button></div>
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
        <><header className="chat-header"><button className="icon-button back-button" onClick={()=>setMobileChatOpen(false)} aria-label="Назад к чатам"><ArrowLeft size={19}/></button><Avatar value={selected.avatar} name={selected.title}/><div className="header-person"><b>{selected.title}</b><span>{selected.kind==='group'?'Групповой чат':'Личная переписка'}</span></div><div className="header-secure" title={e2eeReady?'Защищённое устройство подключено':'Защита устройства ещё не готова'}><ShieldCheck size={16}/><span>{e2eeReady?'Сквозное шифрование':'Подключение защиты'}</span></div></header>
        <div className="messages-pane">
          {loadingMessages && <div className="list-hint">Загружаем историю переписки…</div>}
          {!loadingMessages && messages.length===0 && <div className="empty-messages"><div className="empty-chat-icon"><MessageCircle size={28}/></div><b>Начните общение</b><span>Напиши первое сообщение — оно появится здесь.</span></div>}
          {messages.map((m,index) => {
            const mine=m.sender_id===session.user.id;
            const previous=messages[index-1];
            const showDate=!previous || dateLabel(previous.created_at)!==dateLabel(m.created_at);
            return <React.Fragment key={m.id}>
              {showDate && <div className="day-divider"><span>{dateLabel(m.created_at)}</span></div>}
              <div className={'message-row '+(mine?'mine':'')}>
                {!mine && <Avatar className="message-avatar" value={selected.avatar} name={selected.title}/>}
                <div className="message-bubble">
                  {m.encrypted ? <div className="encrypted-placeholder"><LockKeyhole size={17}/><span>Не удалось расшифровать</span><small>{m.decryptionError || 'Не удалось открыть защищённое сообщение.'}</small></div> : <div className="message-content">{m.content}</div>}
                  {m.reply_to_id && <div className="reply-reference" onClick={()=>document.getElementById('message-'+m.reply_to_id)?.scrollIntoView({behavior:'smooth',block:'center'})}>↪ Ответ на сообщение</div>}
                  <div className="message-reactions">{(messageReactions[m.id] || []).map(reaction=><button key={reaction.emoji} className={reaction.selectedByMe?"selected":""} onClick={async()=>{try{await toggleMessageReaction(supabase,selected.id,m.id,reaction.emoji);setMessageReactions(await loadMessageReactions(supabase,selected.id))}catch(error){setNotice("Не удалось поставить реакцию: "+error.message)}}}>{reaction.emoji} {reaction.count}</button>)}<button onClick={async()=>{try{await toggleMessageReaction(supabase,selected.id,m.id,"❤️");setMessageReactions(await loadMessageReactions(supabase,selected.id))}catch(error){setNotice("Не удалось поставить реакцию: "+error.message)}}}>＋</button><button onClick={()=>setReplyTarget(m)} aria-label="Ответить">↩</button></div>
                  <div className="message-meta"><time>{timeLabel(m.created_at)}</time>{mine && <CheckCheck size={13}/>}</div>
                </div>
              </div>
            </React.Fragment>;
          })}
          <div className="security-note"><LockKeyhole size={14}/><span>{e2eeStatus}</span></div>
        </div>
        {replyTarget && <div className="reply-composer"><span>Ответ на: {String(replyTarget.content || 'Сообщение').slice(0,90)}</span><button onClick={()=>setReplyTarget(null)} aria-label="Отменить ответ">×</button></div>}
        {selected.kind === 'direct' ? <form className="composer" onSubmit={sendMessage}>
          <textarea value={draft} onChange={event=>setDraft(event.target.value)} onKeyDown={event=>{if(event.key==='Enter'&&!event.shiftKey){event.preventDefault();sendMessage(event);}}} placeholder={e2eeReady ? 'Напиши сообщение…' : 'Защищённое соединение не готово'} maxLength={12000} rows={1} disabled={!e2eeReady || sending}/>
          <div className="composer-bottom"><span><LockKeyhole size={12}/> {e2eeReady?'Личное сообщение защищено':'Ожидаем подключение шифрования'} · Shift + Enter — новая строка</span><button className="send-button" type="submit" disabled={!e2eeReady || sending || !draft.trim()} aria-label="Отправить сообщение"><Send size={18}/></button></div>
        </form> : <div className="composer-locked"><div className="lock-circle"><LockKeyhole size={17}/></div><div><b>Отправка в группах пока отключена</b><span>Сначала проверяем совместимость личных переписок с Android. Открытый текст не отправляется.</span></div></div>}
        </>
      }
    </main>
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