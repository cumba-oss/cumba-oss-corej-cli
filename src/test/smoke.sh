#!/usr/bin/env bash
#
# Smoke-test the assembled distribution bundle.
#
# ⛔ WHY THIS EXISTS, AND WHY IT DOES NOT RUN `--help`.
#
# The failure this whole bundle was built to fix is invisible to a `--help` run:
# picocli prints its usage banner before the engine classes are ever touched, so a
# bundle with an empty lib/ — or with a Class-Path pointing at jars that were never
# shipped, which is exactly what the pre-bundle release asset did — exits 0 from
# `--help` and then dies with NoClassDefFoundError on the first real invocation.
#
# So this drives two REAL runs.
#
# Probe 1 LOADS THE BUNDLED RULE CORPUS WITH THE ENGINE, package by package, and
# requires the engine to reach RULE EXECUTION. That proves the launcher found and
# parsed its sidecar .conf, every ${...} in it resolved, the engine classes loaded,
# corej.rules.dir resolved to the bundle rather than to the caller's CWD, and —
# the part nothing else here covers — that the corpus staged in this bundle is one
# THIS engine can actually parse.
#
# ⛔ WHY IT CHANGED, 2026-09-17. Until today probe 1 asked for a rule package that
# CANNOT EXIST and asserted the engine's "Unknown rule package … Available: <names>"
# error. That list is built by StudyValidationService.availableShortNames from a
# Files.list of the directory — a FILENAME FILTER. It never parses a rule. So
# `smoke: OK` printed over a corpus the engine refused to load: most packages of the
# corpus pinned at the time still carried the retired `Operations:` block, and the
# rejection is thrown from a Jackson setter, so ONE legacy rule kills the whole
# rules-<family>.json. Every other guard is blind the same way — the find-based
# floors below, the antrun verify-corej-rules-staged guard in pom.xml, and the
# meta repository's corpus-pin check all count FILES or compare PROPERTY VALUES.
# Nothing but an actual load can see this.
#
# ⭐ THE PACKAGE NAMES COME FROM THE BUNDLE'S OWN rules/packages.json, never from a
# literal. A hard-coded name is a false alarm waiting for the corpus to legitimately
# stop shipping that package (the earlier "Available:" check was reverted for exactly
# that), and it would also leave every OTHER package unchecked. The manifest is the
# authority the engine itself resolves through, so asking for what it declares both
# covers the whole corpus and reconciles the manifest against the files.
#
# ⚠ ONE RUN PER PACKAGE, not one run naming them all. Measured: `-rp` with all
# packages at once dies with "declare more than one TIG leg [adam, cdash, sdtm,
# send] … name one with -mp" — a legitimate multi-standard refusal that has nothing
# to do with loading, and it never reaches execution. A per-package loop is ~0.8 s
# each and names the offending package for free, with no bisect on failure.
#
# ⚠ A PACKAGE MAY LEGITIMATELY STOP SHORT OF EXECUTION. A package declaring several
# TIG legs refuses with the same -mp error (exit 2) — it HAS been loaded by then. The
# DRAFT family's draft-tig-1-0 was such a package; the published corpus carries no
# DRAFT family, so today every package executes. So the per-package assertion is "no
# load failure", and execution is required of the corpus as a WHOLE: at least one
# package must reach the engine's "Validation complete:" line.
#
# ⛔ A LOAD FAILURE IS NOT ALWAYS THROWN (2026-10-03). The engine files a rule that is
# definitionally invalid — an undeclared `&NAME&` token, a forward binding reference,
# an unknown key — onto the rule as a RECORDED load error and keeps going: the
# package loads, the run completes, the exit code is 0, and the rule surfaces only in
# the report, as its Rules_Report row (one row per rule) with
# `"status":"EXECUTION_ERROR"`. Every arm above is blind to that, so a corpus with dead
# rules printed `smoke: OK`. So the report of every run that exits 0 is read too, and
# ANY EXECUTION_ERROR row is fatal. That also catches a rule that fails at RUN time,
# which on this one-row dataset is just as much a defect — measured on the published
# corpus: zero EXECUTION_ERROR rows.
#
# ⚠ THE REPORT IS KEYED ON THE EXIT CODE, NOT ON THE LOG LINE (2026-10-03, review
# lane 4). Exit 0 means the CLI wrote the report it was asked for, so a missing report
# after exit 0 is fatal whether or not "Validation complete:" was logged — reading it
# only after that line would let a run that exits 0 without it skip the check.
#
# ⛔ EVERY PACKAGE IS ACCOUNTED FOR. A package ends in exactly one of two modelled
# states: it EXECUTED (exit 0 and "Validation complete:"), or it was REFUSED with one
# of the two named selection refusals (exit 2). Everything else is fatal on the spot,
# so `executed + refused == npkg` is asserted after the loop, and the refused packages
# are printed by name — a refusal is a package whose rules did NOT run, and the
# operator must be able to see which ones.
#
# ⚠ THE EXIT CODE IS THE DISCRIMINATOR, and it must not be read alone. An unloadable
# package is an UNCAUGHT exception (exit 1, RuleDefinitionException/JsonMappingException
# in the output); a legitimate "you must choose" refusal is the CLI's own `Error:`
# line (exit 2). Judge the output first — reporting "expected 0, got 1" for what is
# really a corpus the engine cannot parse is the least useful description of the one
# failure this probe exists to catch.
#
# ⚠ THAT DISCRIMINATOR RESTS ON TWO PROPERTIES OF CdiscValidate, not one (review
# round 2 R2-16, corrected round 3 H-4). The STATIC run(String[], …) catches
# UsageException and ONLY UsageException, and is declared `throws Exception`, so a
# rule-load failure escapes uncaught and always prints "Exception in thread". Wrap a
# load failure in UsageException for a nicer operator message and this probe would
# reclassify it as a legitimate refusal and pass, as long as one other package still
# executes.
#
# ⚠⚠ The CLOSER hazard is the INSTANCE run(parsed, err), which wraps the very
# StudyValidationService.validate(params) call that LOADS the rule packages in a
# `catch (StudyValidationException e) → err.println("Error: " + …); return 2;`. A load
# failure raised as StudyValidationException — the engine's own operational-failure
# type — becomes `Error: …` / exit 2 TODAY, with no stack trace, so the
# "Exception in thread"/RuleDefinitionException/JsonMappingException arm never sees
# it. That is why the exit-2 arm below no longer trusts the code alone: it keys on the
# two intended refusal messages, and anything else on exit 2 is fatal.
#
# ⚠ The old probe's `"$BUNDLE/rules."` trailing-dot check is gone because this one
# subsumes it: the engine logs "Loaded N rule(s) from <absolute path>" naming the
# package FILE, so requiring that path to start with "$BUNDLE/rules/" pins the
# directory more tightly than matching a message tail ever did.
#
# Probe 2 asks for an output format that cannot exist. The engine answers by
# ENUMERATING the report-writer SUPPLIERS it can see on the classpath, so requiring
# `xlsx` in that list proves cumba-oss-corej-report-xlsx is present and its
# META-INF/services registration resolved. Without it, probe 1 alone passes on a
# bundle whose report writers are missing entirely -- measured: deleting
# cumba-oss-corej-report-xlsx from a built bundle produced `smoke: OK`, because
# probe 1 fails at rule-package resolution before any writer is touched.
#
# ⛔ WHAT PROBE 2 DOES NOT PROVE -- and an earlier version of this comment claimed it
# did. The enumeration is answered by XlsxReportWriterSupplier, which does NOT
# reference POI; XlsxReportWriter, which does, is instantiated lazily at write time.
# Measured: deleting poi, poi-ooxml, poi-ooxml-lite and xmlbeans while KEEPING the
# writer module still produced `smoke: OK`.
#
# POI reaches lib/ by TWO independent edges -- cumba-oss-corej-report-xlsx -> poi-ooxml,
# and cumba-oss-datatable-provider-xlsx -> excel-streaming-reader -> poi + poi-ooxml.
# So a single <exclusion> does NOT drop the chain (the other edge still supplies it),
# and the failure this check actually covers is the one that cuts both at once: a
# `provided` (or excluded) scope for POI in the parent's dependencyManagement. That
# would ship green and fail on the operator's first `-of xlsx` -- and on any .xlsx
# INPUT too, since both consumers share the chain. Hence the explicit name check below.
#
# ⚠ WHAT NOTHING HERE PROVES. The datatable providers (parquet, SAS, xlsx input) are
# resolved lazily when a library of that type is opened, and no probe here opens one
# -- that needs fixtures this repository does not ship. The jar-count floor is the
# only backstop for those. Do not read `smoke: OK` as "every jar is present"; read it
# as "the launcher, the engine and the report-writer registrations load".
#
# ⚑ The twin is the same script with the other bundle names. The two are meant to
# stay in step: a fix to one belongs in the other.
#
# Usage: smoke.sh <path-to-exploded-bundle>

