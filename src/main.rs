mod download;
mod infer;
mod input;
mod logging;
mod native_host;

use std::io::{IsTerminal, Read, Write};
use std::num::NonZeroU32;
use std::path::{Path, PathBuf};
use std::process::ExitCode;

use anyhow::{bail, Result};
use clap::{CommandFactory, Parser};
use download::ModelSource;
use infer::{default_target, Lang, SourceLang, Translator};

const ENV_MODEL: &str = "QWEN_TRANSLATE_MODEL";
/// Add-on ID of the Firefox extension (firefox/manifest.json)
const FIREFOX_ADDON_ID: &str = "qwen-translate@local";

#[derive(Parser)]
#[command(
    name = "translate",
    version,
    disable_help_flag = true,
    about = "Local offline translation with Qwen3.5-0.8B (text and images)",
    long_about = "Local offline translation with Qwen3.5-0.8B (text and images).\n\n\
        Input: command-line arguments take precedence, otherwise stdin is read (pipe-friendly).\n\
        Image translation: pass an image via --image; -x/-y/-w/-h crop the region to translate.\n\
        Model lookup order: --model / env var > user data dir > model/ next to the executable\n\
        > /usr/local/share, /usr/share (macOS also /opt/homebrew/share; Windows %ProgramData%;\n\
        Android/Termux $PREFIX/share);\n\
        auto-downloads to the user data dir when not found anywhere.\n\
        stdout carries only results; logs and errors go to stderr.\n\n\
        Examples:\n  \
        translate \"I love programming\"\n  \
        echo 我喜欢编程 | translate\n  \
        translate --to ja \"你好\"\n  \
        translate --image shot.png -x 10 -y 20 -w 300 -h 200"
)]
struct Args {
    /// Path to the GGUF model
    #[arg(long, env = ENV_MODEL)]
    model: Option<PathBuf>,

    /// Source used when auto-downloading the model
    #[arg(long, value_enum, default_value = "modelscope")]
    model_source: ModelSource,

    /// Source language (default: auto-detect)
    #[arg(long, value_enum, default_value = "auto", env = "TRANSLATE_FROM")]
    from: SourceLang,

    /// Target language (default: Chinese<->English auto pair)
    #[arg(long, value_enum, env = "TRANSLATE_TO")]
    to: Option<Lang>,

    /// Layers to offload to the GPU (falls back to CPU when unavailable)
    #[arg(long, default_value_t = 999)]
    gpu_layers: u32,

    /// CPU threads for inference (default: one per core; half of them on Android)
    #[arg(long, env = "TRANSLATE_THREADS")]
    threads: Option<NonZeroU32>,

    /// llama.cpp log level
    #[arg(long, value_enum, default_value = "warn")]
    log_level: logging::LogLevel,

    /// Image translation: path to the image (direct multimodal translation, not OCR)
    #[arg(long)]
    image: Option<PathBuf>,

    /// Left edge X of the region to translate (requires -y/-w/-h)
    #[arg(short = 'x', requires = "y", requires = "w", requires = "h")]
    x: Option<u32>,

    /// Top edge Y of the region to translate
    #[arg(short = 'y', requires = "x", requires = "w", requires = "h")]
    y: Option<u32>,

    /// Width of the region to translate
    #[arg(short = 'w', long = "width", requires = "x", requires = "y", requires = "h")]
    w: Option<u32>,

    /// Height of the region to translate
    #[arg(short = 'h', long = "height", requires = "x", requires = "y", requires = "w")]
    h: Option<u32>,

    /// Run as a Firefox native messaging host (used by the browser extension)
    #[arg(long)]
    native_host: bool,

    /// Print help
    #[arg(long, action = clap::ArgAction::HelpLong)]
    help: Option<bool>,

    /// Text to translate (read from stdin when omitted)
    text: Vec<String>,
}

