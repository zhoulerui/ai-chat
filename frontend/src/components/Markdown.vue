<script setup>
// Markdown.vue —— 轻量 Markdown 渲染组件(Vue 3)
// 引擎:markdown-it(与 vue-markdown 同引擎),用法对齐 vue-markdown:
//   <Markdown :source="content" :html="false" :breaks="true" />
// 增强:
//   1) 代码高亮(highlight.js,按需语言集)+ 一键复制按钮;
//   2) Mermaid 图表渲染(```mermaid 代码块,流式闭合后初始化,失败降级显示源码);
// 安全设计:
//   1) html=false 时 markdown-it 会把原始 HTML 转义为纯文本;
//   2) markdown-it 内置 validateLink 拒绝 javascript: 等危险协议;
//   3) 渲染结果再过 DOMPurify 消毒,三重防线兜底。
import { computed, nextTick, ref, watch } from 'vue'
import MarkdownIt from 'markdown-it'
import { full as emoji } from 'markdown-it-emoji'  // 3.x 为命名导出,full = 完整表情集
import taskLists from 'markdown-it-task-lists'
import DOMPurify from 'dompurify'
import hljs from 'highlight.js/lib/common'          // 常用语言子集,避免全量包体
import mermaid from 'mermaid'

const props = defineProps({
  source: { type: String, default: '' },
  html: { type: Boolean, default: false },   // 是否允许原始 HTML(默认禁止,安全)
  breaks: { type: Boolean, default: true },  // 换行渲染为 <br>(聊天场景常用)
  linkify: { type: Boolean, default: true }, // 自动识别 URL 生成链接
  emoji: { type: Boolean, default: true }    // :smile: 表情
})

const rootEl = ref(null)

mermaid.initialize({
  startOnLoad: false,
  securityLevel: 'strict',
  theme: document.documentElement.classList.contains('dark') ? 'dark' : 'default'
})

const md = new MarkdownIt({
  html: props.html,
  breaks: props.breaks,
  linkify: props.linkify,
  typographer: true,
  highlight: (str, lang) => {
    if (lang && hljs.getLanguage(lang)) {
      try {
        return `<pre class="hljs-pre"><code class="hljs language-${lang}">` +
          hljs.highlight(str, { language: lang, ignoreIllegals: true }).value + '</code></pre>'
      } catch { /* 高亮失败走默认转义 */ }
    }
    return '<pre class="hljs-pre"><code class="hljs">' + md.utils.escapeHtml(str) + '</code></pre>'
  }
})
if (props.emoji) md.use(emoji)
md.use(taskLists)

// Mermaid 代码块:输出占位容器,渲染完成后由 mermaid.run 初始化
const defaultFence = md.renderer.rules.fence
md.renderer.rules.fence = (tokens, idx, options, env, self) => {
  const token = tokens[idx]
  const info = (token.info || '').trim().toLowerCase()
  if (info === 'mermaid' || info.startsWith('mermaid ')) {
    const code = encodeURIComponent(token.content)
    return `<div class="mermaid-block" data-code="${code}">` +
      `<pre class="mermaid-fallback"><code>${md.utils.escapeHtml(token.content)}</code></pre>` +
      `</div>`
  }
  return defaultFence(tokens, idx, options, env, self)
}

/**
 * 宽容预处理:修复大模型输出中常见的 markdown 不规范。
 * 这些写法在 CommonMark 严格规范下不会被解析(所有标准解析器一致):
 *   1) ATX 标题缺空格:`###1.标题` 应为 `### 1.标题`(# 后必须有空格);
 *   2) 强调符号与中文粘连:`**加粗**和` 的闭合 ** 后紧跟汉字,
 *      CommonMark 要求闭合符后必须是空白或标点,否则强调不生效。
 *   3) 列表前缀不规范:`•` `●` `○` `·` `▪` `◦` 等 → 统一转 `- `,否则
 *      markdown-it 不识别为列表,渲染成普通段落(`•` 当字面字符);
 *   4) `-` 后缺空格:`-资料` 应为 `- 资料`(markdown 列表要求 `- ` 带空格);
 *   5) 列表项之间多空行:连续 `- 项` 之间被空行打断会被拆成多个 <ul>,
 *      合并空行为单个换行,保持一个连续列表。
 * 处理时先把代码块/行内代码临时占位保护,避免误伤。
 */
