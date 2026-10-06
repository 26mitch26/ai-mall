# Local Qwen3 reranker

This optional service scores `(query, passage)` pairs with the actual Qwen3-Reranker-0.6B model. It never downloads model files: set `RERANKER_MODEL_PATH` to a directory that already contains the model and tokenizer files. If the path is absent, startup fails clearly and Java keeps using its feature-based fallback.

```powershell
hf download Qwen/Qwen3-Reranker-0.6B --local-dir .run/reranker-model --include '*.json' '*.safetensors' 'merges.txt' --max-workers 2
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r scripts/rag-reranker/requirements.txt
$env:RERANKER_MODEL_PATH = (Resolve-Path '.run/reranker-model').Path
uvicorn server:app --app-dir scripts/rag-reranker --host 127.0.0.1 --port 8092
```

Set `ai.rag.reranker.enabled=true` in the Spring application and keep `ai.rag.reranker.url` at its default `http://localhost:8092/rerank` (or point it to this local service). The service is CPU-capable; CUDA is used when PyTorch detects it. Java falls back to feature scoring on timeout, an invalid response, or service failure.

Model source and license: [Qwen official model card](https://huggingface.co/Qwen/Qwen3-Reranker-0.6B), Apache-2.0. The service bounds input size and serializes GPU inference, and scores only the last token to avoid allocating logits for every passage token. The current small-policy benchmark does not justify enabling it by default; see [validation](../../docs/agent-upgrade-validation.md).
