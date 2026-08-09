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

async function bootstrap() {
  const lang = useAppStore.getState().language
  const showTranslationKeys = useAppStore.getState().showTranslationKeys
  await initI18n(showTranslationKeys ? 'cimode' : lang)

  ReactDOM.createRoot(document.getElementById('root')!).render(
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
}

bootstrap()