fn data_dir_path(file: &str) -> PathBuf {
    // Local (non-roaming) app data: %LOCALAPPDATA% on Windows, so ~700MB of model
    // files don't travel with roaming profiles; the same as data_dir() elsewhere
    dirs::data_local_dir()
        .unwrap_or_else(|| PathBuf::from("."))
        .join("qwen-translate")
        .join(file)
}

/// Portable and system-wide lookup paths (model-bundled packages install here)
fn system_paths(file: &str) -> Vec<PathBuf> {
    // Portable layout: model/ next to the executable (Windows zip, extracted tarballs)
    let mut dirs: Vec<PathBuf> = std::env::current_exe()
        .ok()
        .and_then(|exe| Some(exe.parent()?.join("model")))
        .into_iter()
        .collect();
    if cfg!(windows) {
        dirs.extend(
            std::env::var_os("ProgramData").map(|d| PathBuf::from(d).join("qwen-translate")),
        );
    } else if cfg!(target_os = "android") {
        // Termux keeps its "/usr" under the app's private data dir
        let prefix = std::env::var_os("PREFIX")
            .map_or_else(|| PathBuf::from("/data/data/com.termux/files/usr"), PathBuf::from);
        dirs.push(prefix.join("share/qwen-translate"));
    } else if cfg!(target_os = "macos") {
        // Apple Silicon Homebrew / Intel Homebrew & manual installs / system packages
        dirs.extend(
            [
                "/opt/homebrew/share/qwen-translate",
                "/usr/local/share/qwen-translate",
                "/usr/share/qwen-translate",
            ]
            .map(PathBuf::from),
        );
    } else {
        dirs.extend(
            [
                "/usr/local/share/qwen-translate",
                "/usr/share/qwen-translate",
            ]
            .map(PathBuf::from),
        );
    }
    dirs.into_iter().map(|d| d.join(file)).collect()
}

/// Lookup order: user data dir > system dirs; returns the user data dir path
/// when not found anywhere (as the auto-download destination)
fn resolve_named_file(file: &str) -> PathBuf {
    let user = data_dir_path(file);
    if user.is_file() {
        return user;
    }
    for p in system_paths(file) {
        if p.is_file() {
            return p;
        }
    }
    user
}

fn resolve_model(explicit: Option<PathBuf>) -> (PathBuf, bool) {
    match explicit {
        Some(p) => (p, true),
        None => (resolve_named_file(download::DEFAULT_FILE), false),
    }
}

/// Auto-download when the default model is missing; error out for explicit paths
fn ensure_model(model: &Path, explicit: bool, source: ModelSource) -> Result<()> {
    if model.is_file() {
        return Ok(());
    }
    if explicit {
        bail!(
            "model file not found: {}\n(paths set via --model / {ENV_MODEL} are not auto-downloaded; please check the path)",
            model.display()
        );
    }
    download::download_model(model, source)
}

fn no_input() -> ! {
    Args::command()
        .error(
            clap::error::ErrorKind::InvalidValue,
            "no input text (pass it as arguments or via stdin)",
        )
        .exit()
}

/// Write the result to stdout. A reader that went away early (e.g. `| head -c0`)
/// is normal for a filter: stop quietly instead of panicking like print! does.
fn print_result(out: &str) -> Result<()> {
    let mut stdout = std::io::stdout().lock();
    let newline: &[u8] = if out.ends_with('\n') { b"" } else { b"\n" };
    let res = stdout
        .write_all(out.as_bytes())
        .and_then(|()| stdout.write_all(newline))
        .and_then(|()| stdout.flush());
    match res {
        Err(e) if e.kind() == std::io::ErrorKind::BrokenPipe => Ok(()),
        res => Ok(res?),
    }
}

/// Whether a browser started us as its native messaging host. Host manifests can
/// only name an executable, and on Windows there is no wrapper script to add
/// --native-host, so recognise the arguments browsers pass instead: Chromium
/// browsers pass the caller's origin (plus --parent-window=N on Windows), Firefox
/// passes the host manifest path and the add-on ID.
fn is_browser_launch(args: &[String]) -> bool {
    let args: Vec<&str> = args
        .iter()
        .map(String::as_str)
        .filter(|a| *a != "--native-host")
        .collect();
    match args.as_slice() {
        [origin, rest @ ..] if origin.starts_with("chrome-extension://") => {
            rest.iter().all(|a| a.starts_with("--parent-window="))
        }
        [manifest, id, ..] => manifest.ends_with(".json") && *id == FIREFOX_ADDON_ID,
        _ => false,
    }
}

