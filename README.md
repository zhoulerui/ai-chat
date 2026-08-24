# 神谕百科(Oracle of Games)—— 游戏知识问答系统

基于 Spring Boot + Spring AI + Vue3 的游戏百科智能问答:知识库(RAG)+ 智能问答双核心,针对 **2核2G 低配服务器** 设计(单 jar 部署,本地 ONNX 嵌入,免 Ollama / Postgres / Redis)。前端构建产物直接打进后端 jar,单进程即可对外提供服务。

## 特性

- **SSE 流式输出**:打字机效果,支持多轮对话
- **多会话管理**:会话落库(重启不丢),切换 / 重命名 / 删除,会话可绑定游戏
- **RAG 知识库**:MySQL 持久化 + SimpleVectorStore 内存检索,按游戏维度隔离;条目列表(向量化状态)/关键词高亮/详情弹窗/删除
- **本地嵌入**:ONNX bge-small-zh(纯 Java,免装 Ollama)
- **URL 一键入库**(jsoup 抓取 + SSRF 防护);本地支持 txt / md / pdf / docx / xlsx
- **Agent 工具调用(Function Calling)**:模型按需自动调用真实工具——按游戏名检索知识库 / 列游戏清单 / 查游戏基本信息(Spring AI `@Tool`)
- **模型档位切换**:⚡ 快速 / 🧠 深度思考 / 🚀 Pro 深度思考,思考档流式展示中文推理过程(灰色折叠区)
- **参考来源侧栏**:条目名 + 相似度百分比 + 可展开完整正文
- **Swagger 文档**:`/swagger-ui.html` 交互式调试全部接口
- Vue3 + Element Plus 响应式 UI(PC / 移动端),支持停止生成 / 复制 / 语音对话
- 单 jar 部署,`mem_limit: 400m` 即可运行

## 目录结构

```
ai-chat/
├── backend/                          # Spring Boot 后端
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/example/aichat/
│       │   ├── AiChatApplication.java
│       │   ├── controller/           # ChatController(SSE+会话)/ KbController(知识库)
│       │   ├── service/              # ConversationService(多会话,接口+Impl)
│       │   ├── rag/                  # RagService + 本地嵌入 LocalBgeEmbeddingModel
│       │   ├── tool/                 # Agent 工具(@Tool:检索知识库/列游戏/查信息)
│       │   ├── mapper/  entity/  dto/  util/(DocParser/UrlImporter)
│       └── resources/
│           ├── application.yml       # 配置(密钥仅经环境变量注入,仓库不含真实凭据)
│           ├── schema.sql            # 建表 DDL(启动自动执行)
│           └── static/               # 前端构建产物(vite build 生成)
├── frontend/                         # Vue3 前端
│   ├── src/router/index.js           # 路由:/chat /kb(hash 模式)
│   ├── src/App.vue                   # 统一入口(Tab ↔ 路由 + 主题)
│   └── src/components/               # Chat / KnowledgeBase / Markdown
├── docs/                             # 设计文档
└── docker-compose.yml                # 部署编排(模型目录挂载)
```

## 环境要求

| 组件      | 版本            |
| ------- | ------------- |
| JDK     | 17+(实测 21 可跑) |
| Maven   | 3.6+          |
| Node.js | 18+           |
| 内存      | 运行期 ≤ 400MB   |

## 快速开始

### 1. 配置模型 API(必做)

API Key **通过环境变量注入**,不写死在代码或配置文件(避免密钥进入版本库)。

Docker 部署时把变量写进项目根目录的 `.env`(复制 `.env.example` 为 `.env` 填写真实值,`.env` 已被 `.gitignore` 忽略):

```bash
# .env
AI_API_KEY=sk-你的key
MYSQL_USER=root
MYSQL_PASSWORD=你的数据库密码
```

本地裸跑 `java -jar` 时(不经过 compose),用 shell 导出:

```bash
export AI_API_KEY=sk-你的key        # DeepSeek / 其他 OpenAI 兼容服务
export AI_MODEL=deepseek-chat       # 可选,默认 deepseek-chat
```

对应 `application.yml` 中的配置(均支持环境变量覆盖):

```yaml
spring:
  ai:
    deepseek:
      api-key: ${AI_API_KEY:}
      chat:
        options:
          model: ${AI_MODEL:deepseek-chat}
```

> 环境变量 `AI_API_KEY` 未设置时,应用启动会因缺少凭据而无法调用模型——这是有意为之的安全设计。

