#!/bin/sh
# Entrypoint for the coreJ CDISC CLI image.
#
# It prepares the five on-disk stores the engine resolves for itself, says once and
# up front which of them are empty or absent, and then execs the launcher (so `java`
# replaces this shell and stays PID 1; args flow through to CdiscValidate).
#
# It also pins the unified CDISC metadata store onto a mountable path and says so
# once when none is present — see the block below. It never seeds it.
#
# ⚠⚠ THIS IMAGE SHIPS NO RULES AND NO DICTIONARIES. The corpora are released on
# their own cadence by cumba-oss-corej-rules and are not Maven dependencies, so the
# build stage has nothing to bake. The bundle's rules/, rules-define/ and
# dictionaries/ directories ship EMPTY, each holding a README that says what
# belongs there. The operator supplies the content; see the Dockerfile for the
# mount recipes.
#
# ⚠⚠ Empty and missing are NOT the same to the engine. "Configured but missing" is
# a hard error for two of the corpora: the dictionary store throws when a validation
# run sets up, and the Define-XML corpus throws on every local-mode -vx run. rules/
# treats missing and empty alike. So each of those blocks guarantees the directory
# EXISTS, falling back to the bundle's own empty copy when it cannot create the
# configured one. An empty store degrades to a loud per-rule SKIP; a missing one
# throws.
#
# ⚠⚠ The metadata store is the exception, and it inverts: it is a single zip FILE,
# an ABSENT one degrades cleanly to the per-rule SKIP, and an empty one does not
# exist as a concept — an empty file is a regular file the engine accepts as
# configured and then fails to open as an archive. So only its parent directory is
# ever created, never the file itself.
#
# Robustness: nothing here ever aborts the container. A read-only or foreign-owned
# mount produces a warning and a fallback. Runs that touch none of this (`--help`)
# always start. The Java layer still fails loud when -vx genuinely finds no rules.
#
# ⛔ DROPPING A FILE IN DOES NOT ADD RULES. The corpus is generated JSON packages,
# and the loader reads ONLY the files packages.json names for the selected
# (family, version) — it does not scan the directory. A site-rules.json copied in
# is silently ignored, and adding it to packages.json as a second entry for the
# same (family, version) does not help either: the manifest lookup takes the first
# match. To add rules, pass --define-rules-file <package.json> — a package-shaped
# JSON file, same schema as the shipped ones. If you edit a package, update its
# ruleCount in packages.json: the loader reconciles the two and refuses a corpus
# whose packages hold fewer rules than its index claims.
set -eu

# ------------------------------------------------------------------
# The Define-XML conformance corpus (-vx, local mode only).
#
# The target directory is made to exist and the bundle's copy is mirrored into it.
# That copy is empty apart from its README — which is the point: the "put the
# corpus here" instruction lands where the operator will look, on their own mount,
# instead of only inside the image.
# ------------------------------------------------------------------
DEFINE_RULES_DIR="${COREJ_DEFINE_RULES_DIR:-/app/rules-define}"
if [ -d /app/dist/rules-define ] \
    && mkdir -p "$DEFINE_RULES_DIR" 2>/dev/null \
    && cp -a /app/dist/rules-define/. "$DEFINE_RULES_DIR"/ 2>/dev/null; then
    export COREJ_DEFINE_RULES_DIR="$DEFINE_RULES_DIR"
else
    echo "warning: could not prepare the Define-XML corpus directory $DEFINE_RULES_DIR;" \
        "-vx will use the bundle's own (empty) /app/dist/rules-define" >&2
    export COREJ_DEFINE_RULES_DIR=/app/dist/rules-define
fi

# ------------------------------------------------------------------
# The data rule corpus (-rp, or -s + -v).
#
# Unlike the two below, missing and empty are equivalent here — the engine reports
# that it found no packages and names the directory it searched. So this only has
# to exist for the notice further down to be able to look inside it.
# ------------------------------------------------------------------
RULES_DIR="${COREJ_RULES_DIR:-/app/rules}"
if mkdir -p "$RULES_DIR" 2>/dev/null; then
    export COREJ_RULES_DIR="$RULES_DIR"
else
    echo "warning: could not prepare the rule corpus directory $RULES_DIR;" \
        "using the bundle's own (empty) /app/dist/rules" >&2
    export COREJ_RULES_DIR=/app/dist/rules
    RULES_DIR=/app/dist/rules
fi

