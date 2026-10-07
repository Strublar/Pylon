import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// The bundle is served by the Scala server from its classpath (modules/server resources).
// `npm run dev` proxies the API to a running `pylon serve` on port 7777.
export default defineConfig({
  plugins: [react()],
  base: './',
  build: {
    outDir: '../modules/server/src/main/resources/viewer',
    emptyOutDir: true,
    // ELK (the package map's layout engine) is a 1.4 MB chunk of its own, loaded on first use.
    chunkSizeWarningLimit: 1600,
  },
  server: {
    proxy: { '/api': 'http://127.0.0.1:7777' },
  },
})
