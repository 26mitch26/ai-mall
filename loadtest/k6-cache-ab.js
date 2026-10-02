// k6 语义缓存 A/B 压测 —— agent-customer 客服对话链路
// 目的：验证 LLM 回答语义缓存的效果。
//   A（缓存开）：SEMANTIC_CACHE_ENABLED=true  重复/同义问句直接命中缓存 → 延迟从 s 级掉到 ms 级
//   B（缓存关）：SEMANTIC_CACHE_ENABLED=false 每轮都走完整 RAG + LLM 生成
// 判定口径（同一脚本、同一并发、同一批问句，只切换环境变量重启服务）：
//   - http_req_duration p(95)：A 应远小于 B
//   - app 侧命中率：A 压测后 GET /api/v1/knowledge/cache/stats 看 hitRate
//
// 问句构成：① 同义改写族（触发语义/向量命中）+ ② 稳定复述（触发精确/哈希命中），
// 模拟"客服问题高度重复"的真实负载。
//
// 运行（Docker 跑 k6，脚本挂载 loadtest）：
//   docker run --rm -v "E:\my_project\简历\ai-mall\loadtest:/loadtest" grafana/k6 run /loadtest/k6-cache-ab.js
//   若 k6 装在宿主机：k6 run -e BASE_AGENT=http://localhost:8083 loadtest/k6-cache-ab.js

import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_AGENT = __ENV.BASE_AGENT || 'http://host.docker.internal:8083';
const appErrors = new Counter('app_errors');

export const options = {
  // 低并发短时长即可：缓存路径的收益在"命中后零 LLM 推理"，不需要大并发（大并发会拉高 Ollama 排队噪声）
  stages: [
    { duration: '20s', target: 4 },
    { duration: '1m', target: 4 },
    { duration: '20s', target: 0 },
  ],
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<15000'],
  },
  tags: { test: 'ai-mall-cache-ab' },
};

// ① 语义命中族：同一意图的不同问法（互相之间是"同义改写"）
const paraphraseGroups = [
  ['推荐一款iPhone', '帮我推荐苹果手机', 'iPhone买哪款好'],
  ['退货流程是什么', '怎么申请退货', '要退货怎么做'],
  ['发货要多久', '什么时候能发货', '物流几天能到'],
];

// ② 精确命中族：完全相同的问句反复出现
const repeated = ['运费多少钱', '支持哪些支付方式'];

export default function () {
  const headers = { 'Content-Type': 'application/json' };
  // 每个 VU 选一组同义改写 + 一个重复问句，混合生成负载
  const family = paraphraseGroups[__VU % paraphraseGroups.length];
  const q = Math.random() < 0.5
    ? family[__ITER % family.length]          // 同义改写族内轮转 → 语义命中
    : repeated[Math.floor(Math.random() * repeated.length)]; // 重复问句 → 精确命中

  group('agent_chat', () => {
    const r = http.post(
      `${BASE_AGENT}/api/v1/chat`,
      JSON.stringify({ message: q, sessionId: `k6_ab_${__VU}_${__ITER}` }),
      { headers, timeout: '60s' }
    );
    if (!check(r, { 'chat 200': (x) => x.status === 200 })) appErrors.add(1);
  });

  sleep(0.3);
}