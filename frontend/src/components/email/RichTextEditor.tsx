/**
 * @file RichTextEditor.tsx
 * @brief Lightweight HTML editor based on contentEditable (no external dependencies).
 *
 * @details
 * Toolbar: bold, italic, underline, strikethrough, lists, links, clear formatting,
 * and an HTML-source view toggle. The value is HTML (a string); text insertion at the
 * cursor (e.g. the {{Nominativo}} placeholder) is exposed through `apiRef.insertText`.
 *
 * Note: uses document.execCommand — deprecated but supported by all browsers;
 * avoids adding a heavy dependency for a simple editor.
 */

import { useEffect, useRef, useState } from 'react'
import { Button, ButtonGroup, ButtonToolbar } from 'react-bootstrap'
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import {
  faBold, faItalic, faUnderline, faStrikethrough,
  faListUl, faListOl, faLink, faEraser, faCode,
} from '@fortawesome/free-solid-svg-icons'
import { useTranslation } from 'react-i18next'
import { safeLinkUrl, sanitizeRichHtml } from '../../utils/sanitizeHtml'
import './RichTextEditor.css'

/** @brief Imperative methods exposed to the parent through `apiRef`. */
export interface RichTextEditorHandle {
  /** @brief Inserts plain text at the current cursor position. */
  insertText: (text: string) => void
}

interface Props {
  /** @brief Current HTML. */
  value: string
  /** @brief Reports every change (updated HTML). */
  onChange: (html: string) => void
  /** @brief Ref through which the component exposes imperative methods. */
  apiRef?: React.RefObject<RichTextEditorHandle | null>
  /** @brief Minimum height of the editable area (px). */
  minHeight?: number
  /** @brief Disables editing and the toolbar while saving. */
  disabled?: boolean
}

export default function RichTextEditor({ value, onChange, apiRef, minHeight = 260, disabled = false }: Props) {
  const { t } = useTranslation()
  const editorRef = useRef<HTMLDivElement>(null)
  const sourceRef = useRef<HTMLTextAreaElement>(null)
  const [sourceMode, setSourceMode] = useState(false)
  const selectionFrameRef = useRef<number | null>(null)

  // Synchronize external HTML → editor only when different (avoids cursor jumps)
  useEffect(() => {
    const el = editorRef.current
    const safeValue = sanitizeRichHtml(value)
    if (el && !sourceMode && el.innerHTML !== safeValue) el.innerHTML = safeValue
  }, [value, sourceMode])

  // Expose imperative methods through the parent's ref
  useEffect(() => {
    if (!apiRef) return
    apiRef.current = {
      insertText: (text: string) => {
        if (disabled) return
        if (sourceMode) {
          const ta = sourceRef.current
          if (!ta) return
          const start = ta.selectionStart ?? ta.value.length
          const end = ta.selectionEnd ?? start
          const next = ta.value.slice(0, start) + text + ta.value.slice(end)
          onChange(next)
          if (selectionFrameRef.current != null) cancelAnimationFrame(selectionFrameRef.current)
          selectionFrameRef.current = requestAnimationFrame(() => {
            ta.focus()
            ta.selectionStart = ta.selectionEnd = start + text.length
            selectionFrameRef.current = null
          })
        } else {
          editorRef.current?.focus()
          document.execCommand('insertText', false, text)
          onChange(sanitizeRichHtml(editorRef.current?.innerHTML ?? ''))
        }
      },
    }
    return () => {
      apiRef.current = null
      if (selectionFrameRef.current != null) cancelAnimationFrame(selectionFrameRef.current)
    }
  }, [apiRef, sourceMode, onChange, disabled])

  /** @brief Executes a formatting command while keeping focus on the editor. */
  function exec(command: string, arg?: string) {
    editorRef.current?.focus()
    document.execCommand(command, false, arg)
    onChange(sanitizeRichHtml(editorRef.current?.innerHTML ?? ''))
  }

  function handleLink() {
    const url = window.prompt(t('rte.linkPrompt', 'URL del link:'))
    const safeUrl = url ? safeLinkUrl(url) : null
    if (safeUrl) exec('createLink', safeUrl)
  }

  function toggleSourceMode() {
    if (sourceMode) onChange(sanitizeRichHtml(value))
    setSourceMode(mode => !mode)
  }

  function insertTransferredContent(html: string, text: string) {
    editorRef.current?.focus()
    if (html) document.execCommand('insertHTML', false, sanitizeRichHtml(html))
    else document.execCommand('insertText', false, text)
    onChange(sanitizeRichHtml(editorRef.current?.innerHTML ?? ''))
  }

  // Metadata only during render: ref access remains confined to the event handler.
  const tools: Array<{ icon: typeof faBold; title: string; command?: string }> = [
    { icon: faBold,          title: t('rte.bold', 'Grassetto'),              command: 'bold' },
    { icon: faItalic,        title: t('rte.italic', 'Corsivo'),              command: 'italic' },
    { icon: faUnderline,     title: t('rte.underline', 'Sottolineato'),      command: 'underline' },
    { icon: faStrikethrough, title: t('rte.strike', 'Barrato'),              command: 'strikeThrough' },
    { icon: faListUl,        title: t('rte.ul', 'Elenco puntato'),           command: 'insertUnorderedList' },
    { icon: faListOl,        title: t('rte.ol', 'Elenco numerato'),          command: 'insertOrderedList' },
    { icon: faLink,          title: t('rte.link', 'Inserisci link') },
    { icon: faEraser,        title: t('rte.clear', 'Rimuovi formattazione'), command: 'removeFormat' },
  ]

  return (
    <div className="rte border rounded">
      <ButtonToolbar className="rte-toolbar gap-1 p-1 border-bottom">
        <ButtonGroup size="sm">
          {tools.map(({ icon, title, command }) => (
            <Button
              key={title}
              variant="outline-secondary"
              title={title}
              disabled={sourceMode || disabled}
              onMouseDown={e => e.preventDefault() /* do not steal focus/selection */}
              onClick={() => command ? exec(command) : handleLink()}
            >
              <FontAwesomeIcon icon={icon} />
            </Button>
          ))}
        </ButtonGroup>
        <ButtonGroup size="sm" className="ms-auto">
          <Button
            variant={sourceMode ? 'secondary' : 'outline-secondary'}
            disabled={disabled}
            title={t('rte.source', 'Mostra/Modifica HTML')}
            onClick={toggleSourceMode}
          >
            <FontAwesomeIcon icon={faCode} />
          </Button>
        </ButtonGroup>
      </ButtonToolbar>

      {sourceMode ? (
        <textarea
          ref={sourceRef}
          className="rte-source form-control border-0 font-monospace"
          style={{ minHeight }}
          value={value}
          disabled={disabled}
          onChange={e => onChange(e.target.value)}
        />
      ) : (
        <div
          ref={editorRef}
          className="rte-area p-2"
          style={{ minHeight }}
          contentEditable={!disabled}
          suppressContentEditableWarning
          onPaste={event => {
            event.preventDefault()
            insertTransferredContent(
              event.clipboardData.getData('text/html'),
              event.clipboardData.getData('text/plain'),
            )
          }}
          onDrop={event => {
            event.preventDefault()
            insertTransferredContent(
              event.dataTransfer.getData('text/html'),
              event.dataTransfer.getData('text/plain'),
            )
          }}
          onInput={() => onChange(sanitizeRichHtml(editorRef.current?.innerHTML ?? ''))}
        />
      )}
    </div>
  )
}
