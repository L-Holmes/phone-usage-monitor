#!/usr/bin/env bash
#
# One-shot, idempotent setup for the NSFW detector test harness.
# Safe to run as many times as you like.
#
#   ./setup.sh
#
# What it does (each step is a no-op if already done):
#   1. create the directory layout
#   2. create a Python venv (.venv) and install conversion deps
#   3. convert every detector to ONNX + sidecar (skips models already built)
#   4. generate test/are-images-ground-truth.json from your images (if missing)
#   5. build the Rust core
#   6. run the harness
#
# Env toggles:
#   SKIP_CONVERT=1            don't (re)run model conversion
#   FORCE_CONVERT=1          re-convert even if a model already exists
#   CONVERT_ONLY="a b c"      only convert these recipe names
#   RUN=0                     build but don't run the harness
#
set -euo pipefail

# --- always operate from the repo root (this script's dir) ---
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

log()  { printf '\n\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[warn]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[error]\033[0m %s\n' "$*" >&2; exit 1; }

GT="test/are-images-ground-truth.json"
IMG_DIR="test/static/testImages"

# --- 0. toolchain checks ----------------------------------------------------
command -v python3 >/dev/null 2>&1 || die "python3 not found. Install Python 3."

command -v cargo >/dev/null 2>&1 || die \
  "cargo not found. Install Rust:  curl https://sh.rustup.rs -sSf | sh  (then: source \$HOME/.cargo/env)"

RUSTC_VER="$(rustc --version | awk '{print $2}')"   # e.g. 1.85.0
RUSTC_MAJOR="${RUSTC_VER%%.*}"
RUSTC_REST="${RUSTC_VER#*.}"
RUSTC_MINOR="${RUSTC_REST%%.*}"
if [ "$RUSTC_MAJOR" -lt 1 ] || { [ "$RUSTC_MAJOR" -eq 1 ] && [ "$RUSTC_MINOR" -lt 81 ]; }; then
  die "rustc $RUSTC_VER is too old; ort needs >= 1.81. Install a current toolchain via rustup."
fi
log "toolchain ok (rustc $RUSTC_VER)"

# --- 1. directories ---------------------------------------------------------
log "ensuring directory layout"
mkdir -p src scripts models "$IMG_DIR" test
[ -f models/.gitkeep ] || touch models/.gitkeep
[ -f "$IMG_DIR/.gitkeep" ] || touch "$IMG_DIR/.gitkeep"

# --- 2. python venv + deps --------------------------------------------------
if [ ! -d .venv ]; then
  log "creating Python venv (.venv)"
  python3 -m venv .venv
else
  log "reusing existing venv (.venv)"
fi
# venv activate scripts aren't always -u clean
set +u
# shellcheck disable=SC1091
source .venv/bin/activate
set -u
log "installing/upgrading conversion deps (first run pulls torch and is slow)"
python -m pip install --quiet --upgrade pip
pip install --quiet -r scripts/requirements.txt

# --- 3. convert models ------------------------------------------------------
if [ "${SKIP_CONVERT:-0}" = "1" ]; then
  warn "SKIP_CONVERT=1 -> skipping model conversion"
else
  log "converting models to ONNX (already-built models are skipped)"
  # word-splitting of CONVERT_ONLY is intentional (each name a separate arg)
  # shellcheck disable=SC2086
  python scripts/convert_models.py \
    ${CONVERT_ONLY:+--only $CONVERT_ONLY} \
    ${FORCE_CONVERT:+--force} || warn "some conversions failed; continuing"
fi

# --- 4. ground truth from images (only if missing) --------------------------
if [ -f "$GT" ]; then
  log "ground truth exists, leaving as-is: $GT"
