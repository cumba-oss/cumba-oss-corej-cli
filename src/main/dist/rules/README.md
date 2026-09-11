# `rules/` — the data rule corpus

⭐ **This directory ships FULL.** The bundle carries a pinned, hash-verified rule corpus, so a
freshly unzipped distribution validates out of the box. You do not have to install anything here.

It should hold `packages.json` and a set of `rules-*.json` package files, directly — no
subdirectory. The version that shipped is pinned in this repository's `pom.xml` as
`dependency.corej-rules.tag`, and it is the **same corpus the cumba data browser ships**, so the
desktop app, the REST service and this CLI answer identically for the same study.

⚠ Until 2026-09-11 this directory shipped **empty**, with instructions to unzip a release asset in
by hand. If you are reading that older README in a bundle, it is that bundle's version that is out
of date, not this one.

## What is in it, and what is not

The corpus comes from [`cumba-oss-corej-rules`](https://github.com/cumba-oss/cumba-oss-corej-rules),
which is the public corpus. Two things to know about its scope:

- **No CORE rule family.** Those rules are externally sourced and may not be redistributed as a
  set, so no cumba bundle ships them — the internal CLI and REST bundles pin this same public
  release for exactly that reason. Everything else is here.
- **The DRAFT family IS present, for now.** Release `v0.3.1` ships 47 packages, 15 of them
  `rules-draft-*`. These are **parked candidate rules**: fully generated, schema-validated and
  executed by their test scenarios upstream, but not promoted, so their content can change and
  they are not a basis for a regulatory submission. Select them deliberately or not at all — they
  are never selected for you. A future corpus release drops them from the published archive
  (`rules-draft-tig-1-0` is the only TIG package outside the CORE family, so TIG coverage goes
  with them), at which point a bundle built against that release will simply not have them.

## Replacing it with a different corpus version

Two ways. Neither requires rebuilding the bundle.

**Point at another directory** — nothing is copied, and the bundle stays as shipped:

```sh
COREJ_RULES_DIR=/srv/corej/rules-0.4.0 ./run.sh -d /study ...
```

`COREJ_RULES_DIR` outranks the `-Dcorej.rules.dir` system property, which outranks this directory —
the precedence `cumba-oss-corej-cli.conf` beside the launcher configures.

**Or replace the contents in place**, from a `cumba-oss-corej-rules` release:

```sh
unzip cumba-oss-corej-rules-<version>.zip
rm -f rules/rules-*.json rules/packages.json
mv cumba-oss-corej-rules-<version>/rules/* ./rules/
```

⚠ The archive has a top-level `cumba-oss-corej-rules-<version>/rules/` directory, so unzipping it
*into* this one leaves the JSON two levels too deep and the engine finds nothing. Move the
contents, as above.

⚠ Delete the old `rules-*.json` first. The engine reads whatever packages are present, so leftovers
from the previous corpus stay selectable and silently mix two versions.

⚠ **Do not delete this directory** even if you point `COREJ_RULES_DIR` elsewhere. Its README is
harmless, and an empty `rules/` is fine — but see `rules-define/README.md` beside it, where a
missing directory is a hard error rather than an empty one.

## Verifying what is loaded

There is no list-packages flag. Name a package that cannot exist and read the error — it names the
directory it searched and enumerates what it did find:

```sh
./run.sh -rp __probe__ -d .
# Error: Unknown rule package '__probe__' — no rules-__probe__.json in
#        <bundle>/rules. Available: cdisc-sdtmig-3-4, ...
```

A package short name is its file name without the invariant `rules-` prefix and `.json` suffix —
`rules-cdisc-sdtmig-3-4.json` is `cdisc-sdtmig-3-4`, which is what `-rp` takes.

⚠ An **empty** `Available:` list means this directory lost its corpus — that is the failure this
probe is worth running for.
