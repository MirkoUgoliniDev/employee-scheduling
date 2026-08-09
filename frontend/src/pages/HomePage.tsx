/**
 * @file HomePage.tsx
 * @brief Post-login landing page (guided empty state).
 */

import { useTranslation } from 'react-i18next'
import { useEffect, useState } from 'react'
import { HOME_COVERS } from '../assets/home-covers'
import { homeUiSettingsApi } from '../api/homeUiSettings'
import { sanitizeRichHtml } from '../utils/sanitizeHtml'
import './HomePage.css'

export default function HomePage() {
  const { t } = useTranslation()
  const [coverKey, setCoverKey] = useState<string>('')
  const [coverDataUrl, setCoverDataUrl] = useState<string>('')
  // Distinguishes "response received" from "empty cover_key": without this flag the two states
  // are indistinguishable (both coverKey === '' and coverDataUrl === ''), and "None" is never
  // reachable because the page remains on its initial value before the fetch.
  const [coverLoaded, setCoverLoaded] = useState(false)

  useEffect(() => {
    homeUiSettingsApi.get()
      .then(cfg => {
        setCoverKey(cfg.cover_key ?? '')
        setCoverDataUrl(cfg.cover_data_url ?? '')
      })
      .catch(() => { setCoverKey(''); setCoverDataUrl('') })
      .finally(() => setCoverLoaded(true))
  }, [])

  const resolvedCover = HOME_COVERS.find(c => c.id === coverKey)
  const activeCover = coverDataUrl
    ? { src: coverDataUrl }
    : (coverLoaded && coverKey === '' ? { src: '' } : (resolvedCover ?? HOME_COVERS[0]))

  const title = t('home.title', '').trim()
  const body = t('home.body', '').trim()
  const hint = t('home.hint', '').trim()
  const hasText = Boolean(title || body || hint)

  return (
    <div className="home-hero" style={{ backgroundImage: `url(${activeCover.src})` }}>
      {hasText && (
        <div className="home-hero__content">
          {title && <h4 className="mb-2">{title}</h4>}
          {body && (
            <div
              className="mb-1"
              dangerouslySetInnerHTML={{ __html: sanitizeRichHtml(body) }}
            />
          )}
          {hint && (
            <div
              className="text-muted mb-0"
              dangerouslySetInnerHTML={{ __html: sanitizeRichHtml(hint) }}
            />
          )}
        </div>
      )}
    </div>
  )
}
