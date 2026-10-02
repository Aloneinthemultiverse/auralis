import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Dev: proxy the Java core. Build: emit straight into Spring Boot's static
// folder, so http://localhost:8080 serves the app with no extra server.
export default defineConfig({
  plugins: [react()],
  server: { proxy: { '/api': 'http://localhost:8080' } },
  build: { outDir: '../auralis-core/src/main/resources/static', emptyOutDir: true },
})
