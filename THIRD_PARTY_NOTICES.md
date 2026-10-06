# 第三方代码说明

AI-Mall 包含由开源 mall 生态项目演进而来的部分电商领域代码与前端结构。
相关原始代码依照 Apache License 2.0 使用；仓库中保留对应 LICENSE 文件。

当前项目已独立维护以下内容：

- Spring Boot 3.5 / JDK 21 多模块工程与统一网关
- 智能客服、智能运维、自动化测试三类 AI Agent
- 本机 MySQL 初始化、演示数据和一键启动链路
- Vue 3 后台与 UniApp 商城的本地接口、品牌和演示账号
- 安全、缓存、检索、可观测性与压力测试改造

运行时不要求访问原作者网站、公众号、二维码或在线体验账号。

可选本地重排服务使用 Qwen 团队发布的 Qwen3-Reranker-0.6B，模型许可为 Apache-2.0，
模型来源与推理说明见 https://huggingface.co/Qwen/Qwen3-Reranker-0.6B 。
权重由使用者单独下载，不包含在本仓库的 Git 文件中。

公开评测样本来自 Bitext Innovations 的 customer-support-llm-chatbot-training-dataset，
固定版本 72ea2203180d14a416b579c657033ecd21e59a11，许可为 CDLA-Sharing-1.0。
`eval/public/bitext-cases.jsonl` 包含原始请求及已标注的中文适配，完整许可文本为
`eval/public/LICENSE-bitext.txt`；数据来源、修改与哈希见 `eval/public/manifest.json`。
这份数据的许可独立于项目代码许可。

低成本评测使用官方 Qwen/Qwen3-0.6B-GGUF 的 Q8_0 权重（Apache-2.0）：
https://huggingface.co/Qwen/Qwen3-0.6B-GGUF 。权重仅在本机下载并导入 Ollama，
不随本仓库分发；不默认替换原有生产模型。
