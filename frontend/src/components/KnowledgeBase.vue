<script setup>
// 神谕百科 · 知识库页:选游戏 → 条目列表(已向量化/删除)→ 点击弹窗详情 → 关键词过滤高亮
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { UploadFilled, Document, Delete, View, Search } from '@element-plus/icons-vue'

const games = ref([])
const gameId = ref(null)
const newGameName = ref('')

const articles = ref([])        // 当前游戏的全部条目
const keyword = ref('')         // 关键词过滤 + 高亮
const detailVisible = ref(false)
const detailArticle = ref(null)

const searchOpen = ref(false)   // 相似度检索测试面板(可选展开)
const searchQuery = ref('')
const searchHits = ref([])
const searching = ref(false)
const expandIdx = ref(-1)

const deleteVisible = ref(false)   // 删除确认弹窗(项目风格,替代默认 MessageBox)
const deleteTarget = ref(null)
const deleting = ref(false)

const uploadVisible = ref(false)
const uploadTitle = ref('')
const uploadCount = ref(0)
const uploading = ref(false)
const uploadRef = ref(null)
const pendingFiles = ref([])    // el-upload 不 expose uploadFiles,由 on-change 回调保存
let pendingUploads = 0

const uploadTab = ref('local')  // 上传弹窗 Tab:local 本地上传 / url 网址导入
const urlInput = ref('')
const urlTitle = ref('')
const urlLoading = ref(false)

// 网址一键入库
async function doImportUrl() {
  const u = urlInput.value.trim()
  if (!u || !gameId.value) return
  urlLoading.value = true
  try {
    const r = await fetch('/api/kb/import-url', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ gameId: gameId.value, url: u, title: urlTitle.value.trim() || null })
    })
    const j = await r.json()
    if (!r.ok) {
      ElMessage.error(j && (j.message || j.error) ? (j.message || j.error) : '抓取失败')
      return
    }
    ElMessage.success(`已入库「${j.title}」,提取 ${j.chars} 字,生成 ${j.chunks} 个分块`)
    urlInput.value = ''
    urlTitle.value = ''
    uploadTab.value = 'local'
    loadArticles()
  } catch (e) {
    ElMessage.error('抓取失败:' + e.message)
  } finally {
    urlLoading.value = false
  }
}

async function loadGames() {
  try {
    const r = await fetch('/api/kb/game')
    games.value = await r.json()
    if (!gameId.value && games.value.length) gameId.value = games.value[0].id
    loadArticles()
  } catch { /* ignore */ }
}

async function createGame() {
  const name = newGameName.value.trim()
  if (!name) return
  try {
    const r = await fetch('/api/kb/game', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name })
    })
    const j = await r.json()
    if (!r.ok) throw new Error(j.message || '创建失败')
    newGameName.value = ''
    ElMessage.success('游戏已创建')
    gameId.value = j.id
    await loadGames()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

// 当前游戏的全部百科条目
async function loadArticles() {
  if (!gameId.value) { articles.value = []; return }
  try {
    const r = await fetch('/api/kb/articles?gameId=' + gameId.value)
    articles.value = await r.json()
  } catch { articles.value = [] }
}

// 关键词过滤(标题/内容)
const filteredArticles = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return articles.value
  return articles.value.filter(a =>
    (a.title || '').toLowerCase().includes(kw) || (a.content || '').toLowerCase().includes(kw))
})

// 关键词高亮(转义正则特殊字符)
function highlight(text) {
  const kw = keyword.value.trim()
  if (!kw || !text) return text
  const esc = kw.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return text.replace(new RegExp(esc, 'gi'), m => `<mark class="hl">${m}</mark>`)
}

// 点击条目弹窗详情
function showDetail(a) {
  detailArticle.value = a
  detailVisible.value = true
}

// 删除条目(先弹项目风格确认框)
function removeArticle(a) {
  deleteTarget.value = a
  deleteVisible.value = true
}

async function confirmDelete() {
  if (!deleteTarget.value || deleting.value) return
  deleting.value = true
  try {
    await fetch('/api/kb/article/' + deleteTarget.value.id, { method: 'DELETE' })
    ElMessage.success('已删除')
    deleteVisible.value = false
    loadArticles()
  } catch {
    ElMessage.error('删除失败')
  } finally {
    deleting.value = false
  }
}

