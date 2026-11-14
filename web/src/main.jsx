import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { createClient } from '@supabase/supabase-js';
import { MessageCircle, Search, Settings, LogOut, Send, Plus, ShieldCheck, LockKeyhole, Users, ArrowLeft, RefreshCw, UserRound, Mail, Eye, EyeOff } from 'lucide-react';
import './styles.css';

const SUPABASE_URL = 'https://lyhzawblxcvpgpjrkspc.supabase.co';
const SUPABASE_KEY = 'sb_publishable_G-EJKck-KO2cgoxaMlg9ag_CQZvDUEY';
const supabase = createClient(SUPABASE_URL, SUPABASE_KEY, {
  auth: { persistSession: true, autoRefreshToken: true, detectSessionInUrl: true },
  realtime: { params: { eventsPerSecond: 5 } }
});

function initials(name = '?') {
  return name.trim().split(/\s+/).slice(0, 2).map(x => x[0]?.toUpperCase()).join('') || '?';
}
function timeLabel(value) {
  if (!value) return '';
  return new Intl.DateTimeFormat('ru-RU', { hour: '2-digit', minute: '2-digit' }).format(new Date(value));
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
  const [loadingChats, setLoadingChats] = useState(false);
  const [loadingMessages, setLoadingMessages] = useState(false);
  const [search, setSearch] = useState('');
  const [notice, setNotice] = useState('');
  const [mobileChatOpen, setMobileChatOpen] = useState(false);
  const [activeTab, setActiveTab] = useState('chats');

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
          id: c.id, kind: c.kind, title: c.kind === 'direct' ? (p?.display_name || p?.username || 'Пользователь') : (c.title || 'Группа'),
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
    if (!session) { setChats([]); setSelected(null); setProfile(null); return; }
    loadChats();
    supabase.from('profiles').select('id,display_name,username,avatar_url,bio').eq('id', session.user.id).maybeSingle()
      .then(({data}) => setProfile(data));
  }, [session?.user?.id]);

  const loadMessages = useCallback(async (chat) => {
    if (!chat?.id) return;
    setLoadingMessages(true); setNotice('');
    const { data, error } = await supabase.from('messages')
      .select('id,sender_id,content,created_at,message_type,deleted_at')
      .eq('conversation_id', chat.id).is('deleted_at', null)
      .order('created_at', { ascending: true }).limit(100);
    if (error) setNotice('Не удалось открыть переписку: ' + error.message);
    else setMessages(data || []);
    setLoadingMessages(false);
  }, []);

  useEffect(() => {
    if (!selected) { setMessages([]); return; }
    loadMessages(selected);
    const channel = supabase.channel('web-chat-' + selected.id)
      .on('postgres_changes', { event: '*', schema: 'public', table: 'messages', filter: 'conversation_id=eq.' + selected.id }, () => loadMessages(selected))
      .subscribe();
    return () => { supabase.removeChannel(channel); };
  }, [selected?.id, loadMessages]);

  const visibleChats = useMemo(() => chats.filter(c => c.title.toLowerCase().includes(search.toLowerCase())), [chats, search]);
  async function signOut() { await supabase.auth.signOut(); }
  function openChat(chat) { setSelected(chat); setMobileChatOpen(true); setActiveTab('chats'); }
  const selectedHasCipher = messages.some(m => isEncrypted(m.content));

  if (initializing) return <div className="loading-screen"><div className="brand-mark"><MessageCircle/></div><span>Загружаем Nexura…</span></div>;
  if (!session) return <AuthScreen/>;

  return <div className="messenger">
    <aside className={'sidebar '+(mobileChatOpen?'mobile-hidden':'')}>
      <div className="side-top"><div className="brand"><span className="brand-mark"><MessageCircle size={21}/></span><span>Nexura</span><small>WEB</small></div><button className="icon-button" onClick={loadChats} title="Обновить"><RefreshCw size={17}/></button></div>
      <div className="me-card"><div className="avatar self">{initials(profile?.display_name || session.user.email)}</div><div className="me-info"><b>{profile?.display_name || session.user.email?.split('@')[0]}</b><span>В сети</span></div><button className="icon-button" onClick={signOut} title="Выйти"><LogOut size={17}/></button></div>
      <div className="side-tabs"><button className={activeTab==='chats'?'active':''} onClick={()=>setActiveTab('chats')}><MessageCircle size={17}/> Чаты</button><button className={activeTab==='contacts'?'active':''} onClick={()=>setActiveTab('contacts')}><Users size={17}/> Контакты</button></div>
      <div className="list-heading"><span>{activeTab==='chats'?'ТВОИ ЧАТЫ':'КОНТАКТЫ'}</span><span>{chats.length}</span></div>
      <div className="search-box"><Search size={16}/><input value={search} onChange={e=>setSearch(e.target.value)} placeholder="Поиск переписки"/></div>
      <div className="chat-list">
        {loadingChats && <div className="list-hint">Загружаем чаты…</div>}
        {!loadingChats && visibleChats.length===0 && <div className="empty-list"><MessageCircle size={24}/><b>Пока тихо</b><span>Когда появятся переписки, они будут здесь.</span></div>}
        {visibleChats.map(chat=><button key={chat.id} className={'chat-item '+(selected?.id===chat.id?'selected':'')} onClick={()=>openChat(chat)}>
          <div className="avatar">{chat.avatar && !chat.avatar.startsWith('http') ? chat.avatar : initials(chat.title)}</div>
          <div className="chat-meta"><b>{chat.title}</b><span>{chat.kind==='group'?'Группа':'Личная переписка'}</span></div>
          <div className="chat-trailing">{chat.updated && <time>{timeLabel(chat.updated)}</time>}{chat.unread>0&&<i>{chat.unread}</i>}</div>
        </button>)}
      </div>
      <div className="sidebar-foot"><ShieldCheck size={15}/> Nexura · ранняя веб-версия</div>
    </aside>
    <main className={'chat-area '+(mobileChatOpen?'mobile-visible':'')}>
      {notice && <div className="toast">{notice}<button onClick={()=>setNotice('')}>×</button></div>}
      {!selected ? <div className="welcome-pane"><div className="welcome-symbol"><MessageCircle size={38}/><span/></div><div className="eyebrow">ТВОЁ ПРОСТРАНСТВО</div><h2>Всё начинается<br/>с разговора.</h2><p>Выбери переписку слева, чтобы продолжить общение.</p><div className="welcome-tags"><span><LockKeyhole size={14}/> Приватность</span><span><Users size={14}/> Свои люди</span></div></div> :
        <><header className="chat-header"><button className="icon-button back-button" onClick={()=>setMobileChatOpen(false)}><ArrowLeft size={19}/></button><div className="avatar">{selected.avatar && !selected.avatar.startsWith('http') ? selected.avatar : initials(selected.title)}</div><div className="header-person"><b>{selected.title}</b><span>{selected.kind==='group'?'Групповой чат':'Личная переписка'}</span></div><div className="header-secure"><ShieldCheck size={16}/> Nexura</div></header>
        <div className="messages-pane">
          <div className="date-chip">История переписки</div>
          {loadingMessages && <div className="list-hint">Загружаем сообщения…</div>}
          {!loadingMessages && messages.length===0 && <div className="empty-messages"><MessageCircle size={28}/><b>Здесь пока нет сообщений</b><span>История появится после синхронизации.</span></div>}
          {messages.map(m => {
            const mine=m.sender_id===session.user.id, encrypted=isEncrypted(m.content);
            return <div key={m.id} className={'message-row '+(mine?'mine':'')}>
              <div className="message-bubble">
                {encrypted ? <div className="encrypted-placeholder"><LockKeyhole size={17}/><span>Зашифрованное сообщение</span><small>Для чтения нужна проверенная E2EE-сессия.</small></div> : <div className="message-content">{m.content}</div>}
                <time>{timeLabel(m.created_at)}</time>
              </div>
            </div>;
          })}
          <div className="security-note"><LockKeyhole size={15}/><span>Веб-клиент пока не расшифровывает E2EE-сообщения. Они не показываются как обычный текст.</span></div>
        </div>
        <div className="composer-locked"><div className="lock-circle"><LockKeyhole size={17}/></div><div><b>Отправка сообщений пока отключена</b><span>Сначала нужно подключить веб-шифрование Nexura. Это не позволит случайно отправить переписку без E2EE.</span></div></div>
        </>
      }
    </main>
  </div>;
}

createRoot(document.getElementById('root')).render(<App/>);