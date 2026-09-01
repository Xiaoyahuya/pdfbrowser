<script setup lang="ts">
import { defineAsyncComponent } from 'vue'
import type { FileEntry } from '@/types'
import { rawFileUrl, rawNasFileUrl } from '@/api'

const MarkdownPreview = defineAsyncComponent(() => import('./MarkdownPreview.vue'))
const PdfPreview = defineAsyncComponent(() => import('./PdfPreview.vue'))

withDefaults(defineProps<{ entry: FileEntry | null; closable?: boolean }>(), { closable: false })
const emit = defineEmits<{ open: [path: string]; close: [] }>()

function entryUrl(entry: FileEntry, download = false): string {
  return entry.mountId
    ? rawNasFileUrl(entry.mountId, entry.path, download)
    : rawFileUrl(entry.path, download)
}
</script>

<template>
  <section class="preview-pane" aria-label="文件预览">
    <header class="preview-header">
      <h2>{{ entry?.name || '选择文件开始阅读' }}</h2>
      <div class="preview-header-actions">
        <a v-if="entry && entry.type !== 'DIRECTORY'" class="secondary-button" :href="entryUrl(entry, true)">下载</a>
        <button v-if="closable" class="secondary-button" type="button" @click="emit('close')">关闭</button>
      </div>
    </header>
    <div class="preview-content" :class="{ 'is-pdf': entry?.type === 'PDF' }">
      <PdfPreview
        v-if="entry?.type === 'PDF'"
        :key="`${entry.mountId || 'library'}:${entry.path}`"
        :path="entry.path"
        :name="entry.name"
        :mount-id="entry.mountId"
        :size="entry.size"
      />
      <MarkdownPreview
        v-else-if="entry?.type === 'MARKDOWN'"
        :key="`${entry.mountId || 'library'}:${entry.path}`"
        :path="entry.path"
        :mount-id="entry.mountId"
        @open="emit('open', $event)"
      />
      <div v-else-if="entry?.type === 'OTHER'" class="preview-message">该文件不支持预览，可以直接下载。</div>
      <div v-else class="preview-placeholder">
        <span>📚</span>
        <p>从左侧选择 PDF 或 Markdown 文件</p>
      </div>
    </div>
  </section>
</template>