set -euo pipefail

# ⚠ PREFLIGHT. A missing tool must fail as a missing tool, not as a broken bundle.
# The assertions below are `case` matches on captured output, so a command that
# vanished (exit 127, empty output) reads as "the bundle did not print what we
# expected" — a broken environment reported as a broken artefact.
# ⚠ grep/sed/find/mkdir were added on 2026-09-17 with the new probe 1, which reads
# the bundle's packages.json and builds a data library. find was already used by the
# corpus floor below and had been missed here.
# ⚠ tr was added on 2026-10-03: the EXECUTION_ERROR message extraction flattens the
# report onto one line first, so a pretty-printed report yields the message too.
for tool in find grep java ls mkdir mktemp printf rm sed tr wc; do
    command -v "$tool" >/dev/null 2>&1 \
        || { echo "smoke: required tool not found: $tool" >&2; exit 1; }
done

BUNDLE="${1:?usage: smoke.sh <path-to-exploded-bundle>}"
[ -d "$BUNDLE" ] || { echo "smoke: not a directory: $BUNDLE" >&2; exit 1; }
# ⚠ Absolute, because the run below deliberately happens from an unrelated CWD --
# a relative bundle path would resolve against that temp directory instead.
BUNDLE="$(cd "$BUNDLE" && pwd)"

