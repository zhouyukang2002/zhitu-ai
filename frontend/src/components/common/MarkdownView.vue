<template>
  <div class="md" v-html="html" @click="handleMdClick"></div>
</template>

<script setup>
import { computed } from 'vue'
import { marked } from 'marked'
import markedKatex from 'marked-katex-extension'
import DOMPurify from 'dompurify'
import hljs from 'highlight.js'
import 'highlight.js/styles/github.css'
import 'katex/dist/katex.min.css'

const props = defineProps({ content: { type: String, default: '' } })

marked.use(
  markedKatex({
    throwOnError: false,
    nonStandard: true,
  }),
  {
    breaks: true,
    gfm: true,
    renderer: {
      code({ text, lang }) {
        const language = (lang || '').trim().split(/\s+/)[0]
        let highlighted
        try {
          highlighted = language && hljs.getLanguage(language)
            ? hljs.highlight(text, { language }).value
            : hljs.highlightAuto(text).value
        } catch {
          highlighted = text
        }
        const langDisplay = language ? language.toUpperCase() : 'CODE'
        return `<div class="code-wrapper">` +
          `<div class="code-header">` +
            `<div class="code-dots">` +
              `<span class="dot red"></span>` +
              `<span class="dot yellow"></span>` +
              `<span class="dot green"></span>` +
            `</div>` +
            `<span class="code-lang">${langDisplay}</span>` +
            `<button class="code-copy-btn" title="复制代码" data-action="copy-code">` +
              `<svg viewBox="0 0 16 16" width="12" height="12" fill="none"><rect x="5" y="5" width="8" height="8" rx="1.5" stroke="currentColor" stroke-width="1.3"/><path d="M3 11V3.5A1.5 1.5 0 0 1 4.5 2H11" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/></svg>` +
              `<span>复制</span>` +
            `</button>` +
          `</div>` +
          `<pre><code class="hljs language-${language || 'text'}">${highlighted}</code></pre>` +
        `</div>`
      },
    },
  }
)

// LLM 输出经 DOMPurify 消毒后再渲染，保留 MathML/SVG/KaTeX 所需标签与样式
const html = computed(() => {
  const text = props.content || ''
  if (!text) return ''
  try {
    const rawHtml = marked.parse(text)
    return DOMPurify.sanitize(rawHtml, {
      USE_PROFILES: { html: true, mathMl: true, svg: true },
      ADD_TAGS: ['semantics', 'annotation', 'svg', 'path', 'rect'],
      ADD_ATTR: ['aria-hidden', 'style', 'display', 'viewBox', 'data-action', 'stroke', 'stroke-width', 'stroke-linecap', 'stroke-linejoin', 'fill', 'rx', 'width', 'height', 'x', 'y', 'd'],
    })
  } catch {
    return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/\n/g, '<br>')
  }
})

function handleMdClick(e) {
  const btn = e.target.closest('[data-action="copy-code"]')
  if (!btn) return
  const wrapper = btn.closest('.code-wrapper')
  const codeEl = wrapper?.querySelector('code')
  if (!codeEl) return
  const codeText = codeEl.innerText
  const span = btn.querySelector('span')

  const setSuccess = () => {
    if (span) span.innerText = '已复制'
    btn.classList.add('copied')
    setTimeout(() => {
      if (span) span.innerText = '复制'
      btn.classList.remove('copied')
    }, 1800)
  }

  if (navigator.clipboard) {
    navigator.clipboard.writeText(codeText).then(setSuccess).catch(() => {
      fallbackCopy(codeText, setSuccess)
    })
  } else {
    fallbackCopy(codeText, setSuccess)
  }
}

function fallbackCopy(text, cb) {
  const ta = document.createElement('textarea')
  ta.value = text
  ta.style.position = 'fixed'
  ta.style.opacity = '0'
  document.body.appendChild(ta)
  ta.select()
  try {
    document.execCommand('copy')
    cb()
  } catch {
    /* ignore */
  }
  document.body.removeChild(ta)
}
</script>

<style>
.md .katex-display {
  overflow-x: auto;
  overflow-y: hidden;
  padding: 0.6rem 0.8rem;
  margin: 0.85rem 0;
  background: rgba(0, 0, 0, 0.02);
  border-radius: 8px;
  scrollbar-width: thin;
  scrollbar-color: rgba(9, 105, 218, 0.2) transparent;
}
.md .katex-display::-webkit-scrollbar {
  height: 4px;
}
.md .katex-display::-webkit-scrollbar-thumb {
  background: rgba(9, 105, 218, 0.25);
  border-radius: 4px;
}
.md .katex {
  font-size: 1.05em;
  text-rendering: auto;
}
/* 答案框 \boxed{} 视觉增强 */
.md .katex .fbox,
.md .katex .boxed {
  border: 1.6px solid var(--blue, #0969da) !important;
  background: rgba(9, 105, 218, 0.05);
  border-radius: 4px;
  padding: 2px 6px !important;
  display: inline-block;
  box-shadow: 0 1px 3px rgba(9, 105, 218, 0.12);
}
.md .katex .boxpad {
  padding: 0 0.15em;
}
.md table {
  width: 100%;
  border-collapse: separate;
  border-spacing: 0;
  margin: 12px 0;
  border-radius: 8px;
  overflow: hidden;
  border: 1px solid var(--hairline, rgba(0, 0, 0, 0.08));
}
.md th {
  background: rgba(0, 0, 0, 0.03);
  padding: 8px 12px;
  font-weight: 600;
  border-bottom: 1px solid var(--hairline, rgba(0, 0, 0, 0.08));
}
.md td {
  padding: 8px 12px;
  border-bottom: 1px solid var(--hairline, rgba(0, 0, 0, 0.05));
}
.md blockquote {
  margin: 10px 0;
  padding: 8px 14px;
  border-left: 3.5px solid var(--blue, #0969da);
  background: rgba(9, 105, 218, 0.04);
  border-radius: 0 6px 6px 0;
  color: var(--text-2, #57606a);
}
</style>
