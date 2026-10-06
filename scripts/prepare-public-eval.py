"""Create a frozen, attributed Bitext sample; no LLM or provider credentials needed."""
import csv
import hashlib
import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REVISION = "72ea2203180d14a416b579c657033ecd21e59a11"
SOURCE = f"https://github.com/bitext/customer-support-llm-chatbot-training-dataset/tree/{REVISION}"
# Chinese adaptations retain the request, but replace placeholder entities with local demo entities.
# Expected answers follow AI-Mall policy, never the unrelated vendor's response column.
SPEC = {
    "check_refund_policy": (["帮我查一下你们的退款政策", "帮我查一下退款要多久"], "refund-policy.md", [["七", "7"], ["退款", "退货"]], []),
    "check_payment_methods": (["怎么查看你们支持哪些付款方式", "支持哪些支付方式？"], "payment-faq.md", [["微信"], ["支付宝"]], []),
    "delivery_period": (["想查一下买的东西多久送到", "怎么查看商品多久能收到"], "shipping-policy.md", [["天", "小时", "地区", "地址"]], []),
    "delivery_options": (["查一下配送方式", "有哪些配送方式可以选择"], "shipping-policy.md", [["配送", "送达", "地区"]], []),
    "get_invoice": (["帮我获取我的发票", "我不知道怎么获取订单的发票"], "invoice-warranty.md", [["发票"], ["订单", "邮箱", "开具"]], []),
    "track_order": (["帮我查订单AM-DEMO-PUBLIC-001的位置", "查一下订单AM-DEMO-PUBLIC-001的状态"], None, [["登录", "认证"]], ["已签收", "已发货", "正在配送"]),
    "cancel_order": (["取消订单AM-DEMO-PUBLIC-001时遇到问题", "我取消不了订单AM-DEMO-PUBLIC-001"], None, [["登录", "认证"]], ["已取消", "取消成功"]),
    "get_refund": (["我要申请退款", "我不知道如何才能拿到退款"], None, [["订单", "登录", "申请", "退货"]], ["退款成功", "已退款", "已提交退款"]),
    "contact_human_agent": (["帮我联系真人客服", "我不知道怎么联系人工客服"], None, [["人工", "真人"], ["联系", "入口", "渠道"]], ["已为您转接", "已转接", "已创建工单"]),
    "payment_issue": (["我不知道怎么反馈支付时遇到的问题", "帮我反馈支付问题"], "payment-faq.md", [["支付", "付款", "扣款"], ["订单", "客服", "失败", "重试"]], []),
}

ENGLISH = {
    '七':['seven','7'], '7':['seven'], '退款':['refund'], '退货':['return'],
    '微信':['WeChat'], '支付宝':['Alipay'], '天':['day'], '小时':['hour'], '地区':['region','location'],
    '地址':['address'], '配送':['delivery','shipping'], '送达':['delivery','arrive'],
    '发票':['invoice','bill'], '订单':['order'], '邮箱':['email'], '开具':['issue'],
    '登录':['log in','login','sign in','authenticate'], '认证':['authenticate'],
    '申请':['request','apply'], '人工':['human','live agent','person'], '真人':['human','live agent'],
    '联系':['contact','speak'], '入口':['channel'], '渠道':['channel'],
    '支付':['payment','pay'], '付款':['payment','pay'], '扣款':['charged'], '客服':['support'],
    '失败':['fail'], '重试':['retry'], '工作日':['business day','working day'],
}


def prepare():
    public = ROOT / ".run/bitext-public"
    actual_revision = subprocess.check_output(['git','-C',str(public),'rev-parse','HEAD'],text=True).strip()
    if actual_revision != REVISION:
        raise ValueError('Public repository revision differs from the frozen manifest; checkout the pinned commit first')
    csv_path = next((public / "data").glob("*.csv"))
    raw = csv_path.read_bytes()
    rows = list(csv.DictReader(raw.decode("utf-8-sig").splitlines()))
    out = ROOT / "eval/public"
    out.mkdir(parents=True, exist_ok=True)
    cases = []
    for intent, (translations, source, required, forbidden) in SPEC.items():
        unique = {r["instruction"].strip().lower(): (index, r) for index, r in enumerate(rows, 2) if r["intent"] == intent}
        selected = sorted(unique.values(), key=lambda x: hashlib.sha256(x[1]["instruction"].encode()).hexdigest())[:2]
        for n, (line, row) in enumerate(selected):
            for language, query in (("en", row["instruction"]), ("zh-adapted", translations[n])):
                concepts = required
                if intent == 'check_refund_policy' and n == 1:
                    concepts = [['1至3','1到3','1-3','一至三','一到三','一至三个','1 to 3','one to three'], ['工作日']]
                if language == 'en':
                    concepts = [list(dict.fromkeys(group + [word for term in group for word in ENGLISH.get(term,[])])) for group in concepts]
                cases.append({"id": f"bitext-{intent}-{n}-{language}", "group": f"bitext-{intent}-{n}",
                    "intent": intent, "language": language, "split": "dev" if n == 0 else "test",
                    "query": query, "originalInstruction": row["instruction"], "originalLine": line,
                    "sourceUrl": SOURCE, "license": "CDLA-Sharing-1.0", "modification": "Chinese request adaptation; placeholder order substituted; local policy oracle. Human bilingual review pending." if language != "en" else "Original English input; local policy oracle.",
                    "requiredAny": concepts, "forbidden": forbidden,
                    "relevantSources": [source] if source else [], "category": "public-capability"})
    (out / "bitext-cases.jsonl").write_text("".join(json.dumps(c, ensure_ascii=False) + "\n" for c in cases), encoding="utf-8")
    (out / "LICENSE-bitext.txt").write_bytes((public / "LICENSE.txt").read_bytes())
    manifest = {"provider": "Bitext Innovations", "revision": REVISION, "sourceUrl": SOURCE,
        "csvSha256": hashlib.sha256(raw).hexdigest(), "upstreamRows": len(rows), "sampleRows": 20,
        "evalTasks": len(cases), "oracleVersion":2, "oracleReview":"Before test execution: refund-processing question checks 1-3 business days, not 7-day return eligibility; English accepts English or Chinese concepts.", "intents": list(SPEC), "selection": "Deduplicate normalized instruction per intent; sort by SHA256; first two. Each source row and both languages stay in one split.",
        "limitations": "Synthetic public customer-support data; not real customer logs. Chinese adaptations require bilingual review. No training/fine-tuning. Vendor responses are not local policy ground truth."}
    (out / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"rows": len(rows), "tasks": len(cases), "csvSha256": manifest["csvSha256"]}))


if __name__ == "__main__":
    prepare()
