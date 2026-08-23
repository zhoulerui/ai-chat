<script setup>
// 神谕百科 · 智能问答组件(从 App.vue 剥离,与知识库页平级)
import { ref, computed, nextTick, watch, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  Microphone, CopyDocument, VideoPlay, ChatLineRound, Refresh,
  Plus, EditPen, Delete, ChatRound
} from '@element-plus/icons-vue'
import Markdown from './Markdown.vue'

// ---------- 游戏选择(声明在前,会话逻辑依赖) ----------
const games = ref([])
const gameId = ref(null)

// ---------- 多会话 ----------
const conversations = ref([])
const activeConvId = ref(null)
const convRenaming = ref(false)
const renameTarget = ref(null)
const renameTitle = ref('')
const convDeleteVisible = ref(false)
const convDeleting = ref(null)
let switchingConv = false

const DEFAULT_GREETING = '你好,我是神谕百科,可以问我任何游戏问题,例如:甘雨怎么配队?'

async function loadConversations() {
  try {
    const r = await fetch('/api/chat/conversations')
    conversations.value = await r.json()
  } catch { conversations.value = [] }
}

async function ensureConversation() {
  await loadConversations()
  if (conversations.value.length) {
    switchConversation(conversations.value[0])
  } else {
    await createConversation()
  }
}

// 新建会话(绑定当前游戏)
async function createConversation() {
  try {
    const r = await fetch('/api/chat/conversations', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ gameId: gameId.value || null })
    })
    const j = await r.json()
    activeConvId.value = j.id
    messages.value = [{ role: 'assistant', content: DEFAULT_GREETING }]
    await loadConversations()
  } catch { /* 忽略 */ }
}

async function switchConversation(c) {
  activeConvId.value = c.id
  switchingConv = true
  gameId.value = c.gameId
  await loadMessages(c.id)
}

async function loadMessages(convId) {
  try {
    const r = await fetch(`/api/chat/conversations/${convId}/messages`)
    const list = await r.json()
    const msgs = (list || []).map(m => {
      const msg = { role: m.role, content: m.content }
      if (m.role === 'assistant' && m.referencesJson) {
        try {
          const parsed = JSON.parse(m.referencesJson)
          // 兼容新旧格式:新格式 {references:[...], reasoning:"..."},旧格式裸数组
          if (Array.isArray(parsed)) {
            msg.references = parsed
          } else {
            msg.references = parsed.references || []
            if (parsed.reasoning) msg.reasoning = parsed.reasoning
          }
        } catch { msg.references = [] }
      }
      return msg
    })
    messages.value = msgs.length ? msgs : [{ role: 'assistant', content: DEFAULT_GREETING }]
  } catch {
    messages.value = [{ role: 'assistant', content: DEFAULT_GREETING }]
  }
}

function askRename(c) {
  renameTarget.value = c
  renameTitle.value = c.title
  convRenaming.value = true
}
async function confirmRename() {
  const t = renameTitle.value.trim()
  if (t && renameTarget.value) {
    try {
      await fetch('/api/chat/conversations/' + renameTarget.value.id, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ title: t })
      })
      renameTarget.value.title = t
    } catch { /* 忽略 */ }
  }
  convRenaming.value = false
}
function askDeleteConv(c) {
  convDeleting.value = c
  convDeleteVisible.value = true
}
async function confirmDeleteConv() {
  const c = convDeleting.value
  if (!c) return
  convDeleteVisible.value = false
  convDeleting.value = null
  try {
    await fetch('/api/chat/conversations/' + c.id, { method: 'DELETE' })
    ElMessage.success('会话已删除')
  } catch {
    ElMessage.error('删除失败')
    return
  }
  if (activeConvId.value === c.id) {
    await ensureConversation()
  } else {
    await loadConversations()
  }
}

// 用户手动切换游戏 → 自动开一个绑定新游戏的新会话(会话绑定游戏)
watch(gameId, async (val, old) => {
  if (switchingConv) { switchingConv = false; return }
  if (val === old) return
  if (activeConvId.value) await createConversation()
})