fn run() -> Result<()> {
    let raw: Vec<String> = std::env::args_os()
        .skip(1)
        .filter_map(|a| a.into_string().ok())
        .collect();
    let args = if is_browser_launch(&raw) {
        // Browser-supplied arguments are not ours; options come from env vars
        Args::parse_from(["translate", "--native-host"])
    } else {
        Args::parse()
    };
    logging::init(args.log_level);

    let (model, model_explicit) = resolve_model(args.model.clone());
    let source = args.from.resolve();

    if args.native_host {
        return native_host::run(|| {
            ensure_model(&model, model_explicit, args.model_source)?;
            Translator::load(&model, args.gpu_layers, args.threads)
        });
    }

    if let Some(image_path) = &args.image {
        if !args.text.is_empty() {
            bail!("image mode does not accept text input (the text comes from the image itself)");
        }
        ensure_model(&model, model_explicit, args.model_source)?;
        let mmproj = resolve_named_file(download::MMPROJ_FILE);
        if !mmproj.is_file() {
            download::download_mmproj(&mmproj, args.model_source)?;
        }
        let mut translator =
            Translator::load_vision(&model, &mmproj, args.gpu_layers, args.threads)?;
        let region = match (args.x, args.y, args.w, args.h) {
            (Some(x), Some(y), Some(w), Some(h)) => Some((x, y, w, h)),
            _ => None,
        };
        let out = translator.translate_image(image_path, region, source, args.to)?;
        return print_result(&out);
    }

    if args.x.is_some() || args.y.is_some() || args.w.is_some() || args.h.is_some() {
        bail!("-x/-y/-w/-h can only be used together with --image");
    }

    let text = if !args.text.is_empty() {
        args.text.join(" ")
    } else if !std::io::stdin().is_terminal() {
        let mut buf = Vec::new();
        std::io::stdin().read_to_end(&mut buf)?;
        input::decode(buf)
    } else {
        no_input();
    };

    let text = text.trim();
    if text.is_empty() {
        no_input();
    }

    ensure_model(&model, model_explicit, args.model_source)?;
    let translator = Translator::load(&model, args.gpu_layers, args.threads)?;
    let target = args
        .to
        .unwrap_or_else(|| default_target(source.unwrap_or_else(|| Lang::detect(text))));
    let out = translator.translate(text, source, target)?;
    print_result(&out)
}

fn main() -> ExitCode {
    match run() {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("error: {e:#}");
            ExitCode::FAILURE
        }
    }
}

#[cfg(test)]
mod tests {
    use super::is_browser_launch;

    fn launch(args: &[&str]) -> bool {
        is_browser_launch(&args.iter().map(|s| s.to_string()).collect::<Vec<_>>())
    }

    #[test]
    fn browser_launches_are_recognised() {
        let chrome = "chrome-extension://bfkmmaobjaldoelkdphajjagomjehbpc/";
        assert!(launch(&[chrome]));
        assert!(launch(&[chrome, "--parent-window=0"]));
        assert!(launch(&["--native-host", chrome]));
        let manifest = r"C:\Users\me\AppData\Local\qwen-translate\native-host-firefox.json";
        assert!(launch(&[manifest, "qwen-translate@local"]));
    }

    #[test]
    fn normal_invocations_are_not_browser_launches() {
        assert!(!launch(&[]));
        assert!(!launch(&["hello", "world"]));
        assert!(!launch(&["--to", "ja", "chrome-extension://x/"]));
        assert!(!launch(&["chrome-extension://x/", "and more text"]));
        assert!(!launch(&["config.json", "qwen-translate@local!"]));
    }
}
