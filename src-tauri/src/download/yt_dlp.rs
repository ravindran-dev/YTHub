use crate::{
    engine,
    models::{Job, StagedMedia},
};
use std::{path::Path, time::Duration};
use tokio_util::sync::CancellationToken;
pub fn args(job: &Job, deno: Option<&Path>) -> Vec<String> {
    let mut a = engine::network_args(&job.settings);
    // The scheduler owns whole-job retries; keep engine retries short and finite.
    if let Some(index) = a.iter().position(|v| v == "--retries") {
        a[index + 1] = "1".into();
    }
    let cap = if job.quality == "best" {
        String::new()
    } else {
        format!("[height<={}]", job.quality)
    };
    let selector = if job.media_type == "audio" {
        "bestaudio/best".into()
    } else if job.settings.codec == "compatible" && job.settings.container == "mp4" {
        format!("bv{cap}[vcodec^=avc]/bv{cap}/b{cap},ba[ext=m4a]/ba/b{cap}")
    } else {
        format!("bv{cap}/b{cap},ba/b{cap}")
    };
    if let Some(deno) = deno {
        a.extend([
            "--js-runtimes".into(),
            format!("deno:{}", deno.to_string_lossy()),
        ]);
    }
    a.extend([
        "--no-playlist".into(),
        "--no-plugin-dirs".into(),
        "--newline".into(),
        "--progress".into(),
        "--progress-delta".into(),
        "0.1".into(),
        "--no-simulate".into(),
        "--windows-filenames".into(),
        "--trim-filenames".into(),
        "190".into(),
        "--continue".into(),
        "--no-overwrites".into(),
        "--fixup".into(),
        "never".into(),
        "--downloader".into(),
        "native".into(),
        "--fragment-retries".into(),
        "1".into(),
        "--retry-sleep".into(),
        "fragment:exp=1:8".into(),
        "--concurrent-fragments".into(),
        job.settings.fragments.to_string(),
        "--paths".into(),
        job.temp_dir.clone(),
        "-o".into(),
        engine::output_template(job).replace(".%(ext)s", ".f%(format_id)s.%(ext)s"),
        "-f".into(),
        selector,
        "--progress-template".into(),
        "download:YTHUB_PROGRESS:%(progress)j".into(),
        "--print".into(),
        "after_move:YTHUB_FILE:%(filepath)j".into(),
        "--print".into(),
        "before_dl:YTHUB_META:%(.{title,uploader,duration})j".into(),
    ]);
    if job.media_type == "audio" && job.settings.embed_thumbnail {
        a.extend([
            "--write-thumbnail".into(),
            "-o".into(),
            "thumbnail:cover.%(ext)s".into(),
        ]);
    }
    if let Some(rate) = super::rate_limit::per_worker(&job.settings) {
        a.extend(["--limit-rate".into(), rate.to_string()]);
    }
    a.extend(["--".into(), job.url.clone()]);
    a
}
pub async fn download(
    job: &Job,
    yt: &Path,
    deno: Option<&Path>,
    cancel: &CancellationToken,
    line: impl FnMut(&str) + Send,
) -> Result<StagedMedia, String> {
    let mut command = engine::command(yt);
    command.args(args(job, deno));
    let result = super::supervisor::run(
        command,
        cancel,
        vec![job.temp_dir.clone().into(), job.output_dir.clone().into()],
        job.settings.disk_safety_mb * 1024 * 1024,
        Duration::from_secs(24 * 3600),
        line,
    )
    .await?;
    let _ = result.exit_code;
    let mut stage = StagedMedia {
        title: job.title.clone(),
        ..Default::default()
    };
    for line in result.lines {
        if let Some(data) = line.strip_prefix("YTHUB_FILE:") {
            let path: String = serde_json::from_str(data).map_err(|e| e.to_string())?;
            let actual = std::fs::canonicalize(&path).map_err(|e| e.to_string())?;
            let root = std::fs::canonicalize(&job.temp_dir).map_err(|e| e.to_string())?;
            if !actual.starts_with(root)
                || std::fs::metadata(&actual).map_err(|e| e.to_string())?.len() == 0
            {
                return Err("Invalid output from yt-dlp".into());
            }
            if !stage.files.contains(&path) {
                stage.files.push(path);
            }
        } else if let Some(data) = line.strip_prefix("YTHUB_META:") {
            if let Ok(v) = serde_json::from_str::<serde_json::Value>(data) {
                stage.title = v["title"].as_str().unwrap_or(&stage.title).to_string();
                stage.artist = v["uploader"].as_str().unwrap_or("").to_string();
            }
        }
    }
    let first = stage
        .files
        .first()
        .ok_or("yt-dlp exited without a downloaded file")?;
    let stem = Path::new(first)
        .file_stem()
        .ok_or("Invalid output filename")?
        .to_string_lossy();
    let stem = stem
        .rsplit_once(".f")
        .map(|(name, _)| name)
        .unwrap_or(&stem);
    stage.final_name = format!(
        "{stem}.{}",
        if job.media_type == "audio" {
            "mp3"
        } else {
            &job.settings.container
        }
    );
    if job.settings.embed_thumbnail {
        for file in std::fs::read_dir(&job.temp_dir)
            .map_err(|e| e.to_string())?
            .flatten()
        {
            let p = file.path();
            if p.file_stem().and_then(|v| v.to_str()) == Some("cover")
                && matches!(
                    p.extension().and_then(|v| v.to_str()),
                    Some("jpg" | "jpeg" | "webp" | "png")
                )
            {
                stage.thumbnail = Some(p.to_string_lossy().into());
                break;
            }
        }
    }
    Ok(stage)
}
