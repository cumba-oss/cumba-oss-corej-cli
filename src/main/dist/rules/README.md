# `rules/` — the data rule corpus goes here

This directory ships **empty**. The engine needs a rule corpus on disk at runtime;
it is not a Maven dependency and is not vendored in this bundle, because the rules
are versioned and released on their own cadence.

Get it from the matching release of
[cumba-oss-corej-rules](https://github.com/cumba-oss/cumba-oss-corej-rules) —
asset `cumba-oss-corej-rules-<version>.zip` — and put its **contents** here, so
that this directory ends up holding `packages.json` and `rules-*.json` directly:

```sh
unzip cumba-oss-corej-rules-<version>.zip
mv cumba-oss-corej-rules-<version>/rules/* ./rules/
```

⚠ The archive has a top-level `cumba-oss-corej-rules-<version>/rules/` directory,
so unzipping it *into* this one leaves the JSON two levels too deep and the engine
finds nothing. Move the contents, as above.

Alternatively point `COREJ_RULES_DIR` at wherever you unpacked it; the environment
variable outranks the bundle default configured in `cumba-oss-corej-cli.conf`.

## Verifying

There is no list-packages flag. Name a package that cannot exist and read the error —
it names the directory it searched and enumerates what it did find:

```sh
./run.sh -rp __probe__ -d .
# Error: Unknown rule package '__probe__' — no rules-__probe__.json in
#        <bundle>/rules. Available: cdisc-sdtmig-3-4, ...
```

A package short name is its file name without the invariant `rules-` prefix and
`.json` suffix — `rules-cdisc-sdtmig-3-4.json` is `cdisc-sdtmig-3-4`, which is what
`-rp` takes.