### 2. 本地嵌入模型(首次部署必做)

应用启动时需要 `model.onnx` + `tokenizer.json`(默认路径 `/opt/ai-chat/models/bge-small-zh-v1.5/`,可用环境变量 `AI_MODEL_PATH` / `AI_TOKENIZER_PATH` 覆盖)。本地验证阶段可先跳过,知识库上传/检索会报错但问答仍可用。

### 3. 构建前端(产物自动进入后端 static)

```bash
cd frontend
npm install
npm run build
```

### 4. 构建并启动后端

```bash
cd backend
mvn -DskipTests package

java -Xms64m -Xmx192m -XX:MaxMetaspaceSize=96m -XX:+UseSerialGC \
     -jar target/ai-chat.jar
```

浏览器访问 `http://服务器IP:8080`(问答页)/ `/#/kb`(知识库页)。

## Docker 部署(宿主机端口 8082)

> 容器内应用监听 8080,宿主机映射到 **8082**(避免与服务器已有服务冲突)。改端口只需修改 `docker-compose.yml` 中 `ports` 的左侧数字。

> **MySQL 连接**:容器内的 `localhost` 指向容器自身,连宿主机的 MySQL 必须用 `host.docker.internal`(compose 已配置 `extra_hosts` 映射)。
> `docker-compose.yml` 的 `environment.MYSQL_URL / MYSQL_USER / MYSQL_PASSWORD` 按你的服务器实际值配置;**请使用环境变量或服务器密钥管理,不要提交真实密码到版本库**。

### 部署前准备:模型文件(必须,否则容器内嵌入加载失败)

```bash
mkdir -p /opt/ai-chat/models/bge-small-zh-v1.5 && cd /opt/ai-chat/models/bge-small-zh-v1.5
wget https://hf-mirror.com/Xenova/bge-small-zh-v1.5/resolve/main/onnx/model.onnx
wget https://hf-mirror.com/Xenova/bge-small-zh-v1.5/resolve/main/tokenizer.json
ls -lh   # model.onnx ≈ 90MB;tokenizer.json ≈ 500KB(断流用 wget -c 续传)
```

> `docker-compose.yml` 已配置 `volumes: - /opt/ai-chat/models:/opt/ai-chat/models`,把宿主机模型挂载进容器。

### 方式 A:compose 一键部署(推荐)

1. **本地构建好 jar**(服务器上无需 JDK/Maven/Node):
   ```bash
   cd ai-chat/backend && mvn -DskipTests clean package
   ```
2. **上传到服务器**:
   ```bash
   scp backend/target/ai-chat.jar backend/Dockerfile backend/.dockerignore \
       docker-compose.yml 服务器用户@IP:/opt/ai-chat/
   ```
3. **配置环境变量**(服务器上,不要让真实凭据进仓库):
   ```bash
   cd /opt/ai-chat
   export AI_API_KEY=sk-你的key
   export MYSQL_PASSWORD=你的数据库密码
   ```
4. **启动**:
   ```bash
   docker compose up -d --build
   docker compose logs -f ai-chat    # 查看启动日志(应看到 ONNX 模型加载成功)
   ```
   访问 `http://服务器IP:8082` 即可。

### 方式 B:纯 docker 命令

```bash
docker build -t ai-chat backend
docker run -d --name ai-chat \
  -p 8082:8080 \
  -v /opt/ai-chat/models:/opt/ai-chat/models \
  -e AI_API_KEY=sk-你的key \
  --memory=400m --restart unless-stopped \
  ai-chat
```

### 运维备忘

| 命令                               | 说明               |
| -------------------------------- | ---------------- |
| `docker compose ps`              | 查看状态             |
| `docker compose logs -f ai-chat` | 看日志(docker logs) |
| `tail -f /opt/ai-chat/logs/ai-chat.log` | 看落盘日志(持久,推荐) |
| `docker compose restart`         | 重启               |
| `docker compose down`            | 停止并删除容器(数据在 MySQL,可随意) |
| `docker compose up -d --build`   | 更新镜像并重建          |

> 镜像基于 `eclipse-temurin:21-jre`(Ubuntu,约 250MB,**必须用 glibc 系、不能用 alpine**——ONNX Runtime native 库依赖 `libstdc++.so.6`,Alpine 缺失会启动失败),JVM 参数默认 `-Xmx192m`;compose 已加 `mem_limit: 400m` 保护 2G 小服务器。

