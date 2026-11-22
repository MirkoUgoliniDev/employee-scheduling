/**
 * @file main.tsx
 * @brief Boots the React application into the {@code #root} container.
 *
 * @details
 * ## Why the boot placeholder is removed by hand
 * `index.html` ships a spinner inside `#root` so that the page is never blank while the
 * bundle loads — and still shows something if it never does. `createRoot` appends to the
 * container instead of emptying it, so the placeholder is cleared here.
 *
 * ## Why `vite:preloadError` is handled
 * Asset file names are content-hashed. After a frontend rebuild — an upgrade, a redeploy —
 * an already open page still points at chunk names that no longer exist: the dynamic import
 * rejects, React never mounts, and the user is left with a blank screen and no hint that a
 * reload would fix it. `vite:preloadError` fires for exactly that case, so reload once to
 * pick up the new `index.html`. The sessionStorage guard prevents a reload loop when the
 * chunk is genuinely missing rather than stale.
 */

import React from 'react'
import ReactDOM from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { Toaster } from 'react-hot-toast'
import 'bootstrap/dist/css/bootstrap.min.css'
import '@fortawesome/fontawesome-free/css/all.min.css'
import './index.css'
import App from './App'
import { initI18n } from './i18n'
import { useAppStore } from './store/useAppStore'

/** @brief sessionStorage flag recording that the single automatic reload has been used. */
const PRELOAD_RELOAD_FLAG = 'preload-error-reloaded'

window.addEventListener('vite:preloadError', () => {
  if (sessionStorage.getItem(PRELOAD_RELOAD_FLAG) === '1') return
  sessionStorage.setItem(PRELOAD_RELOAD_FLAG, '1')
  window.location.reload()
})

async function bootstrap() {
  const lang = useAppStore.getState().language
  const showTranslationKeys = useAppStore.getState().showTranslationKeys
  await initI18n(showTranslationKeys ? 'cimode' : lang)

  const container = document.getElementById('root')!
  container.replaceChildren()

  ReactDOM.createRoot(container).render(
    <React.StrictMode>
      <BrowserRouter>
        <App />
        <Toaster
          position="top-right"
          toastOptions={{ duration: 3500 }}
        />
      </BrowserRouter>
    </React.StrictMode>
  )

  // Mounted, so whatever caused an earlier reload is behind us: the flag must not consume
  // the one automatic recovery of a later, unrelated stale bundle.
  sessionStorage.removeItem(PRELOAD_RELOAD_FLAG)
}

bootstrap()
