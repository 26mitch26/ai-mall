// k6 压测：agent-customer 智能客服对话链路（/api/v1/chat）
// 当前知识库为空（agent-customer 未提供文档导入端点，无法灌数据），
// 因此本压测测量的是：输入净化 → Milvus 向量检索 + Redis BM25 → RRF/重排 → 拒答护栏 的完整链路，
// 不包含 LLM 生成环节（检索为空时护栏直接拒答，不会调用大模型）。
//
// 运行（k6 用 Docker 跑时）：
//   docker run --rm -v e:\my_project\resume\ai-mall\loadtest:/scripts grafana/k6 run /scripts/k6-chat-only.js
// 若 k6 装在宿主机：
//   k6 run -e BASE_AGENT=http://localhost:8083 loadtest/k6-chat-only.js

import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_AGENT = __ENV.BASE_AGENT || 'http://host.docker.internal:8083';
const appErrors = new Counter('app_errors');

export const options = {
  // 单次对话实测 ~1.1s（检索+护栏），VU 过高只会排队，取 10/30 两档
  stages: [
    { duration: '20s', target: 10 },
    { duration: '1m', target: 10 },
    { duration: '20s', target: 30 },
    { duration: '1m', target: 30 },
    { duration: '20s', target: 0 },
  ],
  thresholds: {
    http_req_failed: ['rate<0.05'],
  },
};

const questions = [
  'what is the return policy',
  'how long does shipping take',
  'do you have phones in stock',
  'how to track my order',
];

export default function () {
  const q = questions[Math.floor(Math.random() * questions.length)];
  const sessionId = `k6_${__VU}_${Math.floor(__ITER / 10)}`;

  group('agent_chat', () => {
    const r = http.post(
      `${BASE_AGENT}/api/v1/chat`,
      JSON.stringify({ message: q, sessionId }),
      { headers: { 'Content-Type': 'application/json' }, timeout: '120s' }
    );
    if (!check(r, { 'chat 200': (x) => x.status === 200 })) appErrors.add(1);
  });

  sleep(0.2);
}
