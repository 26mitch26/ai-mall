// k6 客服对话链路压测 —— ai-mall（LLM：RAG + Agent）
// 目的：客服对话链路（Milvus 向量检索 + BM25 + RRF + 重排 + LLM 生成）的吞吐与延迟画像。
// 注意：本机 Ollama 单模型 ~57 tok/s，NUM_PARALLEL=2，所以对话链路吞吐上限 ≈ 2~4 QPS，
//       这是单机 LLM 服务的真实形态（并发越大排队越久），不是数据链路那种毫秒级容量。
// 生产方案：LLM 走异步队列 + 流式返回，见面试准备.md 的架构思考。
//
// ── 运行 ──
//   docker run --rm -v "E:\my_project\简历\ai-mall\loadtest:/loadtest" grafana/k6 run /loadtest/k6-chat-link.js
//   建议并发不宜超过 8（超过后排队延迟随并发线性增长）。

import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_AGENT = __ENV.BASE_AGENT || 'http://host.docker.internal:8083';

const appErrors = new Counter('app_errors');

export const options = {
  // 低并发阶梯：观察 LLM 排队特性（2→4→8 VU）
  stages: [
    { duration: '30s', target: 2 },
    { duration: '30s', target: 4 },
    { duration: '30s', target: 8 },
    { duration: '30s', target: 0 },
  ],
  thresholds: {
    http_req_failed: ['rate<0.05'],
    // 聊天走 LLM，P95 放宽到 15s（本地单机推理的现实）
    http_req_duration: ['p(95)<15000'],
  },
  tags: { test: 'ai-mall-chat-link' },
};

const keywords = ['iPhone', '手机', '笔记本', '耳机', '相机'];

export default function () {
  const headers = { 'Content-Type': 'application/json' };
  const kw = keywords[Math.floor(Math.random() * keywords.length)];

  group('agent_customer_chat', () => {
    const r = http.post(
      `${BASE_AGENT}/api/v1/chat`,
      JSON.stringify({ message: `推荐一款${kw}`, sessionId: `k6_chat_${__VU}_${__ITER}` }),
      { headers, timeout: '60s' }
    );
    if (!check(r, { 'chat 200': (x) => x.status === 200 })) appErrors.add(1);
  });

  sleep(0.5);
}