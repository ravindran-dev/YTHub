use crate::models::Settings;
pub fn slots(s: &Settings) -> usize {
    s.concurrency
        .clamp(1, if s.advanced_concurrency { 12 } else { 6 })
}
pub fn per_worker(s: &Settings) -> Option<u64> {
    (s.bandwidth_limit > 0).then(|| s.bandwidth_limit / slots(s) as u64)
}
pub fn fragments(s: &Settings) -> u32 {
    s.fragments.min(s.fragment_budget / slots(s) as u32).max(1)
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn aggregate_budget_never_multiplies() {
        for n in 1..=12 {
            let s = Settings {
                concurrency: n,
                advanced_concurrency: true,
                bandwidth_limit: 5_000_000,
                fragments: 8,
                ..Default::default()
            };
            assert!(per_worker(&s).unwrap() * n as u64 <= s.bandwidth_limit);
            assert!(fragments(&s) * n as u32 <= s.fragment_budget);
        }
    }
}
