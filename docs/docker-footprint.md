# Docker 本地资源范围与安全说明

## 本地课设默认启动

从仓库根目录运行 `scripts/start-demo.ps1` 时，脚本调用 `infra/docker-compose.yml`，并显式将 Compose project directory 固定为仓库根目录；根目录的 `docker-compose.yml` 也 include 同一份定义。两个开发入口都使用 project name `infra`，复用现有 `infra_*` 命名卷，服务配置只有一个来源。

默认容器服务如下：

| 服务 | 保留原因 |
|---|---|
| Redis | 客服检索 BM25 索引、Agent 工作流和本地商城缓存/状态依赖。 |
| MongoDB | `mall-portal` 的会员浏览历史、收藏和品牌关注 MongoTemplate 功能依赖。 |
| Milvus | 客服 RAG 的向量检索依赖。 |
| etcd、MinIO | Milvus standalone 的元数据和对象存储依赖。 |
| Neo4j | 运维 RCA/知识图谱依赖；客服政策主题图可复用它，但该客服能力是可选项。 |

MySQL 不属于 Compose 服务。会员商城和各业务服务连接 Windows 本机 MySQL 9.7，默认 `127.0.0.1:3306`；初始化走 `scripts/init-local-db.ps1`。Compose 文件里不声明 MySQL 容器或 MySQL 数据卷。生产 Compose 的应用容器默认以 `host.docker.internal:3306` 访问宿主数据库，并加入 `host-gateway` 映射以兼容 Linux Docker Engine。设置 `SPRING_DATASOURCE_URL` 或 `MALL_DB_URL` 可覆盖 URL；用户名依次兼容 `SPRING_DATASOURCE_USERNAME`、`MALL_DB_USERNAME`、`MYSQL_USER`，密码依次兼容 `SPRING_DATASOURCE_PASSWORD`、`MALL_DB_PASSWORD`、`MYSQL_PASSWORD`。容器内不要把 `127.0.0.1` 当宿主机地址。

## 按需服务

本地可以给启动脚本传对应开关；不传时不会启动这些额外服务。

| Compose profile | 脚本开关 | 用途依据 |
|---|---|---|
| `messaging` | `-EnableRabbitMq` | `mall-portal` 的异步取消订单/消息扩展使用 Spring AMQP；开发配置默认关闭 Rabbit listener。 |
| `kafka` | `-EnableKafka` | `agent-ops`、`mall-message` 的 Kafka 扩展依赖；运维 Agent 默认使用本地同步事件总线，不要求启动 broker。 |
| `search` | `-EnableSearch` | 独立 `mall-search` 模块使用 Spring Data Elasticsearch；ES 与 IK setup 保留 ES 8.15 配置。启动脚本只启动依赖服务，不会因此运行 `mall-search` 应用。 |
| `monitoring` | `-EnableMonitoring` | Prometheus/Grafana 观测栈；Prometheus targets 指向本机运行的 Spring 服务。 |

等价 Compose 示例：

```powershell
docker compose --project-directory . -f infra/docker-compose.yml --profile search up -d elasticsearch elasticsearch-ik-setup
docker compose --project-directory . -f infra/docker-compose.yml --profile kafka up -d zookeeper kafka
```

Production Compose 也将 RabbitMQ、Kafka、Elasticsearch 和 Prometheus/Grafana 设为 opt-in profiles。开启 profile 不会自动改变应用的业务开关；例如让 `agent-ops` 用 Kafka 时，还要把 `AIOPS_LOCAL_MODE` 设为 `false` 并确认应用 Kafka 配置与 broker 地址相符。启用 RabbitMQ 消费时，将 `RABBITMQ_LISTENER_AUTO_STARTUP=true`，并核实目标环境账号与虚拟主机。

## 卷与已有数据

开发入口的根目录 Compose 和 `infra/docker-compose.yml` 都显式使用 project name `infra`，脚本入口也固定调用这一 project，因而继续使用当前容器的 `infra_*` 命名卷。生产 Compose 单独使用 project name `ai-mall`。脚本没有运行 `down -v`、卷删除、容器清除或数据库重置命令。`start-demo.ps1` 会复用现有监听端口，并且默认构建不再传 Maven `clean`，减少已运行 JAR 被清理替换时的 Windows 文件锁风险。

本地当前运行容器已由父任务检查，Compose project 为 `infra`，其 Redis、Mongo、MinIO、etcd、Milvus、Neo4j 使用现有 `infra_*` 卷。开发配置继续使用该 project identity，不触发向另一组空卷切换，也不需要做卷迁移。生产 Compose 的 `ai-mall_*` 卷属于另一 project，不会替代或覆盖本地 `infra_*` 数据。

MinIO 继续使用现有 `/minio_data` 挂载目标与 `infra_minio-data` named volume，没有改成 `/data`。父任务检查还发现 MinIO 容器有 `/data` anonymous volume；保持原样，清理与数据处置由父任务核实后记录。不要运行 `docker compose down -v` 或 `docker volume prune`。

## 生产数据库配置

`infra/docker-compose.prod.yml` 不创建 MySQL，也不再让业务容器依赖 MySQL healthcheck。默认连接宿主机 3306；传入现有 `SPRING_DATASOURCE_*`、`MALL_DB_*` 或旧版 `MYSQL_USER`/`MYSQL_PASSWORD` 可继续使用已有部署凭证，配置文件不读取 `.env` 内容。部署时必须确保容器到宿主 MySQL 的网络、防火墙、监听地址、库名与授权账号均已配置。

## 2026-10-03 实际清理结果

已核对容器的 Compose project、工作目录、实际挂载和应用端口，并将商城管理、会员、客服及网关服务切到本机 MySQL 3306 / 原有 Redis 6379。原有 `mall` 数据库未重置。

已删除 5 个闲置容器：`ai-mall-mysql`、`ai-mall-upgrade-verify-mysql`、`ai-mall-upgrade-verify-redis`、`ai-mall-upgrade-verify-rabbit`、`mall-redis-bench`。删除前停止验证容器，对各自数据卷做只读 tar.gz 归档、检查归档目录和 SHA-256；确认卷没有其他容器使用后，删除 `infra_mysql-data` 和 4 个对应的匿名卷。Docker 本地卷占用从约 2.835 GB 降至 2.427 GB，归档另占约 16.4 MB。

备份在本机被 Git 忽略的 `.run/docker-retired-backups/`，检查记录在 `.run/docker-cleanup-result.json`；旧 MySQL 数据保存在 `ai-mall-old-mysql-volume.tar.gz`。恢复时先建立隔离卷，以同一 MySQL 镜像版本恢复归档并检查数据，再决定导出或迁移。不要直接覆盖当前本机数据库。该目录包含数据库私有数据，请保持本地保管。

保留当前 6 个基础设施容器及其实际挂载；保留另一个 `ppt` 项目的 `ai-platform-mysql` / `ai-platform-redis` 和数据卷。共享 MySQL 镜像、用途不明的孤立卷和 MinIO `/data` 匿名卷均保留，未运行全局 prune。
