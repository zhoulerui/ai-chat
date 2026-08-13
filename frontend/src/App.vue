<script setup>
// 神谕百科 · 统一入口:顶部导航(居中 Tab)+ 主题切换;智能问答 / 知识库走 vue-router
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Sunny, Moon } from '@element-plus/icons-vue'

// ---------- 主题(深色/浅色,全局生效) ----------
const isDark = ref(localStorage.getItem('og-theme') === 'dark')
function applyTheme() {
  document.documentElement.classList.toggle('dark', isDark.value)
}
function toggleTheme() {
  isDark.value = !isDark.value
  localStorage.setItem('og-theme', isDark.value ? 'dark' : 'light')
  applyTheme()
}

// ---------- 页签 ↔ 路由(Tab 即 URL,URL 即 Tab) ----------
const route = useRoute()
const router = useRouter()
const activeTab = computed({
  get: () => (route.path.startsWith('/kb') ? 'kb' : 'chat'),
  set: (v) => router.push(v === 'kb' ? '/kb' : '/chat')
})

// keep-alive 缓存的活动组件实例;「清空」只对智能问答生效
const viewRef = ref(null)
function resetChat() {
  viewRef.value?.reset?.()
}

onMounted(applyTheme)
</script>

<template>
  <div class="app">
    <header class="topbar">
      <div class="brand">
        <img class="logo" src="/favicon.svg" alt="神谕百科" />
        <div class="brand-text">
          <span class="brand-name">神谕百科</span>
          <span class="brand-sub">Oracle of Games</span>
        </div>
      </div>

      <el-radio-group v-model="activeTab" class="center-tab" size="default">
        <el-radio-button value="chat">智能问答</el-radio-button>
        <el-radio-button value="kb">知识库</el-radio-button>
      </el-radio-group>

      <div class="top-actions">
        <el-tooltip :content="isDark ? '切换浅色模式' : '切换深色模式'">
          <el-button circle @click="toggleTheme">
            <el-icon :size="16"><Sunny v-if="!isDark" /><Moon v-else /></el-icon>
          </el-button>
        </el-tooltip>
        <el-button v-if="activeTab === 'chat'" link type="primary" @click="resetChat">清空</el-button>
      </div>
    </header>

    <!-- keep-alive 缓存 Chat/KnowledgeBase 实例,切换 Tab 不丢聊天记录与知识库状态 -->
    <div class="tab-body">
      <router-view v-slot="{ Component }">
        <keep-alive>
          <component :is="Component" ref="viewRef" />
        </keep-alive>
      </router-view>
    </div>
  </div>
</template>

