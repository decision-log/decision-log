import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { resolve } from 'node:path'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: { alias: { '@': resolve(import.meta.dirname, 'src') } },   // shadcn 이 쓰는 경로
  build: { outDir: 'dist' },
  server: { proxy: { '/api': 'http://localhost:8080' } },   // dev 전용 편의
  test: { environment: 'jsdom', globals: true, setupFiles: './src/setupTests.ts' },
})
