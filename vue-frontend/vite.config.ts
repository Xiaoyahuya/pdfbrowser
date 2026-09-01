import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    port: 5174,
    proxy: {
      '/api': {
        target: process.env.PDFBROWSER_API_TARGET || 'http://localhost:8082',
        changeOrigin: true,
      },
    },
  },
})