for f in run.sh cumba-oss-corej-cli.jar cumba-oss-corej-cli.conf; do
    [ -e "$BUNDLE/$f" ] || { echo "smoke: bundle is missing $f" >&2; exit 1; }
done
[ -x "$BUNDLE/run.sh" ] || { echo "smoke: run.sh is not executable" >&2; exit 1; }

# The application jar must be in lib/, not just its dependencies. An assembly
# declared before maven-jar-plugin silently produces a bundle without it.
ls "$BUNDLE"/lib/cumba-oss-corej-cli-*.jar >/dev/null 2>&1 \
    || { echo "smoke: no cumba-oss-corej-cli-*.jar in $BUNDLE/lib" >&2; exit 1; }

# A crude floor, not a manifest: it catches a dependencySet that resolved the wrong
# scope (or nothing), which is the realistic build-configuration failure. The bundle
# carries ~70 jars; anything under 50 means the graph, not a single artifact, went
# wrong.
libcount="$(ls "$BUNDLE"/lib/*.jar 2>/dev/null | wc -l)"
[ "$libcount" -ge 50 ] \
    || { echo "smoke: only $libcount jars in $BUNDLE/lib — expected ~70; the dependencySet resolved the wrong scope?" >&2; exit 1; }

# ⛔ The store directories are the one bundle property the .conf marks DO-NOT-DELETE:
# for dictionaries/ a configured-but-missing directory is a hard error on every real
# run. They exist only because src/assembly/dist.xml ships them, and they are only in
# git because each holds a README -- so a dropped fileSet, or a deleted README, ships
# a bundle that passes everything else and fails on the operator's first run.
# Measured: `rm -rf rules/` from a built bundle used to produce `smoke: OK`.
for d in rules rules-define dictionaries; do
    [ -d "$BUNDLE/$d" ] \
        || { echo "smoke: bundle is missing $d/ — see the DO-NOT-DELETE note in the .conf" >&2; exit 1; }
