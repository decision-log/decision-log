import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  build: { outDir: 'dist' },
  server: { proxy: { '/api': 'http://localhost:8080' } },   // dev 전용 편의
  test: { environment: 'jsdom', globals: true, setupFiles: './src/setupTests.ts' },
})