<style>
:root {
  --el-color-primary: #1677ff;
  --bg: linear-gradient(180deg, #f5f7fb 0%, #eef2f7 100%);
  --surface: #ffffff;
  --surface-2: #f7f9fc;
  --border: #e7ebf1;
  --border-2: #d4dbe5;
  --text: #1b2333;
  --text-2: #5a6478;
  --text-3: #8a94a8;
  --bubble-ai: #ffffff;
  --danger: #e5484d;
  --primary: #1677ff;
  --primary-2: #5b8cff;
  --user-grad-a: #1677ff;
  --user-grad-b: #4e9bff;
  --grad-brand: linear-gradient(135deg, #1677ff 0%, #7a5cff 100%);
  --shadow-sm: 0 1px 2px rgba(23, 30, 46, .05), 0 1px 3px rgba(23, 30, 46, .06);
  --shadow-md: 0 4px 12px rgba(23, 30, 46, .09), 0 2px 4px rgba(23, 30, 46, .05);
  --shadow-lg: 0 16px 40px rgba(23, 30, 46, .14), 0 4px 12px rgba(23, 30, 46, .07);
  --radius: 14px;
  /* 代码高亮 token(GitHub 风格浅色) */
  --code-bg: #f6f8fa;
  --code-text: #1b2333;
  --code-keyword: #cf222e;
  --code-string: #0a3069;
  --code-comment: #6e7781;
  --code-number: #0550ae;
  --code-func: #8250df;
  --code-tag: #116329;
  --code-attr: #953800;
}
html.dark {
  --el-color-primary: #3b82f6;
  --bg: linear-gradient(180deg, #0f1620 0%, #0a1017 100%);
  --surface: #151d29;
  --surface-2: #1b2432;
  --border: #273245;
  --border-2: #39465e;
  --text: #e8eef5;
  --text-2: #a8b4c6;
  --text-3: #76829a;
  --bubble-ai: #1b2432;
  --primary: #3b82f6;
  --primary-2: #7aa7ff;
  --user-grad-a: #2f6fd0;
  --user-grad-b: #4c8ee8;
  --grad-brand: linear-gradient(135deg, #3b82f6 0%, #8b6cff 100%);
  --shadow-sm: 0 1px 2px rgba(0, 0, 0, .30), 0 1px 3px rgba(0, 0, 0, .35);
  --shadow-md: 0 4px 14px rgba(0, 0, 0, .45), 0 2px 4px rgba(0, 0, 0, .30);
  --shadow-lg: 0 16px 44px rgba(0, 0, 0, .55), 0 4px 14px rgba(0, 0, 0, .35);
  /* 代码高亮 token(GitHub 风格深色) */
  --code-bg: #161b22;
  --code-text: #e6edf3;
  --code-keyword: #ff7b72;
  --code-string: #a5d6ff;
  --code-comment: #8b949e;
  --code-number: #79c0ff;
  --code-func: #d2a8ff;
  --code-tag: #7ee787;
  --code-attr: #ffa657;
}

* { margin: 0; padding: 0; box-sizing: border-box; }
html, body, #app { height: 100%; }
body {
  font-family: -apple-system, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
  font-size: 16px;
  background: var(--bg);
  color: var(--text);
  transition: background .25s, color .25s;
}

/* 精致滚动条 */
::-webkit-scrollbar { width: 8px; height: 8px; }
::-webkit-scrollbar-track { background: transparent; }
::-webkit-scrollbar-thumb { background: var(--border-2); border-radius: 8px; border: 2px solid transparent; background-clip: content-box; }
::-webkit-scrollbar-thumb:hover { background: var(--text-3); background-clip: content-box; border: 2px solid transparent; }

/* 输入框聚焦光晕(提升质感) */
.el-input__wrapper { transition: box-shadow .2s; }
.el-input__wrapper.is-focus {
  box-shadow: 0 0 0 1px var(--el-color-primary) inset, 0 0 0 4px color-mix(in srgb, var(--el-color-primary) 16%, transparent) !important;
}
.el-textarea__inner:focus {
  box-shadow: 0 0 0 1px var(--el-color-primary) inset, 0 0 0 4px color-mix(in srgb, var(--el-color-primary) 16%, transparent) !important;
}

.app { height: 100%; display: flex; flex-direction: column; }

.topbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 24px;
  background: color-mix(in srgb, var(--surface) 82%, transparent);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  border-bottom: 1px solid var(--border);
  box-shadow: 0 1px 0 rgba(0,0,0,.02), 0 6px 24px rgba(23,30,46,.05);
}
html.dark .topbar { box-shadow: 0 1px 0 rgba(0,0,0,.2), 0 6px 24px rgba(0,0,0,.25); }
.brand { display: flex; align-items: center; gap: 10px; width: 260px; }
.brand .logo {
  width: 34px; height: 34px; border-radius: 10px;
  box-shadow: 0 2px 8px color-mix(in srgb, var(--primary) 45%, transparent), 0 0 0 1px color-mix(in srgb, var(--primary) 25%, transparent);
}
.brand-text { display: flex; flex-direction: column; line-height: 1.25; }
.brand-name {
  font-size: 19px; font-weight: 600; letter-spacing: .3px;
  background: var(--grad-brand);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}
.brand-sub { font-size: 13px; color: var(--text-3); }
.center-tab { flex: 1; display: flex; justify-content: center; }
.center-tab :deep(.el-radio-button__inner) { transition: all .2s; }
.center-tab :deep(.el-radio-button__original-radio:checked + .el-radio-button__inner) {
  box-shadow: 0 2px 8px color-mix(in srgb, var(--el-color-primary) 35%, transparent);
}
.top-actions { display: flex; align-items: center; gap: 6px; width: 260px; justify-content: flex-end; }

.tab-body { flex: 1; min-height: 0; overflow: hidden; }

@media (max-width: 768px) {
  .topbar { padding: 10px 14px; gap: 8px; }
  .brand { width: auto; }
  .brand-sub { display: none; }
  .center-tab :deep(.el-radio-button__inner) { padding: 6px 12px; }
  .top-actions { width: auto; }
}
</style>
