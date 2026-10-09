use std::collections::HashMap;
#[derive(Default)]
pub struct Adaptive {
    pub limit: usize,
    hosts: HashMap<String, (i64, u32)>,
    healthy_since: i64,
}
impl Adaptive {
    pub fn update(&mut self, configured: usize, enabled: bool, pressure: bool, now: i64) -> usize {
        if !enabled {
            self.limit = configured;
        } else {
            if self.limit == 0 {
                self.limit = 2.min(configured);
                self.healthy_since = now;
            }
            if pressure {
                self.limit = self.limit.saturating_sub(1).max(1);
                self.healthy_since = now;
            } else if now - self.healthy_since >= 30_000 {
                self.limit = (self.limit + 1).min(configured);
                self.healthy_since = now;
            }
        }
        self.limit = self.limit.clamp(1, configured.max(1));
        self.limit
    }
    pub fn throttle(&mut self, host: &str, now: i64) {
        let previous = self.hosts.get(host).map(|(_, n)| *n).unwrap_or(0);
        let n = (previous + 1).min(5);
        self.hosts
            .insert(host.into(), (now + 15_000 * (1i64 << n.min(4)), n));
        self.limit = self.limit.saturating_sub(1).max(1);
        self.healthy_since = now;
    }
    pub fn ready(&self, host: &str, now: i64) -> bool {
        self.hosts.get(host).is_none_or(|(until, _)| now >= *until)
    }
    pub fn cooling(&self, now: i64) -> usize {
        self.hosts
            .values()
            .filter(|(until, _)| *until > now)
            .count()
    }
    pub fn recovered(&mut self, host: &str) {
        // An already-running success must never erase another task's cooldown.
        if self
            .hosts
            .get(host)
            .is_some_and(|(until, _)| *until <= chrono::Utc::now().timestamp_millis())
        {
            self.hosts.remove(host);
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn throttling_cools_only_host_and_recovers_slowly() {
        let mut a = Adaptive::default();
        assert_eq!(a.update(6, true, false, 0), 2);
        a.throttle("youtube", 1000);
        assert!(!a.ready("youtube", 2000));
        assert!(a.ready("other", 2000));
        assert_eq!(a.update(6, true, false, 2000), 1);
        assert_eq!(a.update(6, true, false, 31000), 2);
        assert_eq!(a.update(6, true, false, 32000), 2);
        assert_eq!(a.update(6, false, false, 32000), 6);
    }
}