done

# ⛔ THE BUNDLED RULE CORPUS. rules/ and rules-define/ ship POPULATED since
# 2026-09-11; before that they were empty by design and only had to exist.
#
# The manifest is asserted separately from the count on purpose: the engine resolves
# rules THROUGH packages.json, so a corpus that lost it fails only at run time, and a
# count alone passes straight over that.
#
# ⚠ These floors are the distribution build's MIN_CORE_RULES (28) and
# MIN_DEFINE_PKGS (4), so the app and the clients move together. They are floors, not
# counts, and they are NOT the place to encode which families shipped. The exact
# DRAFT-package ratchet lives in pom.xml, where the pin it tracks lives; duplicating
# that number here would make it two places to bump.
for corpus in "rules:rules-*.json:28" "rules-define:rules-define-*.json:4"; do
    dir="${corpus%%:*}"; rest="${corpus#*:}"; glob="${rest%%:*}"; floor="${rest##*:}"
    [ -f "$BUNDLE/$dir/packages.json" ] \
        || { echo "smoke: $dir/packages.json is missing from the bundle — the engine resolves rules through that manifest, so this fails only at run time" >&2; exit 1; }
    n="$(find "$BUNDLE/$dir" -maxdepth 1 -name "$glob" -type f | wc -l)"
    [ "$n" -ge "$floor" ] \
        || { echo "smoke: only $n $glob in $BUNDLE/$dir — expected at least $floor; the assembly staged the wrong path (it points through a version-bearing directory)" >&2; exit 1; }
done

# The xlsx writer's POI chain, by name. See the ⛔ block in the header: probe 2 cannot
# see this, because the supplier that answers the enumeration never touches POI.
#
# ⚠ ANCHOR THE VERSION SEGMENT. `poi-*.jar` is a SUPERSET glob — it also matches
# poi-ooxml and poi-ooxml-lite, and `poi-ooxml-*.jar` matches poi-ooxml-lite — so the
# unanchored form was satisfied by the wrong jar and two of the three checks could not
# fail on a single-jar loss. Measured: deleting poi-5.5.1.jar alone (the only jar
# carrying org/apache/poi/ss/usermodel/Workbook) still passed. `-[0-9]*` pins each
# name to its own artifact.
#
# All four are load-bearing: poi has Workbook, poi-ooxml has XSSFWorkbook,
# poi-ooxml-lite has the 2292 org/openxmlformats schema classes XSSFWorkbook
# resolves, and xmlbeans underpins those.
for j in poi poi-ooxml poi-ooxml-lite xmlbeans; do
    ls "$BUNDLE"/lib/$j-[0-9]*.jar >/dev/null 2>&1 \
        || { echo "smoke: lib/ has no $j-<version>.jar — the xlsx writer and any .xlsx input will NoClassDefFoundError at use time" >&2; exit 1; }
done

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
# ⚠ The data library is a SUBDIRECTORY, deliberately separate from where the report
# is written. -o puts report.runtime.csv beside the report, and with both in one
# directory the engine picks that CSV up as a dataset called REPORT.RUNTIME on the
# next run — a self-inflicted finding in what is supposed to be a fixed input.
mkdir -p "$work/data"
printf 'STUDYID,DOMAIN,USUBJID\nS1,DM,U1\n' > "$work/data/dm.csv"

