#!/usr/bin/env bash
# collapse_rust.sh — merge every src/*.rs module into one src/main.rs
# Target: Debian 13 (trixie)
#
# Every `mod <name>;` line in main.rs is replaced with an inline
#   mod <name> { … }
# block whose body is the (4-space-indented) contents of src/<name>.rs.
# Because the module tree is preserved, all `use crate::…` paths
# continue to work without any edits.
#
# Usage:
#   cd /path/to/crate
#   bash collapse_rust.sh           # uses src/ by default
#   bash collapse_rust.sh my_src    # custom source dir

set -euo pipefail

SRCDIR="${1:-src}"
MAIN="$SRCDIR/main.rs"
BACKUP="$SRCDIR/main.rs.bak"

if [ ! -f "$MAIN" ]; then
    echo "ERROR: $MAIN not found" >&2
    exit 1
fi

# ── backup ──────────────────────────────────────────────────────────
cp "$MAIN" "$BACKUP"
echo "backup → $BACKUP"

# ── discover which `mod <name>;` lines exist ────────────────────────
MODULES=()
mapfile -t MODULES < <(
    grep -oP '^\s*mod\s+\K\w+(?=\s*;)' "$BACKUP" || true
)

if [ ${#MODULES[@]} -eq 0 ]; then
    echo "No \`mod <name>;\` declarations found — nothing to inline."
    exit 0
fi

echo "modules to inline: ${MODULES[*]}"

# ── build merged file ───────────────────────────────────────────────
TMPFILE=$(mktemp --tmpdir collapse.XXXXXX.rs)
trap 'rm -f "$TMPFILE"' EXIT

while IFS= read -r line || [ -n "$line" ]; do
    inlined=0

    # trim leading whitespace
    trimmed="${line#"${line%%[![:space:]]*}"}"
    # trim trailing whitespace
    trimmed="${trimmed%"${trimmed##*[![:space:]]}"}"

    for mod in "${MODULES[@]}"; do
        if [ "$trimmed" = "mod ${mod};" ]; then
            modfile="$SRCDIR/${mod}.rs"
            if [ ! -f "$modfile" ]; then
                echo "WARNING: $modfile not found — keeping \`mod ${mod};\` as-is" >&2
                printf '%s\n' "$line" >> "$TMPFILE"
                inlined=1
                break
            fi
            # write  mod <name> {  …indented body…  }
            printf 'mod %s {\n' "$mod" >> "$TMPFILE"
            sed 's/^/    /' "$modfile" >> "$TMPFILE"
            printf '}\n' >> "$TMPFILE"
            inlined=1
            break
        fi
    done

    [ "$inlined" -eq 1 ] || printf '%s\n' "$line" >> "$TMPFILE"
done < "$BACKUP"

# ── replace main.rs ─────────────────────────────────────────────────
mv "$TMPFILE" "$MAIN"
echo "wrote $MAIN ($(wc -l < "$MAIN") lines)"

# ── offer to remove now-orphaned module files ───────────────────────
orphans=()
for mod in "${MODULES[@]}"; do
    f="$SRCDIR/${mod}.rs"
    [ -f "$f" ] && orphans+=("$f")
done

if [ ${#orphans[@]} -gt 0 ]; then
    echo ""
    echo "These module files are no longer needed (Cargo ignores them now):"
    printf '  %s\n' "${orphans[@]}"
    echo ""
    read -rp "Delete them? [y/N] " ans
    case "$ans" in
        [yY]*) rm -v -- "${orphans[@]}" ;;
    esac
fi

echo ""
echo "Done. Verify with:  cargo check"
echo "Restore original:   mv $BACKUP $MAIN"
