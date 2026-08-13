// 路由表:智能问答 / 知识库
// hash 模式:单 jar 静态托管下刷新/直达不 404(无需后端 forward 配置)
import { createRouter, createWebHashHistory } from 'vue-router'
import Chat from '../components/Chat.vue'
import KnowledgeBase from '../components/KnowledgeBase.vue'

const routes = [
  { path: '/', redirect: '/chat' },
  { path: '/chat', name: 'chat', component: Chat, meta: { title: '智能问答' } },
  { path: '/kb', name: 'kb', component: KnowledgeBase, meta: { title: '知识库' } }
]

export default createRouter({
  history: createWebHashHistory(),
  routes
})
