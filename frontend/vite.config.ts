import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig(({ mode }) => {
  const target = loadEnv(mode, '.', 'T15_').T15_BACKEND_ORIGIN || 'http://127.0.0.1:8080'
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