fail() { echo "smoke: $1" >&2; echo "--- output ---" >&2; echo "$out" >&2; exit 1; }

# ---- Probe 1: the engine LOADS the bundled corpus and RUNS rules ----------------
#
# Every package short name the bundle's own manifest declares. See the ⭐ note in
# the header: the manifest is the authority, a literal package name is not.
packages=""
npkg=0
while IFS= read -r file; do
    [ -n "$file" ] || continue
    short="${file#rules-}"; short="${short%.json}"
    [ -n "$short" ] || continue
    packages="$packages $short"
    npkg=$((npkg + 1))
done <<< "$(grep -o '"file"[[:space:]]*:[[:space:]]*"rules-[^"]*\.json"' \
                "$BUNDLE/rules/packages.json" \
            | sed 's/.*"\(rules-[^"]*\.json\)".*/\1/')"

# ⚠ NON-VACUITY. A manifest that parsed to nothing — a shape change, a truncated
# download — would otherwise make the loop below run zero times and pass silently:
# the exact "check that quietly stops checking" this probe replaced.
[ "$npkg" -ge 1 ] \
    || { echo "smoke: $BUNDLE/rules/packages.json declares no rules-*.json packages — the manifest is empty, truncated, or its shape changed; probe 1 would have checked nothing" >&2; exit 1; }

# Extracts the operator-actionable half of a load failure: which rule, and why.
# The engine reports it as a Jackson binding failure whose reference chain carries
# the rule id: ... LinkedHashMap["CDISC-CG0001"]->...Rule["Operations"].
explain_load_failure() {
    local o="$1" id why
    id="$(printf '%s\n' "$o" | grep -o 'LinkedHashMap\["[^"]*"\]' | head -1 \
            | sed 's/.*\["\(.*\)"\]/\1/')"
    why="$(printf '%s\n' "$o" \
            | grep -oE '(RuleDefinitionException|JsonMappingException|[A-Za-z]+Exception): .*' \
            | head -1 | sed 's/^[A-Za-z]*Exception: //')"
    printf '%s%s' "${id:+rule $id: }" "${why:-unrecognised failure, see the output below}"
}

bad=""
nbad=0
executed=0
refused=""
nrefused=0
stalled=""
loadedfrom=""
recorded=""
nrecorded=0
nrows=0
for p in $packages; do
    # ⚠ A FRESH report per package. The recorded-load-error arm below reads it, and a
    # report left over from the previous package would be judged in this one's name.
    rm -f "$work/report.json"
    set +e
    out="$(cd "$work" && "$BUNDLE/run.sh" \
            -rp "$p" -d "$work/data" -o "$work/report.json" 2>&1)"
    rc=$?
    set -e

    case "$out" in
        *NoClassDefFoundError*|*ClassNotFoundException*)
            fail "the bundle's classpath is incomplete" ;;
        *"bootstrap: "*)
            fail "the launcher rejected its configuration" ;;
    esac

    # The manifest named a package the engine cannot resolve: packages.json and the
    # staged files disagree, which the find-based floors above cannot see.
    case "$out" in
        *"Unknown rule package '$p'"*)
            fail "rules/packages.json declares '$p' but the engine cannot resolve it in $BUNDLE/rules — the manifest and the staged files disagree" ;;
    esac

    # A LOAD failure. Exit 1 with an uncaught exception; see the ⚠ note in the header
    # on why exit 2 (the CLI's own `Error:`) is a different thing entirely.
    case "$out" in
        *"Exception in thread"*|*RuleDefinitionException*|*JsonMappingException*)
            bad="$bad
