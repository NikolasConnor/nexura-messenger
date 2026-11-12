//! Bounded in-memory cache for active Olm sessions.
//!
//! The cache owns one Arc per session. Callers hold a temporary Arc while using
//! a session; eviction only removes entries whose Arc is otherwise unreferenced.
//! The cache mutex is held only for lookup/LRU bookkeeping, never for crypto work.

use std::collections::{HashMap, VecDeque};
use std::sync::{Arc, Mutex};

use vodozemac::olm::Session;

struct CacheState {
    sessions: HashMap<String, Arc<Mutex<Session>>>,
    recency: VecDeque<String>,
}

/// Per-process Olm session cache. Each session is independently synchronized.
pub(crate) struct SessionManager {
    capacity: usize,
    state: Mutex<CacheState>,
}

impl SessionManager {
    pub(crate) fn new(capacity: usize) -> Self {
        assert!(capacity > 0, "session cache capacity must be positive");
        Self {
            capacity,
            state: Mutex::new(CacheState {
                sessions: HashMap::with_capacity(capacity),
                recency: VecDeque::with_capacity(capacity),
            }),
        }
    }

    /// Return a cached session or restore it exactly once from the supplied
    /// durable pickle. The loader is called outside the cache lock.
    pub(crate) fn get_or_load<F>(&self, id: &str, loader: F) -> Result<Arc<Mutex<Session>>, String>
    where
        F: FnOnce() -> Result<Session, String>,
    {
        if id.is_empty() {
            return Err("session id must not be empty".into());
        }

        {
            let mut state = self.state.lock().map_err(|_| "session cache lock poisoned")?;
            if let Some(session) = state.sessions.get(id).cloned() {
                Self::touch(&mut state.recency, id);
                return Ok(session);
            }
        }

        let restored = Arc::new(Mutex::new(loader()?));
        let mut state = self.state.lock().map_err(|_| "session cache lock poisoned")?;

        // Another thread may have loaded the same ID while this thread restored it.
        if let Some(existing) = state.sessions.get(id).cloned() {
            Self::touch(&mut state.recency, id);
            return Ok(existing);
        }

        if state.sessions.len() >= self.capacity {
            let idle_id = state.recency.iter().find(|candidate| {
                state.sessions.get(*candidate).is_some_and(|session| Arc::strong_count(session) == 1)
            }).cloned();

            let Some(idle_id) = idle_id else {
                return Err("all cached Olm sessions are currently in use".into());
            };
            state.sessions.remove(&idle_id);
            state.recency.retain(|entry| entry != &idle_id);
        }

        state.sessions.insert(id.to_owned(), Arc::clone(&restored));
        state.recency.push_back(id.to_owned());
        Ok(restored)
    }

    pub(crate) fn invalidate(&self, id: &str) -> Result<(), String> {
        let mut state = self.state.lock().map_err(|_| "session cache lock poisoned")?;
        if let Some(session) = state.sessions.get(id) {
            if Arc::strong_count(session) > 1 {
                return Err("cannot invalidate an Olm session while it is in use".into());
            }
        }
        state.sessions.remove(id);
        state.recency.retain(|entry| entry != id);
        Ok(())
    }

    /// Atomically evict every cached session belonging to one user namespace.
    /// Refuse the whole operation if any matching session is currently in use.
    pub(crate) fn invalidate_prefix(&self, prefix: &str) -> Result<(), String> {
        if prefix.is_empty() {
            return Err("session namespace prefix must not be empty".into());
        }
        let mut state = self.state.lock().map_err(|_| "session cache lock poisoned")?;
        let matching: Vec<String> = state.sessions.keys()
            .filter(|id| id.starts_with(prefix))
            .cloned()
            .collect();
        if matching.iter().any(|id| {
            state.sessions.get(id).is_some_and(|session| Arc::strong_count(session) > 1)
        }) {
            return Err("cannot clear E2EE session cache while a session is in use".into());
        }
        for id in matching {
            state.sessions.remove(&id);
            state.recency.retain(|entry| entry != &id);
        }
        Ok(())
    }

