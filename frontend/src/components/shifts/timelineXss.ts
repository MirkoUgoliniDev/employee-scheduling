import type { TimelineOptions } from 'vis-timeline/peer'

/** Keep the HTML used by timeline labels/tooltips while rejecting executable markup. */
export const SAFE_TIMELINE_XSS: NonNullable<TimelineOptions['xss']> = {
  disabled: false,
  filterOptions: {
    whiteList: {
      table: ['style'], tr: ['style'], td: ['style'],
      div: ['class', 'style'], span: ['class', 'style'], strong: [],
      i: ['class', 'data-action', 'data-id', 'style', 'title'],
      svg: ['viewbox', 'width', 'height', 'fill', 'style'], path: ['d'],
    },
    stripIgnoreTag: true,
    stripIgnoreTagBody: ['script', 'style'],
    css: {
      whiteList: {
        'background-color': true,
        border: true,
        'border-collapse': true,
        'border-color': true,
        'border-left-color': true,
        'border-radius': true,
        color: true,
        cursor: /^(?:auto|default|pointer)$/,
        'font-size': true,
        'font-weight': true,
        height: true,
        'line-height': true,
        'overflow-wrap': /^(?:normal|break-word|anywhere)$/,
        padding: true,
        'padding-left': true,
        'text-align': /^(?:left|right|center|start|end)$/,
        'vertical-align': true,
        'white-space': /^(?:normal|nowrap|pre|pre-wrap|pre-line)$/,
        width: true,
      },
    },
  },
}
