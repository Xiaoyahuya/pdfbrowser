<script setup lang="ts">
import type { FileEntry } from '@/types'
import { browseHref, formatBytes, formatDate } from '@/utils'

defineProps<{ entries: FileEntry[]; selectedPath?: string }>()
const emit = defineEmits<{ activate: [entry: FileEntry] }>()

function icon(entry: FileEntry): string {
  if (entry.type === 'DIRECTORY') return '📁'
  if (entry.type === 'PDF') return '📕'
  if (entry.type === 'MARKDOWN') return '📝'
  return '📄'
}
</script>

<template>
  <div v-if="entries.length" class="file-list" role="list">
    <component
      v-for="entry in entries"
      :key="entry.path"
      :is="entry.type === 'DIRECTORY' ? 'a' : 'button'"
      :href="entry.type === 'DIRECTORY' ? browseHref(entry.path) : undefined"
      :type="entry.type === 'DIRECTORY' ? undefined : 'button'"
      class="file-row"
      :class="{ selected: selectedPath === entry.path }"
      role="listitem"
      @click.prevent="emit('activate', entry)"
    >
      <span class="file-icon" aria-hidden="true">{{ icon(entry) }}</span>
      <span class="file-main">
        <strong>{{ entry.name }}</strong>
        <small>{{ formatDate(entry.lastModified) }}</small>
      </span>
      <span class="file-size">{{ formatBytes(entry.size) }}</span>
      <span v-if="entry.type === 'DIRECTORY'" class="file-enter" aria-hidden="true">进入 →</span>
    </component>
  </div>
  <div v-else class="empty-state">这个目录没有可显示的内容</div>
</template>
