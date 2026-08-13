# ai-chat 全栈学习指南 —— Spring AI + Vue3 SSE 流式聊天

> 目标:读完这份文档,你能独立讲清楚**每一个文件为什么这么写**、**一条消息从按下回车到屏幕出现文字的完整旅程**。
> 对应代码位于 `ai-chat/` 目录,建议边读边对照。

> **版本说明**:本指南基于最初版(单页聊天 + SSE + RAG 雏形)编写,代码讲解仍准确对应核心链路(SSE 协议、ChatController、Spring AI 概念)。  
> 后续演进(均不影响本指南的核心原理):  
> - 前端接入 `vue-router`(hash)替代 Tab 切换,组件拆分 `Chat.vue` / `KnowledgeBase.vue` / `Markdown.vue`;  
> - 新增多会话管理(conversation / chat_message 表)、URL 网页入库(jsoup + SSRF)、代码高亮 + Mermaid 渲染;  
> - 最新页面结构 / 接口清单 / 部署步骤以 [游戏百科拓展设计.md](./游戏百科拓展设计.md) 和项目根目录 `README.md` 为准。

---

## 目录

1. [整体架构与数据流](#一整体架构与数据流)
2. [SSE 流式通信原理(核心)](#二sse-流式通信原理核心)
3. [后端代码逐段讲解](#三后端代码逐段讲解)
4. [前端代码逐段讲解](#四前端代码逐段讲解)
5. [Spring AI 核心概念梳理](#五spring-ai-核心概念梳理)
6. [完整运行步骤](#六完整运行步骤)
7. [低内存优化原理](#七低内存优化原理)
8. [学习清单与延伸方向](#八学习清单与延伸方向)

---

## 一、整体架构与数据流

```
┌───────────────────────── 浏览器 ─────────────────────────┐
│  Vue3 页面 (App.vue)                                      │
│   · 维护消息列表 messages = [{role, content}, ...]        │
│   · fetch POST /api/chat/stream 发送全部历史              │
│   · ReadableStream 边收边渲染(打字机效果)                │
└──────────────────────────┬───────────────────────────────┘
                           │ 1. HTTP POST (JSON)
                           ▼
┌───────────────── Spring Boot 3.4 后端 (单 jar) ──────────┐
│  ChatController                                          │
│   · 把 JSON 消息转成 Spring AI 的 Message 对象            │
│   · 调 chatModel.stream() 获得响应流                     │
│   · 用 SseEmitter 把 token 流"翻译"成 SSE 逐块转发        │
│  static/ 目录:托管前端构建产物(替代 Nginx)              │
└──────────────────────────┬───────────────────────────────┘
                           │ 2. stream(new Prompt(messages))
                           ▼
┌───────────────── Spring AI 1.0 ──────────────────────────┐
│  ChatModel (OpenAiChatModel)                             │
│   · 统一抽象:任何 OpenAI 兼容厂商只需改配置              │
│   · 内部用 WebClient 调模型 HTTP 接口,保持流式            │
└──────────────────────────┬───────────────────────────────┘
                           │ 3. HTTPS (SSE 协议)
                           ▼
              ┌──────────────────────────┐
              │  DeepSeek / 通义 / Kimi   │
              │  OpenAI 兼容 chat API     │
              └──────────────────────────┘
```

**一次完整旅程(重点背下来):**

1. 用户在输入框敲回车 → `send()` 把整段历史(含新消息)POST 给后端;
2. `ChatController.stream()` 把 JSON 里的每条消息按 `role` 映射成 `UserMessage` / `AssistantMessage` / `SystemMessage`,组装成 `Prompt`;
3. `chatModel.stream(prompt)` 返回一个 **`Flux<ChatResponse>`**(响应式流,不是一次性结果);
4. 模型 API 开始"挤牙膏"式返回 token(`你` → `好` → `!` ...),Spring AI 把这些 chunk 包装成 `ChatResponse` 逐个推给 Flux;
5. 后端用 `subscribe` 订阅这个 Flux,**每收到一个 token 就通过 `SseEmitter` 立刻推给浏览器**;
6. 浏览器 `fetch` 的 `ReadableStream` 收到字节,按 `data:` 前缀切出内容,追加到当前气泡 → 打字机效果。

> 核心心智模型:**后端不是"等到答案完整再返回",而是"拿到一个 token 就转发一个"**。这是流式应用与普通接口最大的思维差异。

---

## 二、SSE 流式通信原理(核心)

### 2.1 为什么是 SSE 而不是轮询 / WebSocket?

| 方案 | 机制 | 适合 | 本项目选择 |
|---|---|---|---|
| 轮询 | 前端定时发请求 | 数据低频变化 | 太浪费,且做不到"逐字" |
| WebSocket | 全双工长连接,需协议升级 | 双向实时(游戏、协作) | 重,前端复杂度高 |
| **SSE** | **单向长连接,HTTP 原生协议** | **服务端推送流(LLM 打字机)** | ✅ 简单、够用 |

SSE(Server-Sent Events)是标准 HTML5 API,浏览器原生支持 `EventSource`,本质是:**一次 HTTP 请求,响应体持续不断地追加内容,连接不关闭**。本项目用 `fetch` + `ReadableStream` 手动解析,因为要 POST JSON 且要拿到错误码,比 `EventSource`(只支持 GET)更灵活。

### 2.2 SSE 线上传输的原始格式

后端 `SseEmitter.send()` 之后,浏览器收到的字节长这样(以换行分隔):

```
event:message
data:你

event:message
data:好

event:message
data:!

```

规则:每个事件由若干 `key:value` 行组成,**空行表示一个事件结束**;`data:` 是事件内容,`event:` 是事件名。

### 2.3 前端为什么能"边收边渲染"

`fetch` 返回的 `response.body` 是一个 `ReadableStream`(可读流)。`reader.read()` 每次只取**当前已到达的字节**并返回 `{done, value}`——TCP 一有数据就触发,而不是等全部下载完。所以模型每吐一个 token,前端 `while` 循环就多转一圈,把新内容拼进气泡。

> 注意:`value` 是 `Uint8Array`(字节数组),必须用 `TextDecoder` 转成字符串。`{stream: true}` 参数是为了处理**多字节字符被 TCP 分包截断**的情况(比如"你"的 UTF-8 字节 3 个,可能第 1 个字节先到)——它会在流结束前暂存不完整的字节。

---

## 三、后端代码逐段讲解

### 3.1 pom.xml —— 依赖与构建

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.4.5</version>
</parent>
```

**为什么用 parent?** Spring Boot 的 parent POM 是一个"版本圣经"——它替你锁定了几百个依赖的**互相兼容的版本**(比如 Tomcat、Jackson、Spring 框架版本)。你不写版本号,它全管好。这叫 **依赖版本管理(Spring Boot BOM)**。

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
    <version>1.0.0</version>
</dependency>
```

- `spring-boot-starter-web`:内嵌 Tomcat + Spring MVC + Jackson,一套 HTTP 服务所需全齐;
- `spring-ai-starter-model-openai`:Spring AI 的 OpenAI 兼容接入器。它是一个 **starter**,意味着只要引入它 + 配置 `application.yml`,Spring 会自动创建 `ChatModel`、`OpenAiApi` 等 Bean——**零手写客户端代码**。

```xml
<build>
    <finalName>ai-chat</finalName>
    <plugins>
        <plugin>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-maven-plugin</artifactId>
        </plugin>
    </plugins>
</build>
```

**spring-boot-maven-plugin 做了什么?** 普通 jar 只含你的代码,不含依赖。这个插件在 `package` 阶段把应用"重打包"成 **fat jar(可执行 jar)**:所有第三方依赖塞进 `BOOT-INF/lib/`、用特殊的 `JarLauncher` 入口,于是 `java -jar ai-chat.jar` 一个命令就能跑,不需要额外装 Tomcat。这对低配服务器部署至关重要。

### 3.2 application.yml —— 全部配置就这两处

```yaml
server:
  port: 8080
  compression:
    enabled: true
    mime-types: text/plain,text/html,text/css,application/javascript,application/json

spring:
  ai:
    openai:
      base-url: ${AI_BASE_URL:https://api.deepseek.com}
      api-key: ${AI_API_KEY:}
      chat:
        options:
          model: ${AI_MODEL:deepseek-chat}
          temperature: 0.7
          max-tokens: 2048
```

逐行解读:

| 配置 | 含义 |
|---|---|
| `server.port: 8080` | 服务监听端口 |
| `server.compression.enabled: true` | 开启响应压缩,SSE 文本类内容能省带宽(前端 JS/CSS 走 gzip) |
| `spring.ai.openai.base-url` | **模型 API 地址**。`${AI_BASE_URL:默认值}` 是环境变量占位语法:优先读环境变量 `AI_BASE_URL`,没设置就用冒号后的默认值 |
| `spring.ai.openai.api-key` | API 密钥,从环境变量读,**不硬编码进仓库**(安全习惯) |
| `chat.options.model` | 模型名,如 `deepseek-chat` / `qwen-plus` |
| `temperature: 0.7` | 采样温度,0~2。越高越"天马行空",越低越"保守确定" |
| `max-tokens: 2048` | 单次回答最大 token 数 |

> **为什么能自由切换厂商?** 因为 DeepSeek、通义(兼容模式)、Kimi、硅基流动都实现了 **OpenAI 兼容协议**(同一个 HTTP 接口规范,同一个 `messages` 请求体格式)。Spring AI 只认这套协议,所以换厂商 = 改两个环境变量,代码零改动。

### 3.3 AiChatApplication.java —— 入口

```java
package com.example.aichat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AiChatApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiChatApplication.class, args);
    }
}
```

`@SpringBootApplication` 是三个注解的合成:

- `@SpringBootConfiguration`:标记为配置类;
- `@EnableAutoConfiguration`:**核心**。启动时扫描 classpath,凡是发现引入的 starter,就自动创建对应的 Bean——比如发现 `spring-ai-starter-model-openai`,就自动装配 `ChatModel`。这就是"约定优于配置";
- `@ComponentScan`:自动扫描本包及子包下的 `@RestController`、`@Service` 等组件。

### 3.4 DTO —— record 一行搞定数据传输

```java
public record ChatMessageDto(String role, String content) {}

public record ChatRequestDto(List<ChatMessageDto> messages) {}
```

- `record` 是 Java 16+ 的语法糖,自动生成构造器、`getter`(方法名 `role()` / `content()`)、`equals/hashCode/toString`;
- 与前端 JSON 天然对应:`{"messages":[{"role":"user","content":"hi"}]}` 反序列化时 Jackson 自动按名字匹配;
- 只承载数据、无逻辑,用 record 最简洁。

### 3.5 ChatController.java —— 全项目最核心的文件

```java
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatModel chatModel;

    public ChatController(ChatModel chatModel) {
        this.chatModel = chatModel;
    }
    ...
}
```

- `@RestController` = `@Controller` + `@ResponseBody`:方法的返回值直接序列化成 HTTP 响应体;
- **构造器注入**:`ChatModel` 是 Spring 自动配置好的 Bean,通过构造器注入(推荐方式,便于测试)。这就是 **依赖注入(DI)**。

```java
@PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter stream(@RequestBody ChatRequestDto request) {
    SseEmitter emitter = new SseEmitter(0L);
```

- `produces = text/event-stream`:告诉浏览器"我这个接口返回的是 SSE 流,请不要缓存、请按流处理";
- `SseEmitter`:Spring MVC 提供的 **SSE 发射器**。返回值类型是 `SseEmitter` 时,Spring 不会走"JSON 序列化返回"路径,而是把这个连接挂起,等你主动 `emitter.send()`;
- `new SseEmitter(0L)`:参数是超时毫秒,`0` 表示**永不超时**——因为大模型回答可能要 1 分钟,默认 30 秒会提前断开。

```java
    List<Message> messages = request.messages().stream()
        .map(this::toSpringMessage)
        .toList();

    Flux<String> tokens = chatModel.stream(new Prompt(messages))
        .map(response -> response.getResult().getOutput().getText());
```

- 把前端传来的历史消息逐条映射成 Spring AI 的 `Message`(见下);
- `chatModel.stream(new Prompt(messages))` 返回 `Flux<ChatResponse>`——**响应式流**,模型每吐一个 chunk 就发出一个元素;
- `.map(...)` 把每个 `ChatResponse` 里的 token 文本提取出来。链式调用:`ChatResponse → ChatResult → AssistantMessage → String`。
  - ⚠️ 踩坑笔记:Spring AI 1.0 取文本要用 **`getText()`**(旧版 0.8 是 `getContent()`,1.0 重构后已不存在)。

```java
    tokens.subscribe(
        token -> safeSend(emitter, token),
        error -> { ... emitter.complete(); },
        emitter::complete
    );

    return emitter;
}
```

**`subscribe` 是这段的灵魂**,它注册三个回调:

| 参数 | 触发时机 | 作用 |
|---|---|---|
| `token -> safeSend(...)` | 每收到一个 token | 立刻 `emitter.send()` 推给浏览器 |
| `error -> ...` | 流异常(如 API 返回 401) | 推送 `event:error` 并结束连接 |
| `emitter::complete` | 流正常结束 | 关闭 SSE 连接,浏览器收到 `done` |

> 为什么用 `subscribe` 而不是 `blockLast()`?因为**不能等**。`blockLast()` 会阻塞线程直到整个回答完成,那就回到"一次性返回"了。`subscribe` 是异步的——请求线程立刻返回 `emitter`,真正的转发发生在 Reactor 的后台线程,数据一到就推。

```java
private Message toSpringMessage(ChatMessageDto dto) {
    return switch (dto.role()) {
        case "system" -> new SystemMessage(dto.content());
        case "assistant" -> new AssistantMessage(dto.content());
        default -> new UserMessage(dto.content());
    };
}

private void safeSend(SseEmitter emitter, String data) {
    try {
        emitter.send(SseEmitter.event().name("message").data(data));
    } catch (Exception ignored) {
    }
}
```

- `toSpringMessage`:把前端 JSON 的 `role` 映射成 Spring AI 的三种消息类型,这就是**多轮对话**的实现基础——模型需要看到完整的"对话历史"(谁说了什么),才能接上上下文;
- `SseEmitter.event().name("message").data(data)`:构造一个 SSE 事件,`name("message")` 对应前端按 `event:` 区分(本实现前端只认 `data:`,所以 name 是辅助信息);
- `safeSend` 包 try-catch:客户端中途断开(比如用户关页面)时 `send` 会抛异常,不能让它中断整个流。

---

## 四、前端代码逐段讲解

### 4.1 工程配置

**package.json**(核心依赖只有 `vue` 一个):

```json
"dependencies": { "vue": "^3.5.13" },
"devDependencies": {
    "@vitejs/plugin-vue": "^5.2.1",
    "vite": "^6.0.7"
}
```

**vite.config.js** —— 两个关键设计:

```js
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: { '/api': 'http://localhost:8080' }   // 开发时跨域代理
  },
  build: {
    outDir: '../backend/src/main/resources/static',  // 产物直接进后端
    emptyOutDir: true
  }
})
```

- `server.proxy`:开发模式下,前端跑在 5173,后端在 8080,把 `/api` 开头的请求转发给后端,**前端代码里写相对路径 `/api/...` 即可,无需 CORS**;
- `build.outDir`:生产构建产物(html/js/css)直接输出到后端的 `static/` 目录——Spring Boot 会自动托管该目录下的静态资源。于是 **前端被"打进"后端 jar,部署时只有一个进程**。

### 4.2 App.vue —— 全部逻辑解析

```js
const messages = ref([{ role: 'assistant', content: '你好,我是轻量 AI 助手...' }])
const input = ref('')
const loading = ref(false)
```

- `ref()` 创建**响应式数据**:值改变时,模板自动更新(Vue 3 的响应式原理是 Proxy 代理);
- `messages` 是消息数组,`role` 决定气泡在左(assistant)还是在右(user)。

```js
watch(messages, () => {
  nextTick(() => {
    if (listEl.value) listEl.value.scrollTop = listEl.value.scrollHeight
  })
}, { deep: true })
```

- `watch` 监听 `messages` 变化(流式追加内容时它一直在变),`nextTick` 等 DOM 更新完再滚动到底部——保证"始终看到最新内容"。

**send() 主流程:**

```js
async function send() {
  const text = input.value.trim()
  if (!text || loading.value) return          // 空消息或正在回复时忽略
  input.value = ''
  messages.value.push({ role: 'user', content: text })
  messages.value.push({ role: 'assistant', content: '' })   // 先占位,流式填充
  loading.value = true

  const ai = messages.value[messages.value.length - 1]
  const res = await fetch('/api/chat/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ messages: messages.value })   // 整个历史一起发
  })
  ...
```

- 关键点:先 push 一个**内容为空的 assistant 气泡**,后面拿到 token 就 `ai.content += token`,这就是"打字机"的数据基础;
- 把**整个 `messages` 数组**发给后端 = 多轮上下文(不是只发当前这条)。

```js
  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buf = ''
  let currentEvent = ''
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buf += decoder.decode(value, { stream: true })   // 字节 → 字符串
    let idx
    while ((idx = buf.indexOf('\n')) >= 0) {          // 按行切分
      const line = buf.slice(0, idx).trim()
      buf = buf.slice(idx + 1)
      if (line.startsWith('event:')) {
        currentEvent = line.slice(6).trim()           // 记录当前事件名
      } else if (line.startsWith('data:')) {
        // 后端把 token 做了 JSON 序列化(换行/引号/行首空格转义为单行),
        // 这里 JSON.parse 还原;解析失败按原文兜底
        const raw = line.slice(5).trimStart().replace(/\r$/, '')
        let data = raw
        try { data = JSON.parse(raw) } catch { /* 保留原文 */ }
        if (data) {
          if (currentEvent === 'error') {             // 错误事件:红色提示
            ai.error = true
            ai.content = '请求失败:' + data
          } else {
            ai.content += data                        // 正常 token:追加 → 打字机
          }
        }
      }
    }
  }
```

这一段是前端最值得反复看的 10 行:

1. `res.body.getReader()`:拿到响应体的可读流;
2. `reader.read()` 返回 `Promise<{done, value}>`——**每次返回当前已到的字节**;
3. `decoder.decode(value, {stream:true})`:字节数组转字符串,`stream:true` 处理跨包的多字节字符;
4. `buf` 是**跨 chunk 的残行缓冲**——TCP 分包后,一个 `data:` 行可能被切成两半到达,`buf` 把它们拼起来;
5. 按 `\n` 切出完整行,`trim()` 顺带去掉行尾的 `\r`(Windows 换行);
6. `event:` 行记录事件名;`data:` 行 JSON.parse 还原后:`error` 事件置红,正常 token 追加。

> **为什么 data 必须是 JSON?(本项目的关键坑)**
>
> SSE 协议规定 **`data:` 行以换行符结束**。如果 token 文本本身含 `\n`(模型几乎必然输出换行),后端直接发送会把一个事件的 data 拆成多行;而前端是按 `\n` 切行的,**换行符就在切分时被吞掉了**——结果就是"AI 输出不换行"。
>
> 此外,`line.slice(5).trim()` 会把**行首空格**也去掉,而 markdown 的缩进代码块(4 空格)恰恰依赖行首空格,被破坏后就渲染不出代码块。
>
> 修复:后端发送前用 `ObjectMapper` 把 token 序列化成 JSON(`"第一行\n第二行"`),换行变成 `\n` 两个字符、行首空格被引号包住,整条 data 是**单行安全文本**;前端 `JSON.parse` 还原,换行和缩进 100% 保真。

> 为什么不用现成的 `EventSource`?因为它只支持 GET 且拿不到 HTTP 状态码。这里手动解析换来的是:POST 传大段历史 + 能区分 401 等错误。

**错误处理:**

```js
  } catch (e) {
    ai.content = '请求失败:' + e.message
  } finally {
    loading.value = false
  }
```

- `catch`:网络错误或 HTTP 非 2xx(如 401 API Key 无效)会抛错,直接把错误文本渲染进气泡——用户立刻看到原因;
- `finally`:无论成败都解锁 `loading`,允许发下一条。

**模板与样式:**

```html
<div v-for="(m, i) in messages" :key="i" class="msg" :class="m.role">
  <div class="bubble">{{ m.content }}<span v-if="loading && 是最后一条" class="caret"></span></div>
</div>
```

- `:class="m.role"` 让 user/assistant 气泡左右分列(见 CSS `.msg.user { justify-content: flex-end }`);
- `.caret` 是一个 CSS 动画的光标(`@keyframes blink`),只有"正在生成最后一条"时才显示;
- `.bubble { white-space: pre-wrap }`:保留换行——模型输出含 `\n` 时能正确显示,否则全挤一行。

### 4.3 Markdown 渲染组件(Markdown.vue)

模型回答经常带 **markdown 格式**(标题/列表/代码块/表格),纯文本显示很糟糕。前端增加了自封装的 `Markdown.vue` 组件:

```vue
<Markdown v-if="m.role === 'assistant' && !m.error" :source="m.content" />
```

**为什么不用现成的 `vue-markdown` 包?**(一个真实的坑)

| 候选 | 结论 |
|---|---|
| `vue-markdown`(原版) | **Vue 2 组件**,引擎 markdown-it 6(2016 年),Vue 3 项目无法可靠使用 |
| `vue-markdown-v3`(社区移植) | 源码里 `render()` 用的是 Vue 2 的 `h('div', { domProps: { innerHTML } })` 写法——**Vue 3 中 `domProps` 不生效,组件渲染出空白**!已用 SSR 实测验证:`<div></div>` |

**本项目方案**:自己封装 20 行组件,引擎用 `markdown-it`(与 vue-markdown 同引擎),用法完全对齐:

```js
const md = new MarkdownIt({ html: false, breaks: true, linkify: true, typographer: true })
md.use(full)        // emoji 插件(3.x 是命名导出 full)
md.use(taskLists)   // GFM 任务列表
const html = computed(() => DOMPurify.sanitize(md.render(props.source)))
```

**三重安全防线**(模型输出不可信,必须防 XSS):

1. `html: false`:markdown-it 把原始 HTML(如 `<script>`)转义为纯文本;
2. `validateLink`:markdown-it 内置拒绝 `javascript:` 等危险链接协议;
3. `DOMPurify.sanitize()`:渲染结果再过一遍白名单消毒,兜底任何漏网之鱼。

**流式性能**:`computed` 在每次内容变化时重新解析,markdown-it 对几百字文本解析在 1ms 级,打字机场景无压力。

**体积**:新增依赖 `markdown-it` + `markdown-it-emoji` + `markdown-it-task-lists` + `dompurify`,前端 JS 从 67KB → 255KB(gzip 27KB → 104KB),仍属轻量。

**宽容预处理(`fixCommonMistakes`)** —— 模型输出的 markdown 常不规范,标准解析器不会宽容:

| 模型常见写法 | 违反的 CommonMark 规则 | 预处理修复 |
|---|---|---|
| `###1.标题` | ATX 标题 `#` 后**必须有空格**,否则不是标题 | 行首 `#{1,6}` 后补空格 → `### 1.标题` |
| `**加粗**和` | 闭合 `**` 后必须跟**空白或标点**,跟汉字/字母则强调不生效 | 闭合 `**` 后补空格 → `**加粗** 和` |

处理顺序:先把代码块(``` ``` ```)和行内代码(`` ` ``)临时占位保护,再做正则修复,最后还原——避免误伤代码内容。已实测:修复后 `<strong>`/`<h3>` 正常生成,代码块内原文不受影响。

> 学习点:这些"不渲染"不是 bug,是**所有标准 markdown 解析器(GitHub/Typora/CommonMark)的一致行为**。大模型输出 markdown 时不会严格遵守规范,所以聊天应用普遍需要这种"宽容预处理"。

### 4.4 体验增强:停止生成 / 复制 / 语音对话

**停止生成(前后端配合)**
- 前端:每次请求创建 `AbortController`,把 `signal` 传给 `fetch`;点"停止"调 `abort()`;`catch` 里判断 `e.name === 'AbortError'`——这是**用户主动取消**,不是错误,保留已生成内容并打上"已停止"标记;
- 后端:`subscribe()` 返回 `Disposable`,注册 `emitter.onCompletion/onError(disposable::dispose)`——**客户端断开时主动取消对模型流的订阅**,避免模型还在生成但没人接收。
- 学习点:HTTP 请求取消机制;SSE 连接断开 ≠ 后台流自动停止,需要手动清理。

**复制消息**
- `navigator.clipboard.writeText()` 需要 **https 或 localhost**;http 部署时兜底 `textarea + document.execCommand('copy')`。
- 学习点:浏览器安全上下文(secure context)对 API 的限制,以及兼容性兜底的通用思路。

**语音对话(Web Speech API,零后端依赖)**
- 语音输入:`SpeechRecognition`(Chrome/Edge 为 `webkitSpeechRecognition`),`lang='zh-CN'`,识别结果填入输入框;
- 语音朗读:`speechSynthesis.speak(...)`,再次点击 `cancel()` 停止;
- **朗读前去掉 markdown 符号**(否则会读出星号/井号):代码块→"代码块"、行内代码去反引号、去掉 `*_~#` 等;
- 注意:语音识别需要 https/localhost;Firefox 不支持需降级提示。
- 学习点:浏览器原生能力集成;多语音请求冲突管理(朗读前先 `cancel()`)。

---

## 五、Spring AI 核心概念梳理

| 概念 | 作用 | 本项目用法 |
|---|---|---|
| `ChatModel` | 聊天模型的统一接口,stream/非 stream 都有 | 构造器注入,直接调 `stream()` |
| `Prompt` | 一次"提问",内含消息列表 + 参数 | `new Prompt(messages)` |
| `Message` | 单条消息,三种:`UserMessage`(用户)、`AssistantMessage`(助手)、`SystemMessage`(系统设定) | 由前端 role 映射 |
| `Flux<T>` | Reactor 响应式流,异步、可订阅、一元素一回调 | 承载 token 流,`subscribe` 转发 |
| `ChatResponse` | 每次响应的包装,含 `ChatResult → AssistantMessage → getText()` | 提取 token 文本 |

**依赖注入链**:`application.yml` 配置 → `OpenAiAutoConfiguration` 自动创建 `OpenAiApi` → 创建 `OpenAiChatModel`(实现 `ChatModel`)→ 注入到 `ChatController`。全程你只写了配置和注入点,中间过程全部由 starter 自动完成。

---

## 六、完整运行步骤

### 6.1 环境准备

| 组件 | 要求 | 检查命令 |
|---|---|---|
| JDK | 17+(本项目用 21 验证过) | `java -version` |
| Maven | 3.6+ | `mvn -version` |
| Node.js | 18+ | `node -v` |
| 模型 API Key | DeepSeek 等任意 OpenAI 兼容平台 | 官网申请 |

### 6.2 配置模型(必做)

```bash
# Linux / macOS
export AI_API_KEY=sk-你的key
export AI_BASE_URL=https://api.deepseek.com   # 默认就是这个,可不设
export AI_MODEL=deepseek-chat                  # 默认就是这个,可不设

# Windows CMD
set AI_API_KEY=sk-你的key
```

切换厂商示例(三选一即可):

```bash
# 通义千问
export AI_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
export AI_MODEL=qwen-plus
# Kimi
export AI_BASE_URL=https://api.moonshot.cn/v1
export AI_MODEL=moonshot-v1-8k
```

### 6.3 构建前端(产物自动进后端 static)

```bash
cd ai-chat/frontend
npm install
npm run build
# 看到 backend/src/main/resources/static/ 下出现 index.html 即成功
```

### 6.4 构建并启动后端

```bash
cd ai-chat/backend
mvn -DskipTests package          # 产物: target/ai-chat.jar

java -Xms64m -Xmx192m -XX:MaxMetaspaceSize=96m -XX:+UseSerialGC \
     -jar target/ai-chat.jar
```

看到 `Started AiChatApplication in ~7 seconds` 即成功。

### 6.5 验证

```bash
# 1) 页面可访问(应返回 200 和 HTML)
curl -i http://localhost:8080/

# 2) SSE 接口链路(能看到流式事件即通)
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","content":"你好"}]}'
# 正常会看到 event:message / data:xxx 交替输出
```

浏览器打开 `http://localhost:8080` 聊天。

### 6.6 部署到服务器(systemd 守护)

```ini
# /etc/systemd/system/ai-chat.service
[Unit]
Description=ai-chat
After=network.target

[Service]
Environment=AI_API_KEY=sk-你的key
WorkingDirectory=/opt/ai-chat
ExecStart=/usr/bin/java -Xms64m -Xmx192m -XX:MaxMetaspaceSize=96m -XX:+UseSerialGC -jar /opt/ai-chat/ai-chat.jar
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now ai-chat
```

---

## 七、低内存优化原理

| 参数 | 含义 | 为什么这么设 |
|---|---|---|
| `-Xms64m` | 堆初始 64MB | 启动即按需分配,不浪费 |
| `-Xmx192m` | 堆上限 192MB | 本项目无大对象,192MB 足够;实际实测进程总占用约 300MB |
| `-XX:MaxMetaspaceSize=96m` | 类元数据上限 | 防止元空间无限增长 |
| `-XX:+UseSerialGC` | 单线程串行 GC | 2 核小内存下,G1 的并发线程反而浪费;SerialGC 内存占用最低、暂停可控 |
| 不引入 Nginx | 前端打进 jar | 少一个进程 ≈ 少 30~50MB |
| 不引入 DB/Redis/MQ | 会话历史存前端内存 | 少三个客户端连接 + 线程池 |

实测数据(本机验证):启动 **6.9 秒**,堆上限 192MB 下运行稳定,jar 仅 **31.2MB**。

---

## 八、学习清单与延伸方向

### 本项目的知识清单(面试/自检用)

- [ ] 能画出架构图并讲清一次请求的完整链路
- [ ] 能解释 SSE 与轮询/WebSocket 的区别,以及 `text/event-stream` 的含义
- [ ] 能说清 `SseEmitter` + `Flux.subscribe` 为什么能实现流式(为什么不用 `blockLast`)
- [ ] 能解释 `TextDecoder` 的 `{stream:true}` 是解决什么问题的
- [ ] 能解释 `@SpringBootApplication` 三个注解各自的作用
- [ ] 能解释 Spring Boot fat jar 的原理(`java -jar` 为什么能跑)
- [ ] 能解释 `-Xmx`、`-XX:+UseSerialGC` 对内存的影响
- [ ] 能解释为什么"换模型厂商只改配置不改代码"

### 进阶路线(在这个项目上逐步加功能)

1. **RAG 知识库**:加 `spring-ai-starter-document-reader` + PDF 解析 + 向量库(PGVector/Elasticsearch),提问前先检索文档 → 塞进 `SystemMessage`。这是企业落地最常用的能力;
2. **Agent 工具调用**:Spring AI 的 `ToolCallback` + `@Tool` 注解,让模型能调用你的 Java 方法(查天气、查数据库);
3. **对话记忆**:把历史从"前端每次全传"改为后端 `ChatMemory` 管理,减少 token 消耗;
4. **鉴权与限流**:加 Spring Security + 每用户 token 配额,防滥用;
5. **多模型网关**:一个接口背后路由多个厂商,带计费(参考 one-api 思路)。

### 推荐官方资料

- Spring AI 官方文档:https://docs.spring.io/spring-ai/reference/
- Spring Boot 参考文档:https://docs.spring.io/spring-boot/reference/
- MDN SSE:https://developer.mozilla.org/zh-CN/docs/Web/API/Server-sent_events

---

> 学习建议:先跑通,再"破坏"——比如把 `subscribe` 换成 `blockLast()` 看会发生什么、把 `{stream:true}` 去掉看中文会不会乱码、把 `SseEmitter(0L)` 改成默认超时看长回答会不会断。**亲手制造 bug 是理解系统最好的方式。**

---

## 九、常见错误排查(实战 FAQ)

> 下面这些错误**都不是代码 bug**,而是配置或账户问题。学会看状态码就能快速定位。

### 9.1 `402 Payment Required` —— 模型账户余额不足(最常见)

```
WebClientResponseException: 402 Payment Required
from POST https://api.deepseek.com/v1/chat/completions
```

**含义**:HTTP 402 = "需要付款"。DeepSeek 等平台 **API 无免费额度,必须充值**;余额为 0 时拒绝请求。

**处理**:
1. 登录 platform.deepseek.com 充值;
2. 或换有免费额度的厂商(通义 DashScope 新用户送额度、硅基流动注册送额度),只需改环境变量:
   ```bash
   export AI_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
   export AI_MODEL=qwen-plus
   ```
3. 验证命令(直接调 API 看原始错误信息):
   ```bash
   curl https://api.deepseek.com/chat/completions \
     -H "Content-Type: application/json" \
     -H "Authorization: Bearer $AI_API_KEY" \
     -d '{"model":"deepseek-chat","messages":[{"role":"user","content":"hi"}],"stream":false}'
   ```

> 学习点:这个报错恰好证明**你的代码链路是通的**——请求成功到达模型厂商,并拿到了明确的业务拒绝。排查顺序永远先看状态码:402=钱、401=钥匙、404=地址。

### 9.2 `401 Unauthorized` —— API Key 错误

**处理**:检查 `AI_API_KEY` 是否完整复制、有无空格;确认 key 属于 `AI_BASE_URL` 对应平台(DeepSeek 的 key 不能用在通义)。

### 9.3 `404 Not Found` —— base-url 配置错

**处理**:确认 `AI_BASE_URL` 是平台的 OpenAI 兼容地址。DeepSeek 是 `https://api.deepseek.com`(不带 `/v1`),通义是 `https://dashscope.aliyuncs.com/compatible-mode/v1`,Kimi 是 `https://api.moonshot.cn/v1`。

### 9.4 `429 Too Many Requests` —— 限流

**处理**:降低调用频率;部分平台付费档位限流更宽。可在 `application.yml` 调小 `max-tokens`,缩短单次占用时长。

### 9.5 启动失败:提示 api-key 相关异常

**原因**:`spring.ai.openai.api-key` 为空,starter 无法创建 `ChatModel` Bean。
**处理**:启动前必须 `export AI_API_KEY=...`,再 `java -jar`。

### 9.6 页面能打开但接口 404 / 前端一直"无响应"

**排查顺序**:
1. 后端是否启动成功(看日志 `Started AiChatApplication`);
2. 端口是否对上(`application.yml` 的 `server.port` 与访问地址);
3. 前端构建产物是否在 `backend/static`(重新 `npm run build`);
4. 浏览器 F12 → Network 看 `/api/chat/stream` 的响应状态码和内容。

### 9.7 前端错误提示的两种形态(本项目已优化)

| 场景 | 前端显示 | 原因 |
|---|---|---|
| `catch` 分支 | `请求失败:HTTP 401` | fetch 层面:连接失败或 HTTP 非 2xx |
| SSE `event:error` | `请求失败:402 Payment Required...`(红色) | 连接建立后,模型 API 流式阶段报错 |

两者都会把 `ai.error = true` 置红。后端在 `subscribe` 的 error 回调里发送 `event:error`,前端解析 `event:` 行后不再把它当正常 token 追加——这就是 4.2 节 `event:` 区分逻辑的作用。

### 9.8 流式中途报错 `The mapper [...] returned a null value`(代码坑)

```
请求失败:The mapper [ChatController$$Lambda/...] returned a null value.
```

**根因(非常典型,值得理解)**:
1. Spring AI 1.0 的流式响应中,**最后一个 chunk 通常没有文本内容**,只携带 `finish_reason`(结束原因)和 `usage`(token 用量);
2. 此时 `response.getResult().getOutput().getText()` 返回 `null`;
3. **Reactor 的 `map()` 不允许 mapper 返回 null**,一旦返回就抛这个异常,整个流中断。

**修复**:改用 `mapNotNull()`(Reactor 3.5+,自动跳过返回 null 的元素),并做 null 防御:

```java
Flux<String> tokens = chatModel.stream(new Prompt(messages))
        .mapNotNull(response -> {
            if (response.getResult() == null || response.getOutput() == null) {
                return null;
            }
            String text = response.getOutput().getText();
            return (text == null || text.isBlank()) ? null : text;
        });
```

> 学习点:这也是"为什么报错信息里的 mapper 是 `ChatController$$Lambda`"——`$$Lambda` 就是你在 Controller 里写的那个 lambda 表达式的运行时类名。这个报错恰好说明 **token 流已经真正到达**了,只是被最后一个空 chunk 绊倒。

### 9.9 顺带记住:Reactor 操作符返回 null 的规则

| 操作符 | 允许返回 null? | 说明 |
|---|---|---|
| `map()` | ❌ | mapper 返回 null 直接抛异常 |
| `mapNotNull()` | ✅ | null 元素被静默跳过 |
| `filter()` | ✅(返回 boolean) | 用 `Objects::nonNull` 也能过滤 null |