// ---------- 游戏选择 ----------
async function loadGames() {
  try {
    const r = await fetch('/api/kb/game')
    games.value = await r.json()
    // 当前选中的游戏已被删除时自动清空选择
    if (gameId.value && !games.value.some(g => g.id === gameId.value)) {
      gameId.value = null
    }
  } catch { /* 知识库未就绪时静默 */ }
}

// ---------- 聊天 ----------
const messages = ref([
  { role: 'assistant', content: '你好,我是神谕百科,可以问我任何游戏问题,例如:甘雨怎么配队?' }
])
const input = ref('')
const loading = ref(false)
const listEl = ref(null)
// 模型档位:fast=快速(deepseek-chat) / deep=深度思考(deepseek-v4-flash) / pro=旗舰深度思考(deepseek-v4-pro)
const modelId = ref('fast')
const modelOptions = [
  { id: 'fast', label: '⚡ 快速', hint: 'deepseek-chat' },
  { id: 'deep', label: '🧠 深度思考', hint: 'deepseek-v4-flash' },
  { id: 'pro', label: '🚀 Pro 深度思考', hint: 'deepseek-v4-pro' }
]

let abortCtrl = null
let recognition = null
const listening = ref(false)
const speakingId = ref(null)

// 参考来源:最后一条带 references 的 AI 回复
const activeRefs = computed(() => {
  for (let i = messages.value.length - 1; i >= 0; i--) {
    const m = messages.value[i]
    if (m.role === 'assistant' && m.references && m.references.length) return m.references
  }
  return null
})

// 侧边栏:展开某条来源的完整正文(-1 = 不展开)
const expandRef = ref(-1)
function toggleRef(i) {
  expandRef.value = expandRef.value === i ? -1 : i
}

watch(messages, () => {
  nextTick(() => {
    if (listEl.value) listEl.value.scrollTop = listEl.value.scrollHeight
  })
}, { deep: true })

async function send() {
  const text = input.value.trim()
  if (!text || loading.value) return
  input.value = ''
  messages.value.push({ role: 'user', content: text })
  messages.value.push({ role: 'assistant', content: '' })
  loading.value = true

  const ai = messages.value[messages.value.length - 1]
  // 深度思考模型:预留思考过程容器(有内容才显示)
  ai.reasoning = ''
  ai.reasoningOpen = true
  abortCtrl = new AbortController()
  try {
    // 发送前剔除"空内容 assistant"占位消息(当前轮流式占位)。
    // 否则 DeepSeek 看到最后一条 assistant 消息为空且无 reasoning_content,
    // 会判定为工具轮缺失 reasoning → 400 "reasoning_content must be passed back"
    const sendMsgs = messages.value.filter(m => !(m.role === 'assistant' && !m.content))
    const res = await fetch('/api/chat/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ gameId: gameId.value || null, conversationId: activeConvId.value || null, modelId: modelId.value, messages: sendMsgs }),
      signal: abortCtrl.signal
    })
    if (!res.ok || !res.body) throw new Error('HTTP ' + res.status)

    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buf = ''
    let currentEvent = ''
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buf += decoder.decode(value, { stream: true })
      let idx
      while ((idx = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, idx).trim()
        buf = buf.slice(idx + 1)
        if (line.startsWith('event:')) {
          currentEvent = line.slice(6).trim()
        } else if (line.startsWith('data:')) {
          const raw = line.slice(5).trimStart().replace(/\r$/, '')
          let data = raw
          try { data = JSON.parse(raw) } catch { /* 保留原文 */ }
          if (data) {
            if (currentEvent === 'error') {
              ai.error = true
              ai.content = '请求失败:' + data
            } else if (currentEvent === 'references') {
              ai.references = parseRefs(data)
              currentEvent = ''
            } else if (currentEvent === 'reasoning') {
              // 思考过程(仅深度思考模型):累积,灰色折叠区渲染
              ai.reasoning += data
              currentEvent = ''
            } else {
              // 思考结束后自动收起折叠区,专注最终答案
              if (ai.reasoning && ai.reasoningOpen && !ai.content) {
                ai.reasoningOpen = false
              }
              ai.content += data
            }
          }
        }
      }
    }
    if (!ai.content.trim()) ai.content = '(无响应)'
  } catch (e) {
    if (e.name === 'AbortError') {
      ai.stopped = true
    } else {
      ai.error = true
      ai.content = '请求失败:' + e.message
    }
  } finally {
    loading.value = false
    abortCtrl = null
    // 回答完成/停止后,刷新会话列表(标题、排序已更新)
    loadConversations()
  }
}

