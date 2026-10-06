# Model selection verification

Date: 2026-10-07. This is interface/transport/guard verification, not an unseen
model-quality benchmark. No real cloud credentials or paid cloud calls were used.

- `catalog.json`: live installed-model metadata; seven installed models, four completion-capable choices.
- `policy-selection.json`: an explicit local selection propagated through chat; policy quotation used no generation.
- `local-probe.json`: the fixed-text local probe was paused by the conservative memory guard, with no model load.
- `policy-regression.json`: all seven existing anonymous policy/source/state checks passed.
- `model-settings.jpg`: the visible model selector and optional cloud input form; no real Key was entered.

Backend package verification: mall-common62 + agent-customer143 = 205 tests;
gateway10 tests passed in its own reactor. Do not add common tests a second time.
The live semantic test was excluded. Admin type checking and builds, and member
H5 builds passed. A 512MB admin type-check heap attempt failed; the serial 768MB
run passed. This was a build resource issue, not an inference quality result.

Mock HTTP cases verify actual outgoing model overrides, return to default,
non-thinking models, short probes, scope isolation, model cache namespaces,
cloud Bearer/body format, bounded cloud responses and sanitized failures.
Gateway cases verify public model entrypoints and retained write/private protection.

See `docs/product/model-selection.md` for user-facing usage and limitations.
