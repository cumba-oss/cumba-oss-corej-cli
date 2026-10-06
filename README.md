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
| `cumba-oss-commons` | `0.5.0-SNAPSHOT` |
| `cumba-oss-datatable` | `0.5.0-SNAPSHOT` |
| `cumba-oss-corej` | `0.6.0-SNAPSHOT` |

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

⚠ `GPG_PRIVATE_KEY` and `GPG_PASSPHRASE` are **organisation-level** secrets on `cumba-oss`, so
they are inherited here rather than set per repository. The signing path has run for both tags cut
so far, `v0.1.0` and `v0.2.0`: each GitHub release carries a `.asc` beside its zip and its jar. The
release job fails by name on an empty key rather than publishing unsigned assets.

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
mvn -B clean install -Drevision=0.2.0-SNAPSHOT
```

`0.2.0-SNAPSHOT` is the current `<revision>` and also its fallback value in the pom, so
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
mvn -B spotless:check -Drevision=0.2.0-SNAPSHOT
mvn -B clean install -Drevision=0.2.0-SNAPSHOT \
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
├── rules/                      the pinned rule corpus (32 packages)
├── rules-define/               the pinned Define-XML corpus (4 packages)
├── dictionaries/               empty; ./run.sh installs into it
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
java -jar target/cumba-oss-corej-cli-0.2.0-SNAPSHOT.jar --help
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
java -jar target/cumba-oss-corej-cli-0.2.0-SNAPSHOT.jar \
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
| `-vx`, `--validate-xml` | also run Define-XML conformance on `-dxp`; **requires `--define-family`** (CDISC and/or PMDA). Its `Requires: ct` / `Requires: library` rules read the metadata store like every other check; with no store they SKIP, named on the `CT basis:` / `Library basis:` stderr lines |
| `-ct`, `--controlled-terminology-package` | CT package id, repeatable; governs the dataset run **and** `-vx` (an explicit list replaces the define's own declaration or, failing that, the Trial Summary's). ⚠ A named package the store does not hold **aborts the whole invocation**: exit 2, no report of either kind |
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

## ⭐ The rule corpus is bundled

**`rules/` and `rules-define/` ship FULL**, so a freshly unzipped bundle validates out of the box.
Until 2026-09-11 both shipped empty with a README telling the operator to unzip a release asset in
by hand, which was the one store in this product that worked that way.

`download-corej-rules` in `pom.xml` fetches two assets of one pinned
[cumba-oss-corej-rules](https://github.com/cumba-oss/cumba-oss-corej-rules) release at
`generate-resources` — `cumba-oss-corej-rules-<version>.zip` (data rule packages) and
`cumba-oss-corej-rules-define-<version>.zip` (Define-XML packages) — verifies each against its own
`sha256`, and `src/assembly/dist.xml` stages them into the bundle. The mechanism is
`cumba-databrowser-launch`'s, copied deliberately: the data browser, the REST service and this CLI
then acquire the **same corpus the same way**, and answer identically for the same study.

⚠ The bundled corpus has **no CORE rule family** (externally sourced, not redistributable as a set)
and therefore **no TIG coverage** — `rules-draft-tig-1-0` is the only TIG package outside CORE, and
it is a DRAFT. The pinned release `v0.5.0` ships no `rules-draft-*` package: the DRAFT family is
generated and tested in the corpus repo but not released. See `src/main/dist/rules/README.md`.

⚠⚠ **Bumping the corpus is a five-repo change** — this repo, `cumba-oss-corej-rest`, their two
internal twins and `cumba-databrowser` all pin `dependency.corej-rules.tag` and the two hashes,
and `databrowser-meta/check_versions.sh` asserts they agree. Bump the tag, the version and **both**
hashes together, and re-measure `corej.rules.draft-packages`.

⚠ The operator is never locked to the bundled version: `COREJ_RULES_DIR` /
`COREJ_DEFINE_RULES_DIR` point the engine anywhere, outranking the bundle.

### The guard

`verify-corej-rules-staged` (antrun, `prepare-package`) counts what was unpacked and fails by name:
`packages.json` present in each corpus, ≥ 28 `rules-*.json`, ≥ 4 `rules-define-*.json`, and
**exactly** `corej.rules.draft-packages` DRAFT packages. `src/test/smoke.sh` makes the matching
assertion against the *assembled bundle*. The two are deliberately different subjects — the
download and the zip — because the fileSets that join them point **through a version-bearing
directory** (`cumba-oss-corej-rules-<version>/rules/`), and a wrong path there stages nothing at
all, silently, with every gate green.

⚠ The draft assertion is an **exact equality**, and since the pin moved to the DRAFT-free `v0.4.0`
the number is 0. `build_distribution.sh`'s `MAX_DRAFT_PKGS=0` is a different subject — the data
browser's staged platform archives. Equality here means a tag bump that changes the release scope
turns this build red and forces the number to be re-measured in the same edit, rather than passing
silently.

### Pointing the engine somewhere else

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
a `-D` the operator passed and silently fall back to the bundle's own directory.

To run against a **different** corpus version, either point `COREJ_RULES_DIR` at it — nothing is
copied — or replace the bundle's contents in place:

```bash
unzip cumba-oss-corej-rules-<version>.zip
rm -f cumba-oss-corej-cli-<version>/rules/rules-*.json \
      cumba-oss-corej-cli-<version>/rules/packages.json