// 参考来源解析:后端 sendEvent 对字符串又序列化了一次(JSON 二次编码),
// 需先 parse 出内层 JSON 字符串,再 parse 成数组
function parseRefs(raw) {
  let v = raw
  try { v = JSON.parse(v) } catch { return [] }
  if (typeof v === 'string') {
    try { v = JSON.parse(v) } catch { return [] }
  }
  return Array.isArray(v) ? v : []
}

function stop() { if (abortCtrl) abortCtrl.abort() }

async function copy(m) {
  if (!m.content) return
  const text = m.content
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text)
    } else {
      const ta = document.createElement('textarea')
      ta.value = text
      ta.style.position = 'fixed'
      ta.style.opacity = '0'
      document.body.appendChild(ta)
      ta.select()
      document.execCommand('copy')
      ta.remove()
    }
    m.copied = true
    ElMessage({ message: '已复制', type: 'success', duration: 1200 })
    setTimeout(() => { m.copied = false }, 1500)
  } catch {
    ElMessage({ message: '复制失败', type: 'warning' })
  }
}

function getRecognition() {
  const SR = window.SpeechRecognition || window.webkitSpeechRecognition
  if (!SR) return null
  const r = new SR()
  r.lang = 'zh-CN'
  r.interimResults = false
  r.continuous = false
  r.onresult = (e) => { input.value = e.results[0][0].transcript }
  r.onend = () => { listening.value = false }
  r.onerror = () => { listening.value = false }
  return r
}

function toggleVoice() {
  if (!recognition) {
    recognition = getRecognition()
    if (!recognition) {
      ElMessage({ message: '当前浏览器不支持语音识别,请使用 Chrome 或 Edge', type: 'warning' })
      return
    }
  }
  if (listening.value) {
    recognition.stop()
    listening.value = false
  } else {
    try {
      recognition.start()
      listening.value = true
    } catch { listening.value = false }
  }
}

