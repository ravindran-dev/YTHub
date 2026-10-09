use crate::{
    engine,
    models::{PlaylistItem, Settings},
};
use futures_util::StreamExt;
use std::{
    path::{Path, PathBuf},
    process::Stdio,
    time::Duration,
};
use tokio::io::{AsyncBufReadExt, AsyncReadExt, AsyncSeekExt, AsyncWriteExt, BufReader, BufWriter};
use tokio_util::{
    codec::{FramedRead, LinesCodec},
    sync::CancellationToken,
};
pub struct Discovery {
    pub title: String,
    pub count: usize,
    pub playlist: bool,
    pub duration: f64,
}
pub async fn discover(
    app: &tauri::AppHandle,
    s: &Settings,
    url: &str,
    target: &Path,
    cancel: &CancellationToken,
) -> Result<Discovery, String> {
    let yt = engine::resolve(app, s, "yt-dlp")?;
    let mut args = engine::network_args(s);
    if let Ok(deno) = engine::resolve(app, s, "deno") {
        args.extend([
            "--js-runtimes".into(),
            format!("deno:{}", deno.to_string_lossy()),
        ]);
    }
    args.extend([
        "--flat-playlist".into(),
        "--lazy-playlist".into(),
        "--dump-json".into(),
        "--skip-download".into(),
        "--ignore-errors".into(),
        "--yes-playlist".into(),
        "--playlist-end".into(),
        "10001".into(),
        "--".into(),
        engine::validate_url(url)?,
    ]);
    let mut command = engine::command(yt);
    command
        .args(args)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    let (mut child, _tree) = crate::process::spawn_managed(&mut command)?;
    let mut out = FramedRead::new(
        child.stdout.take().ok_or("No metadata stdout")?,
        LinesCodec::new_with_max_length(512 * 1024),
    );
    let mut err = FramedRead::new(
        child.stderr.take().ok_or("No metadata stderr")?,
        LinesCodec::new_with_max_length(64 * 1024),
    );
    let mut writer = BufWriter::new(
        tokio::fs::File::create(target)
            .await
            .map_err(|e| e.to_string())?,
    );
    let (mut a, mut b) = (false, false);
    let mut errors = String::new();
    let mut result = Discovery {
        title: "Playlist".into(),
        count: 0,
        playlist: false,
        duration: 0.0,
    };
    let deadline = tokio::time::sleep(Duration::from_secs(300));
    tokio::pin!(deadline);
    while !a || !b {
        tokio::select! {biased;
            _=cancel.cancelled()=>return Err("Cancelled metadata discovery".into()),_=&mut deadline=>return Err("Playlist discovery timed out".into()),
            line=out.next(),if !a=>match line{Some(Ok(line))=>{let v:serde_json::Value=serde_json::from_str(&line).map_err(|e|format!("Invalid playlist metadata: {e}"))?;result.count+=1;if result.count>10000{return Err("Playlist exceeds the 10,000 item admission limit".into());}let id=v["id"].as_str().unwrap_or("");let raw=v["webpage_url"].as_str().or(v["url"].as_str()).filter(|p|p.starts_with("http")).map(str::to_string).unwrap_or_else(||format!("https://www.youtube.com/watch?v={id}"));let item=PlaylistItem{url:engine::validate_url(&raw)?,title:v["title"].as_str().unwrap_or("Unavailable item").chars().take(500).collect(),duration:v["duration"].as_f64(),index:v["playlist_index"].as_u64().map(|v|v as usize).unwrap_or(result.count),source_id:id.chars().take(100).collect()};result.duration+=item.duration.unwrap_or(0.0);result.playlist|=v["playlist_id"].is_string();result.title=v["playlist_title"].as_str().or(v["title"].as_str()).unwrap_or("Playlist").chars().take(200).collect();let mut bytes=serde_json::to_vec(&item).map_err(|e|e.to_string())?;bytes.push(b'\n');writer.write_all(&bytes).await.map_err(|e|e.to_string())?;},Some(Err(e))=>return Err(e.to_string()),None=>a=true},
            line=err.next(),if !b=>match line{Some(Ok(line))=>{if errors.len()<8000{errors.push_str(&engine::redact(&line,s));}},Some(Err(e))=>return Err(e.to_string()),None=>b=true}
        }
    }
    let status = tokio::select! {biased;_=cancel.cancelled()=>return Err("Cancelled metadata discovery".into()),_=&mut deadline=>return Err("Playlist discovery timed out".into()),status=child.wait()=>status.map_err(|e|e.to_string())?};
    if !status.success() || result.count == 0 {
        return Err(if errors.is_empty() {
            "Playlist is empty or unavailable".into()
        } else {
            errors
        });
    }
    writer.flush().await.map_err(|e| e.to_string())?;
    writer
        .get_ref()
        .sync_all()
        .await
        .map_err(|e| e.to_string())?;
    Ok(result)
}
pub async fn read_window(
    path: PathBuf,
    cursor: u64,
    count: usize,
) -> Result<(Vec<PlaylistItem>, u64), String> {
    let mut file = tokio::fs::File::open(path)
        .await
        .map_err(|e| e.to_string())?;
    file.seek(std::io::SeekFrom::Start(cursor))
        .await
        .map_err(|e| e.to_string())?;
    let mut reader = BufReader::new(file);
    let mut entries = Vec::with_capacity(count.min(64));
    let mut offset = cursor;
    for _ in 0..count.min(64) {
        let mut line = String::new();
        let n = (&mut reader)
            .take(64 * 1024 + 1)
            .read_line(&mut line)
            .await
            .map_err(|e| e.to_string())?;
        if n == 0 {
            break;
        }
        if n > 64 * 1024 {
            return Err("Invalid oversized playlist manifest entry".into());
        }
        offset += n as u64;
        entries.push(serde_json::from_str(&line).map_err(|e| e.to_string())?);
    }
    Ok((entries, offset))
}
#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test]
    async fn lazy_manifest_stages_only_bounded_window() {
        let d = tempfile::tempdir().unwrap();
        let p = d.path().join("playlist.jsonl");
        let mut f = std::fs::File::create(&p).unwrap();
        for i in 0..1000 {
            use std::io::Write;
            writeln!(
                f,
                "{}",
                serde_json::to_string(&PlaylistItem {
                    url: format!("https://youtu.be/{i}"),
                    title: "A".into(),
                    duration: None,
                    index: i + 1,
                    source_id: i.to_string()
                })
                .unwrap()
            )
            .unwrap();
        }
        drop(f);
        let (a, c) = read_window(p.clone(), 0, 1000).await.unwrap();
        assert_eq!(a.len(), 64);
        let (b, _) = read_window(p, c, 32).await.unwrap();
        assert_eq!(b[0].index, 65);
        assert_eq!(b.len(), 32);
    }
}