// ---------- 上传(拖拽 + 多文件 + XHR 进度) ----------
function customUpload(opt) {
  const xhr = new XMLHttpRequest()
  xhr.open('POST', '/api/kb/upload')
  const fd = new FormData()
  fd.append('gameId', gameId.value)
  fd.append('file', opt.file)
  if (uploadTitle.value.trim()) fd.append('title', uploadTitle.value.trim())
  xhr.upload.onprogress = (e) => {
    if (e.lengthComputable) opt.onProgress({ percent: Math.round((e.loaded / e.total) * 100) })
  }
  xhr.onload = () => {
    try { opt.onSuccess(JSON.parse(xhr.responseText)) } catch { opt.onError(new Error('响应解析失败')) }
  }
  xhr.onerror = () => opt.onError(new Error('上传失败'))
  xhr.send(fd)
}

function onUploadSuccess(res, file) {
  ElMessage.success(`${file.name} 入库成功,生成 ${res.chunks} 个分块`)
  pendingUploads--
  if (pendingUploads <= 0) {
    uploading.value = false
    ElMessage.success('全部文件上传完成')
    setTimeout(() => {
      uploadRef.value?.clearFiles()
      pendingFiles.value = []
      uploadCount.value = 0
    }, 800)
  }
  loadArticles()
}

function onUploadError(err, file) {
  ElMessage.error(`${file.name} 上传失败`)
  pendingUploads--
  if (pendingUploads <= 0) uploading.value = false
}

function onUploadChange(uploadFile, uploadFiles) {
  pendingFiles.value = uploadFiles
  uploadCount.value = uploadFiles.length
}

function startUpload() {
  const files = pendingFiles.value || []
  pendingUploads = files.filter(f => f.status !== 'success').length
  if (!pendingUploads) return
  uploading.value = true
  uploadRef.value.submit()
}

function closeUpload() {
  uploadVisible.value = false
  uploadTitle.value = ''
  uploading.value = false
}

// ---------- 相似度检索测试 ----------
async function doSearch() {
  if (!gameId.value) return ElMessage.warning('请先选择游戏')
  if (!searchQuery.value.trim()) return
  searching.value = true
  try {
    const r = await fetch('/api/kb/search', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ gameId: gameId.value, query: searchQuery.value, topK: 5 })
    })
    const j = await r.json()
    if (!r.ok) {
      ElMessage.error(j && j.error ? '检索失败:' + j.error : '检索失败')
      searchHits.value = []
      return
    }
    searchHits.value = Array.isArray(j) ? j : []
  } catch (e) {
    ElMessage.error('检索失败:' + e.message)
    searchHits.value = []
  } finally { searching.value = false }
}

onMounted(loadGames)
</script>

