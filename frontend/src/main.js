import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import 'element-plus/theme-chalk/dark/css-vars.css' // Element Plus 深色模式变量

createApp(App).use(router).mount('#app')