else
  log "generating $GT from images in $IMG_DIR"
  entries=()
  while IFS= read -r -d '' f; do
    base="$(basename "$f")"
    lower="${base,,}"
    case "$lower" in
      *not-allowed*|*not_allowed*|*notallowed*) val=true ;;
      *) val=false ;;
    esac
    entries+=("  \"$f\": $val")
  done < <(find "$IMG_DIR" -maxdepth 1 -type f \
            \( -iname '*.jpg' -o -iname '*.jpeg' -o -iname '*.png' \
               -o -iname '*.webp' -o -iname '*.bmp' -o -iname '*.gif' \) \
            -print0 | sort -z)

  {
    echo "{"
    n=${#entries[@]}
    for i in "${!entries[@]}"; do
      if [ "$i" -lt $((n - 1)) ]; then echo "${entries[$i]},"; else echo "${entries[$i]}"; fi
    done
    echo "}"
  } > "$GT"

  if [ "${#entries[@]}" -eq 0 ]; then
    warn "no images found in $IMG_DIR yet; wrote an empty ground truth"
  else
    echo "  labelled ${#entries[@]} image(s) (true = NSFW). Edit $GT if any are wrong:"
    cat "$GT"
  fi
fi

# --- 5. ONNX Runtime native lib (ort uses load-dynamic -> needs it at runtime) ---
# ort rc.10 requires ONNX Runtime 1.22.x specifically.
ORT_VERSION="${ORT_VERSION:-1.22.0}"
ORT_DIR="onnxruntime"
# version-aware check: re-download if the present lib isn't the wanted version
if ls "$ORT_DIR"/lib/libonnxruntime.so."$ORT_VERSION" >/dev/null 2>&1; then
  log "ONNX Runtime $ORT_VERSION already present in $ORT_DIR/lib"
else
  log "downloading ONNX Runtime v$ORT_VERSION (CPU, linux-x64) for ort load-dynamic"
  tgz="onnxruntime-linux-x64-${ORT_VERSION}.tgz"
  url="https://github.com/microsoft/onnxruntime/releases/download/v${ORT_VERSION}/${tgz}"
  tmpd="$(mktemp -d)"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "$url" -o "$tmpd/$tgz" || die "failed to download $url"
  elif command -v wget >/dev/null 2>&1; then
    wget -q "$url" -O "$tmpd/$tgz" || die "failed to download $url"
  else
    die "need curl or wget to download ONNX Runtime"
  fi
  rm -rf "$ORT_DIR"; mkdir -p "$ORT_DIR"
  tar -xzf "$tmpd/$tgz" -C "$ORT_DIR" --strip-components=1
  rm -rf "$tmpd"
fi

# resolve the .so (prefer the unversioned symlink) to an absolute path
ORT_SO="$(ls "$ORT_DIR"/lib/libonnxruntime.so 2>/dev/null || true)"
[ -n "$ORT_SO" ] || ORT_SO="$(ls "$ORT_DIR"/lib/libonnxruntime.so.* 2>/dev/null | head -n1 || true)"
[ -n "$ORT_SO" ] || die "libonnxruntime.so not found under $ORT_DIR/lib after extraction"
ORT_SO_ABS="$(cd "$(dirname "$ORT_SO")" && pwd)/$(basename "$ORT_SO")"
export ORT_DYLIB_PATH="$ORT_SO_ABS"
log "ORT_DYLIB_PATH=$ORT_DYLIB_PATH"

# persist for every future `cargo run`/`cargo build` in this repo
mkdir -p .cargo
cat > .cargo/config.toml <<CARGOEOF
# Auto-generated by setup.sh. Points ort (load-dynamic) at libonnxruntime.so.
# Re-running setup.sh refreshes this if you move the repo or change ORT_VERSION.
[env]
ORT_DYLIB_PATH = { value = "$ORT_SO_ABS", force = true }
CARGOEOF

# --- 6. heal stale/bad model dirs ------------------------------------------
# NudeNet was removed (AGPL); delete any leftover copy so it can't fail the run.
if [ -d models/nudenet ]; then
  warn "removing models/nudenet — NudeNet was dropped from this project (AGPL)"
  rm -rf models/nudenet
fi
# Also drop any model dir whose model.onnx is an HTML error page from a bad download.
for d in models/*/; do
  m="${d}model.onnx"
  [ -f "$m" ] || continue
  if [ "$(head -c 1 "$m" 2>/dev/null)" = "<" ]; then
    warn "removing ${d} — model.onnx is an HTML page, not ONNX (bad download)"
    rm -rf "$d"
  fi
done

# --- 7. build ---------------------------------------------------------------
log "building Rust core (compiles deps; no OpenSSL needed now)"
cargo build --release

# --- 8. run the NSFW model test ---------------------------------------------
LEVEL_ARG=()
[ -n "${LEVEL:-}" ] && LEVEL_ARG=(--level "$LEVEL")
if [ "${RUN:-1}" = "0" ]; then
  log "RUN=0 -> built but not running. Start it with:"
  log "  cargo run --release -- --test-models ${LEVEL_ARG[*]}"
else
  log "running NSFW model test (--test-models ${LEVEL_ARG[*]})"
  cargo run --release -- --test-models "${LEVEL_ARG[@]}"
fi

log "done"
