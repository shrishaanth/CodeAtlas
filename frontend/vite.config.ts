import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // In `npm run dev`, forward API calls to the locally running backend so no CORS setup is needed.
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