<template>
  <div class="kb-layout">
    <aside class="kb-side">
      <div class="kb-side-title">游戏列表</div>
      <div class="kb-new">
        <el-input v-model="newGameName" size="small" placeholder="新游戏名" @keyup.enter="createGame" />
        <el-button size="small" type="primary" @click="createGame">新建</el-button>
      </div>
      <div
        v-for="g in games" :key="g.id"
        class="kb-game-item" :class="{ active: gameId === g.id }"
        @click="gameId = g.id; loadArticles()"
      >
        <span class="kb-game-avatar">{{ (g.name || '?').slice(0, 1) }}</span>
        <span class="kb-game-name">{{ g.name }}</span>
      </div>
      <div v-if="!games.length" class="kb-side-empty">暂无游戏,先新建一个</div>
    </aside>

    <div class="kb-main">
      <div class="kb-toolbar">
        <p class="kb-current">{{ (games.find(g => g.id === gameId) || {}).name || '未选择游戏' }}</p>
        <el-input v-model="keyword" class="kb-kw" placeholder="输入关键词过滤条目并高亮" clearable>
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button @click="searchOpen = !searchOpen">检索测试</el-button>
        <el-button type="primary" :disabled="!gameId" @click="uploadVisible = true">
          <el-icon style="margin-right: 4px"><UploadFilled /></el-icon>上传文档
        </el-button>
      </div>

      <div v-if="searchOpen" class="kb-search">
        <div class="kb-search-row">
          <el-input v-model="searchQuery" placeholder="输入问题,如:甘雨怎么配队" @keyup.enter="doSearch" />
          <el-button type="primary" :loading="searching" @click="doSearch">检索</el-button>
        </div>
        <div v-if="searchHits.length" class="kb-hits">
          <div v-for="(h, i) in searchHits" :key="i" class="kb-hit-card">
            <div class="kb-hit">
              <span class="kb-score">{{ (h.similarity ?? 0).toFixed(3) }}</span>
              <span class="kb-hit-text">{{ h.content }}</span>
              <el-button v-if="h.articleContent" link size="small" class="kb-article-toggle"
                @click="expandIdx = expandIdx === i ? -1 : i">
                {{ expandIdx === i ? '收起正文' : '完整正文' }}
              </el-button>
            </div>
            <div v-if="expandIdx === i && h.articleContent" class="kb-article">
              <div class="kb-article-title">{{ h.articleTitle || '完整正文' }}</div>
              <pre class="kb-article-content">{{ h.articleContent }}</pre>
            </div>
          </div>
        </div>
      </div>

      <!-- 条目列表:默认展示全部,关键词过滤 + 高亮 -->
      <div class="kb-list">
        <div v-for="a in filteredArticles" :key="a.id" class="kb-card" @click="showDetail(a)">
          <div class="kb-card-head">
            <span class="kb-card-title" v-html="highlight(a.title)"></span>
            <span v-if="a.type" class="kb-type">{{ a.type }}</span>
            <span class="kb-vec" :class="{ ok: a.vectorized }">
              {{ a.vectorized ? '已向量化' : '未向量化' }}
            </span>
            <span class="kb-count">{{ a.chunkCount || 0 }} 分块</span>
            <span class="kb-card-ops" @click.stop>
              <el-button link size="small" @click="showDetail(a)">
                <el-icon style="margin-right: 2px"><View /></el-icon>详情
              </el-button>
              <el-button link size="small" type="danger" @click="removeArticle(a)">
                <el-icon style="margin-right: 2px"><Delete /></el-icon>删除
              </el-button>
            </span>
          </div>
          <p class="kb-card-content" v-html="highlight(a.content)"></p>
        </div>
        <div v-if="!filteredArticles.length" class="kb-empty">
          <el-icon style="margin-bottom: 6px; font-size: 28px;"><Document /></el-icon>
          <p v-if="keyword">没有匹配「{{ keyword }}」的条目</p>
          <p v-else>暂无条目,点击右上角「上传文档」入库</p>
        </div>
      </div>
    </div>

    <!-- 条目详情弹窗 -->
    <el-dialog v-model="detailVisible" :title="detailArticle?.title || '条目详情'" width="680px">
      <template v-if="detailArticle">
        <div class="detail-meta">
          <span v-if="detailArticle.type" class="kb-type">{{ detailArticle.type }}</span>
          <span class="kb-vec" :class="{ ok: detailArticle.vectorized }">
            {{ detailArticle.vectorized ? '已向量化' : '未向量化' }}
          </span>
          <span class="kb-count">{{ detailArticle.chunkCount || 0 }} 分块</span>
        </div>
        <pre class="detail-content" v-html="highlight(detailArticle.content)"></pre>
      </template>
    </el-dialog>

    <!-- 上传弹窗:本地上传 / 网址导入 双 Tab -->
    <el-dialog v-model="uploadVisible" title="上传文档" width="560px" :close-on-click-modal="false" @closed="closeUpload">
      <el-tabs v-model="uploadTab">
        <el-tab-pane label="本地上传" name="local">
          <div class="upload-extra">
            <el-input v-model="uploadTitle" placeholder="条目标题(可选,留空用文件名)" clearable />
          </div>
          <el-upload
            ref="uploadRef"
            drag multiple :auto-upload="false" :http-request="customUpload"
            :show-file-list="true" accept=".txt,.md,.markdown,.pdf,.doc,.docx,.xls,.xlsx,.csv"
            :on-success="onUploadSuccess" :on-error="onUploadError" :on-change="onUploadChange"
          >
            <el-icon class="upload-icon"><UploadFilled /></el-icon>
            <div class="upload-text">拖拽文件到这里,或 <em>点击选择</em></div>
            <template #tip>
              <div class="el-upload__tip">支持 .txt / .md / .pdf / .docx / .xlsx 等常见文档(单个 ≤ 20MB),可多选</div>
            </template>
          </el-upload>
        </el-tab-pane>

        <el-tab-pane label="网址导入" name="url">
          <div class="url-import">
            <el-input v-model="urlInput" placeholder="粘贴网页链接,如 https://wiki.biligame.com/ys/" clearable />
            <el-input v-model="urlTitle" placeholder="条目标题(可选,默认用网页标题)" clearable style="margin-top: 10px" />
            <p class="url-tip">仅支持公网 http/https 网页;自动提取标题和正文后入库向量化</p>
          </div>
        </el-tab-pane>
      </el-tabs>
      <template #footer>
        <el-button @click="uploadVisible = false">取消</el-button>
        <el-button v-if="uploadTab === 'local'" type="primary" :loading="uploading"
          :disabled="!uploadCount || uploading" @click="startUpload">
          开始上传{{ uploadCount ? `(${uploadCount})` : '' }}
        </el-button>
        <el-button v-else type="primary" :loading="urlLoading"
          :disabled="!urlInput.trim() || urlLoading" @click="doImportUrl">
          抓取并入库
        </el-button>
      </template>
    </el-dialog>

    <!-- 删除确认弹窗(项目风格) -->
    <el-dialog v-model="deleteVisible" title="删除确认" width="420px" :close-on-click-modal="false">
      <div class="del-body">
        <div class="del-icon">
          <el-icon :size="22"><Delete /></el-icon>
        </div>
        <div class="del-text">
          <p class="del-title">确定删除条目「<b>{{ deleteTarget?.title }}</b>」吗?</p>
          <p class="del-sub">将同时删除该条目的 {{ deleteTarget?.chunkCount || 0 }} 个知识分块,并从向量库中移除,<b>此操作不可恢复</b>。</p>
        </div>
      </div>
      <template #footer>
        <el-button @click="deleteVisible = false">取消</el-button>
        <el-button type="danger" :loading="deleting" @click="confirmDelete">确认删除</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.kb-layout { height: 100%; display: flex; background: var(--bg); }

