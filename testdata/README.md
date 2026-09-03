# Test data fixtures

Two checked-in study fixtures used by `BlankCellFormatIndependenceTest`, which asserts
that a blank cell resolves by column type identically across every input format.

| File | Format |
|---|---|
| `xpt/01_plain/adsl.xpt` | SAS v5 transport |
| `sas7bdat/01_plain/adsl.sas7bdat` | SAS7BDAT |

Both are ADSL from **CDISCPILOT01**, the public CDISC pilot study — not real study data.
They carry blank character cells (`DISCONFL`, `DSRAEFL`, `DTHFL`, `DCSREAS`) and missing
numeric cells (`BMIBL`, `WEIGHTBL`) in both encodings, which is exactly what the test counts.

Resolved from the repository root via the `repoRoot` system property, which surefire sets.
