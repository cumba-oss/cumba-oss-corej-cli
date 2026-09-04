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

Tagging `vX.Y.Z` builds, signs and attaches **four** files to the release, on both the GitHub and
the Gitea mirror:

```
cumba-oss-corej-cli-<version>.zip        ← the runnable distribution
cumba-oss-corej-cli-<version>.zip.asc
cumba-oss-corej-cli-<version>.jar        ← the plain artifact, also on Nexus
cumba-oss-corej-cli-<version>.jar.asc
```

⭐ **The zip is what you run** (§ Running). The jar is kept for consumers who assemble their own
classpath; on its own it is not runnable.

The two releases are cut independently from the same tag by
`.github/workflows/ci.yml` and `.gitea/workflows/main.yml`; the Gitea run additionally deploys to
the internal Nexus. Nothing reaches Maven Central.

⚠ **This repository has never cut a tag.** `GPG_PRIVATE_KEY` and `GPG_PASSPHRASE` are
**organisation-level** secrets on `cumba-oss`, so they are inherited here rather than set per
repository — but the signing path itself has never run in this repo, so the first tag is its
first real test. The release job fails by name on an empty key rather than publishing unsigned
assets.

### Verifying a release asset

**Every asset is signed** — the zip as well as the jar — with the same GPG key as the project's
Maven Central artifacts, one trust root rather than two. Nothing here reaches Central, so the
release asset is the **only** delivery, and a release asset **can be replaced in place** by anyone
with write access: unlike an immutable Central artifact, the signature is the only thing standing
between you and a swapped file.

⚠ Verify the asset you actually intend to run. For most people that is the **zip**:

```bash
curl -sLO <asset-url> && curl -sLO <asset-url>.asc
gpg --verify cumba-oss-corej-cli-<version>.zip.asc cumba-oss-corej-cli-<version>.zip
# and the same two lines with .jar, if you are consuming the jar
```

Key fingerprint: `AE5AA7685BED3FC5DF4AE8DD7727EF25F931AF6B`

⚠ **Release assets are mutable**, unlike Central artifacts. The signature proves an asset is
authentic; **your pinned hash proves *which* authentic asset you adopted.** Record both, and
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

### From a release — the distribution zip

Every release carries **`cumba-oss-corej-cli-<version>.zip`**, a self-contained runnable
bundle. Download it (and its `.asc`, see *Verifying a release asset*), unzip, run:

```bash
unzip cumba-oss-corej-cli-<version>.zip
cd cumba-oss-corej-cli-<version>
./run.sh --help            # run.bat on Windows
```

```
cumba-oss-corej-cli-<version>/
├── cumba-oss-corej-cli.jar     the cumba-oss-bootstrap launcher
├── cumba-oss-corej-cli.conf    its configuration — edit freely, no rebuild
├── run.sh   run.bat
├── rules/  rules-define/  dictionaries/    ship empty; each has a README
└── lib/                        the application jar and every dependency
```

The bundle is **relocatable** — move it anywhere, invoke `run.sh` by any path, from any working
directory. `JAVA_OPTS` is passed through to the JVM (`JAVA_OPTS=-Xmx8g ./run.sh …`).

`cumba-oss-corej-cli.jar` is not the application: it is
[`cumba-oss-bootstrap`](https://github.com/cumba-oss/cumba-oss-commons), a dependency-free
launcher that reads the sidecar `.conf` beside it, applies its `[properties]` as system
properties, assembles the `lib/` classpath and invokes `net.cumba.corej.cli.CdiscValidate`.
⚠ **The jar and the `.conf` must keep the same basename** — the lookup is "strip `.jar`, append
`.conf`, look beside me". Rename one, rename both, or pass `-Dbootstrap.config=<path>`.

Everything an operator normally needs to change lives in that `.conf`: where the rule corpus,
the Define-XML corpus and the dictionary store are (defaulted to the bundle's own directories,
with the `COREJ_*` environment variables still winning), and any extra system properties.

### From a checkout

`mvn package` also produces the plain application jar with a `Main-Class`
(`net.cumba.corej.cli.CdiscValidate`) and stages its dependencies into `target/libs/`, which the
manifest's `Class-Path` references with a `libs/` prefix:

```bash
java -jar target/cumba-oss-corej-cli-0.1.0-SNAPSHOT.jar --help
```

⚠ **That works only while `libs/` sits beside the jar.** A manifest `Class-Path` resolves
relative to the jar's own location, not the working directory — so the jar keeps working wherever
you invoke it from, as long as `libs/` travels with it. The jar **copied out of `target/` on its
own** does not: a missing
`Class-Path` entry is ignored rather than reported, so it starts and then dies on the first class
it cannot load. This is why the release asset is the zip
— it was previously the jar alone, and a `--help` run hid the problem because picocli prints its
banner before the engine is touched. `mvn package` builds the zip too, at
`target/cumba-oss-corej-cli-<version>.zip`, and the same tree exploded at
`target/cumba-oss-corej-cli-<version>/cumba-oss-corej-cli-<version>/` for `src/test/smoke.sh`.

⚠ **Two levels, not one.** The assembly's `dir` format nests its `<baseDirectory>` inside the
execution's `<finalName>`, so the bundle root is the doubled path above — pointing the smoke
script at the outer directory reports `bundle is missing run.sh` on a perfectly good build.

The jar is still published to Nexus and still attached to the release, for consumers who put it
on a classpath they manage themselves.

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

⭐ **In the distribution zip all three are already pointed at the bundle**, so the last column's
CWD-relative default never applies. `cumba-oss-corej-cli.conf` sets each system property to
`${env:COREJ_…:-${sys:<property>:-${bootstrap.dir}/<dir>}}`, giving

    COREJ_* environment  >  -D system property  >  the bundle's own directory

— the table's precedence, but resolving against the bundle rather than against wherever you
happened to be standing. ⚠ The `${sys:…}` level is not cosmetic: the launcher applies
`[properties]` with an unconditional `System.setProperty`, so a two-level form would *overwrite*
a `-D` the operator passed and silently fall back to the bundle's empty directory. The three
directories ship empty, each with a README saying what goes in it:

```bash
unzip cumba-oss-corej-rules-<version>.zip
mv cumba-oss-corej-rules-<version>/rules/* cumba-oss-corej-cli-<version>/rules/
```

⚠ Move the **contents**. The corpus archive has its own top-level directory, so unzipping it
*into* the bundle's `rules/` buries the JSON two levels too deep and the engine finds nothing.

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
