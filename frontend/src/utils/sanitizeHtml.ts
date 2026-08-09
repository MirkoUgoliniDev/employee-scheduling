import DOMPurify from 'dompurify'

const RICH_TEXT_CONFIG = {
  ALLOWED_TAGS: ['p', 'br', 'strong', 'b', 'em', 'i', 'u', 's', 'ul', 'ol', 'li', 'a', 'span'],
  ALLOWED_ATTR: ['href', 'rel'],
  ALLOW_DATA_ATTR: false,
}

/** Removes executable markup while retaining the formatting supported by the editor. */
export function sanitizeRichHtml(html: string): string {
  return DOMPurify.sanitize(html, RICH_TEXT_CONFIG)
}

/** Accept only explicitly safe schemes for links created through the toolbar. */
export function safeLinkUrl(rawUrl: string): string | null {
  const trimmed = rawUrl.trim()
  if (!trimmed) return null
  try {
    const url = new URL(trimmed, window.location.origin)
    return ['http:', 'https:', 'mailto:'].includes(url.protocol) ? trimmed : null
  } catch {
    return null
  }
}