function fixCommonMistakes(src) {
  const blocks = []
  const protect = (m) => { blocks.push(m); return '\u0000' + (blocks.length - 1) + '\u0000' }
  src = src.replace(/```[\s\S]*?```/g, protect)   // 围栏代码块(含 mermaid)
  src = src.replace(/`[^`\n]+`/g, protect)        // 行内代码
  src = src.replace(/(^|\n)(#{1,6})(?=[^\s#])/gm, '$1$2 ')   // 标题补空格
  src = src.replace(/(\*\*[^*\n]+?\*\*)(?=[^\s\p{P}\p{S}])/gu, '$1 ')  // 粗体闭合后补空格
  // 列表前缀标准化(• ● ○ · ▪ ◦ → - ):大模型常用 • 输出,markdown-it 不识别
  src = src.replace(/^[ \t]*[•●○·▪◦]\s+/gm, '- ')
  // `-` 后缺空格:`-资料` 应为 `- 资料`(markdown 列表要求 `- ` 带空格;数字/`-` 开头不误伤)
  src = src.replace(/^[ \t]*-(?=[^\s\d-])/gm, '- ')
  // 合并连续列表项之间的空行(避免被拆成多个 ul)
  src = src.replace(/(\n- .+)(?:\n[ \t]*\n)(?=- )/g, '$1\n')
  return src.replace(/\u0000(\d+)\u0000/g, (_, i) => blocks[+i])
}

// 流式输入时每次内容变化都会重新解析;
// markdown-it 对数百字文本解析在 1ms 级别,打字机场景无压力
const html = computed(() => DOMPurify.sanitize(md.render(fixCommonMistakes(props.source))))

watch(html, async () => {
  await nextTick()
  renderMermaid()
  addCopyButtons()
}, { immediate: true })

// ---------- Mermaid 渲染(逐块初始化,失败降级为源码) ----------
async function renderMermaid() {
  if (!rootEl.value) return
  const blocks = rootEl.value.querySelectorAll('.mermaid-block:not(.rendered):not(.failed)')
  for (const node of blocks) {
    try {
      await mermaid.run({ nodes: [node] })
      node.classList.add('rendered')
    } catch {
      node.classList.add('failed')   // 保留 fallback 源码展示
    }
  }
}

// ---------- 代码复制按钮 ----------
async function addCopyButtons() {
  if (!rootEl.value) return
  rootEl.value.querySelectorAll('pre.hljs-pre').forEach(pre => {
    if (pre.querySelector('.code-copy-btn')) return
    const btn = document.createElement('button')
    btn.className = 'code-copy-btn'
    btn.textContent = '复制'
    btn.type = 'button'
    btn.onclick = async () => {
      const code = pre.querySelector('code')
      const text = code ? code.innerText : pre.innerText
      try {
        await navigator.clipboard.writeText(text)
      } catch {
        const ta = document.createElement('textarea')
        ta.value = text
        ta.style.position = 'fixed'
        ta.style.opacity = '0'
        document.body.appendChild(ta)
        ta.select()
        document.execCommand('copy')
        ta.remove()
      }
      btn.textContent = '已复制 ✓'
      setTimeout(() => { btn.textContent = '复制' }, 1500)
    }
    pre.appendChild(btn)
  })
}
</script>

<template>
  <div ref="rootEl" class="markdown-body" v-html="html"></div>
</template>

<style scoped>
.markdown-body { word-break: break-word; }

/* 代码块(hljs 高亮 + 复制按钮) */
.markdown-body :deep(pre.hljs-pre) {
  position: relative;
  background: var(--code-bg);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 12px 14px;
  overflow-x: auto;
  margin: 8px 0;
  line-height: normal;
}
.markdown-body :deep(pre.hljs-pre code) {
  background: none;
  border: none;
  padding: 0;
  font-family: "JetBrains Mono", Consolas, "Courier New", monospace;
  font-size: 13px;
  color: var(--code-text);
  display: block;
}
.markdown-body :deep(.code-copy-btn) {
  position: absolute;
  top: 8px;
  right: 10px;
  border: 1px solid var(--border);
  background: var(--surface);
  color: var(--text-2);
  font-size: 12px;
  padding: 2px 10px;
  border-radius: 6px;
  cursor: pointer;
  opacity: 0;
  transition: opacity .2s, background .2s;
  z-index: 1;
}
.markdown-body :deep(pre.hljs-pre:hover .code-copy-btn) { opacity: 1; }
.markdown-body :deep(.code-copy-btn:hover) { background: var(--surface-2); color: var(--primary); }

/* hljs token 颜色(跟随主题变量) */
.markdown-body :deep(code.hljs .hljs-keyword),
.markdown-body :deep(code.hljs .hljs-selector-tag) { color: var(--code-keyword); }
.markdown-body :deep(code.hljs .hljs-string),
.markdown-body :deep(code.hljs .hljs-regexp) { color: var(--code-string); }
.markdown-body :deep(code.hljs .hljs-comment),
.markdown-body :deep(code.hljs .hljs-quote) { color: var(--code-comment); font-style: italic; }
.markdown-body :deep(code.hljs .hljs-number),
.markdown-body :deep(code.hljs .hljs-literal) { color: var(--code-number); }
.markdown-body :deep(code.hljs .hljs-title),
.markdown-body :deep(code.hljs .hljs-title.function_),
.markdown-body :deep(code.hljs .hljs-function .hljs-title) { color: var(--code-func); }
.markdown-body :deep(code.hljs .hljs-tag),
.markdown-body :deep(code.hljs .hljs-name),
.markdown-body :deep(code.hljs .hljs-built_in) { color: var(--code-tag); }
.markdown-body :deep(code.hljs .hljs-attr),
.markdown-body :deep(code.hljs .hljs-attribute),
.markdown-body :deep(code.hljs .hljs-params) { color: var(--code-attr); }
.markdown-body :deep(code.hljs .hljs-symbol),
.markdown-body :deep(code.hljs .hljs-bullet) { color: var(--code-number); }
.markdown-body :deep(code.hljs .hljs-meta),
.markdown-body :deep(code.hljs .hljs-variable) { color: var(--code-text); }

/* Mermaid 图表容器 */
.markdown-body :deep(.mermaid-block) {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 12px;
  margin: 8px 0;
  overflow-x: auto;
  text-align: center;
}
.markdown-body :deep(.mermaid-fallback) {
  text-align: left;
  margin: 0;
  font-family: "JetBrains Mono", Consolas, monospace;
  font-size: 13px;
  color: var(--text-2);
  white-space: pre-wrap;
}
.markdown-body :deep(.mermaid-block.rendered .mermaid-fallback) { display: none; }
</style>

<!--
  markdown 渲染样式(非 scoped):
  v-html 注入的内容不带组件 data-v 标记,scoped 样式无法命中,
  因此 markdown 的通用排版样式必须放在非 scoped 全局块中。
  仅作用于 .markdown-body 类,不影响其他区域。
-->
<style>
.markdown-body { word-break: break-word; max-width: 100%; }
.markdown-body > :first-child { margin-top: 0; }
.markdown-body > :last-child { margin-bottom: 0; }

.markdown-body p { margin: 4px 0; line-height: normal; overflow-wrap: anywhere; }
.markdown-body h1, .markdown-body h2, .markdown-body h3, .markdown-body h4 { margin: 2px 0 6px; font-weight: 500; }
.markdown-body h1 { font-size: 15px; }
.markdown-body h2 { font-size: 15px; }
.markdown-body h3 { font-size: 15px; }

/* 列表:彻底去掉圆点/数字标记,保留缩进 */
.markdown-body ul, .markdown-body ol { margin: 0 0; padding-left: 16px; list-style: none; line-height: 0;}
.markdown-body li { margin: 2px 0; overflow-wrap: anywhere; line-height: normal; }
.markdown-body li > p { margin: 4px 0; }
.markdown-body li > ul, .markdown-body li > ol { margin: 4px 0; list-style: none; }

.markdown-body code {
  background: var(--surface-2);
  border: 0.5px solid var(--border);
  padding: 1px 5px;
  border-radius: 4px;
  font-family: Consolas, "Courier New", monospace;
  font-size: 15px;
  word-break: break-all;
}
.markdown-body pre code { background: none; border: none; padding: 0; font-size: 15px; white-space: pre; word-break: normal; }
.markdown-body blockquote { border-left: 3px solid var(--border-2); padding-left: 12px; color: var(--text-2); margin: 2px 0; overflow-wrap: anywhere; }
.markdown-body table { border-collapse: collapse; margin: 8px 0; display: block; overflow-x: auto; max-width: 100%; }
.markdown-body th, .markdown-body td { border: 1px solid var(--border); padding: 4px 10px; font-size: 15px; overflow-wrap: anywhere; }
.markdown-body th { background: var(--surface-2); }
.markdown-body a { color: var(--primary); word-break: break-all; }
.markdown-body img { max-width: 100%; border-radius: 8px; }
.markdown-body hr { border: none; border-top: 1px solid var(--border); margin: 2px 0; }
</style>
