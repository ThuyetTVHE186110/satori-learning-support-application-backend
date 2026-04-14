#!/usr/bin/env bash
# =============================================================================
# apply-migrations.sh — Automatic migration runner with tracking
#
# Applies SQL init scripts that have not yet been recorded in the
# schema_migrations tracking table.  Safe to re-run: already-applied
# scripts are skipped automatically.
#
# Usage (from repo root):
#   cd environment
#   ./apply-migrations.sh [options]
#
# Options:
#   --dry-run              Print which files would run, without executing
#   --container NAME       Run psql via "docker exec NAME psql ..." instead
#                          of calling psql directly.  Use this in production
#                          where PostgreSQL port is not exposed to the host.
#   --from N               Force-apply starting from script number N,
#                          bypassing the tracking table (manual override).
#
# Environment variables (direct-psql mode only):
#   PGHOST       (default: localhost)
#   PGPORT       (default: 5432)
#   PGDATABASE   (default: satori_learning)
#   PGUSER       (default: satori_user)
#   PGPASSWORD   REQUIRED — no default
#
# Examples:
#   # Local dev (postgres exposed on 5432)
#   export PGPASSWORD=SatoriSecure2026!
#   ./apply-migrations.sh
#
#   # Production (postgres only inside Docker network)
#   ./apply-migrations.sh --container satori-postgres
#
#   # Dry-run to preview pending scripts
#   ./apply-migrations.sh --dry-run
#
#   # Force-apply from script 54 onwards (ignore tracking table)
#   ./apply-migrations.sh --from 54
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MIGRATION_DIR="$SCRIPT_DIR/docker/init-scripts/postgres"

# ---------- defaults ----------
PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-5432}"
PGDATABASE="${PGDATABASE:-satori_learning}"
PGUSER="${PGUSER:-satori_user}"
DRY_RUN=false
CONTAINER=""
FROM_NUM=""

# ---------- arg parsing ----------
while [[ $# -gt 0 ]]; do
    case "$1" in
        --dry-run)   DRY_RUN=true ;;
        --container) CONTAINER="$2"; shift ;;
        --from)      FROM_NUM="$2"; shift ;;
        *) echo "Unknown option: $1" >&2; exit 1 ;;
    esac
    shift
done

# ---------- validate credentials (direct mode) ----------
if [[ -z "$CONTAINER" && -z "${PGPASSWORD:-}" ]]; then
    echo "ERROR: PGPASSWORD is required when not using --container." >&2
    echo "  Local:  export PGPASSWORD=SatoriSecure2026!" >&2
    echo "  Prod:   export PGPASSWORD=<production-password>" >&2
    exit 1
fi

export PGPASSWORD="${PGPASSWORD:-}"

# ---------- psql wrapper ----------
# Runs SQL from a string or file, returns output.
# Usage: run_sql [-f file] or run_sql <sql-string>
run_sql() {
    if [[ -n "$CONTAINER" ]]; then
        if [[ "$1" == "-f" ]]; then
            local file="$2"
            local fname
            fname="$(basename "$file")"
            docker cp "$file" "$CONTAINER:/tmp/$fname"
            docker exec "$CONTAINER" \
                psql -v ON_ERROR_STOP=1 -U "$PGUSER" -d "$PGDATABASE" \
                     -f "/tmp/$fname"
            docker exec "$CONTAINER" rm -f "/tmp/$fname"
        else
            docker exec "$CONTAINER" \
                psql -v ON_ERROR_STOP=1 -U "$PGUSER" -d "$PGDATABASE" \
                     -c "$1"
        fi
    else
        if [[ "$1" == "-f" ]]; then
            psql -h "$PGHOST" -p "$PGPORT" -d "$PGDATABASE" -U "$PGUSER" \
                 -v ON_ERROR_STOP=1 -f "$2"
        else
            psql -h "$PGHOST" -p "$PGPORT" -d "$PGDATABASE" -U "$PGUSER" \
                 -v ON_ERROR_STOP=1 -c "$1"
        fi
    fi
}

run_sql_quiet() {
    run_sql "$@" > /dev/null 2>&1
}

query_scalar() {
    if [[ -n "$CONTAINER" ]]; then
        docker exec "$CONTAINER" \
            psql -U "$PGUSER" -d "$PGDATABASE" -tAc "$1"
    else
        psql -h "$PGHOST" -p "$PGPORT" -d "$PGDATABASE" -U "$PGUSER" \
             -tAc "$1"
    fi
}

# ---------- header ----------
echo "=== Satori Migration Runner ==="
if [[ -n "$CONTAINER" ]]; then
    echo "Mode:   docker exec → $CONTAINER"
else
    echo "Mode:   direct psql → ${PGUSER}@${PGHOST}:${PGPORT}/${PGDATABASE}"
fi
$DRY_RUN && echo "[DRY RUN — no changes will be made]"
echo ""

# ---------- ensure tracking table ----------
if ! $DRY_RUN; then
    run_sql_quiet "
        CREATE TABLE IF NOT EXISTS public.schema_migrations (
            script_name  VARCHAR(255) PRIMARY KEY,
            applied_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
        );
    "
fi

# ---------- collect applied scripts ----------
declare -A APPLIED_SET
if ! $DRY_RUN; then
    while IFS= read -r name; do
        [[ -n "$name" ]] && APPLIED_SET["$name"]=1
    done < <(query_scalar "SELECT script_name FROM public.schema_migrations ORDER BY script_name;")
fi

# ---------- find and apply scripts ----------
APPLIED=0
SKIPPED=0
FAILED=0

for file in $(ls "$MIGRATION_DIR"/*.sql 2>/dev/null | sort -V); do
    filename="$(basename "$file")"
    num="$(echo "$filename" | grep -o '^[0-9]\+')"

    if [[ -z "$num" ]]; then
        echo "  [SKIP ] $filename — no leading number"
        ((SKIPPED++)) || true
        continue
    fi

    # --from override: skip scripts before the given number
    if [[ -n "$FROM_NUM" && "$num" -lt "$FROM_NUM" ]]; then
        echo "  [SKIP ] $filename — before --from $FROM_NUM"
        ((SKIPPED++)) || true
        continue
    fi

    # Tracking table check (skip if already applied, unless --from is set)
    if [[ -z "$FROM_NUM" && -n "${APPLIED_SET[$filename]+x}" ]]; then
        echo "  [SKIP ] $filename — already applied"
        ((SKIPPED++)) || true
        continue
    fi

    echo "  [APPLY] $filename"

    if ! $DRY_RUN; then
        if run_sql -f "$file" 2>&1 | sed 's/^/         /'; then
            run_sql_quiet "
                INSERT INTO public.schema_migrations (script_name)
                VALUES ('$filename')
                ON CONFLICT DO NOTHING;
            "
            ((APPLIED++)) || true
        else
            ((FAILED++)) || true
            echo ""
            echo "ERROR: Migration failed: $filename" >&2
            echo "Fix the script then re-run. Aborting." >&2
            exit 1
        fi
    else
        ((APPLIED++)) || true
    fi
done

echo ""
echo "=== Done: applied=$APPLIED  skipped=$SKIPPED  failed=$FAILED ==="
