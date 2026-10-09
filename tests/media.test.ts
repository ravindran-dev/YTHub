import { describe, it, expect } from "vitest";
import { validateUrl, bytes, time, active, terminal } from "../src/utils/media";
describe("YouTube URL boundary", () => {
  it.each([
    "https://youtu.be/abc",
    "https://www.youtube.com/watch?v=abc",
    "https://youtube.com/playlist?list=PLabc",
    "https://youtube.com/@channel/videos",
  ])("accepts %s", (u) => expect(validateUrl(u)).toBeNull());
  it.each([
    "",
    "file:///C:/secret",
    "https://youtube.com.evil.test/watch?v=abc",
    "https://evil.test",
    "https://user:secret@youtube.com/watch?v=a",
    "https://youtube.com",
  ])("rejects %s", (u) => expect(validateUrl(u)).not.toBeNull());
});
describe("real progress presentation", () => {
  it("formats bytes and ETA", () => {
    expect(bytes(1024 ** 3)).toBe("1.0 GB");
    expect(bytes(null)).toBe("—");
    expect(time(81)).toBe("01:21");
  });
  it("distinguishes running and finished tasks", () => {
    expect(active("downloading")).toBe(true);
    expect(active("paused")).toBe(false);
    expect(terminal("failed")).toBe(true);
    expect(terminal("processing")).toBe(false);
  });
});
