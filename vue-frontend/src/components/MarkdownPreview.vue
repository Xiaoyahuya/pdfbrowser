<script lang="ts">
const renderedCache = new Map<string, string>()
const MAX_RENDERED_CACHE = 24
</script>

<script setup lang="ts">
import MarkdownIt from 'markdown-it'
import { onBeforeUnmount, ref, watch } from 'vue'
import { loadMarkdown, loadNasMarkdown, rawFileUrl, rawNasFileUrl } from '@/api'
import { resolveRelativePath } from '@/utils'

const props = defineProps<{ path: string; mountId?: string }>()
const emit = defineEmits<{ open: [path: string] }>()
const source = ref('')
const html = ref('')
const status = ref<'loading' | 'rendering' | 'ready' | 'error'>('loading')
const error = ref('')
let controller: AbortController | null = null

const renderer = new MarkdownIt({ html: false, linkify: true, typographer: true })
const defaultLinkOpen = renderer.renderer.rules.link_open
renderer.renderer.rules.link_open = (tokens, index, options, env, self) => {
  const token = tokens[index]
  const original = token?.attrGet('href')
  if (token && original && !original.startsWith('#')) {
    let withoutFragment = original.split('#', 1)[0]?.split('?', 1)[0] || ''
    try {
      withoutFragment = decodeURIComponent(withoutFragment)
    } catch {
      // Keep the literal URL if a document contains malformed percent escapes.
    }
    const resolved = withoutFragment.startsWith('/') && !withoutFragment.startsWith('//')
      ? withoutFragment.slice(1)
      : resolveRelativePath(props.path, withoutFragment)
    if (resolved) {
      token.attrSet('href', '#')
      token.attrSet('data-pdfbrowser-path', resolved)
    } else {
      token.attrSet('target', '_blank')
      token.attrSet('rel', 'noopener noreferrer')
    }
  }
  return defaultLinkOpen ? defaultLinkOpen(tokens, index, options, env, self) : self.renderToken(tokens, index, options)
}
const defaultImage = renderer.renderer.rules.image
renderer.renderer.rules.image = (tokens, index, options, env, self) => {
  const token = tokens[index]
  const original = token?.attrGet('src')
  if (token && original) {
    const resolved = resolveRelativePath(props.path, original)
    if (resolved) {
      token.attrSet('src', props.mountId
        ? rawNasFileUrl(props.mountId, resolved)
        : rawFileUrl(resolved))
    }
  }
  return defaultImage ? defaultImage(tokens, index, options, env, self) : self.renderToken(tokens, index, options)
}

function remember(path: string, rendered: string) {
  renderedCache.delete(path)
  renderedCache.set(path, rendered)
  if (renderedCache.size > MAX_RENDERED_CACHE) {
    const oldest = renderedCache.keys().next().value
    if (oldest) renderedCache.delete(oldest)
  }
}

function yieldForPaint(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0))
}

function handleClick(event: MouseEvent) {
  const target = event.target
  if (!(target instanceof Element)) return
  const link = target.closest<HTMLAnchorElement>('a[data-pdfbrowser-path]')
  const path = link?.dataset.pdfbrowserPath
  if (!path) return
  event.preventDefault()
  emit('open', path)
}

watch(
  () => [props.mountId, props.path] as const,
  async ([mountId, path]) => {
    controller?.abort()
    const cacheKey = `${mountId || 'library'}:${path}`
    const cached = renderedCache.get(cacheKey)
    if (cached) {
      html.value = cached
      status.value = 'ready'
      error.value = ''
      return
    }
    const requestController = new AbortController()
    controller = requestController
    status.value = 'loading'
    error.value = ''
    source.value = ''
    html.value = ''
    try {
      source.value = mountId
        ? await loadNasMarkdown(mountId, path, requestController.signal)
        : await loadMarkdown(path, requestController.signal)
      status.value = 'rendering'
      await yieldForPaint()
      if (requestController.signal.aborted) return
      const rendered = renderer.render(source.value)
      remember(cacheKey, rendered)
      html.value = rendered
      status.value = 'ready'
    } catch (reason) {
      if (reason instanceof DOMException && reason.name === 'AbortError') return
      error.value = reason instanceof Error ? reason.message : 'Markdown 加载失败'
      status.value = 'error'
    }
  },
  { immediate: true },
)

onBeforeUnmount(() => controller?.abort())
</script>

<template>
  <div v-if="status === 'loading'" class="preview-message">正在读取 Markdown…</div>
  <div v-else-if="status === 'rendering'" class="preview-message">正在排版…</div>
  <div v-else-if="status === 'error'" class="preview-message error-message">{{ error }}</div>
  <article v-else class="markdown-body" @click="handleClick" v-html="html"></article>
</template>
