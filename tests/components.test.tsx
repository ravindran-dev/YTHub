import { afterEach, beforeEach, describe, it, expect, vi } from "vitest";
import {
  render,
  screen,
  cleanup,
  fireEvent,
  waitFor,
  act,
} from "@testing-library/react";
import {
  JobList,
  JobDetails,
  Progress,
  StatusBadge,
} from "../src/components/Common";
import type { Job, Settings } from "../src/types";
import SettingsPage from "../src/pages/Settings";
import { call, copyUrl, confirmAction } from "../src/api";
const historyEvents = vi.hoisted(() => ({
  changed: undefined as (() => void) | undefined,
}));
vi.mock("@tauri-apps/api/event", () => ({
  listen: vi.fn(async (_event: string, callback: () => void) => {
    historyEvents.changed = callback;
    return () => {
      historyEvents.changed = undefined;
    };
  }),
}));
vi.mock("../src/api", () => ({
  call: vi.fn(async () => undefined),
  choose: vi.fn(async () => null),
  desktop: true,
  copyUrl: vi.fn(async () => undefined),
  confirmAction: vi.fn(async () => true),
}));
import { useApp } from "../src/stores/app";
import Downloads from "../src/pages/Downloads";
const job = {
  id: "one",
  title: "A real track",
  url: "https://youtu.be/abc",
  mediaType: "audio",
  status: "downloading",
  progress: 42.5,
  quality: "320",
  downloadedBytes: 1024,
  totalBytes: 2048,
  speed: 512,
  eta: 2,
  settings: { container: "mp4" },
  error: null,
} as Job;
afterEach(cleanup);
beforeEach(() => {
  vi.clearAllMocks();
  useApp.setState({ error: null, message: null });
  HTMLDialogElement.prototype.showModal = function () {
    this.setAttribute("open", "");
  };
  HTMLDialogElement.prototype.close = function () {
    this.removeAttribute("open");
  };
});
describe("download action regression", () => {
  const completed = {
    ...job,
    status: "completed",
    outputPath: "C:/Media/My track [id].mp3",
    outputDir: "C:/Media",
    createdAt: "2026-10-08T12:00:00Z",
  } as Job;
  it("opens the folder from the history row and opens details from the dots", async () => {
    const select = vi.fn();
    render(<JobList jobs={[completed]} onSelect={select} />);
    fireEvent.click(
      screen.getByRole("button", { name: "Open folder for A real track" }),
    );
    await waitFor(() =>
      expect(call).toHaveBeenCalledWith("open_output", {
        id: "one",
        kind: "folder",
      }),
    );
    fireEvent.click(
      screen.getByRole("button", { name: "Details for A real track" }),
    );
    expect(select).toHaveBeenCalledWith("one");
  });
  it("copies through the native boundary and confirms success", async () => {
    render(<JobDetails job={completed} onClose={() => {}} />);
    fireEvent.click(screen.getByRole("button", { name: "Copy URL" }));
    await waitFor(() =>
      expect(copyUrl).toHaveBeenCalledWith("one", completed.url),
    );
    expect(
      await screen.findByRole("button", { name: "Copied!" }),
    ).toBeInTheDocument();
  });
  it("opens the actual file and folder with the correct IDs", async () => {
    render(<JobDetails job={completed} onClose={() => {}} />);
    fireEvent.click(screen.getByRole("button", { name: "Open file" }));
    await waitFor(() =>
      expect(call).toHaveBeenCalledWith("open_output", {
        id: "one",
        kind: "file",
      }),
    );
    fireEvent.click(screen.getByRole("button", { name: "Open folder" }));
    await waitFor(() =>
      expect(call).toHaveBeenCalledWith("open_output", {
        id: "one",
        kind: "folder",
      }),
    );
  });
  it("shows a missing-file error inside the open dialog", async () => {
    vi.mocked(call).mockRejectedValueOnce(
      new Error("The file no longer exists."),
    );
    render(<JobDetails job={completed} onClose={() => {}} />);
    fireEvent.click(screen.getByRole("button", { name: "Open file" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "The file no longer exists.",
    );
    expect(screen.getByRole("dialog")).toContainElement(
      screen.getByRole("alert"),
    );
  });
  it("does not remove history when the native confirmation is cancelled", async () => {
    vi.mocked(confirmAction).mockResolvedValueOnce(false);
    const close = vi.fn();
    render(<JobDetails job={completed} onClose={close} />);
    fireEvent.click(screen.getByRole("button", { name: "Remove entry" }));
    await waitFor(() => expect(confirmAction).toHaveBeenCalled());
    expect(call).not.toHaveBeenCalled();
    expect(close).not.toHaveBeenCalled();
  });
  it.each([
    ["downloading", "Pause A real track", "pause"],
    ["paused", "Resume A real track", "resume"],
    ["failed", "Retry A real track", "retry"],
    ["queued", "Cancel A real track", "cancel"],
    ["waitingforworker", "Pause A real track", "pause"],
    ["waitingforprocessor", "Pause A real track", "pause"],
    ["retrywaiting", "Pause A real track", "pause"],
    ["interrupted", "Resume A real track", "resume"],
    ["merging", "Pause A real track", "pause"],
  ] as const)("dispatches %s controls", async (status, label, action) => {
    render(<JobList jobs={[{ ...job, status }]} onSelect={() => {}} />);
    fireEvent.click(screen.getByRole("button", { name: label }));
    await waitFor(() =>
      expect(call).toHaveBeenCalledWith("control_job", { id: "one", action }),
    );
  });
});
describe("queue and progress", () => {
  it("reloads paged history after a native state change and clears visible entries", async () => {
    let rows = [{ ...job, status: "completed" as const }];
    vi.mocked(call).mockImplementation(async (command) => {
      if (command === "get_history") return rows;
      if (command === "clear_history") {
        rows = [];
        return;
      }
      return undefined;
    });
    render(<Downloads history onSelect={() => {}} />);
    expect(await screen.findByText("A real track")).toBeInTheDocument();
    rows = [];
    await act(async () => {
      historyEvents.changed?.();
    });
    await waitFor(() =>
      expect(screen.queryByText("A real track")).not.toBeInTheDocument(),
    );
    rows = [{ ...job, status: "completed" as const }];
    await act(async () => {
      historyEvents.changed?.();
    });
    expect(await screen.findByText("A real track")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Clear history" }));
    await waitFor(() =>
      expect(screen.queryByText("A real track")).not.toBeInTheDocument(),
    );
  });
  it("renders structured progress with accessible values", () => {
    render(<Progress value={42.5} label="Download progress" />);
    expect(screen.getByRole("progressbar")).toHaveAttribute(
      "aria-valuenow",
      "43",
    );
  });
  it("renders status and task details from engine data", () => {
    const select = vi.fn();
    render(<JobList jobs={[job]} onSelect={select} />);
    expect(screen.getByText("42.5%")).toBeInTheDocument();
    expect(screen.getByText("1.0 KB / 2.0 KB")).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /Pause A real track/ }),
    ).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "A real track" }));
    expect(select).toHaveBeenCalledWith("one");
  });
  it("shows failures without pretending completion", () => {
    render(<StatusBadge status="failed" />);
    expect(screen.getByText("failed")).toBeInTheDocument();
  });
  it("filters queue history and shows useful empty state", () => {
    useApp.setState({ db: null });
    render(<Downloads onSelect={() => {}} />);
    expect(screen.getByText("Your queue is clear")).toBeInTheDocument();
  });
});
describe("settings command boundary", () => {
  it("sends edited preferences to the real command name rather than changing only UI state", async () => {
    const settings: Settings = {
      theme: "dark",
      downloadDir: "C:\\Downloads\\YTHub",
      tempDir: "C:\\Downloads\\YTHub\\Temp",
      zipDir: "C:\\Downloads\\YTHub\\ZIP",
      autoStart: true,
      concurrency: 2,
      filenameTemplate: "%(title)s.%(ext)s",
      playlistTemplate: "%(playlist_index)03d - %(title)s.%(ext)s",
      audioQuality: "320",
      embedThumbnail: true,
      embedMetadata: true,
      videoQuality: "best",
      container: "mp4",
      codec: "quality",
      preserveOrder: true,
      autoZip: false,
      keepFolder: true,
      zipPartial: false,
      duplicate: "skip",
      ytDlpPath: "",
      ffmpegPath: "",
      ffprobePath: "",
      retries: 3,
      fragments: 2,
      socketTimeout: 30,
      proxy: "",
      cookiesFile: "",
      logging: "normal",
      notifications: true,
      startWithWindows: false,
      startMinimized: false,
      setupComplete: true,
      advancedConcurrency: false,
      ffmpegConcurrency: 2,
      hostConcurrency: 3,
      bandwidthLimit: 0,
      retryBackoff: 2,
      adaptiveConcurrency: true,
      progressHz: 5,
      diskSafetyMb: 256,
      fragmentBudget: 24,
      prefetch: 32,
    };
    useApp.setState({ db: { settings, jobs: [], batches: [] } });
    render(<SettingsPage versions={[]} refresh={() => {}} checking={false} />);
    fireEvent.change(screen.getByLabelText("Concurrent downloads"), {
      target: { value: "3" },
    });
    fireEvent.change(screen.getByLabelText("Default MP3 quality"), {
      target: { value: "192" },
    });
    fireEvent.click(screen.getAllByRole("button", { name: "Save changes" })[0]);
    await waitFor(() =>
      expect(call).toHaveBeenCalledWith("save_settings", {
        settings: { ...settings, concurrency: 3, audioQuality: "192" },
      }),
    );
  });
});