# ------------------------------------------------------------------
# The unified CDISC metadata store.
#
# The Dockerfile sets CDISC_METADATA_STORE to /app/metadata/metadata-cache.zip so
# it lands on a directory an operator can mount, NOT on the engine's own default
# ~/.cumbaDataBrowser/metadata-cache.zip — which, with this image's
# `useradd --home-dir /app`, is an image-layer path inside a container that runs
# once and exits. Re-defaulted here as well so a `docker run` against a stripped
# environment behaves the same, and exported so the JVM sees it either way.
#
# ⚠⚠ Only the PARENT DIRECTORY is created, never the file: an empty file is a
# regular file, so the engine accepts it as configured and then fails opening a
# malformed archive, instead of degrading to "no store configured" and the loud
# per-rule SKIP.
# ⚠ Best-effort, like every other step here — a read-only or foreign-owned mount
# must warn, never abort the container.
# ⛔ No seeding is done here. Seeding is an explicit --seed-cache* run: it does
# outbound network I/O and takes minutes, which is not something a one-shot
# validation container may do on the caller's behalf.
# ------------------------------------------------------------------
STORE="${CDISC_METADATA_STORE:-/app/metadata/metadata-cache.zip}"
export CDISC_METADATA_STORE="$STORE"
if mkdir -p "$(dirname "$STORE")" 2>/dev/null; then :; else
    echo "warning: cannot create $(dirname "$STORE") for the CDISC metadata store;" \
        "a --seed-cache run will fail there and validation will SKIP the rules that" \
        "need CDISC Library metadata" >&2
fi

# ------------------------------------------------------------------
# The CDISC Library web-API cache.
#
# ⚠ NOT the metadata store above. Since the engine's pickle read leg was retired
# the only path that reads this directory is a Define-XML conformance run (-vx in
# local mode), which calls the live CDISC Library and caches the responses here;
# --seed-cache does not fill it and -ca does not name it.
#
# The Dockerfile sets CDISC_API_CACHE to /app/api-cache so it lands on a directory
# an operator can mount, NOT on the client's own default ~/.cdiscApiCache — which,
# with this image's `useradd --home-dir /app`, is an image-layer path inside a
# container that runs once and exits. Re-defaulted here as well so a `docker run`
# against a stripped environment behaves the same, and exported so the JVM sees it
# either way.
# ------------------------------------------------------------------
API_CACHE="${CDISC_API_CACHE:-/app/api-cache}"
export CDISC_API_CACHE="$API_CACHE"
if mkdir -p "$API_CACHE" 2>/dev/null; then :; else
    echo "warning: cannot create $API_CACHE for the CDISC Library API cache;" \
        "a -vx run will re-fetch every Library response it needs from the network" >&2
fi

# ------------------------------------------------------------------
# External dictionaries.
#
# coreJ ships NO dictionary data, so the image starts with an empty store at
# COREJ_DICTIONARIES_DIR and best-effort fills it here: every step is guarded
# (this script runs `set -eu`), warns on failure and NEVER aborts the container —
# a failed install degrades to the engine's loud per-rule SKIP, not to a dead
# container.
#
# - AUTO-CONVERT (on by default): a licensed vendor distribution the operator
#   mounts at the conventional path /licensed-dictionaries/<type> (types:
#   meddra whodrug loinc medrt unii snomed neoplasm) is converted into the
#   store on start. Local files only — no network — and idempotent via
#   --skip-installed: a type/version already in the store is not re-converted.
# - AUTO-DOWNLOAD (OPT-IN, COREJ_DICTIONARY_AUTO_INSTALL=1, default off):
#   downloads and installs the credential-free trio (MED-RT, UNII, neoplasm).
#   Off by default because validation must be reproducible — same data + same
#   rules + same dictionary version must give the same findings, and a
#   container fetching "latest" on boot would report different findings from
#   identical inputs a month later. These deployments are also frequently
#   air-gapped or egress-restricted, where the attempt could only fail slowly.
# ------------------------------------------------------------------
DICT_DIR="${COREJ_DICTIONARIES_DIR:-/app/dictionaries}"
if mkdir -p "$DICT_DIR" 2>/dev/null; then
    export COREJ_DICTIONARIES_DIR="$DICT_DIR"
else
    # A configured-but-missing store is a deliberate hard error in the engine;
    # the bundle's own (empty) store keeps an image-supplied default from ever
    # tripping it. Dictionary rules then SKIP, loudly and by name.
    echo "warning: cannot create the dictionary store at $DICT_DIR; using the bundle's" \
        "own read-only /app/dist/dictionaries — dictionary rules will SKIP" >&2
    export COREJ_DICTIONARIES_DIR=/app/dist/dictionaries
fi

