# Exchange API v1 — vendored contract

`schemas/` and `examples/` are copies of the exchange v1 JSON Schemas and conformance fixtures of
`krt-profit/basetool`, taken at commit `65fe413d5cd04d33df6d9685e1ee1fc5cb172aa6` (2026-09-27):

| Here | There |
| --- | --- |
| `schemas/` | `ingest/src/main/resources/exchange/v1/schemas/` |
| `examples/` | `docs/exchange/examples/v1/` |

`ExchangeContractTest` validates everything the extractor sends against these schemas and decodes
every valid fixture of an answer it reads. The schemas are served at their permanent `$id`
(`https://ingest.profit-base.online/exchange/v1/schemas/<name>.schema.json`); the test maps that
prefix onto this folder.

Within v1 the contract only grows (ADR-0219), so an old copy stays a valid test of what the
extractor sends. Refresh the copy when the extractor starts using something the contract added, and
record the new commit above.
