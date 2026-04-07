#!/usr/bin/env bash
# =============================================================================
# apply-migrations.sh — Manual migration runner
#
# Applies SQL init scripts that are NEW since the database was first initialized.
# Docker only runs init-scripts on first volume creation. After that, any new
# SQL files must be applied manually using this script.
#
# Usage (from repo root):
#   cd environment
#   ./apply-migrations.sh [--dry-run] [--from 43]
#
# Options:
#   --dry-run       Print which files would run, without executing them
#   --from N        Start applying from script number N (inclusive)
#
# Environment variables (override to target non-local DB):
#   PGHOST     (default: localhost)
#   PGPORT     (default: 5432)
#   PGDATABASE (default: satori_learning)
#   PGUSER     (default: satori_user)
#   PGPASSWORD (REQUIRED — no default to prevent accidental dev password use in prod)
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MIGRATION_DIR="$SCRIPT_DIR/docker/init-scripts/postgres"

# ---------- defaults ----------
PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-5432}"
PGDATABASE="${PGDATABASE:-satori_learning}"
PGUSER="${PGUSER:-satori_user}"

# PGPASSWORD must be set explicitly — no default to avoid using dev credentials in prod
if [[ -z "${PGPASSWORD:-}" ]]; then
    echo "ERROR: PGPASSWORD environment variable is not set." >&2
    echo "  Local:  export PGPASSWORD=SatoriSecure2026!" >&2
    echo "  Prod:   export PGPASSWORD=<your-production-password>" >&2
    exit 1
fi
DRY_RUN=false
FROM_NUM=0

# ---------- arg parsing ----------
while [[ $# -gt 0 ]]; do
    case "$1" in
        --dry-run) DRY_RUN=true ;;
        --from)    FROM_NUM="$2"; shift ;;
        *) echo "Unknown option: $1" >&2; exit 1 ;;
    esac
    shift
done

export PGPASSWORD

echo "=== Satori Migration Runner ==="
echo "Target: ${PGUSER}@${PGHOST}:${PGPORT}/${PGDATABASE}"
echo "From script number: ${FROM_NUM}"
if $DRY_RUN; then echo "[DRY RUN — no changes will be made]"; fi
echo ""

# ---------- find scripts ----------
APPLIED=0
SKIPPED=0

for file in $(ls "$MIGRATION_DIR"/*.sql | sort -V); do
    filename=$(basename "$file")
    # Extract leading number from filename (e.g. "43" from "43-practice-running-score.sql")
    num=$(echo "$filename" | grep -o '^[0-9]\+')

    if [[ -z "$num" ]]; then
        echo "  [SKIP] $filename — no leading number, skipping"
        ((SKIPPED++)) || true
        continue
    fi

    if [[ "$num" -lt "$FROM_NUM" ]]; then
        echo "  [SKIP] $filename — before --from $FROM_NUM"
        ((SKIPPED++)) || true
        continue
    fi

    echo "  [APPLY] $filename"
    if ! $DRY_RUN; then
        psql \
            --host="$PGHOST" \
            --port="$PGPORT" \
            --dbname="$PGDATABASE" \
            --username="$PGUSER" \
            --file="$file" \
            --on-error-stop \
            --single-transaction \
            2>&1 | sed 's/^/         /'
    fi
    ((APPLIED++)) || true
done

echo ""
echo "=== Done: applied=$APPLIED skipped=$SKIPPED ==="
