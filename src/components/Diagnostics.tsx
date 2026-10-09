import { useEffect } from "react";
import { call } from "../api";
import { useApp, perform } from "../stores/app";
import type { Diagnostics as Snapshot } from "../types";
import { bytes } from "../utils/media";

export default function Diagnostics() {
  const metrics = useApp((s) => s.metrics);
  useEffect(() => {
    void perform(async () =>
      useApp.getState().setMetrics(await call<Snapshot>("engine_diagnostics")),
    );
  }, []);
  if (!metrics) return null;
  const m = metrics;
  return (
    <section
      className="card settings-card"
      aria-label="Download engine diagnostics"
    >
      <h2>Download engine diagnostics</h2>
      <p className="muted">Live measurements from the local scheduler.</p>
      <dl className="details engine-details">
        <div>
          <dt>Download / processing / ZIP workers</dt>
          <dd>
            {m.activeWorkers} / {m.processingWorkers} / {m.zipJobs}
          </dd>
        </div>
        <div>
          <dt>Queued items</dt>
          <dd>{m.queuedTasks}</dd>
        </div>
        <div>
          <dt>Effective / configured concurrency</dt>
          <dd>
            {m.currentConcurrency} / {m.configuredConcurrency}
          </dd>
        </div>
        <div>
          <dt>yt-dlp / FFmpeg processes</dt>
          <dd>
            {m.activeYtDlp} / {m.activeFfmpeg}
          </dd>
        </div>
        <div>
          <dt>Download speed / session peak</dt>
          <dd>
            {bytes(m.throughput)}/s / {bytes(m.peakSpeed)}/s
          </dd>
        </div>
        <div>
          <dt>Completed / failed / cancelled</dt>
          <dd>
            {m.completedTasks} / {m.failedTasks} / {m.cancelledTasks}
          </dd>
        </div>
        <div>
          <dt>Session retries / cooling hosts</dt>
          <dd>
            {m.retryCount} / {m.throttledHosts}
          </dd>
        </div>
        <div>
          <dt>System CPU / available memory</dt>
          <dd>
            {m.resources.cpuPercent.toFixed(1)}% /{" "}
            {bytes(m.resources.memoryAvailable)}
          </dd>
        </div>
        <div>
          <dt>Resource pressure</dt>
          <dd>{m.resources.pressure ? "Reducing new work" : "Normal"}</dd>
        </div>
        <div>
          <dt>Disk write throughput</dt>
          <dd>
            {m.diskWriteThroughput == null
              ? "Not measured"
              : `${bytes(m.diskWriteThroughput)}/s`}
          </dd>
        </div>
      </dl>
      {m.processes.map((p) => (
        <p className="field-note wrap" key={p.taskId}>
          {p.command} · PID {p.pid ?? "starting"} · {p.taskId}
        </p>
      ))}
    </section>
  );
}
