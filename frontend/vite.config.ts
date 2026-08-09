import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import packageJson from './package.json' with { type: 'json' }
import packageLock from './package-lock.json' with { type: 'json' }

const lockedVersion = (name: string) => packageLock.packages[`node_modules/${name}` as keyof typeof packageLock.packages]?.version ?? 'N/D'

export default defineConfig({
  plugins: [react()],
  define: {
    __APP_VERSION__: JSON.stringify(packageJson.version),
    __TECH_VERSIONS__: JSON.stringify({
      react: lockedVersion('react'),
      typescript: lockedVersion('typescript'),
      vite: lockedVersion('vite'),
      bootstrap: lockedVersion('bootstrap'),
      jspdf: lockedVersion('jspdf'),
      i18next: lockedVersion('i18next'),
      reactI18next: lockedVersion('react-i18next'),
      reactBootstrap: lockedVersion('react-bootstrap'),
      fontawesome: lockedVersion('@fortawesome/free-solid-svg-icons'),
      visTimeline: lockedVersion('vis-timeline'),
      zustand: lockedVersion('zustand'),
      reactHotToast: lockedVersion('react-hot-toast'),
    }),
  },
  server: {
    port: 5173,
    proxy: {
      '/demo-data': 'http://localhost:8080',
      '/translations': 'http://localhost:8080',
      '/structures': 'http://localhost:8080',
      '/schedules': 'http://localhost:8080',
      '/labels': 'http://localhost:8080',
      '/languages': 'http://localhost:8080',
      '/localizzazioni': 'http://localhost:8080',
      '/system-info': 'http://localhost:8080',
      '/backup': 'http://localhost:8080',
      // Authentication: without these two routes, the dev server responds with index.html and
      // login through :5173 always fails with "Server unreachable".
      '/auth': 'http://localhost:8080',
      '/j_security_check': 'http://localhost:8080',
      '/users': 'http://localhost:8080',
      '/specialists': 'http://localhost:8080',
      '/email': 'http://localhost:8080',
    },
  },
  build: {
    outDir: '../src/main/resources/META-INF/resources',
    emptyOutDir: true,
    rolldownOptions: {
      output: {
        // Vite 8 (Rolldown): codeSplitting replaces the manualChunks object
        codeSplitting: {
          groups: [
            { name: 'vendor-react',     test: /node_modules[\\/](?:react|react-dom|scheduler|react-router|react-router-dom)[\\/]/ },
            { name: 'vendor-bootstrap', test: /node_modules[\\/](?:react-bootstrap|bootstrap)[\\/]/ },
            { name: 'vendor-state',     test: /node_modules[\\/](?:zustand|i18next|react-i18next|react-hot-toast)[\\/]/ },
            { name: 'vendor-pdf',       test: /node_modules[\\/](?:jspdf)[\\/]/ },
            // Keep moment with vis-timeline: the vis-timeline peer build uses external moment
            // (one shared copy, with locales registered in VisTimeline.tsx)
            { name: 'vendor-timeline',  test: /node_modules[\\/](?:vis-timeline|vis-data|moment)[\\/]/ },
          ],
        },
      },
    },
  },
})
