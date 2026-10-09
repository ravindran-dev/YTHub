import type { Job } from "../types";
export function validateUrl(input: string): string | null {
  try {
    const u = new URL(input.trim());
    const h = u.hostname;
    if (
      !["http:", "https:"].includes(u.protocol) ||
      u.username ||
      u.password ||
      !(
        h === "youtube.com" ||
        h.endsWith(".youtube.com") ||
        h === "youtu.be" ||
        h === "youtube-nocookie.com" ||
        h.endsWith(".youtube-nocookie.com")
      )
    )
      return "Enter a YouTube video, playlist, or channel URL.";
    if (u.pathname === "/" && !u.search)
      return "Paste a video, playlist, or channel URL.";
    return null;
  } catch {
    return "Enter a valid YouTube URL.";
  }
}
export const active = (s: Job["status"]) =>
  [
    "queued",
    "analyzing",
    "downloading",
    "processing",
    "converting",
    "zipping",
    "waitingforworker",
    "downloadcomplete",
    "waitingforprocessor",
    "merging",
    "retrywaiting",
    "cancelling",
  ].includes(s);
export const terminal = (s: Job["status"]) =>
  ["completed", "failed", "cancelled", "skipped"].includes(s);
export const bytes = (n: number | null | undefined) => {
  if (n == null) return "—";
  if (n === 0) return "0 B";
  const i = Math.min(Math.floor(Math.log(n) / Math.log(1024)), 4);
  return `${(n / 1024 ** i).toFixed(i > 0 ? 1 : 0)} ${["B", "KB", "MB", "GB", "TB"][i]}`;
};
export const time = (n: number | null | undefined) =>
  n == null
    ? "Calculating…"
    : [Math.floor(n / 3600), Math.floor((n % 3600) / 60), Math.floor(n % 60)]
        .filter((_, i) => i > 0 || n >= 3600)
        .map((v) => String(v).padStart(2, "0"))
        .join(":");
export const quality = (j: Job) =>
  j.quality === "best"
    ? "Best available"
    : j.mediaType === "audio"
      ? `${j.quality} kbps`
      : `${j.quality}p`;
export const today = (date: string | null) =>
  !!date && new Date(date).toDateString() === new Date().toDateString();