smoke:   rules-$p.json: $(explain_load_failure "$out")"
            nbad=$((nbad + 1))
            continue ;;
    esac
    [ "$rc" -eq 0 ] || [ "$rc" -eq 2 ] \
        || fail "rule package '$p' exited $rc, which is neither a clean run (0) nor a usage/selection refusal (2)"

    # ⛔ EXIT 2 IS A WIDE FUNNEL, NOT A REFUSAL SIGNATURE (review round 3, H-3).
    # CdiscValidate maps EVERY StudyValidationException to `Error: …` / exit 2, and the
    # engine's run/ package throws it from 14 sites — "no datasets", "no rules", and
    # review finding R-8's CORPUS fault (a package declaring a CDISC Library standard
    # that the store cannot resolve — as the two sendig/dart-1-2 packages do on a store
    # seeded before the E14 seed supplement, PLAN-seed-sendig-dart-1-2). Judging
    # the exit CODE alone accepted all of them silently, while the REST twin is fatal on
    # the very same event — the two probes are meant to stay in step, so key on the two
    # INTENDED refusal messages here exactly as the REST probe does.
    if [ "$rc" -eq 2 ]; then
        case "$out" in
            *'declare more than one TIG leg'*|*'declare no CDISC Library standard'*)
                refused="$refused
smoke:   rules-$p.json: $(printf '%s\n' "$out" | grep -m1 '^Error: ' || printf 'no Error: line in the output')"
                nrefused=$((nrefused + 1)) ;;
            *)
                fail "rule package '$p' exited 2, but with neither of the two selection refusals this probe models (a multi-leg TIG package; a package declaring no CDISC Library standard). Exit 2 is EVERY StudyValidationException — R-8's corpus fault included — so this is an unmodelled failure, not a refusal: $(printf '%s\n' "$out" | grep -m1 '^Error: ' || printf 'no Error: line in the output')" ;;
        esac
    fi

    # ⛔ THE RECORDED LOAD ERROR. See the ⛔ note in the header: a rule the engine could
    # not load is reported, never thrown, so only the report shows it. Keyed on the EXIT
    # CODE (see the ⚠ note in the header), not on the "Validation complete:" line.
    if [ "$rc" -eq 0 ]; then
        [ -s "$work/report.json" ] \
            || fail "rule package '$p' exited 0 but wrote no report to $work/report.json — a run that writes nothing is a broken report-writer chain"
        report="$(< "$work/report.json")"
        # ⚠ NON-VACUITY, per report: the rows this arm reads must be there in the shape
        # it reads them, or a renamed key would turn it into a no-op.
        case "$report" in
            *'"Rules_Report"'*) ;;
            *) fail "the report for rule package '$p' has no Rules_Report — its shape changed, and the recorded-load-error check would have read nothing" ;;
        esac
        n="$(printf '%s' "$report" \
                | grep -oE '"status"[[:space:]]*:[[:space:]]*"[A-Z_]+"' | wc -l)" || true
        nrows=$((nrows + n))
        n="$(printf '%s' "$report" \
                | grep -oE '"status"[[:space:]]*:[[:space:]]*"EXECUTION_ERROR"' | wc -l)" || true
        if [ "$n" -gt 0 ]; then
            # BEST-EFFORT, for the operator only — the count above is the verdict. The
            # report is flattened onto one line first (tr), so a pretty-printed report
            # yields the message too; it still assumes the row's message immediately
            # precedes its status, as the report writer orders the keys. A load error's
            # message opens with the rule id in brackets, so it names rule and cause.
            why="$(printf '%s' "$report" | tr '\n' ' ' \
                    | grep -oE '"message"[[:space:]]*:[[:space:]]*"([^"\\]|\\.)*"[[:space:]]*,[[:space:]]*"status"[[:space:]]*:[[:space:]]*"EXECUTION_ERROR"' \
                    | head -1 \
                    | sed -E 's/^"message"[[:space:]]*:[[:space:]]*"//; s/"[[:space:]]*,[[:space:]]*"status".*$//')" || true
            recorded="$recorded
smoke:   rules-$p.json: $n EXECUTION_ERROR row(s), first: ${why:-no message found next to the status (best-effort extraction)}"
            nrecorded=$((nrecorded + 1))
        fi
        case "$out" in
            *"Validation complete:"*) executed=$((executed + 1)) ;;
            *) stalled="$stalled rules-$p.json" ;;
        esac
    fi
    if [ -z "$loadedfrom" ]; then
        while IFS= read -r line; do
            case "$line" in
                *"Loaded "*" rule(s) from "*) loadedfrom="${line#*rule(s) from }"; break ;;
            esac
        done <<< "$out"
    fi
