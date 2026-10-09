use std::{
    collections::VecDeque,
    time::{Duration, Instant},
};
pub struct ProgressMeter {
    samples: VecDeque<(Instant, u64)>,
    base: u64,
    previous: u64,
    last_emit: Instant,
    interval: Duration,
}
impl ProgressMeter {
    pub fn new(hz: u32) -> Self {
        Self {
            samples: VecDeque::with_capacity(64),
            base: 0,
            previous: 0,
            last_emit: Instant::now() - Duration::from_secs(1),
            interval: Duration::from_secs_f64(1.0 / hz.clamp(1, 10) as f64),
        }
    }
    pub fn update(
        &mut self,
        bytes: u64,
        total: Option<u64>,
        now: Instant,
    ) -> (u64, Option<f64>, Option<f64>, bool) {
        if bytes < self.previous {
            self.base = self.base.saturating_add(self.previous);
        }
        self.previous = bytes;
        let cumulative = self.base.saturating_add(bytes);
        self.samples.push_back((now, cumulative));
        while self.samples.len() > 64
            || self
                .samples
                .front()
                .is_some_and(|(at, _)| now.saturating_duration_since(*at) > Duration::from_secs(5))
        {
            self.samples.pop_front();
        }
        let speed = self.samples.front().and_then(|(at, start)| {
            let seconds = now.saturating_duration_since(*at).as_secs_f64();
            (seconds >= 0.2 && cumulative >= *start).then(|| (cumulative - *start) as f64 / seconds)
        });
        let eta = total
            .zip(speed.filter(|v| *v > 1.0))
            .map(|(total, speed)| total.saturating_sub(bytes) as f64 / speed);
        let emit = now.saturating_duration_since(self.last_emit) >= self.interval;
        if emit {
            self.last_emit = now;
        }
        (cumulative, speed, eta, emit)
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn coalesces_real_bytes_and_handles_stream_reset() {
        let mut p = ProgressMeter::new(5);
        let start = Instant::now();
        let mut count = 0;
        for i in 0..1000 {
            count += usize::from(p.update(i, Some(2000), start + Duration::from_millis(i)).3);
        }
        assert!(count <= 6);
        let (bytes, speed, _, _) = p.update(10, None, start + Duration::from_secs(2));
        assert_eq!(bytes, 1009);
        assert!(speed.unwrap() > 0.0);
    }
}