function plainText(content) {
  return content
    .replace(/```[\s\S]*?```/g, '代码块。')
    .replace(/`([^`\n]+)`/g, '$1')
    .replace(/!\[[^\]]*\]\([^)]*\)/g, '图片。')
    .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/[*_~#>|]/g, '')
}

function speak(m) {
  if (!('speechSynthesis' in window)) return
  if (speakingId.value === m) {
    window.speechSynthesis.cancel()
    speakingId.value = null
    return
  }
  window.speechSynthesis.cancel()
  const u = new SpeechSynthesisUtterance(plainText(m.content))
  u.lang = 'zh-CN'
  u.onend = () => { speakingId.value = null }
  u.onerror = () => { speakingId.value = null }
  window.speechSynthesis.speak(u)
  speakingId.value = m
}

function reset() {
  window.speechSynthesis?.cancel()
  // 清空当前会话并新建一个
  createConversation()
}

defineExpose({ reset })

onMounted(async () => {
  await loadGames()
  await ensureConversation()
})
</script>

<template>
  <div class="chat-layout">
    <!-- 会话栏 -->
    <aside class="conv-side">
      <div class="conv-head">
        <span class="conv-title">会话</span>
        <el-tooltip content="新建会话">
          <el-button circle size="small" class="conv-add" @click="createConversation">
            <el-icon><Plus /></el-icon>
          </el-button>
        </el-tooltip>
      </div>
      <div class="conv-list">
        <div v-for="c in conversations" :key="c.id"
          class="conv-item" :class="{ active: activeConvId === c.id }"
          @click="switchConversation(c)">
          <el-icon class="conv-item-icon"><ChatRound /></el-icon>
          <span class="conv-item-title">{{ c.title }}</span>
          <span class="conv-item-ops" @click.stop>
            <el-icon class="conv-op" title="重命名" @click="askRename(c)"><EditPen /></el-icon>
            <el-icon class="conv-op danger" title="删除" @click="askDeleteConv(c)"><Delete /></el-icon>
          </span>
        </div>
        <div v-if="!conversations.length" class="conv-empty">暂无会话,点击右上角新建</div>
      </div>
    </aside>

    <div class="chat-col">
      <div class="game-bar">
        <span class="game-label">当前游戏</span>
        <el-select v-model="gameId" clearable placeholder="通用(不检索知识库)" style="width: 180px">
          <el-option v-for="g in games" :key="g.id" :label="g.name" :value="g.id" />
        </el-select>
        <el-tooltip content="刷新游戏列表">
          <el-button class="refresh-btn" circle size="small" @click="loadGames">
            <el-icon><Refresh /></el-icon>
          </el-button>
        </el-tooltip>
        <span class="game-hint">回答将基于该游戏知识库</span>
        <span class="bar-spacer"></span>
        <el-select v-model="modelId" style="width: 150px" size="small">
          <el-option v-for="mo in modelOptions" :key="mo.id" :label="mo.label" :value="mo.id">
            <span>{{ mo.label }}</span>
            <span class="model-hint">{{ mo.hint }}</span>
          </el-option>
        </el-select>
      </div>

      <main ref="listEl" class="msg-list">
        <div v-for="(m, i) in messages" :key="i" class="msg-wrap" :class="m.role">
          <div class="bubble" :class="{ err: m.error }">
            <!-- 思考过程折叠区:仅深度思考模型的回复 -->
            <div v-if="m.role === 'assistant' && m.reasoning" class="reasoning-block">
              <button class="reasoning-toggle" @click="m.reasoningOpen = !m.reasoningOpen">
                <span class="reasoning-arrow" :class="{ open: m.reasoningOpen }">▸</span>
                <span class="reasoning-label">🤔 思考过程</span>
                <span class="reasoning-state">{{ m.reasoningOpen ? '收起' : '展开' }}</span>
              </button>
              <div v-if="m.reasoningOpen" class="reasoning-content">{{ m.reasoning }}</div>
            </div>
            <Markdown v-if="m.role === 'assistant' && !m.error" :source="m.content" />
            <template v-else>{{ m.content }}</template>
            <span v-if="loading && m === messages[messages.length - 1]" class="caret"></span>
            <span v-if="m.stopped" class="stopped-tag">已停止</span>
          </div>
          <div class="actions">
            <el-button link size="small" :disabled="!m.content" @click="copy(m)">
              <el-icon class="act-icon"><CopyDocument /></el-icon>
              {{ m.copied ? '已复制' : '复制' }}
            </el-button>
            <el-button v-if="m.role === 'assistant' && !m.error && m.content" link size="small" @click="speak(m)">
              <el-icon class="act-icon"><VideoPlay /></el-icon>
              {{ speakingId === m ? '停止朗读' : '朗读' }}
            </el-button>
            <span v-if="m.references && m.references.length" class="ref-tag">
              <el-icon class="act-icon"><ChatLineRound /></el-icon>
              参考来源 {{ m.references.length }}
            </span>
          </div>
        </div>
      </main>

      <footer class="input-bar">
        <el-button class="mic" :class="{ on: listening }" circle
          :title="listening ? '停止聆听' : '语音输入'" @click="toggleVoice">
          <el-icon :size="18"><Microphone /></el-icon>
        </el-button>
        <el-input
          v-model="input"
          :disabled="loading"
          :placeholder="listening ? '正在聆听...' : '输入消息,回车发送'"
          size="large"
          @keyup.enter="send"
        />
        <el-button class="send" :type="loading ? 'danger' : 'primary'" size="large"
          :disabled="!loading && !input.trim()"
          @click="loading ? stop() : send()">
          {{ loading ? '停止' : '发送' }}
        </el-button>
      </footer>
    </div>

    <aside class="refs-side">
      <div class="refs-title">参考来源</div>
      <template v-if="activeRefs && activeRefs.length">
        <div v-for="(r, i) in activeRefs" :key="i" class="ref-item" @click="toggleRef(i)">
          <div class="ref-head">
            <span class="ref-title">{{ r.articleTitle || '参考条目 #' + (r.chunkId ?? '') }}</span>
            <span class="ref-score">{{ Math.round((r.similarity ?? 0) * 100) }}%</span>
          </div>
          <p class="ref-content">{{ r.content }}</p>
          <div v-if="expandRef === i && r.articleContent" class="ref-full">
            <div class="ref-full-title">{{ r.articleTitle || '完整正文' }}</div>
            <pre class="ref-full-content">{{ r.articleContent }}</pre>
          </div>
          <span v-if="r.articleContent" class="ref-expand-hint">{{ expandRef === i ? '收起全文 ▲' : '查看完整正文 ▼' }}</span>
        </div>
      </template>
      <p v-else class="refs-empty">选择游戏并提问后,这里会展示回答所依据的知识库内容</p>
    </aside>

    <!-- 重命名会话 -->
    <el-dialog v-model="convRenaming" title="重命名会话" width="380px" :close-on-click-modal="false">
      <el-input v-model="renameTitle" maxlength="50" show-word-limit @keyup.enter="confirmRename" />
      <template #footer>
        <el-button @click="convRenaming = false">取消</el-button>
        <el-button type="primary" @click="confirmRename">保存</el-button>
      </template>
    </el-dialog>

    <!-- 删除会话确认 -->
    <el-dialog v-model="convDeleteVisible" title="删除确认" width="380px" :close-on-click-modal="false">
      <div class="del-body">
        <div class="del-icon"><el-icon :size="20"><Delete /></el-icon></div>
        <p class="del-text">确定删除会话「<b>{{ convDeleting?.title }}</b>」吗?其中所有消息将一并删除,<b>不可恢复</b>。</p>
      </div>
      <template #footer>
        <el-button @click="convDeleteVisible = false">取消</el-button>
        <el-button type="danger" @click="confirmDeleteConv">删除</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.chat-layout { height: 100%; display: flex; min-height: 0; }

/* 会话栏 */
.conv-side {
  width: 220px;
  flex-shrink: 0;
  border-right: 1px solid var(--border);
  background: color-mix(in srgb, var(--surface) 85%, transparent);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.conv-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 14px 14px 10px;
}
.conv-title { font-size: 15px; font-weight: 600; letter-spacing: .2px; }
.conv-add { color: var(--primary); }
.conv-list { flex: 1; overflow-y: auto; padding: 0 10px 12px; display: flex; flex-direction: column; gap: 4px; }
.conv-item {
  display: flex; align-items: center; gap: 8px;
  padding: 9px 10px; border-radius: 10px; cursor: pointer;
  color: var(--text); position: relative;
  transition: background .15s, box-shadow .15s;
}
.conv-item:hover { background: var(--surface); box-shadow: var(--shadow-sm); }
.conv-item.active { background: var(--surface); box-shadow: var(--shadow-sm); }
.conv-item.active::before {
  content: '';
  position: absolute; left: -10px; top: 50%; transform: translateY(-50%);
  width: 3px; height: 20px; border-radius: 3px;
  background: var(--grad-brand);
}
.conv-item-icon { color: var(--primary); flex-shrink: 0; font-size: 15px; }
.conv-item-title {
  flex: 1; min-width: 0; font-size: 14px;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.conv-item-ops { display: none; gap: 2px; flex-shrink: 0; }
.conv-item:hover .conv-item-ops { display: inline-flex; }
.conv-op { font-size: 13px; color: var(--text-3); cursor: pointer; padding: 2px; border-radius: 4px; }
.conv-op:hover { color: var(--primary); background: var(--surface-2); }
.conv-op.danger:hover { color: var(--danger); }
.conv-empty { font-size: 13px; color: var(--text-3); padding: 12px 10px; }

.del-body { display: flex; gap: 12px; align-items: flex-start; }
.del-icon {
  width: 36px; height: 36px; flex-shrink: 0; border-radius: 9px;
  display: flex; align-items: center; justify-content: center;
  background: color-mix(in srgb, var(--danger) 14%, transparent);
  color: var(--danger);
}
.del-text { font-size: 14px; color: var(--text-2); line-height: 1.7; margin: 0; }
.del-text b { color: var(--text); }
.del-text b:last-child { color: var(--danger); }

.chat-col { flex: 1; display: flex; flex-direction: column; min-width: 0; }

.game-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 24px;
  border-bottom: 1px solid var(--border);
  background: color-mix(in srgb, var(--surface) 78%, transparent);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
}
.game-label { font-size: 14px; color: var(--text-2); }
.refresh-btn { margin-left: 2px; }
.game-hint { font-size: 13px; color: var(--text-3); }
.bar-spacer { flex: 1; }
.model-hint { color: var(--text-3); font-size: 12px; margin-left: 8px; }

/* 思考过程折叠区(深度思考模型) */
.reasoning-block {
  margin: 2px 0 10px;
  border: 0.5px solid var(--border);
  border-radius: 8px;
  background: var(--surface-2);
  overflow: hidden;
}
.reasoning-toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 6px 10px;
  border: none;
  background: none;
  cursor: pointer;
  color: var(--text-2);
  font-size: 13px;
  font-family: inherit;
}
.reasoning-toggle:hover { color: var(--text-1); }
.reasoning-arrow { display: inline-block; transition: transform 0.2s; font-size: 12px; }
.reasoning-arrow.open { transform: rotate(90deg); }
.reasoning-label { font-weight: 500; }
.reasoning-state { margin-left: auto; font-size: 12px; color: var(--text-3); }
.reasoning-content {
  padding: 8px 12px;
  color: var(--text-2);
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  border-top: 0.5px solid var(--border);
  max-height: 240px;
  overflow-y: auto;
}

.msg-list {
  flex: 1;
  overflow-y: auto;
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 18px;
  background: var(--bg);
}
.msg-wrap { display: flex; flex-direction: column; gap: 4px; max-width: 78%; min-width: 0; }
.msg-wrap.user { align-self: flex-end; align-items: flex-end; }
.msg-wrap.assistant { align-self: flex-start; align-items: flex-start; }
.bubble {
  padding: 12px 16px;
  border-radius: 14px;
  font-size: 16px;
  line-height: normal;
  white-space: pre-wrap;
  word-break: break-word;
  overflow-wrap: anywhere;
  max-width: 100%;
}
.msg-wrap.user .bubble {
  background: linear-gradient(135deg, var(--user-grad-a), var(--user-grad-b));
  color: #fff;
  border-bottom-right-radius: 4px;
  box-shadow: 0 4px 14px color-mix(in srgb, var(--user-grad-a) 35%, transparent);
}
.msg-wrap.assistant .bubble {
  background: var(--bubble-ai);
  border: 1px solid var(--border);
  border-bottom-left-radius: 4px;
  box-shadow: var(--shadow-sm);
}
.bubble.err { color: var(--danger); }
.stopped-tag { font-size: 13px; color: var(--text-3); margin-left: 6px; }

.actions { display: flex; align-items: center; gap: 2px; padding: 0 2px; opacity: .55; transition: opacity .2s; }
.msg-wrap:hover .actions, .actions:focus-within { opacity: 1; }
.act-icon { margin-right: 3px; vertical-align: -2px; }
.ref-tag { font-size: 13px; color: var(--text-3); margin-left: 8px; display: inline-flex; align-items: center; }

.input-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 24px;
  background: color-mix(in srgb, var(--surface) 82%, transparent);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border-top: 1px solid var(--border);
  box-shadow: 0 -6px 24px rgba(23, 30, 46, .04);
}
html.dark .input-bar { box-shadow: 0 -6px 24px rgba(0, 0, 0, .25); }
.input-bar .el-input { flex: 1; }
.mic.on { background: var(--danger); border-color: var(--danger); color: #fff; }
.send {
  min-width: 92px;
  background: linear-gradient(135deg, var(--user-grad-a), var(--user-grad-b));
  border: none;
  box-shadow: 0 3px 10px color-mix(in srgb, var(--user-grad-a) 35%, transparent);
}
.send:hover { opacity: .92; }

.refs-side {
  width: 300px;
  flex-shrink: 0;
  border-left: 1px solid var(--border);
  background: color-mix(in srgb, var(--surface) 78%, transparent);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  overflow-y: auto;
  padding: 18px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.refs-title { font-size: 15px; font-weight: 600; margin-bottom: 2px; letter-spacing: .2px; }
.ref-item {
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 10px 12px;
  background: var(--surface);
  cursor: pointer;
  box-shadow: var(--shadow-sm);
  transition: box-shadow .2s, border-color .2s, transform .15s;
}
.ref-item:hover { border-color: color-mix(in srgb, var(--primary) 45%, var(--border)); box-shadow: var(--shadow-md); transform: translateY(-1px); }
.ref-head { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.ref-title { flex: 1; min-width: 0; font-size: 14px; font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.ref-score { flex-shrink: 0; font-size: 12px; color: var(--primary); background: color-mix(in srgb, var(--primary) 12%, transparent); padding: 1px 8px; border-radius: 10px; }
.ref-content { font-size: 13px; line-height: 1.6; color: var(--text-2); }
.ref-expand-hint { display: block; margin-top: 6px; font-size: 12px; color: var(--text-3); }
.ref-full { margin-top: 8px; border-top: 1px dashed var(--border); padding-top: 8px; }
.ref-full-title { font-size: 13px; font-weight: 500; margin-bottom: 4px; }
.ref-full-content {
  white-space: pre-wrap; word-break: break-word;
  font-family: inherit; font-size: 12px; line-height: 1.6;
  color: var(--text-2); max-height: 260px; overflow-y: auto;
}
.refs-empty { font-size: 14px; color: var(--text-3); line-height: 1.7; }

.caret {
  display: inline-block;
  width: 8px;
  height: 15px;
  margin-left: 2px;
  vertical-align: -2px;
  background: var(--primary);
  animation: blink 1s step-start infinite;
}
@keyframes blink { 50% { opacity: 0; } }

/* 注意:markdown 渲染样式(markdown-body)已迁移至 Markdown.vue 的全局样式块。
   scoped 样式无法命中 v-html 注入的内容,放这里永不生效。 */

@media (max-width: 1024px) {
  .refs-side { display: none; }
}
@media (max-width: 768px) {
  .conv-side { display: none; }   /* 移动端优先聊天,会话栏隐藏 */
  .game-bar { padding: 8px 14px; }
  .game-hint { display: none; }
  .msg-list { padding: 16px 12px; gap: 14px; }
  .msg-wrap { max-width: 88%; }
  .actions { opacity: 1; }
  .input-bar { padding: 12px 12px calc(12px + env(safe-area-inset-bottom)); gap: 8px; }
  .send { min-width: 68px; padding: 0 14px; }
}
</style>