# Skip the auto-fill when the caller runs the installer explicitly (do not
# compete with it) or only wants usage text (--help must never trigger a
# download). Matched against whole argument tokens, one by one — a substring
# match over "$*" would be fooled by any argument VALUE containing the text.
#
# The two notices below report an EMPTY store. Each stays quiet when the caller
# only wants usage, when they are filling that store right now (the fix being
# recommended), and when they named a location of their own on the command line —
# our path is then not what the run reads, so the notice would be actively wrong.
run_autofill=1
announce_store=1
announce_rules=1
for arg in "$@"; do
    case "$arg" in
    --install-dictionaries|-h|--help)
        run_autofill=0
        ;;
    esac
    # The metadata-store notice further down reports a MISSING store. Stay quiet
    # when the caller only wants usage or is installing dictionaries (neither run
    # reads the store), when they are seeding it right now (the seed is the fix
    # being recommended), and when -ca / --cache names a store of their own —
    # $STORE is then not the file the run will read, so the notice would be
    # actively wrong. Whole-token matching, for the reason given above.
    case "$arg" in
    -h|--help|--install-dictionaries|--seed-cache*|-ca|--cache|--cache=*)
        announce_store=0
        ;;
    esac
    case "$arg" in
    -h|--help|--install-dictionaries|--seed-cache*|--rules-dir|--rules-dir=*)
        announce_rules=0
        ;;
    esac
done
case "$run_autofill" in
0)
    ;;
*)
    if [ "$COREJ_DICTIONARIES_DIR" = "$DICT_DIR" ]; then
        for type in meddra whodrug loinc medrt unii snomed neoplasm; do
            src="/licensed-dictionaries/$type"
            [ -d "$src" ] || continue
            if /app/dist/run.sh --install-dictionaries --skip-installed \
                --dictionaries-dir "$DICT_DIR" "--$type" "$src" >&2; then :; else
                echo "warning: auto-convert of $type from $src failed;" \
                    "its rules will SKIP" >&2
            fi
        done
        if [ "${COREJ_DICTIONARY_AUTO_INSTALL:-0}" = "1" ]; then
            if /app/dist/run.sh --install-dictionaries --skip-installed \
                --dictionaries-dir "$DICT_DIR" >&2; then :; else
                echo "warning: dictionary auto-download failed; the affected rules" \
                    "will SKIP" >&2
            fi
        fi
    fi
    # When no dictionary resolves, say so once, up front, naming the exact command
    # — the report's Dictionary_Basis line and the per-rule SKIP reasons repeat it
    # per run.
    if ! find "$COREJ_DICTIONARIES_DIR" \( -name '*.json' -o -name '*.json.gz' \) \
        ! -name selected-versions.json -type f 2>/dev/null | grep -q .; then
        echo "notice: no external dictionaries are installed — all dictionary" \
            "conformance rules will SKIP (loudly, by name). Install the" \
            "credential-free set with:" >&2
        echo "    docker run --rm -v corej-dicts:/app/dictionaries <this-image>" \
            "--install-dictionaries" >&2
        echo "or mount a licensed distribution at /licensed-dictionaries/<type>" \
            "to have it converted on start." >&2
    fi
    ;;
esac

# The rule corpus. This image bakes none, so an operator who has not supplied one
# gets a run that validates nothing and says only that it found no packages —
# after the container has already exited. Say it first instead, and name where the
# corpus comes from. packages.json is the manifest every corpus has.
if [ "$announce_rules" = "1" ] && [ ! -f "$COREJ_RULES_DIR/packages.json" ]; then
    echo "notice: no rule corpus in $COREJ_RULES_DIR (no packages.json) — this image" \
        "ships none. Take the matching cumba-oss-corej-rules release asset and put its" \
        "CONTENTS there, so the directory holds packages.json and rules-*.json" \
        "directly:" >&2
    echo "    docker run --rm -v corej-rules:/app/rules <this-image> …" >&2
    echo "or point --rules-dir / COREJ_RULES_DIR at wherever you unpacked it." >&2
fi

# The same surface for the unified CDISC metadata store. Without one a run
# silently skips every rule needing CDISC Library metadata, and in a one-shot
# container there is nothing left afterwards to inspect and work out why.
if [ "$announce_store" = "1" ] && [ ! -f "$STORE" ]; then
    echo "notice: no CDISC metadata store at $STORE — every rule needing CDISC Library" \
        "metadata will SKIP (loudly, by name). Seed it once onto a volume you keep, then" \
        "reuse that volume on later runs:" >&2
    echo "    docker run --rm -v corej-metadata:/app/metadata <this-image> --seed-cache" >&2
    echo "Add --seed-cache-from-api plus CDISC_API_KEY to seed from the live CDISC Library" \
        "instead of the published pickle metadata, or name your own store with -ca <file>." >&2
fi

exec /app/dist/run.sh "$@"
