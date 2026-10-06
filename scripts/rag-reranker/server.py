"""Local Qwen3-Reranker-0.6B service. Model files must already exist locally."""
import os
from pathlib import Path

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
from threading import Lock

MODEL_PATH = Path(os.environ.get("RERANKER_MODEL_PATH", "Qwen/Qwen3-Reranker-0.6B"))
if not MODEL_PATH.exists():
    raise RuntimeError(f"Local reranker model is missing: {MODEL_PATH}. Download it separately before starting this service.")

try:
    import torch
    from transformers import AutoModelForCausalLM, AutoTokenizer
except ImportError as exc:
    raise RuntimeError("Install the optional local service requirements before starting") from exc

tokenizer = AutoTokenizer.from_pretrained(str(MODEL_PATH), local_files_only=True, padding_side="left")
tokenizer.pad_token = tokenizer.eos_token
device = "cuda" if torch.cuda.is_available() else "cpu"
model = AutoModelForCausalLM.from_pretrained(str(MODEL_PATH), local_files_only=True,
                                          torch_dtype=torch.float16 if device == "cuda" else torch.float32)
model.to(device)
model.eval()
true_token = tokenizer("yes", add_special_tokens=False).input_ids[0]
false_token = tokenizer("no", add_special_tokens=False).input_ids[0]
prefix = '<|im_start|>system\nJudge whether the Document meets the requirements based on the Query and the Instruct provided. Note that the answer can only be "yes" or "no".<|im_end|>\n<|im_start|>user\n'
suffix = "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
prefix_tokens = tokenizer.encode(prefix, add_special_tokens=False)
suffix_tokens = tokenizer.encode(suffix, add_special_tokens=False)
app = FastAPI(title="Local Qwen3 Reranker")
inference_lock = Lock()


class Request(BaseModel):
    query: str = Field(min_length=1, max_length=2000)
    documents: list[str] = Field(min_length=1, max_length=16)


@app.post("/rerank")
def rerank(request: Request):
    if any(len(text) > 16000 for text in request.documents):
        raise HTTPException(400, "Candidate document length exceeds limit")
    task = "Given a customer-support query, retrieve passages that directly support its answer"
    inputs = [f"<Instruct>: {task}\n<Query>: {request.query}\n<Document>: {text}" for text in request.documents]
    batch = tokenizer(inputs, padding=False, truncation="longest_first", max_length=4096 - len(prefix_tokens) - len(suffix_tokens),
                      return_attention_mask=False)
    batch["input_ids"] = [prefix_tokens + ids + suffix_tokens for ids in batch["input_ids"]]
    batch = tokenizer.pad(batch, padding=True, return_tensors="pt", max_length=4096)
    batch = {key: value.to(device) for key, value in batch.items()}
    with inference_lock, torch.inference_mode():
        # Only the last token is scored. Materializing logits for every document token wastes GBs of VRAM.
        logits = model(**batch, logits_to_keep=1).logits[:, -1, :]
        yes_no = torch.stack([logits[:, false_token], logits[:, true_token]], dim=1)
        scores = torch.softmax(yes_no.float(), dim=1)[:, 1].tolist()
    return {"scores": scores, "model": "Qwen3-Reranker-0.6B"}
