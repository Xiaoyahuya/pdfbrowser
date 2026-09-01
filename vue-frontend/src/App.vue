<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  ContextMenuIds,
  VueFinder,
  contextMenuItems,
  type ConfigDefaults,
  type DirEntry,
  type FeaturesConfig,
  type ItemDclickEvent,
} from 'vuefinder'
import { getCapabilities } from './api'
import NasMountPanel from './components/NasMountPanel.vue'
import PasswordChangePanel from './components/PasswordChangePanel.vue'
import PreviewPane from './components/PreviewPane.vue'
import type { FileEntry, StorageCapabilities } from './types'
import { browseHref, pathFromHash } from './utils'
import { PdfBrowserVueFinderDriver, VIRTUAL_ROOT } from './vuefinderDriver'

const driver = new PdfBrowserVueFinderDriver()
const previewEntry = ref<FileEntry | null>(null)
const capabilities = ref<StorageCapabilities>({ writable: false, maxUploadBytes: 100 * 1024 * 1024 })
const showNasPanel = ref(false)
const showPasswordPanel = ref(false)
const finderKey = ref(0)
const nasMountCount = ref(0)
const nasMountTotal = ref(0)

const initialPath = (() => {
  const physicalPath = pathFromHash(window.location.hash)
  if (!physicalPath) return VIRTUAL_ROOT
  try {
    return driver.toVirtualPath(physicalPath)
  } catch {
    return VIRTUAL_ROOT
  }
})()

const config: ConfigDefaults = {
  initialPath,
  persist: false,
  view: 'list',
  theme: 'silver',
  showTreeView: true,
  expandTreeByDefault: false,
  showHiddenFiles: false,
  showThumbnails: false,
  metricUnits: true,
  showMenuBar: true,
  showToolbar: true,
  showBreadcrumbBar: true,
  loadingIndicator: 'linear',
  maxFileSize: '100mb',
}

const features: FeaturesConfig = {
  search: true,
  preview: true,
  upload: true,
  download: true,
  fullscreen: true,
  history: true,
  theme: true,
  language: false,
  pinned: false,
  edit: false,
  newfile: false,
  newfolder: false,
  archive: false,
  unarchive: false,
  rename: false,
  delete: false,
  move: false,
  copy: false,
}

const storageStatus = computed(() => capabilities.value.writable ? 'Google Drive 可写' : 'Google Drive 只读')
const nasStatus = computed(() => nasMountTotal.value
  ? `存储 ${nasMountCount.value}/${nasMountTotal.value}`
  : '挂载存储')

function entryType(name: string): FileEntry['type'] {
  if (/\.pdf$/i.test(name)) return 'PDF'
  if (/\.(md|markdown)$/i.test(name)) return 'MARKDOWN'
  return 'OTHER'
}

function fromVueFinderEntry(entry: DirEntry): FileEntry {
  const resource = driver.resolveVirtualPath(entry.path)
  return {
    path: resource.path,
    name: entry.basename,
    type: entry.type === 'dir' ? 'DIRECTORY' : entryType(entry.basename),
    size: entry.file_size,
    lastModified: entry.last_modified ? entry.last_modified * 1000 : 0,
    mimeType: entry.mime_type,
    previewable: entry.type === 'file' && /\.(pdf|md|markdown)$/i.test(entry.basename),
    mountId: resource.mountId,
  }
}

function openSpecialPreview(entry: DirEntry): boolean {
  if (entry.type !== 'file' || !/\.(pdf|md|markdown)$/i.test(entry.basename)) return false
  previewEntry.value = fromVueFinderEntry(entry)
  return true
}

function handleFileDoubleClick(event: ItemDclickEvent) {
  if (!openSpecialPreview(event.item)) return
  event.preventDefault()
}

function openLinkedDocument(path: string) {
  const name = path.split('/').filter(Boolean).at(-1) || path
  const mountId = previewEntry.value?.mountId
  previewEntry.value = {
    path,
    name,
    type: entryType(name),
    size: null,
    lastModified: 0,
    mimeType: /\.pdf$/i.test(name) ? 'application/pdf' : 'text/markdown',
    previewable: true,
    mountId,
  }
}

function handlePathChange(virtualPath: string) {
  try {
    const nextUrl = `${window.location.pathname}${window.location.search}${browseHref(driver.toPhysicalPath(virtualPath))}`
    window.history.replaceState(null, '', nextUrl)
  } catch {
    // VueFinder will surface invalid paths through its own notification system.
  }
}

const customizedContextMenuItems = contextMenuItems.map((item) => {
  if (item.id !== ContextMenuIds.preview) return item
  return {
    ...item,
    action: (app: Parameters<typeof item.action>[0], selectedItems: DirEntry[]) => {
      const selected = selectedItems[0]
      if (selected && openSpecialPreview(selected)) return
      item.action(app, selectedItems)
    },
  }
})

function handleGlobalKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && previewEntry.value) previewEntry.value = null
  else if (event.key === 'Escape' && showNasPanel.value) showNasPanel.value = false
  else if (event.key === 'Escape' && showPasswordPanel.value) showPasswordPanel.value = false
}

async function handleNasChanged() {
  const mounts = await driver.refreshNasMounts()
  nasMountCount.value = mounts.filter((mount) => mount.status === 'CONNECTED').length
  nasMountTotal.value = mounts.length
  finderKey.value += 1
}

onMounted(async () => {
  window.addEventListener('keydown', handleGlobalKeydown)
  try {
    capabilities.value = await getCapabilities()
  } catch {
    capabilities.value = { writable: false, maxUploadBytes: 100 * 1024 * 1024 }
  }
  const mounts = await driver.refreshNasMounts()
  nasMountCount.value = mounts.filter((mount) => mount.status === 'CONNECTED').length
  nasMountTotal.value = mounts.length
})

onBeforeUnmount(() => window.removeEventListener('keydown', handleGlobalKeydown))
</script>

<template>
  <main class="vuefinder-page">
    <VueFinder
      :key="finderKey"
      id="pdfbrowser-official-vuefinder"
      class="pdfbrowser-vuefinder"
      :driver="driver"
      :config="config"
      :features="features"
      :context-menu-items="customizedContextMenuItems"
      locale="zhCN"
      selection-mode="multiple"
      @file-dclick="handleFileDoubleClick"
      @path-change="handlePathChange"
    >
      <template #menubar-end>
        <button class="nas-menu-button" type="button" @click="showNasPanel = true">{{ nasStatus }}</button>
        <button class="nas-menu-button" type="button" @click="showPasswordPanel = true">修改密码</button>
        <span class="storage-status" :class="{ writable: capabilities.writable }">{{ storageStatus }}</span>
      </template>
    </VueFinder>

    <div v-if="previewEntry" class="special-preview-overlay" @click.self="previewEntry = null">
      <PreviewPane
        class="special-preview-pane"
        :entry="previewEntry"
        closable
        @close="previewEntry = null"
        @open="openLinkedDocument"
      />
    </div>

    <NasMountPanel
      v-if="showNasPanel"
      @close="showNasPanel = false"
      @changed="handleNasChanged"
    />

    <PasswordChangePanel
      v-if="showPasswordPanel"
      @close="showPasswordPanel = false"
    />
  </main>
</template>
