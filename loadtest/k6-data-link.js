// k6 数据链路压测 —— ai-mall（不含 LLM）
// 目的：商品综合搜索 / 商品详情 / ES 全文搜索 三条纯数据链路的容量画像。
//
// 为什么把客服对话拆出去：LLM 生成是秒级 + 有限的（本机 Ollama 57 tok/s），
// 与毫秒级数据接口混压会让 LLM 请求排队堆积、拉爆整体指标，也掩盖数据链路真实容量。
// 客服对话单独用 k6-chat-link.js 压。
//
// ── 运行（Docker Desktop 里 k6 容器访问宿主机用 host.docker.internal）──
//   docker run --rm -v "E:\my_project\简历\ai-mall\loadtest:/loadtest" grafana/k6 run /loadtest/k6-data-link.js
//   并发可用 --vus/--duration 覆盖。

import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_PORTAL = __ENV.BASE_PORTAL || 'http://host.docker.internal:8087';
const BASE_SEARCH = __ENV.BASE_SEARCH || 'http://host.docker.internal:8082';

const appErrors = new Counter('app_errors');

export const options = {
  stages: [
    { duration: '30s', target: 20 },
    { duration: '1m', target: 50 },
    { duration: '1m', target: 100 },
    { duration: '30s', target: 200 },
    { duration: '30s', target: 0 },
  ],
  thresholds: {
    http_req_failed: ['rate<0.02'],
    http_req_duration: ['p(95)<500'],
  },
  tags: { test: 'ai-mall-data-link' },
};

const keywords = ['iPhone', '手机', '笔记本', '耳机', '相机'];

export default function () {
  const headers = { 'Content-Type': 'application/json' };
  const kw = keywords[Math.floor(Math.random() * keywords.length)];

  group('portal_product_search', () => {
    const r = http.get(`${BASE_PORTAL}/product/search?keyword=${encodeURIComponent(kw)}&pageSize=10`, { headers });
    if (!check(r, { 'product search 200': (x) => x.status === 200 })) appErrors.add(1);
  });

  group('portal_product_detail', () => {
    const r = http.get(`${BASE_PORTAL}/product/detail/1`, { headers });
    if (!check(r, { 'product detail 200': (x) => x.status === 200 })) appErrors.add(1);
  });

  group('search_es_product', () => {
    const r = http.get(`${BASE_SEARCH}/esProduct/search?keyword=${encodeURIComponent(kw)}&pageSize=10`, { headers });
    if (!check(r, { 'es search 200': (x) => x.status === 200 })) appErrors.add(1);
  });

  sleep(0.2);
}