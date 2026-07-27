// defineConfig comes from vitest/config so the `test` block is typed alongside the
// Vite options — otherwise the two configs have to be maintained separately.
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  build: {
    // The Worker serves this directory as its static assets.
    outDir: 'dist',
    sourcemap: true,
  },
  server: {
    port: 5173,
    // In development the SPA and the API behave as one origin, exactly as they do in
    // production where the Worker serves both.
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8787',
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}'],
  },
})