mv cumba-oss-corej-rules-<version>/rules/* cumba-oss-corej-cli-<version>/rules/
```

⚠ Move the **contents**. The corpus archive has its own top-level directory, so unzipping it
*into* the bundle's `rules/` buries the JSON two levels too deep and the engine finds nothing.

⚠ Delete the shipped `rules-*.json` first, as above: the engine reads whatever packages are
present, so leftovers from the bundled corpus stay selectable and silently mix two versions.

### CDISC Library access

A run reads its CDISC metadata from the **unified metadata store**, a single zip file resolved as
`-ca` / `--cache` &gt; `CDISC_METADATA_STORE` &gt; `-Dcdisc.metadata.store` &gt;
`~/.cumbaDataBrowser/metadata-cache.zip`. With no store, every rule needing CDISC Library metadata
SKIPs — loudly, by name. Three seeding modes build it, each running standalone and exiting:

- `--seed-cache [<repoUri>]` seeds from the Python engine's published pickle metadata. It **needs
  no API key**.
- `--seed-cache-from-dir <dir>` seeds from an existing pickle directory you already have.
- `--seed-cache-from-api` seeds from the live CDISC Library API, and does need `CDISC_API_KEY`.

`--seed-overwrite` re-acquires everything instead of carrying forward what the store already
holds; `--seed-dry-run` reports what a run would change without touching it.

⚠ **Store format 5 (2026-10-03).** Format 5 adds the errata register (corrections of CDISC Library
content, each citing its printed source) and text normalisation; format 4 (2026-09-28) added the
CDASH fields' codelist C-codes. A store seeded before it (format 4, 3, or 2 from a release
before 2026-09-26) is refused — every run on it exits 2 naming the file and `--seed-cache` — never
read without the corrections; re-seed once
with a plain `--seed-cache`. A store written by a NEWER build than this tool is refused the other way
round (*"upgrade this tool"*): `--seed-cache` leaves it alone unless `--seed-overwrite` is passed.

⚠ `CDISC_API_CACHE` is a different thing and is **not** the store: it is the HTTP response cache
of the CDISC Library client, and the only path that reads it is `--seed-cache-from-api`. No
validation run calls the Library any more — `-vx` reads its CT and Library metadata from the
store like every other check.

`--install-dictionaries` populates the dictionary store and exits; with no inputs it downloads the
credential-free MED-RT, UNII and neoplasm sets. It is a local maintenance mode and is rejected in
combination with `--remote`.

## Containers

`Dockerfile`, `docker-compose.yml` and `docker-entrypoint.sh` build and run the CLI as a one-shot
container, from this repository alone:

```sh
docker compose build
docker compose run --rm cli --help
docker compose run --rm cli -rp cdisc-sdtmig-3-4 -d /data/datasets -o /data/CORE-Report.json
```

The build stage runs one `mvn -B -DskipTests package` and unpacks the dist zip; the runtime stage
is a JRE over that bundle, running as uid 1000. Compose bind-mounts `./corej-data` at `/data`, so
the study, its report, the dictionary store, the seeded metadata store and the CDISC Library API
cache all stay on the host — `mkdir -p corej-data && sudo chown -R 1000:1000 corej-data` once,
first.

⚠ **The image bakes no metadata store either**, and the engine's default
`~/.cumbaDataBrowser/metadata-cache.zip` is an image-layer path there (the image user's home is
`/app`). Both the Dockerfile and the entrypoint therefore point `CDISC_METADATA_STORE` at a
mountable path, and the entrypoint says so once, up front, when no store is present. Seed it once:

```sh
docker compose run --rm cli --seed-cache
```

⚠ The store lives in a **subdirectory** of the mount (`/data/metadata/metadata-cache.zip`), never
at `/data` itself: `-d <dir>` stages every regular file it lists with no extension filter, so a
loose store file at the mount root would be uploaded as a study dataset.

⭐ **The image bakes the pinned rule corpus**, and the entrypoint seeds it into
`corej-data/rules` and `corej-data/rules-define` on first start, so the stack validates with no
manual step. ⚠ Seeding is **skipped once a directory holds its own `packages.json`** — put another
corpus version's contents there and it wins permanently. If a run still reports no packages, the
entrypoint says so before the run starts and names the directory it searched.

## What is deliberately not here

- **The rule editor.** Not part of the open-source distribution.

## Test data

`testdata/` holds two checked-in study fixtures — see [`testdata/README.md`](testdata/README.md).

## Licence

AGPL-3.0-only — see [LICENSE](LICENSE).
