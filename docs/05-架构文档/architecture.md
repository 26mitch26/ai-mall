# AI-Mall 架构设计文档

## 1. 系统概述

AI-Mall 是一个 AI 增强的电商系统，基于 Spring Boot 3.5 构建，集成智能客服、智能运维、自动化测试三大 AI Agent 能力。

### 1.1 设计目标

- **完整性**：提供完整的电商功能（商品、订单、用户）
- **智能化**：通过 AI Agent 提升用户体验和运维效率
- **可测试**：内置自动化测试能力
- **可部署**：Docker Compose 一键部署

### 1.2 技术选型

| 层次 | 技术 | 选型理由 |
|------|------|----------|
| 核心框架 | Spring Boot 3.5 | 生态成熟，社区活跃 |
| ORM | MyBatis Plus | 简化数据库操作 |
| 缓存 | Redis | 高性能，支持分布式 |
| 消息队列 | Kafka | 高吞吐，支持事务 |
| 向量数据库 | Milvus | 国产，性能好 |
| 知识图谱 | Neo4j | 图数据库，适合关系查询 |
| 大模型 | MiMo + OpenAI | 国产模型 + 兜底方案 |

---

## 2. 架构设计

### 2.1 整体架构

```
┌──────────────────────────────────────────────────────────────┐
│                      客户端层                                  │
│              Web / Mobile / API Consumer                      │
└───────────────────────────┬──────────────────────────────────┘
                            │
┌───────────────────────────▼──────────────────────────────────┐
│                      网关层                                    │
│                   AI Gateway (8080)                           │
│              路由转发 · 认证授权 · 限流熔断                      │
└───────────────────────────┬──────────────────────────────────┘
                            │
┌───────────────────────────▼──────────────────────────────────┐
│                      业务层                                    │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐     │
│  │ Mall     │  │ Agent    │  │ Agent    │  │ Agent    │     │
│  │ Admin    │  │ Customer │  │ Ops      │  │ Test     │     │
│  │ (8081)   │  │ (8083)   │  │ (8084)   │  │ (8085)   │     │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘     │
└───────────────────────────┬──────────────────────────────────┘
                            │
┌───────────────────────────▼──────────────────────────────────┐
│                      基础设施层                                 │
│  ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐     │
│  │MySQL │ │Redis │ │Kafka │ │Milvus│ │Neo4j │ │ES    │     │
│  └──────┘ └──────┘ └──────┘ └──────┘ └──────┘ └──────┘     │
└──────────────────────────────────────────────────────────────┘
```

### 2.2 模块依赖

```
ai-mall (父POM)
├── mall-core (电商核心)
│   ├── mall-common (通用组件)
│   ├── mall-mbg (数据库操作)
│   ├── mall-security (安全认证)
│   ├── mall-admin (后台管理)
│   ├── mall-portal (前台商城)
│   ├── mall-search (商品搜索)
│   └── mall-demo (示例代码)
├── ai-gateway (统一入口)
├── agent-customer (智能客服)
├── agent-ops (智能运维)
└── agent-test (自动化测试)
```

---

## 3. 核心模块设计

### 3.1 智能客服 Agent

**架构**：ReAct 模式（Thought → Action → Observation）

```
用户提问
  ↓
[意图识别] → 商品咨询 / 订单问题 / 售后
  ↓
[ReAct 循环] → Thought(分析) → Action(调用工具) → Observation(结果)
  ↓
[答案生成] → 调用 MiMo 大模型
  ↓
[记忆存储] → Redis 短期记忆
  ↓
返回答案
```

**核心组件**：
- ReActAgent：ReAct 循环控制
- ToolRegistry：工具注册和调用
- MemoryService：对话记忆管理
- RagService：RAG 检索

### 3.2 智能运维 Agent

**架构**：多 Agent 协作 + 事件驱动

```
Prometheus 异常检测
  ↓
[告警事件] → Kafka(aiops.alerts)
  ↓
MonitorAgent → 多算法投票 → 过滤误报
  ↓
RCAAgent → 知识图谱 → 贝叶斯推理 → 定位根因
  ↓
HealAgent → Playbook 匹配 → 安全护栏 → 分级执行
  ↓
ChangeAgent → 风险评分 → 审批门控 → 修复
  ↓
[审计日志] → Kafka(aiops.audit)
```

**核心组件**：
- MonitorAgent：异常检测
- RCAAgent：根因分析
- HealAgent：故障自愈
- ChangeAgent：变更审批
- EventBus：事件总线
- KnowledgeGraphService：知识图谱

### 3.3 自动化测试 Agent

**架构**：测试生成 + 执行 + 报告

```
[API 文档解析] → SpringDoc/OpenAPI
  ↓
[用例生成] → TestCaseGenerator
  ↓
[执行引擎] → TestExecutor
  ↓
[报告生成] → TestReportGenerator
  ↓
[覆盖度分析] → CoverageAnalyzer
```

**核心组件**：
- TestCaseGenerator：测试用例生成
- TestExecutor：测试执行
- TestReportGenerator：报告生成
- TestAgent：测试编排

---

## 4. 数据流设计

### 4.1 一次完整的故障处理

```
1. Prometheus 检测到 order-service CPU 95%
2. MonitorAgent：3-Sigma + EWMA 投票确认异常
3. RCAAgent：查知识图谱，发现 order-service 有新部署
4. HealAgent：匹配 rollback Playbook，dry-run 通过
5. ChangeAgent：风险评分 0.16，自动审批
6. 执行回滚，5 分钟恢复
```

### 4.2 一次完整的客服对话

```
1. 用户问："这个手机壳有什么颜色？"
2. ReActAgent Thought：需要查询商品信息
3. Action：调用 search_products 工具
4. Observation：返回商品列表
5. Final Answer：调用 MiMo 生成答案
6. 存储到 Redis 短期记忆
```

---

## 5. 部署架构

### 5.1 Docker Compose 编排

```yaml
services:
  mysql: 3306
  redis: 6379
  kafka: 9092
  neo4j: 7474/7687
  milvus: 19530
  elasticsearch: 9200
  prometheus: 9090
  grafana: 3000
```

### 5.2 应用部署

```
ai-gateway: 8080 (统一入口)
mall-admin: 8081 (后台管理)
mall-portal: 8082 (前台商城)
agent-customer: 8083 (智能客服)
agent-ops: 8084 (智能运维)
agent-test: 8085 (自动化测试)
```

---

## 6. 安全设计

### 6.1 认证授权

- Spring Security + JWT
- 动态权限控制
- Token 自动刷新

### 6.2 Agent 安全

- 熔断器：防止级联故障
- 限流器：防止请求过载
- 审批门控：高风险操作需审批

---

## 7. 监控设计

### 7.1 指标监控

- Prometheus 采集指标
- Grafana 可视化
- 自定义业务指标

### 7.2 日志监控

- ELK 日志收集
- 结构化日志
- 链路追踪
