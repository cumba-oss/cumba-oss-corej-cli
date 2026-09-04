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
# Probe 1 asks for a rule package that cannot exist and requires the engine's own
# "Unknown rule package" error, naming the BUNDLE's rules directory. That proves
# the launcher found and parsed its sidecar .conf, every ${...} in it resolved, the
# engine classes loaded, and corej.rules.dir resolved to the bundle rather than to
# the caller's CWD.
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
# Usage: smoke.sh <path-to-exploded-bundle>

set -euo pipefail

# ⚠ PREFLIGHT. A missing tool must fail as a missing tool, not as a broken bundle.
# The assertions below are `case` matches on captured output, so a command that
# vanished (exit 127, empty output) reads as "the bundle did not print what we
# expected" — a broken environment reported as a broken artefact.
for tool in java ls mktemp printf rm wc; do
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
printf 'STUDYID,DOMAIN,USUBJID\nS1,DM,U1\n' > "$work/dm.csv"

# ⚠ Run from an unrelated CWD. A bundle that only works when you stand inside it
# is the bug next door to the one this replaces.
set +e
out="$(cd "$work" && "$BUNDLE/run.sh" \
        -rp __corej_smoke_probe__ -d "$work" -o "$work/report.json" 2>&1)"
rc=$?
set -e

fail() { echo "smoke: $1" >&2; echo "--- output ---" >&2; echo "$out" >&2; exit 1; }

case "$out" in
    *NoClassDefFoundError*|*ClassNotFoundException*)
        fail "the bundle's classpath is incomplete" ;;
    *"bootstrap: "*)
        fail "the launcher rejected its configuration" ;;
esac

# The engine's own error, naming the bundle's rules directory — not ./rules.
case "$out" in
    *"Unknown rule package '__corej_smoke_probe__'"*) ;;
    *) fail "did not reach the engine's rule-package resolution" ;;
esac
case "$out" in
    *"$BUNDLE/rules"*) ;;
    *) fail "corej.rules.dir did not resolve to the bundle ($BUNDLE/rules)" ;;
esac

# A usage error exits 2; anything else means it failed somewhere unexpected.
[ "$rc" -eq 2 ] || fail "expected exit 2 from a usage error, got $rc"

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
echo "smoke:      and resolved corej.rules.dir to the bundle ($libcount jars in lib/)"