done

if [ "$nbad" -gt 0 ]; then
    echo "smoke: the rule corpus staged in this bundle is UNLOADABLE by the engine in lib/." >&2
    echo "smoke: $nbad of $npkg packages declared by rules/packages.json failed to load:" >&2
    echo "${bad#$'\n'}" >&2
    echo "smoke:" >&2
    echo "smoke: This is a corpus/engine version mismatch, not a packaging fault: the corpus" >&2
    echo "smoke: pinned by dependency.corej-rules.tag in pom.xml predates the engine this" >&2
    echo "smoke: bundle links. Release cumba-oss-corej-rules FIRST, then bump the pin (its" >&2
    echo "smoke: tag, both sha256 properties and corej.rules.draft-packages) here and in" >&2
    echo "smoke: every other bundle that pins the corpus." >&2
    echo "smoke: ⛔ Do NOT weaken this probe to get a green build." >&2
    exit 1
fi

# ⛔ RECORDED load errors: every package loaded and ran, and the engine still reported
# rules it could not use. Fatal for the same reason as the thrown kind above — a dead
# rule is a check the operator believes ran.
if [ "$nrecorded" -gt 0 ]; then
    echo "smoke: the rule corpus staged in this bundle LOADS, but the engine in lib/ RECORDED" >&2
    echo "smoke: rules it could not use: $nrecorded of $npkg packages report EXECUTION_ERROR rows" >&2
    echo "smoke: (a rule load error is filed on the rule and reported, never thrown):" >&2
    echo "${recorded#$'\n'}" >&2
    echo "smoke:" >&2
    echo "smoke: On this one-row dataset the published corpus has none. A load error means the" >&2
    echo "smoke: corpus and the engine disagree on the rule language, so the remedy is the same" >&2
    echo "smoke: as for an unloadable package above; a run-time error is an engine defect." >&2
    echo "smoke: ⛔ Do NOT weaken this probe to get a green build." >&2
    exit 1
fi

# ⛔ ACCOUNTING (see the ⛔ note in the header): every declared package either executed
# or was refused with a named refusal. A package that exited 0 without reaching
# "Validation complete:" is neither, and is named here.
if [ $((executed + nrefused)) -ne "$npkg" ]; then
    echo "smoke: $executed executed + $nrefused refused != $npkg packages declared by rules/packages.json." >&2
    echo "smoke: exited 0 without a 'Validation complete:' line:${stalled:- none}" >&2
    exit 1
fi

# ⚠ NON-VACUITY across the corpus: the recorded-load-error check read at least one
# rule row, so an empty Rules_Report everywhere cannot pass for a clean corpus.
[ "$nrows" -ge 1 ] \
    || fail "all $npkg packages ran, but their reports carry no rule rows at all — the recorded-load-error check read nothing"

# ⛔ EXECUTION, not merely loading. A corpus can parse and still never reach a rule:
# this is the assertion that the bundled engine, report writer wiring and data
# provider actually run a validation end to end.
[ "$executed" -ge 1 ] \
    || fail "all $npkg packages loaded, but NONE reached rule execution (no 'Validation complete:' line) — the engine never ran a rule against the smoke dataset"

