//! In-process HTTPS download, used on Android: phones ship no curl (only Termux
//! has one), so neither adb nor the Android app could fetch models otherwise.
//! Behaves like the curl invocation used elsewhere: follows redirects, resumes a
//! partial file, retries transient failures and draws a progress line on stderr.

use std::fs::{File, OpenOptions};
use std::io::{self, Read, Write};
use std::path::Path;
use std::time::{Duration, Instant};

use anyhow::{Context, Result};
use ureq::tls::{PemItem, RootCerts, TlsConfig};
use ureq::Agent;

/// Extra attempts after the first one fails (curl --retry 3)
const RETRIES: u32 = 3;
const RETRY_DELAY: Duration = Duration::from_secs(2);

/// Download `url` into `out`, resuming what is already there. Returns whether the
/// file is complete; failures are reported on stderr (like curl) so the caller
/// can try the next mirror.
pub fn fetch(out: &Path, url: &str) -> Result<bool> {
    fetch_with(&agent(Agent::config_builder())?, out, url, RETRY_DELAY)
}

/// Agent with curl-like timeouts. The proxy comes from the usual environment
/// variables (HTTPS_PROXY, NO_PROXY, …); roots are Mozilla's bundled ones, or
/// those in $SSL_CERT_FILE when set (as with curl/OpenSSL).
fn agent(config: ureq::config::ConfigBuilder<ureq::typestate::AgentScope>) -> Result<Agent> {
    let mut config = config
        .timeout_connect(Some(Duration::from_secs(15)))
        .timeout_recv_response(Some(Duration::from_secs(30)))
        .user_agent(concat!("qwen-translate/", env!("CARGO_PKG_VERSION")));
    if let Some(file) = std::env::var_os("SSL_CERT_FILE") {
        let pem = std::fs::read(&file)
            .with_context(|| format!("failed to read SSL_CERT_FILE {}", file.to_string_lossy()))?;
        let certs: Vec<_> = ureq::tls::parse_pem(&pem)
            .filter_map(|item| match item {
                Ok(PemItem::Certificate(c)) => Some(c),
                _ => None,
            })
            .collect();
        config = config.tls_config(
            TlsConfig::builder()
                .root_certs(RootCerts::new_with_certs(&certs))
                .build(),
        );
    }
    Ok(config.build().into())
}

/// Outcome of one request
enum Attempt {
    Done,
    /// Worth retrying (network error, 5xx, cut-off body, …)
    Retry(String),
    /// Retrying won't help (404 and other client errors)
    Fail(String),
}

fn fetch_with(agent: &Agent, out: &Path, url: &str, delay: Duration) -> Result<bool> {
    let mut attempt = 0;
    loop {
        match try_once(agent, out, url)? {
            Attempt::Done => return Ok(true),
            Attempt::Fail(e) => {
                eprintln!("error: {e}");
                return Ok(false);
            }
            Attempt::Retry(e) if attempt < RETRIES => {
                attempt += 1;
                eprintln!(
                    "warning: {e}; retrying in {}s ({attempt}/{RETRIES})",
                    delay.as_secs()
                );
                std::thread::sleep(delay);
            }
            Attempt::Retry(e) => {
                eprintln!("error: {e}");
                return Ok(false);
            }
        }
    }
}

fn try_once(agent: &Agent, out: &Path, url: &str) -> Result<Attempt> {
    let offset = std::fs::metadata(out).map_or(0, |m| m.len());
    let mut req = agent.get(url);
    if offset > 0 {
        req = req.header("Range", format!("bytes={offset}-"));
    }
    let resp = match req.call() {
        Ok(r) => r,
        // The partial file is no prefix of the remote one (e.g. longer): start over
        Err(ureq::Error::StatusCode(416)) if offset > 0 => {
            let _ = std::fs::remove_file(out);
            return Ok(Attempt::Retry("cannot resume the partial download".into()));
        }
        Err(ureq::Error::StatusCode(code)) if code == 408 || code == 429 || code >= 500 => {
            return Ok(Attempt::Retry(format!("HTTP {code} from {url}")));
        }
        Err(ureq::Error::StatusCode(code)) => {
            return Ok(Attempt::Fail(format!("HTTP {code} from {url}")))
        }
        Err(e) => return Ok(Attempt::Retry(e.to_string())),
    };

    let header = |name: &str| resp.headers().get(name).and_then(|v| v.to_str().ok());
    let (mut file, start, total) = match resp.status().as_u16() {
        206 => match header("content-range").and_then(parse_content_range) {
            Some((first, total)) if first == offset && offset > 0 => {
                (OpenOptions::new().append(true).open(out)?, offset, total)
            }
            _ => {
                let _ = std::fs::remove_file(out);
                return Ok(Attempt::Retry("unexpected partial response".into()));
            }
        },
        // Whole file: no partial yet, or the server ignores Range (curl exit 33)
        _ => {
            let total = header("content-length").and_then(|v| v.parse().ok());
            (File::create(out)?, 0, total)
        }
    };

    let mut body = resp.into_body().into_reader();
    let mut progress = Progress::new(start, total);
    let mut buf = vec![0u8; 256 * 1024];
    loop {
        let n = match body.read(&mut buf) {
            Ok(0) => break,
            Ok(n) => n,
            Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
            Err(e) => {
                progress.finish();
                return Ok(Attempt::Retry(format!("download interrupted: {e}")));
            }
        };
        file.write_all(&buf[..n])
            .with_context(|| format!("failed to write {}", out.display()))?;
        progress.advance(n as u64);
    }
    file.sync_all()?;
    progress.finish();

    let len = file.metadata()?.len();
    match total {
        Some(t) if len != t => Ok(Attempt::Retry(format!(
            "download incomplete ({len} of {t} bytes)"
        ))),
        _ => Ok(Attempt::Done),
    }
}

