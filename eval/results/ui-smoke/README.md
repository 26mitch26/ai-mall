# Member customer interface smoke

Verified on 2026-10-07 through the visible in-app browser and the gateway at
`http://localhost:8080`. Member page: `http://127.0.0.1:5174/#/pages/agent/customer`.

The running profile is `eval-low-memory`: BM25 only, no vector store or embedding,
feature reranking disabled, semantic answer cache and memory archival disabled.
The configured generation model is the existing local 0.6B evaluation model;
the seven selected requests required zero generation calls. This run does not
validate the default full semantic deployment or authenticated commerce E2E.
Portal/product/login/order services were not started in this low-memory preview.

The browser displayed the refund question and the conditional one-to-three
business-day answer, the current source revision, and a working full-source
readback. `member-customer.jpg` captures the actual interface.

`anonymous-smoke.json` records seven fixed anonymous regressions: refund timing,
payment methods, refund procedure, same-city timing, honest human guidance,
unsupported knowledge, and login-required order lookup. All seven passed the
limited concept/source/state assertions. These are known regression cases, not
an independent blind semantic benchmark or user-resolution result.

The preceding `anonymous-before-procedure-expansion.json` records 6/7, retaining
the missing next-step failure. Earlier interactive checks also exposed a refusal
for refund timing and a subsequent irrelevant inspection-policy quotation.
Those observations led to the positive BM25 IDF, active-version frequency
filtering, the explicit low-memory reranking opt-out, timing-focused excerpts,
and public procedure query expansion. The evidence threshold was not lowered.

Relevant backend package verification passed 62 mall-common and 131
agent-customer JUnit tests (193 total), with zero failures, errors or skips.
`RagRecallSemanticTest` was excluded; other historical module counts were not
added. Five new tests cover status accuracy, frequent-term scoring, archived
version df isolation, ranking opt-out, and public refund procedure expansion;
an existing policy test gained the common refund timing case (six added tests
relative to the prior 187).

Run the bounded API smoke from the repo root:

```powershell
python scripts/smoke-member-agent.py
```

Actual source content, revision and hash are checked for positive policy cases.
This is a read-only anonymous suite and creates no commerce orders or refunds.

The project resume now emphasizes Agent evaluation: task and trial definitions,
frozen inputs/configuration, development/first-held-out separation, rule and
source checks, tool traces, outcome assertions, failure analysis and controlled
quality/latency/call-count comparisons. Historical evaluation numbers remain
attached to their original experiment scope.
