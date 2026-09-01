<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, watch } from 'vue'
import { listFiles } from '@/api'
import type { DirectoryListing, FileEntry } from '@/types'
import { browseHref } from '@/utils'
import { curateEntries, libraryTrail } from '@/library'

interface TreeRow {
  entry: FileEntry
  depth: number
}

const props = defineProps<{
  listing: DirectoryListing
  rootPath: string
  selectedPath?: string
}>()
const emit = defineEmits<{ activate: [entry: FileEntry] }>()
const cachedEntries = reactive(new Map<string, FileEntry[]>())
const expandedPaths = reactive(new Set<string>())
const loadingPaths = reactive(new Set<string>())
const failedPaths = reactive(new Set<string>())
let active = true

function ancestors(path: string): string[] {
  if (!path.startsWith(props.rootPath)) return []
  return libraryTrail(path)
}

async function ensureDirectory(path: string) {
  if (cachedEntries.has(path) || loadingPaths.has(path)) return
  loadingPaths.add(path)
  failedPaths.delete(path)
  try {
    const result = await listFiles(path)
    if (active) cachedEntries.set(path, curateEntries(path, result.entries))
  } catch {
    if (active) failedPaths.add(path)
  } finally {
    loadingPaths.delete(path)
  }
}

async function reveal(path: string) {
  await ensureDirectory(props.rootPath)
  for (const next of libraryTrail(path)) {
    expandedPaths.add(next)
    await ensureDirectory(next)
  }
}

watch(
  () => props.listing,
  (listing) => {
    cachedEntries.set(listing.path, curateEntries(listing.path, listing.entries))
    ancestors(listing.path).forEach((path) => expandedPaths.add(path))
    void reveal(listing.path)
  },
  { immediate: true },
)

const rows = computed<TreeRow[]>(() => {
  const result: TreeRow[] = []
  const append = (directory: string, depth: number) => {
    for (const entry of cachedEntries.get(directory) || []) {
      if (entry.type !== 'DIRECTORY') continue
      result.push({ entry, depth })
      if (entry.type === 'DIRECTORY' && expandedPaths.has(entry.path)) append(entry.path, depth + 1)
    }
  }
  append(props.rootPath, 0)
  return result
})

function toggleDirectory(entry: FileEntry) {
  if (expandedPaths.has(entry.path)) {
    expandedPaths.delete(entry.path)
    return
  }
  expandedPaths.add(entry.path)
  void ensureDirectory(entry.path)
}

function activate(entry: FileEntry) {
  expandedPaths.add(entry.path)
  void ensureDirectory(entry.path)
  emit('activate', entry)
}

function icon(entry: FileEntry): string {
  return expandedPaths.has(entry.path) ? '▣' : '□'
}

onMounted(() => void reveal(props.listing.path))
onBeforeUnmount(() => { active = false })
</script>

<template>
  <div class="file-tree" role="tree" aria-label="资料目录">
    <div
      v-for="row in rows"
      :key="row.entry.path"
      class="tree-row"
      :class="{
        current: row.entry.type === 'DIRECTORY' && row.entry.path === listing.path,
        selected: row.entry.path === selectedPath,
      }"
      :style="{ '--tree-depth': row.depth }"
      role="treeitem"
      :aria-level="row.depth + 1"
      :aria-expanded="expandedPaths.has(row.entry.path)"
    >
      <button
        class="tree-toggle"
        type="button"
        :title="expandedPaths.has(row.entry.path) ? '收起' : '展开'"
        @click="toggleDirectory(row.entry)"
      >
        <span v-if="loadingPaths.has(row.entry.path)" class="tree-spinner"></span>
        <span v-else>{{ expandedPaths.has(row.entry.path) ? '⌄' : '›' }}</span>
      </button>
      <component
        is="a"
        :href="browseHref(row.entry.path)"
        class="tree-target file-row"
        :title="failedPaths.has(row.entry.path) ? `${row.entry.name}（展开失败）` : row.entry.path"
        @click.prevent="activate(row.entry)"
      >
        <span class="tree-icon" :class="row.entry.type.toLowerCase()">{{ icon(row.entry) }}</span>
        <span class="tree-name">{{ row.entry.name }}</span>
      </component>
    </div>
    <div v-if="!rows.length" class="empty-state">这里还没有资料</div>
  </div>
</template>