/// "bytes 100-999/1000" -> (100, Some(1000)); the total may be "*" (unknown)
fn parse_content_range(v: &str) -> Option<(u64, Option<u64>)> {
    let (range, total) = v.strip_prefix("bytes ")?.split_once('/')?;
    let first = range.split_once('-')?.0.trim().parse().ok()?;
    Some((first, total.trim().parse().ok()))
}

/// One-line progress meter on stderr, redrawn in place with \r
struct Progress {
    done: u64,
    total: Option<u64>,
    started: Instant,
    received: u64,
    drawn: Option<Instant>,
}

impl Progress {
    const INTERVAL: Duration = Duration::from_millis(250);

    fn new(done: u64, total: Option<u64>) -> Self {
        Self {
            done,
            total,
            started: Instant::now(),
            received: 0,
            drawn: None,
        }
    }

    fn advance(&mut self, n: u64) {
        self.done += n;
        self.received += n;
        if self.drawn.is_none_or(|t| t.elapsed() >= Self::INTERVAL) {
            self.draw();
        }
    }

    fn draw(&mut self) {
        const MIB: f64 = 1024.0 * 1024.0;
        let secs = self.started.elapsed().as_secs_f64().max(0.001);
        let speed = self.received as f64 / MIB / secs;
        let done = self.done as f64 / MIB;
        let line = match self.total {
            Some(t) if t > 0 => format!(
                "{:5.1}%  {done:7.1} / {:.1} MiB  {speed:5.1} MiB/s",
                self.done as f64 * 100.0 / t as f64,
                t as f64 / MIB
            ),
            _ => format!("{done:7.1} MiB  {speed:5.1} MiB/s"),
        };
        // Trailing spaces wipe leftovers of a longer previous line
        eprint!("\r{line}    ");
        self.drawn = Some(Instant::now());
    }

