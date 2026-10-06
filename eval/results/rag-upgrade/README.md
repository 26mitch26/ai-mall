# Policy evidence component regression

Date: 2026-10-06. Baseline application source: commit `ecbcbab`.
The same 12 synthetic component cases were exercised before and after the scoped fixes.
Inputs are already-retrieved documents. Cases were used to develop the fix, so this is
a regression result, not an unseen RAG benchmark or end-to-end accuracy claim.

- `component-before.json`: 3/12 passed with the baseline implementation.
- `component-after.json`: 12/12 passed with the candidate implementation.
- Test source: `agent-customer/src/test/java/com/ai/mall/agent/customer/service/agent/PolicyContextBenchmarkTest.java`.
- Production model default unchanged. No actual model calls were required for this validation.

Run from the repository root in PowerShell:

```powershell
$env:MAVEN_OPTS='-Xms96m -Xmx256m'
mvn -o -B -pl agent-customer -am package '-Dtest=!RagRecallSemanticTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx256m'
```

This run passed 62 mall-common and 115 agent-customer JUnit tests with no failures,
errors or skips. The excluded live semantic test was not run. The 12 component
checks are inside one JUnit test and must not be added to the 177 count.
The candidate output is regenerated at `agent-customer/target/policy-context-benchmark.json`.

Frontend checks were run serially with `NODE_OPTIONS=--max-old-space-size=768`:
admin `vue-tsc --build`, admin `vite build`, then member `uni build` (H5).
All completed successfully. No live browser session or authenticated commerce
end-to-end verification was performed in this scoped upgrade.

For interpretation and remaining limitations, see `docs/product/rag-gap-and-upgrade.md`.
