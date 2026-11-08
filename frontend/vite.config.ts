import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

// 后端默认端口 = 8090：compose 的 app 从容器 8080 映射到宿主机 8090，
// 因为本机 8080 已被 file-transfer-nginx-1 占用。
// 需要指向别处时用 VITE_API_TARGET 覆盖，例如本地 bootRun 在 8080：
//   VITE_API_TARGET=http://localhost:8080 npm run dev
const apiTarget = process.env.VITE_API_TARGET ?? 'http://localhost:8090'
const wsTarget = apiTarget.replace(/^http/, 'ws')

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      '/api': { target: apiTarget, changeOrigin: true },
      '/ws': { target: wsTarget, ws: true },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
  },
})