.kb-side {
  width: 210px;
  flex-shrink: 0;
  border-right: 1px solid var(--border);
  background: color-mix(in srgb, var(--surface) 85%, transparent);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  padding: 16px 12px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  overflow-y: auto;
}
.kb-side-title { font-size: 15px; font-weight: 600; padding: 0 6px 8px; letter-spacing: .2px; }
.kb-new { display: flex; gap: 6px; margin-bottom: 8px; }
.kb-game-item {
  display: flex; align-items: center; gap: 8px;
  padding: 9px 10px; border-radius: 10px; cursor: pointer;
  color: var(--text);
  position: relative;
  transition: background .15s, box-shadow .15s;
}
.kb-game-item:hover { background: var(--surface); box-shadow: var(--shadow-sm); }
.kb-game-item.active {
  background: var(--surface);
  box-shadow: var(--shadow-sm);
}
.kb-game-item.active::before {
  content: '';
  position: absolute; left: -12px; top: 50%; transform: translateY(-50%);
  width: 3px; height: 22px; border-radius: 3px;
  background: var(--grad-brand);
}
.kb-game-avatar {
  width: 24px; height: 24px; border-radius: 7px;
  background: var(--grad-brand); color: #fff;
  display: flex; align-items: center; justify-content: center;
  font-size: 12px; flex-shrink: 0;
  box-shadow: 0 2px 6px color-mix(in srgb, var(--primary) 40%, transparent);
}
.kb-game-name { font-size: 15px; }
.kb-side-empty { font-size: 14px; color: var(--text-3); padding: 8px 6px; }

.kb-main { flex: 1; min-width: 0; padding: 18px 22px; overflow-y: auto; display: flex; flex-direction: column; gap: 16px; }
.kb-toolbar { display: flex; align-items: center; gap: 10px; }
.kb-current { flex-shrink: 0; font-size: 19px; font-weight: 600; margin: 0; letter-spacing: .3px; }
.kb-kw { flex: 1; min-width: 140px; max-width: 320px; }

