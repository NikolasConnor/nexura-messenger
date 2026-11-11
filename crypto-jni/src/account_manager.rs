//! Bounded per-device Olm Account cache. Each device has one independently locked account.
use std::collections::{HashMap, VecDeque};
use std::sync::{Arc, Mutex};
use vodozemac::olm::Account;

struct State { accounts: HashMap<String, Arc<Mutex<Account>>>, recency: VecDeque<String> }
pub(crate) struct AccountManager { capacity: usize, state: Mutex<State> }
impl AccountManager {
    pub(crate) fn new(capacity: usize) -> Self {
        assert!(capacity > 0);
        Self { capacity, state: Mutex::new(State { accounts: HashMap::with_capacity(capacity), recency: VecDeque::with_capacity(capacity) }) }
    }
    pub(crate) fn get_or_load<F>(&self, id: &str, loader: F) -> Result<Arc<Mutex<Account>>, String>
    where F: FnOnce() -> Result<Account, String> {
        if id.is_empty() || id.len() > 512 { return Err("invalid native account id".into()); }
        {
            let mut s = self.state.lock().map_err(|_| "account cache lock poisoned")?;
            if let Some(a) = s.accounts.get(id).cloned() { Self::touch(&mut s.recency, id); return Ok(a); }
        }
        let loaded = Arc::new(Mutex::new(loader()?));
        let mut s = self.state.lock().map_err(|_| "account cache lock poisoned")?;
        if let Some(a) = s.accounts.get(id).cloned() { Self::touch(&mut s.recency, id); return Ok(a); }
        if s.accounts.len() >= self.capacity {
            let evict = s.recency.iter().find(|key| s.accounts.get(*key).is_some_and(|a| Arc::strong_count(a) == 1)).cloned();
            let Some(evict) = evict else { return Err("all cached Olm accounts are in use".into()); };
            s.accounts.remove(&evict); s.recency.retain(|x| x != &evict);
        }
        s.accounts.insert(id.to_owned(), Arc::clone(&loaded)); s.recency.push_back(id.to_owned()); Ok(loaded)
    }
    pub(crate) fn invalidate(&self, id: &str) -> Result<(), String> {
        let mut s = self.state.lock().map_err(|_| "account cache lock poisoned")?;
        if let Some(account) = s.accounts.get(id) {
            if Arc::strong_count(account) > 1 {
                return Err("cannot invalidate an Olm account while it is in use".into());
            }
        }
        s.accounts.remove(id); s.recency.retain(|x| x != id); Ok(())
    }
    fn touch(q: &mut VecDeque<String>, id: &str) { q.retain(|x| x != id); q.push_back(id.to_owned()); }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn caches_each_device_account_independently() {
        let cache = AccountManager::new(2);
        let a = cache.get_or_load("user-a|device-1", || Ok(Account::new())).unwrap();
        let again = cache.get_or_load("user-a|device-1", || panic!("unexpected restore")).unwrap();
        let b = cache.get_or_load("user-b|device-1", || Ok(Account::new())).unwrap();
        assert!(Arc::ptr_eq(&a, &again)); assert!(!Arc::ptr_eq(&a, &b));
    }
    #[test] fn invalidation_refuses_to_remove_an_account_in_use() {
        let cache = AccountManager::new(2);
        let active = cache.get_or_load("active", || Ok(Account::new())).unwrap();
        assert!(cache.invalidate("active").is_err());
        drop(active);
        assert!(cache.invalidate("active").is_ok());
    }
    #[test] fn evicts_only_unreferenced_accounts() {
        let cache = AccountManager::new(1);
        let first = cache.get_or_load("first", || Ok(Account::new())).unwrap();
        assert!(cache.get_or_load("second", || Ok(Account::new())).is_err());
        drop(first);
        assert!(cache.get_or_load("second", || Ok(Account::new())).is_ok());
    }
}