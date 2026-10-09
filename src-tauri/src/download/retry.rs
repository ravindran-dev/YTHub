use crate::errors::ErrorKind;
pub fn delay(kind: ErrorKind, attempt: u32, max: u32, base: u64, seed: u64) -> Option<u64> {
    let limit = match kind {
        ErrorKind::Network | ErrorKind::Timeout | ErrorKind::RateLimited => max,
        ErrorKind::Unavailable | ErrorKind::FfmpegFailed => max.min(1),
        _ => 0,
    };
    if attempt > limit || attempt == 0 {
        return None;
    }
    let base = if kind == ErrorKind::RateLimited {
        base.max(15)
    } else {
        base
    };
    let ceiling = base
        .saturating_mul(1u64 << attempt.saturating_sub(1).min(8))
        .min(300);
    Some(ceiling * 1000 + seed % (ceiling * 500 + 1))
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn bounded_jittered_classified_backoff() {
        assert!(delay(ErrorKind::DiskFull, 1, 5, 2, 1).is_none());
        assert!(delay(ErrorKind::Cancelled, 1, 5, 2, 1).is_none());
        assert!(delay(ErrorKind::Network, 6, 5, 2, 1).is_none());
        assert!(
            delay(ErrorKind::Network, 2, 5, 2, 7).unwrap()
                > delay(ErrorKind::Network, 1, 5, 2, 7).unwrap()
        );
        assert_ne!(
            delay(ErrorKind::RateLimited, 1, 5, 2, 1),
            delay(ErrorKind::RateLimited, 1, 5, 2, 99)
        );
    }
}
