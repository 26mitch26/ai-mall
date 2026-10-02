// k6 全链路压测脚本 —— ai-mall
// 目的：测量真实后端栈（MySQL+Redis / Elasticsearch / Milvus+RAG+LLM）在并发下的吞吐与延迟，
//      输出 QPS、P95/P99 延迟、错误率，作为面试"系统能扛多少并发"的实测依据。
//
// 为什么直连服务端口而非 8080 网关：
//   本地 ai-gateway 的 application.yml 用 StripPrefix=0（路径原样转发），而下游控制器路径
//   不带 /api、/search、/agent/customer 前缀，经网关会 404。直连服务测的是真实后端容量
//   （DB 连接池、Redis、ES、Milvus、LLM 延迟），网关只是透传代理，不影响容量结论；
//   若要在网关后测，用 infra/k8s 的 StripPrefix=1 部署即可。
//
// ── 前置 ──────────────────────────────────────────────────────────────
// 1) 起中间件：在 ai-mall 根目录
//      docker compose up -d
// 2) 起服务（直连端口）：
//      mvn -pl mall-portal     spring-boot:run     # 8087
//      mvn -pl mall-search     spring-boot:run     # 8082
//      mvn -pl agent-customer  spring-boot:run      # 8083  （需 MIMO_API_KEY/OPENAI_API_KEY，否则对话降级/拒答）
// 3) 装 k6（Windows，任选其一）：
//      winget install k6 --source winget
//      # 或  choco install k6
//      # 或纯 Docker： docker run grafana/k6
//
// ── 运行 ──────────────────────────────────────────────────────────────
// 默认（匿名，商品搜索/详情 + ES搜索 走 DB/Redis/ES；客服对话走 RAG+LLM 但可能降级）：
//   k6 run loadtest/k6-full-link.js
//
// 带登录鉴权（若商品接口要求 JWT，先用 POST /auth/register 注册一个账号）：
//   k6 run -e USE_AUTH=true -e USER=test -e PASS=test123 loadtest/k6-full-link.js
//
// 只压"商品 + ES"两条纯数据链路（不要 LLM，数字最干净）：
//   把下方 default() 里 group('agent_customer_chat', ...) 整段注释掉即可。
//
// 调并发量级：用 --vus / --duration 覆盖，例如先小压验证：
//   k6 run --vus 20 --duration 1m loadtest/k6-full-link.js

import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Counter } from 'k6/metrics';

// 在 Docker Desktop 里用 k6 容器跑时，容器内 localhost 指容器自身；
// 需用 host.docker.internal 访问宿主机，或运行时用 -e BASE_PORTAL=... 覆盖。
const BASE_PORTAL = __ENV.BASE_PORTAL || 'http://host.docker.internal:8087';
const BASE_SEARCH = __ENV.BASE_SEARCH || 'http://host.docker.internal:8082';
const BASE_AGENT = __ENV.BASE_AGENT || 'http://host.docker.internal:8083';
const USE_AUTH = (__ENV.USE_AUTH || 'false') === 'true';

const appErrors = new Counter('app_errors');

export const options = {
  // 阶梯加压：找拐点。Tomcat 默认 200 线程/实例，DB 池 10~20，先看 50/100/200 三档。
  stages: [
    { duration: '30s', target: 20 },
    { duration: '1m', target: 50 },
    { duration: '1m', target: 100 },
    { duration: '30s', target: 200 },
    { duration: '30s', target: 0 },
  ],
  thresholds: {
    http_req_failed: ['rate<0.05'], // 整体错误率 < 5%
    http_req_duration: ['p(95)<2000'], // P95 < 2s（聊天走 LLM 易超，可放宽或注释聊天组）
  },
  tags: { test: 'ai-mall-full-link' },
};

const keywords = ['iPhone', '手机', '笔记本', '耳机', '相机'];

// 可选登录：拿到 JWT 后所有请求带 Bearer。失败则降级为匿名继续。
export function setup() {
  if (!USE_AUTH) return { token: null };
  const res = http.post(
    `${BASE_PORTAL}/auth/login`,
    JSON.stringify({ username: __ENV.USER || 'test', password: __ENV.PASS || 'test123' }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  if (res.status !== 200) {
    console.warn(`登录失败(status=${res.status})，请确认 USER/PASS 或先 POST ${BASE_PORTAL}/auth/register。将匿名继续。`);
    return { token: null };
  }
  const data = res.json('data') || {};
  const token = data.token;
  const head = data.tokenHead || 'Bearer ';
  return { token: token ? `${head}${token}` : null };
}

export default function (data) {
  const authHeader = data.token ? { Authorization: data.token } : {};
  const headers = Object.assign({ 'Content-Type': 'application/json' }, authHeader);
  const kw = keywords[Math.floor(Math.random() * keywords.length)];

  // 1) 商品综合搜索：MySQL + Redis 缓存路径（真实档下缓存提升 12.83x 的来源）
  group('portal_product_search', () => {
    const r = http.get(`${BASE_PORTAL}/product/search?keyword=${encodeURIComponent(kw)}&pageSize=10`, { headers });
    if (!check(r, { 'product search 200': (x) => x.status === 200 })) appErrors.add(1);
  });

  // 2) 商品详情：MySQL + Redis
  group('portal_product_detail', () => {
    const r = http.get(`${BASE_PORTAL}/product/detail/1`, { headers });
    if (!check(r, { 'product detail 200': (x) => x.status === 200 })) appErrors.add(1);
  });

  // 3) ES 商品搜索：Elasticsearch（中文分词 + 同义词）
  group('search_es_product', () => {
    const r = http.get(`${BASE_SEARCH}/esProduct/search?keyword=${encodeURIComponent(kw)}&pageSize=10`, { headers });
    if (!check(r, { 'es search 200': (x) => x.status === 200 })) appErrors.add(1);
  });

  // 4) 智能客服对话：Milvus 向量检索 + BM25 + RRF + 重排 + LLM 生成
  //    authorization 可选；未登录时涉及用户数据的工具会 fail-closed，RAG 路径仍跑。
  //    未配 MIMO_API_KEY 时本组可能大量失败/降级——那测的是"护栏拒答"路径，不是模型吞吐。
  group('agent_customer_chat', () => {
    const r = http.post(
      `${BASE_AGENT}/api/v1/chat`,
      JSON.stringify({ message: `推荐一款${kw}`, sessionId: `k6_${__VU}_${__ITER}` }),
      { headers, timeout: '60s' }
    );
    if (!check(r, { 'chat 200': (x) => x.status === 200 })) appErrors.add(1);
  });

  sleep(0.5);
}
