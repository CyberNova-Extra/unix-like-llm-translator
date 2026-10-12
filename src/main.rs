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

#[derive(Parser)]
#[command(
    name = "translate",
    version,
    disable_help_flag = true,
    about = "Local offline translation with Qwen3.5-0.8B (text and images)",
    long_about = "Local offline translation with Qwen3.5-0.8B (text and images).\n\n\
        Input: command-line arguments take precedence, otherwise stdin is read (pipe-friendly).\n\
        Image translation: pass an image via --image; -x/-y/-w/-h crop the region to translate.\n\
        Model lookup order: --model / env var > user data dir > /usr/local/share, /usr/share\n\
        (on macOS also the Homebrew prefix /opt/homebrew/share); auto-downloads to the user\n\
        data dir when not found anywhere.\n\
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

    /// CPU threads for inference (default: one per core)
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
    dirs::data_dir()
        .unwrap_or_else(|| PathBuf::from("."))
        .join("qwen-translate")
        .join(file)
}

/// System-wide lookup paths (model-bundled system packages install here)
fn system_paths(file: &str) -> Vec<PathBuf> {
    let dirs: Vec<&str> = if cfg!(target_os = "macos") {
        // Apple Silicon Homebrew / Intel Homebrew & manual installs / system packages
        vec![
            "/opt/homebrew/share/qwen-translate",
            "/usr/local/share/qwen-translate",
            "/usr/share/qwen-translate",
        ]
    } else {
        vec![
            "/usr/local/share/qwen-translate",
            "/usr/share/qwen-translate",
        ]
    };
    dirs.into_iter().map(|d| PathBuf::from(d).join(file)).collect()
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

fn run() -> Result<()> {
    let args = Args::parse();
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
