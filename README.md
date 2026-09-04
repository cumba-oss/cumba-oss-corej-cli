# cumba-oss-corej-cli

The **headless command-line client** for the
[coreJ](https://github.com/cumba-oss/cumba-oss-corej) CDISC conformance engine: point it at a
study, name the rule packages to run, and it writes a JSON or XLSX conformance report.

Part of the [cumba-oss](https://github.com/cumba-oss) set. The engine itself lives in
[cumba-oss-corej](https://github.com/cumba-oss/cumba-oss-corej), the rule corpus in
[cumba-oss-corej-rules](https://github.com/cumba-oss/cumba-oss-corej-rules), and the REST API
over the same engine in
[cumba-oss-corej-rest](https://github.com/cumba-oss/cumba-oss-corej-rest).

This repository is a **single Maven module** — the root pom *is* the artifact
(`net.cumba:cumba-oss-corej-cli`, `packaging=jar`). There is no `clients/` directory and no
`dist/` module.

## ⛔ Nothing here is published to Maven Central

This is an **application, not a library** — there is no coordinate to depend on. The artifact is
excluded from Central via `excludeArtifacts` in the pom's `deploy-central` profile.

⚠ That exclusion is **plugin configuration, deliberately not a property**: a property is a Maven
*user* property, which a release job's `-D` silently overrides per module. That precedence is
exactly how `net.cumba:coverage:0.0.4` became public and immutable.

The libraries this client consumes *are* on Central, released separately on their own cadence:

| Library | Version pinned here |
|---|---|
| `cumba-oss-commons` | `0.2.1` |
| `cumba-oss-datatable` | `0.2.0` |
| `cumba-oss-corej` | `0.3.0` |

(the `dependency.cumba-oss-*.version` properties in `pom.xml`).

## Releases

Tagging `vX.Y.Z` builds, signs and attaches two files to the release, on both the GitHub and the
Gitea mirror:

```
cumba-oss-corej-cli-<version>.jar
cumba-oss-corej-cli-<version>.jar.asc
```

The two releases are cut independently from the same tag by
`.github/workflows/ci.yml` and `.gitea/workflows/main.yml`; the Gitea run additionally deploys to
the internal Nexus. Nothing reaches Maven Central.

⚠ **This repository has never cut a tag.** `GPG_PRIVATE_KEY` and `GPG_PASSPHRASE` are
**organisation-level** secrets on `cumba-oss`, so they are inherited here rather than set per
repository — but the signing path itself has never run in this repo, so the first tag is its
first real test. The release job fails by name on an empty key rather than publishing unsigned
assets.

### Verifying a release asset

Each jar is signed with the same GPG key as the project's Maven Central artifacts — one
trust root, not two. Nothing here reaches Central, so the release asset is the **only**
delivery, and a release asset **can be replaced in place** by anyone with write access:
unlike an immutable Central artifact, the signature is the only thing standing between you
and a swapped jar.

```bash
curl -sLO <asset-url> && curl -sLO <asset-url>.asc
gpg --verify cumba-oss-corej-cli-<version>.jar.asc cumba-oss-corej-cli-<version>.jar
```

Key fingerprint: `AE5AA7685BED3FC5DF4AE8DD7727EF25F931AF6B`

⚠ **Release assets are mutable**, unlike Central artifacts. The signature proves a jar is
authentic; **your pinned hash proves *which* authentic jar you adopted.** Record both, and
don't assume re-downloading a tag returns the same bytes.

## Build

Java 25, Maven 3.9.12+.

```bash
mvn -B clean install -Drevision=0.1.0-SNAPSHOT
```

`0.1.0-SNAPSHOT` is the current `<revision>` and also its fallback value in the pom, so
`-Drevision=…` may be omitted locally; CI always passes it explicitly.

### ⚠ A plain `mvn verify` is not the CI gate

Three of the four gate flags default to **permissive** so that day-to-day builds are not blocked
by a style finding, and `spotless` in its default mode *reformats your sources* instead of
failing on them:

| Property | Default here | CI |
|---|---|---|
| `maven.compiler.failOnWarning` | `true` | `true` |
| `pmd.failOnViolation` | `false` | `true` |
| `spotbugs.failOnError` | `false` | `true` |
| `spotless.check` | `false` (applies) | `true` (checks) |

To run what CI runs — the `MAVEN_CI_GATES` value from both workflow files:

```bash
mvn -B spotless:check -Drevision=0.1.0-SNAPSHOT
mvn -B clean install -Drevision=0.1.0-SNAPSHOT \
    -Dmaven.compiler.failOnWarning=true -Dspotless.check=true \
    -Dpmd.failOnViolation=true -Dspotbugs.failOnError=true
```

CI then runs a Pitest mutation pass (`-P Pitest`) on top, and — on Gitea only — Sonar, the Nexus
deploy and the release.

## Running

The build produces a **plain jar with a `Main-Class`** (`net.cumba.corej.cli.CdiscValidate`) plus
a sibling `libs/` directory: `maven-dependency-plugin` stages the runtime dependencies into
`target/libs/`, and the manifest's `Class-Path` refers to them with the `libs/` prefix.

```bash
java -jar target/cumba-oss-corej-cli-0.1.0-SNAPSHOT.jar --help
```

⛔ **The release asset is the jar alone — `libs/` is not attached.** Both workflows upload
`target/*-<version>.jar` and nothing else, and a `Class-Path` entry that is missing is ignored
rather than reported, so a downloaded jar run with `java -jar` starts and then dies on the first
class it cannot load. Until a bundle asset exists (the monorepo's `dist/` assembly modules did
not come across the split), a consumer of the release has to supply the classpath itself, or
build from a checkout as above.

### Selecting rules

Rules are chosen by **naming rule packages**, never by standard and version:

```bash
java -jar target/cumba-oss-corej-cli-0.1.0-SNAPSHOT.jar \
    -rp cdisc-sdtmig-3-4 \
    -d ./datasets \
    -o CORE-Report.json
```

A package short name is its file name without the invariant `rules-` prefix and `.json` suffix,
so `rules-cdisc-sdtmig-3-4.json` is `cdisc-sdtmig-3-4`. `-rp` accepts a comma-separated list and
may be repeated, and the usage banner lists it under **Required**.

`-mp` / `--metadata-products` selects CDISC Library **metadata only** — it never selects rules.
It is not mandatory, because each rule package declares the standards it runs against and those
supply the products. ⚠ Since `-s` / `-v` were removed there is no implied default: a package that
declares nothing *and* no `-mp` is refused by the engine, not defaulted.

Other options worth knowing (`--help` prints the full banner):

| Option | Meaning |
|---|---|
| `-d`, `--data` | data library: a directory, a single file (`.sas7bdat`, `.xpt`, `.xlsx`, `.dsj`, `.parquet`, …) or a URI |
| `-dxp`, `--define-xml-path` | define.xml — metadata enrichment with `-d`, or the data library itself without it |
| `-o`, `--output` | output file (default `CORE-Report-<ts>.json`) |
| `-of`, `--output-format` | repeatable/comma-separated; the accepted names are enumerated at runtime from the report writers on the classpath (`json`, `json-2`, `xlsx`) |
| `-vx`, `--validate-xml` | also run Define-XML conformance on `-dxp`; **requires `--define-family`** (CDISC and/or PMDA) |
| `-t`, `--threads` | rule worker threads per dataset (default 1) |
| `-sl`, `--severity-level` | weakest level evaluated: Reject, Error, Warning (default) or Info |
| `-h`, `--help` | usage |

A usage error exits **2** and prints the banner to stderr.

### Remote mode

`--remote <baseUrl>` runs the check on a
[cumba-oss-corej-rest](https://github.com/cumba-oss/cumba-oss-corej-rest) service instead of
in-process: the local data, define.xml and rule files are uploaded, the run executes server-side,
and the report is written to `-o` as usual. `--remote-user` / `--remote-password` /
`--remote-token` carry the credentials (`--remote-token` wins when both are set); each has a
`-Dcorej.remote.*` system-property equivalent.

## ⚠ The rule corpus is not in this repository

The engine needs a **rule corpus on disk at runtime**. It is not a Maven dependency and is not
vendored here — it is released separately, as signed archives, from
[cumba-oss-corej-rules](https://github.com/cumba-oss/cumba-oss-corej-rules):
`cumba-oss-corej-rules-<version>.zip` (data rule packages) and
`cumba-oss-corej-rules-define-<version>.zip` (Define-XML packages).

| What | Flag | Falls back to |
|---|---|---|
| Data rule packages | `--rules-dir <dir>` | `COREJ_RULES_DIR`, then `-Dcorej.rules.dir`, then `./rules` |
| Define-XML packages (for `-vx`) | `--define-rules-dir <dir>` | `COREJ_DEFINE_RULES_DIR`, then `-Dcorej.define.rules.dir`, then `./rules-define` |
| External dictionaries | `--dictionaries-dir <dir>` | `COREJ_DICTIONARIES_DIR`, then `-Dcorej.dictionariesDir`, then `./dictionaries` |

### CDISC Library access

Metadata resolution normally calls the CDISC Library, authenticated with the `CDISC_API_KEY`
environment variable or `-Dcdisc.library.api.key`. Two offline routes avoid it:

- `--seed-cache` fills the web-api cache from the Python engine's pickle metadata. It **needs no
  API key**, runs standalone and exits.
- `-pc` / `--pickle-cache` reads an existing pickle metadata directory directly (SDTM only).

`--install-dictionaries` populates the dictionary store and exits; with no inputs it downloads the
credential-free MED-RT, UNII and neoplasm sets. It is a local maintenance mode and is rejected in
combination with `--remote`.

## What is deliberately not here

- **Containers.** The `Dockerfile`, Compose file and entrypoint were wired to the monorepo layout
  (`dist/` bundle modules, the rule corpus in a sibling module) and could not build from this
  repository. They are being reworked into their own repository.
- **The rule editor.** Not part of the open-source distribution.

## Test data

`testdata/` holds two checked-in study fixtures — see [`testdata/README.md`](testdata/README.md).

## Licence

AGPL-3.0-only — see [LICENSE](LICENSE).
