/**
 * @file ContextMenu.tsx
 * @brief Fixed-position context menu activated with a right click.
 *
 * @details
 * Used in vis-timeline (ShiftsPage) for contextual actions on shifts
 * and empty calendar cells (add shift, edit, delete).
 * Closes automatically on an outside click or Escape key press.
 * Its position is adjusted automatically if the menu would extend beyond the viewport.
 */

import { useEffect, useRef } from 'react'

/**
 * @brief A single context-menu entry.
 */
export interface ContextMenuAction {
  label: string
  icon: string
  variant?: string   // 'danger' | 'primary' | default
  onClick: () => void
}

interface Props {
  x: number
  y: number
  actions: ContextMenuAction[]
  onClose: () => void
}

export default function ContextMenu({ x, y, actions, onClose }: Props) {
  const ref = useRef<HTMLDivElement>(null)

  // Close on outside click or Escape
  useEffect(() => {
    function handleClick(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) onClose()
    }
    function handleKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('mousedown', handleClick)
    document.addEventListener('keydown', handleKey)
    return () => {
      document.removeEventListener('mousedown', handleClick)
      document.removeEventListener('keydown', handleKey)
    }
  }, [onClose])

  // Adjust the position if the menu extends beyond the viewport
  const safeX = Math.min(x, window.innerWidth - 180)
  const safeY = Math.min(y, window.innerHeight - actions.length * 40 - 10)

  return (
    <div
      ref={ref}
      style={{
        position: 'fixed',
        left: safeX,
        top: safeY,
        zIndex: 9999,
        background: '#fff',
        border: '1px solid #ccc',
        borderRadius: 4,
        boxShadow: '2px 2px 8px rgba(0,0,0,0.2)',
        minWidth: 170,
        padding: '4px 0',
      }}
    >
      {actions.map((action, i) => (
        <div
          key={i}
          onClick={() => { action.onClick(); onClose() }}
          style={{
            padding: '8px 14px',
            cursor: 'pointer',
            display: 'flex',
            alignItems: 'center',
            gap: 8,
            fontSize: '0.9rem',
            color: action.variant === 'danger' ? '#dc3545' : action.variant === 'primary' ? '#0d6efd' : '#212529',
            borderBottom: i < actions.length - 1 ? '1px solid #eee' : 'none',
          }}
          onMouseEnter={e => (e.currentTarget.style.background = '#f5f5f5')}
          onMouseLeave={e => (e.currentTarget.style.background = 'transparent')}
        >
          <i className={action.icon} style={{ width: 16, textAlign: 'center' }} />
          {action.label}
        </div>
      ))}
    </div>
  )
}