    pub(crate) fn cached_count(&self) -> Result<usize, String> {
        self.state.lock().map(|state| state.sessions.len())
            .map_err(|_| "session cache lock poisoned".into())
    }

    fn touch(recency: &mut VecDeque<String>, id: &str) {
        recency.retain(|entry| entry != id);
        recency.push_back(id.to_owned());
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use nexura_crypto_core::{create_device_keys, start_outbound_session, pickle_session, unpickle_session};

    fn sample_session() -> Result<Session, String> {
        let (alice, _) = create_device_keys(0);
        let (_bob, bob_public) = create_device_keys(1);
        start_outbound_session(
            &alice,
            &bob_public.curve25519,
            &bob_public.one_time_keys[0].key,
            b"cache test",
        ).map(|result| result.0).map_err(|error| error.to_string())
    }

    #[test]
    fn reuses_loaded_session_without_calling_loader_again() {
        let manager = SessionManager::new(2);
        let first = manager.get_or_load("chat-device-a", sample_session).unwrap();
        let second = manager.get_or_load("chat-device-a", || panic!("must not reload")).unwrap();
        assert!(Arc::ptr_eq(&first, &second));
        assert_eq!(manager.cached_count().unwrap(), 1);
    }

    #[test]
    fn evicts_only_idle_sessions_and_respects_capacity() {
        let manager = SessionManager::new(1);
        let first = manager.get_or_load("first", sample_session).unwrap();
        assert!(manager.get_or_load("second", sample_session).is_err());
        drop(first);
        let second = manager.get_or_load("second", sample_session).unwrap();
        assert_eq!(manager.cached_count().unwrap(), 1);
        assert!(manager.get_or_load("second", || panic!("cached session expected")).is_ok());
        drop(second);
    }

    #[test]
    fn invalidation_refuses_to_remove_a_session_in_use() {
        let manager = SessionManager::new(2);
        let active = manager.get_or_load("active", sample_session).unwrap();
        assert!(manager.invalidate("active").is_err());
        assert_eq!(manager.cached_count().unwrap(), 1);
        drop(active);
        assert!(manager.invalidate("active").is_ok());
        assert_eq!(manager.cached_count().unwrap(), 0);
    }

    #[test]
    fn invalidates_only_the_requested_user_namespace() {
        let manager = SessionManager::new(4);
        let first = manager.get_or_load("user-a|chat-1", sample_session).unwrap();
        let second = manager.get_or_load("user-a|chat-2", sample_session).unwrap();
        let other = manager.get_or_load("user-b|chat-1", sample_session).unwrap();
        drop((first, second, other));

        manager.invalidate_prefix("user-a|").unwrap();

        assert_eq!(manager.cached_count().unwrap(), 1);
        assert!(manager.get_or_load("user-b|chat-1", || panic!("other user's session must remain cached")).is_ok());
    }

    #[test]
    fn namespace_invalidation_is_all_or_nothing_when_session_is_in_use() {
        let manager = SessionManager::new(4);
        let first = manager.get_or_load("user-a|chat-1", sample_session).unwrap();
        let second = manager.get_or_load("user-a|chat-2", sample_session).unwrap();
        drop(second);

        assert!(manager.invalidate_prefix("user-a|").is_err());
        assert_eq!(manager.cached_count().unwrap(), 2);
        drop(first);
        manager.invalidate_prefix("user-a|").unwrap();
        assert_eq!(manager.cached_count().unwrap(), 0);
    }

    #[test]
    fn evicted_session_can_be_restored_from_pickle() {
        let manager = SessionManager::new(1);
        let original = sample_session().unwrap();
        let pickle = pickle_session(&original).unwrap();
        let cached = manager.get_or_load("first", || unpickle_session(&pickle).map_err(|e| e.to_string())).unwrap();
        drop(cached);
        let next = manager.get_or_load("second", sample_session).unwrap();
        drop(next);
        let restored = manager.get_or_load("first", || unpickle_session(&pickle).map_err(|e| e.to_string())).unwrap();
        assert_eq!(restored.lock().unwrap().session_id(), original.session_id());
    }
}
