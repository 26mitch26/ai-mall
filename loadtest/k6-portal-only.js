// k6 压测：mall-portal 商品浏览链路（MySQL + Redis）
// 只打商品搜索与详情，避免 ES / 客服链路未就绪时污染聚合指标。
//
// 运行（k6 用 Docker 跑时，宿主机服务用 host.docker.internal 访问）：
//   docker run --rm -v e:\my_project\resume\ai-mall\loadtest:/scripts grafana/k6 run /scripts/k6-portal-only.js
// 若 k6 装在宿主机：
//   k6 run -e BASE_PORTAL=http://localhost:8087 loadtest/k6-portal-only.js

import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_PORTAL = __ENV.BASE_PORTAL || 'http://host.docker.internal:8087';
const appErrors = new Counter('app_errors');

export const options = {
  // 阶梯加压到 200 VU，观察 DB 连接池（max-active 20）的拐点
  stages: [
    { duration: '30s', target: 20 },
    { duration: '1m', target: 50 },
    { duration: '1m', target: 100 },
    { duration: '30s', target: 200 },
    { duration: '20s', target: 0 },
  ],
  thresholds: {
    http_req_failed: ['rate<0.05'],
  },
};

const keywords = ['phone', 'macbook', 'xiaomi'];
const productIds = [1, 2, 3];

export default function () {
  const kw = keywords[Math.floor(Math.random() * keywords.length)];

  group('product_search', () => {
    const r = http.get(`${BASE_PORTAL}/product/search?keyword=${kw}&pageSize=10`);
    if (!check(r, { 'search 200': (x) => x.status === 200 })) appErrors.add(1);
  });

  group('product_detail', () => {
    const id = productIds[Math.floor(Math.random() * productIds.length)];
    const r = http.get(`${BASE_PORTAL}/product/detail/${id}`);
    if (!check(r, { 'detail 200': (x) => x.status === 200 })) appErrors.add(1);
  });

  sleep(0.3);
}
