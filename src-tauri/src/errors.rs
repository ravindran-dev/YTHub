use serde::Serialize;
#[derive(Clone, Copy, Debug, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum ErrorKind {
    InvalidUrl,
    Unavailable,
    Network,
    RateLimited,
    DiskFull,
    PermissionDenied,
    ProcessStartFailed,
    YtDlpFailed,
    FfmpegFailed,
    Cancelled,
    Timeout,
    CorruptOutput,
    Unknown,
}
impl ErrorKind {
    pub fn classify(message: &str) -> Self {
        let m = message.to_lowercase();
        if m.contains("cancel") || m.contains("stopped") {
            Self::Cancelled
        } else if m.contains("429")
            || m.contains("too many requests")
            || m.contains("rate limit")
            || m.contains("throttl")
        {
            Self::RateLimited
        } else if m.contains("disk") && (m.contains("space") || m.contains("full"))
            || m.contains("no space left")
        {
            Self::DiskFull
        } else if m.contains("permission")
            || m.contains("access is denied")
            || m.contains("writable")
        {
            Self::PermissionDenied
        } else if m.contains("timed out") || m.contains("timeout") {
            Self::Timeout
        } else if m.contains("network")
            || m.contains("connection")
            || m.contains("resolve")
            || m.contains("http error 5")
            || m.contains("fragment")
        {
            Self::Network
        } else if m.contains("private")
            || m.contains("removed")
            || m.contains("unavailable")
            || m.contains("not available")
            || m.contains("403")
        {
            Self::Unavailable
        } else if m.contains("invalid url") || m.contains("youtube url") {
            Self::InvalidUrl
        } else if m.contains("cannot start") || m.contains("executable") {
            Self::ProcessStartFailed
        } else if m.contains("corrupt output") {
            Self::CorruptOutput
        } else if m.contains("ffmpeg") || m.contains("conversion") {
            Self::FfmpegFailed
        } else if m.contains("output")
            && (m.contains("invalid") || m.contains("empty") || m.contains("without"))
        {
            Self::CorruptOutput
        } else if m.contains("yt-dlp") {
            Self::YtDlpFailed
        } else {
            Self::Unknown
        }
    }
    pub fn code(self) -> String {
        serde_json::to_value(self)
            .unwrap()
            .as_str()
            .unwrap()
            .to_string()
    }
}
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AppError {
    pub code: &'static str,
    pub message: String,
}
impl From<String> for AppError {
    fn from(message: String) -> Self {
        let lower = message.to_lowercase();
        let code = if lower.contains("space") || lower.contains("disk full") {
            "disk_space"
        } else if lower.contains("permission")
            || lower.contains("writable")
            || lower.contains("access is denied")
        {
            "permission"
        } else if lower.contains("not found") || lower.contains("executable") {
            "engine_missing"
        } else if lower.contains("unavailable") || lower.contains("private") {
            "media_unavailable"
        } else if lower.contains("url") || lower.contains("invalid") || lower.contains("template") {
            "validation"
        } else if lower.contains("network") || lower.contains("timed out") {
            "network"
        } else if lower.contains("zip") {
            "archive"
        } else {
            "operation_failed"
        };
        Self { code, message }
    }
}
impl From<&str> for AppError {
    fn from(message: &str) -> Self {
        message.to_string().into()
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn user_facing_codes() {
        let e: AppError = "Insufficient disk space".into();
        assert_eq!(e.code, "disk_space");
        let e: AppError = "Enter a valid URL".into();
        assert_eq!(e.code, "validation");
        assert!(serde_json::to_string(&e).unwrap().contains("message"));
    }
}
