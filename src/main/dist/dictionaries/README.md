# `dictionaries/` — the external-dictionary store

This directory ships **empty** and is the bundle's dictionary store root, wired up
in `cumba-oss-corej-cli.conf` as `corej.dictionariesDir`.

External dictionaries (MED-RT, UNII, neoplasm, MedDRA, WHODrug, SNOMED, LOINC) are
licensed separately and are never redistributed here. Populate the store with the
CLI's own maintenance mode, which downloads the credential-free sets and exits:

```sh
./run.sh --install-dictionaries
```

Licensed sets are installed from local copies you already hold — see
`--install-dictionaries` in `./run.sh --help`.

⚠ **Do not delete this directory.** A *configured* dictionary store that does not
exist is a deliberate hard error in the engine, so removing it makes every validation
run fail. ⚠ The store is resolved when a run sets up, **not** at process startup, so
the symptom is a failing run rather than a tool that will not start — do not go looking
in startup output for it.

An empty store is fine: dictionary-backed rules then report as un-answerable rather
than failing the run.

`COREJ_DICTIONARIES_DIR` overrides the bundle default.