# corej.rules.dir resolved to the BUNDLE, not to the caller's CWD and not to
# rules-define/. The engine names the package file it read; require that path.
case "$loadedfrom" in
    "$BUNDLE/rules/"*) ;;
    "") fail "the engine never logged which package file it loaded — probe 1 cannot confirm corej.rules.dir resolved to $BUNDLE/rules" ;;
    *) fail "corej.rules.dir did not resolve to the bundle: the engine loaded rules from '$loadedfrom', expected $BUNDLE/rules/" ;;
esac

# ⚠ No post-loop report check: the last package may legitimately be a refusal (exit 2,
# no report), and every package that exited 0 had its own report asserted in the loop.
# ---- Probe 2: the report writers actually on the classpath --------------------
set +e
out="$(cd "$work" && "$BUNDLE/run.sh" \
        -rp __corej_smoke_probe__ -d "$work" -of __corej_smoke_format__ 2>&1)"
rc=$?
set -e

# ⚠ Diagnose the classpath BEFORE judging the exit code — as probe 1 does. The other
# order reports "expected exit 2, got 1" for what is actually a missing jar.
case "$out" in
    *NoClassDefFoundError*|*ClassNotFoundException*)
        fail "the bundle's classpath is incomplete (report writers)" ;;
esac

# A usage error exits 2, as in probe 1. Anything else means it died somewhere else
# and the format list below would be judged on the wrong output.
[ "$rc" -eq 2 ] || fail "probe 2: expected exit 2 from a usage error, got $rc"
# ⛔ MATCH THE ERROR LINE ONLY, NOT $out.
#
# The usage banner is printed after the error and it mentions `xlsx` twice as
# literal help text (`-of json|json-2|xlsx`, and `.xlsx` among the data-input
# extensions). A `case "$out" in *xlsx*` therefore matches on a bundle with the
# xlsx writer DELETED — measured: the first version of this check reported
# `smoke: OK` on exactly the mutation it was written to catch.
#
# ⚠ And it is not enough to take the FIRST line either. The JVM launcher prints
# `Picked up JAVA_TOOL_OPTIONS: …` / `_JAVA_OPTIONS` / `JDK_JAVA_OPTIONS` to stderr
# ahead of anything the application writes, and JAVA_TOOL_OPTIONS is routinely set
# in container images — including the kind of Maven image the Gitea job runs in. A
# first-line grab would then capture the JVM notice and fail a perfectly good
# bundle. Search for the line we actually want.
err=""
while IFS= read -r line; do
    case "$line" in
        "Error: Only --output-format"*) err="$line"; break ;;
    esac
done <<< "$out"

[ -n "$err" ] \
    || fail "did not reach output-format validation (no 'Only --output-format' line)"
# ⛔ NARROW $err TO THE ENUMERATION ITSELF. This is the third variant of the same
# trap: round 1 matched the usage banner, round 2 assumed line 1, and matching the
# whole error line still includes the `(got <sentinel>)` tail — so a sentinel
# containing "xlsx" would satisfy the xlsx check on a bundle that has no xlsx writer.
# Cut to the text between "--output-format " and " are supported".
formats="${err#*--output-format }"
formats="${formats%% are supported*}"

# ⚠ Delimited comparison. `json` is a substring of `json-2`, so a bare *"$fmt"* match
# passes the `json` check on a bundle offering only `json-2`.
for fmt in json json-2 xlsx; do
    case ", $formats," in
        *", $fmt,"*) ;;
        *) fail "report format '$fmt' is missing from the bundle — the engine offered: $formats" ;;
    esac
done

echo "smoke: OK — $BUNDLE launched, loaded the engine and its report writers,"
echo "smoke:      resolved corej.rules.dir to the bundle ($libcount jars in lib/),"
echo "smoke:      LOADED all $npkg bundled rule packages with the engine and ran"
echo "smoke:      rules to completion in $executed of them"
if [ "$nrefused" -gt 0 ]; then
    echo "smoke:      $nrefused refused with a named selection refusal (loaded, NOT run):"
    echo "${refused#$'\n'}"
fi