## 低内存优化说明(2核2G / 剩余 <1G)

1. **堆内存压到 192MB**:`-Xmx192m`,实测足够;并发多可调到 `-Xmx256m`。
2. **SerialGC**:单核友好、内存占用最小的垃圾回收器。
3. **单进程**:前端静态资源由 Spring Boot 托管,省掉 Nginx(约 30~50MB)。
4. **无 Redis/额外中间件**:会话历史落 MySQL,内存向量库规模小(千级分块),不引入常驻服务。

## 开发模式(前后端分离热更新)

```bash
# 终端1:后端
cd backend && mvn spring-boot:run

# 终端2:前端(vite 代理 /api 到 8080)
cd frontend && npm run dev
# 访问 http://localhost:5173
```

## 接口说明

> 已集成 **Swagger 文档**(springdoc-openapi),浏览器打开即可交互式调试所有接口:
>
> - UI: `http://<host>:8082/swagger-ui.html`
> - JSON: `http://<host>:8082/v3/api-docs`
>
> **公开部署建议关闭 Swagger**(它会暴露全部接口结构):把 `application.yml` 的 `springdoc.api-docs.enabled` 改为 `false` 并重新打包。

### 智能问答

`POST /api/chat/stream` —— 请求体:

```json
{
  "gameId": 1,
  "conversationId": 3,
  "modelId": "deep",
  "messages": [
    { "role": "user", "content": "甘雨怎么配队?" }
  ]
}
```

- `gameId` 可选:带则检索该游戏知识库(命中时先推 `event:references` 参考来源);
- `conversationId` 可选:带则回答完成/停止后自动落库该轮问答;
- `modelId` 可选:`fast`(默认)/ `deep` / `pro`,思考档额外推送 `event:reasoning`(推理过程);
- 响应 `text/event-stream`:`event:message` 逐 token,出错推 `event:error`。

会话管理:

```
GET    /api/chat/conversations                会话列表(含消息数)
POST   /api/chat/conversations                新建 {title?, gameId?}
GET    /api/chat/conversations/{id}/messages  历史消息(含 references)
PATCH  /api/chat/conversations/{id}           重命名 {title}
DELETE /api/chat/conversations/{id}           删除(级联消息)
```

### 知识库

```
POST   /api/kb/game               新建游戏 {name}
GET    /api/kb/game               游戏列表
POST   /api/kb/upload             上传入库(gameId + file + title?)
GET    /api/kb/articles?gameId=   条目列表(chunkCount / vectorized)
DELETE /api/kb/article/{id}       删除条目(级联分块 + 重建向量库)
POST   /api/kb/search             检索测试 {gameId, query, topK}(含完整正文回溯)
POST   /api/kb/import-url         网址入库 {gameId, url, title?}(仅公网,SSRF 防护)
```

### 数据库

所有表统一归属独立库 **`ai_chat`**(DDL 均带 `ai_chat.` 前缀,与其他项目表隔离):

```sql
-- 首次部署建库(账号需建库权限;schema.sql 顶部也会自动执行该语句)
CREATE DATABASE IF NOT EXISTS ai_chat CHARACTER SET utf8mb4;
```

- 表结构见 `backend/src/main/resources/schema.sql`(幂等,应用启动自动执行):`ai_chat.game` / `ai_chat.article` / `ai_chat.chunk` / `ai_chat.conversation` / `ai_chat.chat_message`;
- 生产连接串默认 `jdbc:mysql://localhost:3306/ai_chat`,可用环境变量 `MYSQL_URL` 覆盖(务必指向 `ai_chat` 库,或改库名后同步改前缀);
- 本地 H2 开发:连接串已带 `INIT=CREATE SCHEMA IF NOT EXISTS ai_chat;SET SCHEMA ai_chat`,与 schema.sql 前缀配套。

## 安全与隐私

- 本仓库**不含任何真实凭据**:API Key、数据库密码均通过环境变量注入(`AI_API_KEY` / `MYSQL_PASSWORD` 等),请勿在提交代码时写入真实值;
- 如你的仓库历史中曾出现真实密钥,请**立即在对应平台重置**,并清理提交历史(见 `git filter-repo`);
- 公网部署建议:关闭 Swagger、限制端口访问、数据库账号使用最小权限;
- 知识库内容入库前请确认无隐私/侵权内容;URL 入库仅允许公网地址(内置 SSRF 防护)。
