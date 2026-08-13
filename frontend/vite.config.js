import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

export default defineConfig({
  plugins: [
    vue(),
    // Element Plus 按需引入:只打包用到的组件与样式,控制体积
    AutoImport({ resolvers: [ElementPlusResolver()] }),
    Components({ resolvers: [ElementPlusResolver()] })
  ],
  server: {
    port: 5173,
    proxy: {
      // '/api': 'http://localhost:8080'
          '/api': {
            target: 'http://122.152.202.74:8082',
            changeOrigin: true
          }
    }

  },
  build: {
    outDir: '../backend/src/main/resources/static',
    emptyOutDir: true
  }
})
