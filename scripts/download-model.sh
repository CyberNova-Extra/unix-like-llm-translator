#!/usr/bin/env bash
# Download the Qwen3.5-0.8B GGUF model + mmproj projector weights into the platform data dir.
# Downloads from ModelScope by default; set MODEL_SOURCE=huggingface for HuggingFace (uses a mirror).
set -euo pipefail

MODEL_NAME="${MODEL_NAME:-Qwen3.5-0.8B-Q4_K_M.gguf}"
MMPROJ_NAME="${MMPROJ_NAME:-mmproj-F16.gguf}"
REPO="${MODEL_REPO:-unsloth/Qwen3.5-0.8B-GGUF}"
SOURCE="${MODEL_SOURCE:-modelscope}"

case "$(uname -s)" in
  Darwin) DEST_DIR="$HOME/Library/Application Support/qwen-translate" ;;
  # Git Bash / MSYS2 / Cygwin on Windows: %LOCALAPPDATA%\qwen-translate
  MINGW* | MSYS* | CYGWIN*) DEST_DIR="$(cygpath -u "$LOCALAPPDATA")/qwen-translate" ;;
  *) DEST_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/qwen-translate" ;;
esac
mkdir -p "$DEST_DIR"

download_one() {
  local name="$1"
  local dest="$DEST_DIR/$name"
  if [[ -f "$dest" ]]; then
    echo "Already exists: $dest" >&2
    return 0
  fi

  local urls=()
  case "$SOURCE" in
    modelscope)
      urls=("https://modelscope.cn/models/$REPO/resolve/master/$name")
      ;;
    huggingface|hf)
      urls=(
        "https://hf-mirror.com/$REPO/resolve/main/$name"
        "https://huggingface.co/$REPO/resolve/main/$name"
      )
      ;;
    *)
      echo "MODEL_SOURCE must be modelscope or huggingface" >&2
      exit 1
      ;;
  esac

  # A partial download is kept in $dest.tmp and resumed (-C -) on the next attempt
  local url rc
  for url in "${urls[@]}"; do
    echo "Downloading: $url" >&2
    rc=0
    curl -fL --connect-timeout 15 --retry 3 --retry-delay 2 -C - -o "$dest.tmp" "$url" || rc=$?
    if [[ $rc -eq 33 ]]; then
      # Server ignores Range requests: start over
      rm -f "$dest.tmp"
      rc=0
      curl -fL --connect-timeout 15 --retry 3 --retry-delay 2 -o "$dest.tmp" "$url" || rc=$?
    fi
    if [[ $rc -eq 0 ]]; then
      mv "$dest.tmp" "$dest"
      echo "Done: $dest" >&2
      return 0
    fi
  done

  echo "Download failed: $name (rerun to resume the partial download)" >&2
  return 1
}

download_one "$MODEL_NAME"
download_one "$MMPROJ_NAME"