.kb-search { border: 1px solid var(--border); border-radius: 14px; padding: 14px; background: var(--surface); box-shadow: var(--shadow-sm); }
.kb-search-row { display: flex; gap: 8px; }
.kb-hits { margin-top: 10px; display: flex; flex-direction: column; gap: 8px; }
.kb-hit-card { border: 1px solid var(--border); border-radius: 12px; padding: 10px 12px; background: var(--surface); box-shadow: var(--shadow-sm); }
.kb-hit { display: flex; gap: 8px; align-items: baseline; font-size: 14px; }
.kb-score { color: var(--primary); flex-shrink: 0; font-size: 13px; }
.kb-hit-text { color: var(--text-2); flex: 1; min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.kb-article-toggle { flex-shrink: 0; }
.kb-article { margin-top: 10px; border-top: 1px dashed var(--border); padding-top: 10px; }
.kb-article-title { font-size: 15px; font-weight: 500; margin-bottom: 6px; }
.kb-article-content {
  white-space: pre-wrap; word-break: break-word;
  font-family: inherit; font-size: 14px; line-height: 1.7;
  color: var(--text-2); max-height: 320px; overflow-y: auto;
}

.kb-list { display: flex; flex-direction: column; gap: 10px; }
.kb-card {
  border: 1px solid var(--border);
  border-radius: 14px;
  padding: 12px 14px;
  background: var(--surface);
  cursor: pointer;
  box-shadow: var(--shadow-sm);
  transition: box-shadow .2s, border-color .2s, transform .15s;
}
.kb-card:hover {
  border-color: color-mix(in srgb, var(--primary) 45%, var(--border));
  box-shadow: var(--shadow-md);
  transform: translateY(-1px);
}
.kb-card-head { display: flex; align-items: center; gap: 10px; }
.kb-card-title { flex: 1; font-size: 15px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.kb-type {
  font-size: 12px; padding: 2px 9px; border-radius: 10px;
  background: color-mix(in srgb, var(--primary) 10%, var(--surface));
  border: 0.5px solid color-mix(in srgb, var(--primary) 25%, transparent);
  color: var(--primary);
  flex-shrink: 0;
}
.kb-vec { font-size: 12px; flex-shrink: 0; color: var(--text-3); }
.kb-vec.ok {
  color: #1d9e75;
  background: color-mix(in srgb, #1d9e75 10%, transparent);
  padding: 1px 8px; border-radius: 10px;
}
.kb-count { font-size: 12px; color: var(--text-3); flex-shrink: 0; }
.kb-card-ops { display: flex; gap: 2px; flex-shrink: 0; }
.kb-card-content {
  margin: 6px 0 0; font-size: 14px; color: var(--text-2);
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
  line-height: 1.6;
}
.kb-empty { text-align: center; color: var(--text-3); padding: 40px 0; font-size: 14px; }

.detail-meta { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.detail-content {
  white-space: pre-wrap; word-break: break-word;
  font-family: inherit; font-size: 14px; line-height: 1.7;
  color: var(--text); max-height: 60vh; overflow-y: auto;
}

.del-body { display: flex; gap: 14px; align-items: flex-start; }
.del-icon {
  width: 40px; height: 40px; flex-shrink: 0;
  border-radius: 10px; display: flex; align-items: center; justify-content: center;
  background: color-mix(in srgb, var(--danger) 14%, transparent);
  color: var(--danger);
}
.del-text { flex: 1; min-width: 0; }
.del-title { font-size: 15px; margin-bottom: 6px; word-break: break-all; }
.del-title b { color: var(--text); }
.del-sub { font-size: 13px; color: var(--text-2); line-height: 1.7; }
.del-sub b { color: var(--danger); }

.upload-extra { margin-bottom: 14px; }
.upload-icon { font-size: 44px; color: var(--el-color-primary); margin-bottom: 6px; }
.upload-text { font-size: 15px; color: var(--text); }
.upload-text em { color: var(--el-color-primary); font-style: normal; }
.url-import { padding: 4px 2px; }
.url-tip { font-size: 13px; color: var(--text-3); margin-top: 10px; line-height: 1.6; }

:deep(mark.hl) { background: #ffe58f; color: inherit; padding: 0 1px; border-radius: 2px; }

@media (max-width: 768px) {
  .kb-side { width: 150px; }
  .kb-main { padding: 14px 12px; }
  .kb-current { display: none; }
}
</style>