    fn finish(&mut self) {
        if self.drawn.is_some() {
            self.draw();
            eprintln!();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::BufRead;
    use std::net::TcpListener;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::Arc;

    const DATA: &[u8] = b"0123456789abcdefghijklmnopqrstuvwxyz";

    /// What the test server does with a request carrying `range` (first byte asked for)
    type Handler = dyn Fn(usize, Option<u64>) -> Vec<u8> + Send + Sync;

    /// Serves one response per connection, then closes it. Returns the base URL
    /// and the number of requests seen.
    fn server(handler: Box<Handler>) -> (String, Arc<AtomicUsize>) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let url = format!("http://{}/model.gguf", listener.local_addr().unwrap());
        let count = Arc::new(AtomicUsize::new(0));
        let seen = count.clone();
        std::thread::spawn(move || {
            for stream in listener.incoming() {
                let mut stream = stream.unwrap();
                let mut reader = io::BufReader::new(stream.try_clone().unwrap());
                let mut range = None;
                loop {
                    let mut line = String::new();
                    reader.read_line(&mut line).unwrap();
                    if line.trim().is_empty() {
                        break;
                    }
                    let lower = line.to_ascii_lowercase();
                    if let Some(v) = lower.strip_prefix("range: bytes=") {
                        range = v.trim().trim_end_matches('-').parse().ok();
                    }
                }
                let n = seen.fetch_add(1, Ordering::SeqCst);
                let _ = stream.write_all(&handler(n, range));
            }
        });
        (url, count)
    }

    fn full() -> Vec<u8> {
        let mut r = format!(
            "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
            DATA.len()
        )
        .into_bytes();
        r.extend_from_slice(DATA);
        r
    }

    fn partial(from: u64) -> Vec<u8> {
        let from = from as usize;
        let mut r = format!(
            "HTTP/1.1 206 Partial Content\r\nContent-Length: {}\r\n\
             Content-Range: bytes {from}-{}/{}\r\nConnection: close\r\n\r\n",
            DATA.len() - from,
            DATA.len() - 1,
            DATA.len()
        )
        .into_bytes();
        r.extend_from_slice(&DATA[from..]);
        r
    }

    fn status(code: u16) -> Vec<u8> {
        format!("HTTP/1.1 {code} X\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").into_bytes()
    }

    fn run(handler: Box<Handler>, existing: Option<&[u8]>) -> (bool, Vec<u8>, usize) {
        let (url, count) = server(handler);
        let dir = std::env::temp_dir().join(format!(
            "translate-https-{}-{}",
            std::process::id(),
            url.rsplit(':').next().unwrap().replace('/', "_")
        ));
        std::fs::create_dir_all(&dir).unwrap();
        let out = dir.join("model.gguf.tmp");
        let _ = std::fs::remove_file(&out);
        if let Some(bytes) = existing {
            std::fs::write(&out, bytes).unwrap();
        }
        let agent = agent(Agent::config_builder().proxy(None)).unwrap();
        let ok = fetch_with(&agent, &out, &url, Duration::ZERO).unwrap();
        let got = std::fs::read(&out).unwrap_or_default();
        std::fs::remove_dir_all(&dir).unwrap();
        (ok, got, count.load(Ordering::SeqCst))
    }

    #[test]
    fn downloads_whole_file() {
        let (ok, got, requests) = run(Box::new(|_, _| full()), None);
        assert!(ok);
        assert_eq!(got, DATA);
        assert_eq!(requests, 1);
    }

    #[test]
    fn resumes_partial_file() {
        let handler = |_, range: Option<u64>| partial(range.expect("no Range header"));
        let (ok, got, _) = run(Box::new(handler), Some(&DATA[..10]));
        assert!(ok);
        assert_eq!(got, DATA);
    }

    #[test]
    fn restarts_when_range_is_ignored() {
        let (ok, got, _) = run(Box::new(|_, _| full()), Some(b"stale bytes"));
        assert!(ok);
        assert_eq!(got, DATA);
    }

    #[test]
    fn resumes_after_connection_drop() {
        let handler = |n, range: Option<u64>| match (n, range) {
            // Promise the whole file, deliver half of it, hang up
            (0, _) => full()[..full().len() - DATA.len() / 2].to_vec(),
            (_, Some(from)) => partial(from),
            _ => status(500),
        };
        let (ok, got, requests) = run(Box::new(handler), None);
        assert!(ok);
        assert_eq!(got, DATA);
        assert_eq!(requests, 2);
    }

    #[test]
    fn starts_over_when_partial_cannot_be_resumed() {
        let handler = |_, range: Option<u64>| match range {
            Some(_) => status(416),
            None => full(),
        };
        let (ok, got, requests) = run(Box::new(handler), Some(&[b'x'; 64]));
        assert!(ok);
        assert_eq!(got, DATA);
        assert_eq!(requests, 2);
    }

    #[test]
    fn client_errors_are_not_retried() {
        let (ok, _, requests) = run(Box::new(|_, _| status(404)), None);
        assert!(!ok);
        assert_eq!(requests, 1);
    }

    #[test]
    fn server_errors_are_retried() {
        let (ok, _, requests) = run(Box::new(|_, _| status(503)), None);
        assert!(!ok);
        assert_eq!(requests, 1 + RETRIES as usize);
    }

    #[test]
    fn content_range_parsing() {
        assert_eq!(
            parse_content_range("bytes 100-999/1000"),
            Some((100, Some(1000)))
        );
        assert_eq!(parse_content_range("bytes 5-9/*"), Some((5, None)));
        assert_eq!(parse_content_range("items 1-2/3"), None);
    }

    /// Real mirrors over TLS (through $HTTPS_PROXY if set):
    /// `cargo test -- --ignored real_mirrors`
    #[test]
    #[ignore]
    fn real_mirrors() {
        let dir = std::env::temp_dir().join(format!("translate-https-real-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        for url in [
            "https://modelscope.cn/models/unsloth/Qwen3.5-0.8B-GGUF/resolve/master/README.md",
            "https://hf-mirror.com/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/README.md",
            "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/README.md",
        ] {
            let out = dir.join("README.md");
            let _ = std::fs::remove_file(&out);
            assert!(fetch(&out, url).unwrap(), "{url}");
            assert!(std::fs::metadata(&out).unwrap().len() > 0, "{url}");
        }
        std::fs::remove_dir_all(&dir).unwrap();
    }
}
