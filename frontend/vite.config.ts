import { realpathSync } from 'node:fs'
import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig(({ mode }) => {
  // Windows 8.3 short names (a %TEMP% path looks like C:\Users\RUNNER~1\...) make Node resolve the same file
  // under its long name; vite then computes the html chunk name outside the root and the build fails with
  // "fileName ... must be neither absolute nor relative". Pinning root to the long form keeps every id under it.
  const root = realpathSync(new URL('.', import.meta.url))
  const target = loadEnv(mode, root, 'T15_').T15_BACKEND_ORIGIN || 'http://127.0.0.1:8080'
  const url = new URL(target)

  if (
    url.protocol !== 'http:' ||
    !['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname) ||
    url.pathname !== '/' ||
    url.username ||
    url.password ||
    url.search ||
    url.hash
  ) {
    throw new Error('T15_BACKEND_ORIGIN must be a loopback HTTP origin')
  }

  return {
    root,
    base: '/react-app/',
    plugins: [react(), tailwindcss()],
    server: {
      proxy: {
        '/api': {
          target,
          changeOrigin: true,
          headers: { Origin: url.origin },
        },
      },
    },
  }
})
