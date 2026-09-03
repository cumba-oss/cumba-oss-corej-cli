# cumba-oss-cdisc-cli

Command-line tool that drives the coreJ engine end-to-end: load a study
from files, fetch or load a rule package, run validation, write a JSON
report. Headless.

> **Not published to Maven Central.** This is a runnable application, not a
> library — there is no artifact to declare as a dependency. Build it from
> source, or take the jar from a GitHub release.

## Java packages

- `net.cumba.dataviewer.examples` — `CdiscValidate.java` (the
  `main(String[])` entry point)

## Build and run

Produces a plain jar with a `Main-Class` manifest entry plus a sibling
`libs/` directory, so the jar runs as:

```bash
mvn -pl clients/cumba-oss-cdisc-cli -am install
java -jar clients/cumba-oss-cdisc-cli/target/cumba-oss-cdisc-cli-*.jar --help
```

Point the tool at a rules directory with `--rules-dir` or `COREJ_RULES_DIR`.
The rule packages are distributed separately from this repository.

## ⛔ Migration — `-s` / `-v` / `-f` were removed

Rules are now selected by **naming the rule package(s)** you want, not by a standard and
version. Every previous invocation must change.

| before | now |
|---|---|
| `-s sdtmig -v 3-4` | `-rp cdisc-sdtmig-3-4` |
| `-s adamig -v 1-3` | `-rp cdisc-adamig-1-3` |
| `-s sdtmig -v 3-4 -f FDA` | `-rp fda-sdtmig-3-4` |
| `-s sdtmig -v 3-4 -f CDISC,FDA` | `-rp cdisc-sdtmig-3-4,fda-sdtmig-3-4` |

A package short name is its file name without the invariant `rules-` prefix and `.json`
suffix, so `rules-cdisc-sdtmig-3-4.json` is `cdisc-sdtmig-3-4`. `-rp` takes a comma-separated
list and may also be repeated. Run with an unknown name to have the CLI list what is available.

**Why.** `-s`/`-v` did two unrelated jobs at once — they picked the rule packages *and* the
CDISC Library metadata product. Each package now declares the library standards it runs
against, so naming the package is enough; the metadata follows from it. `-mp` /
`--metadata-products` still exists and still selects **metadata only** — it never selects rules.

⚑ **A rules package is now required.** A run that names none fails, listing the packages in the
rules directory it searched. Previously such a run could resolve to nothing and validate
silently.

⚑ **`-s`, `-v` and `-f` are rejected with a message naming the replacement** rather than
"unknown option", so an old command line tells you what to type instead.

## Dependencies

| Artifact | Scope | Why |
|---|---|---|
| `net.cumba:cumba-oss-datatable` | compile | table contract |
| `net.cumba:cumba-oss-datatable-impl` | compile | concrete tables / buffers |
| `net.cumba:cumba-oss-datatable-manager-local` | compile | local file-system manager (loads the study) |
| `net.cumba:cumba-oss-datatable-provider-csv` | compile | reads CSV studies |
| `net.cumba:cumba-oss-datatable-provider-dsj` | compile | reads Dataset-JSON (`.json` / `.ndjson` / `.dsjc`) studies |
| `net.cumba:cumba-oss-datatable-provider-cdt` | compile | reads `.cdt` studies |
| `net.cumba:cumba-oss-datatable-provider-sas` | compile | reads SAS Transport (`.xpt`) and SAS dataset (`.sas7bdat`) studies |
| `net.cumba:cumba-oss-datatable-provider-xlsx` | compile | reads Excel (`.xls` / `.xlsx`) studies |
| `net.cumba:cumba-oss-datatable-provider-parquet` | compile | reads Apache Parquet (`.parquet`) studies |
| `net.cumba:cumba-oss-datatable-provider-define` | compile | Define-XML metadata loading |
| `net.cumba:cumba-oss-cdisc-core` | compile | the engine |
| `net.cumba:cumba-oss-cdisc-library` | compile | rule-package loading via CDISC Library |
| `net.cumba:cumba-oss-cdisc-report-json` | compile | JSON report writer (`json`, `json-2`) |
| `net.cumba:cumba-oss-cdisc-report-xlsx` | compile | XLSX report writer |
| `net.cumba:cumba-oss-cdisc-define` | compile | Define-XML model |
| `net.cumba:cumba-oss-cdisc-define-conformance` | compile | Define-XML conformance validation (`-vx`) |
| `net.cumba:cumba-oss-web-api` | compile | HTTP transport (transitive but listed explicitly) |

## Notes

- Ships the dataviewer's `CdiscValidate.java` verbatim **except** the
  removed `net.cumba.license.LicenseGate.evaluateOrExitHeadless()` call
  at the top of `main()` — that's a dataviewer-internal license check
  that doesn't apply to the OSS distribution.
- Sibling example tools that ship in the dataviewer's CLI module
  (`DataConverter`, `CreateBigXpt`, `PatchDataset`,
  `SimpleLibraryMetadataProvider`) are intentionally NOT included — they
  are not part of the validation engine surface.
- **Containers moved out.** The Dockerfile and Compose files that used to
  live here are being reworked into their own repository; they were wired
  to the monorepo layout (`dist/` bundle modules, the SPA, the rule
  corpus) and could not build from this repository.

See the root [README](../../README.md) for project-wide context.
