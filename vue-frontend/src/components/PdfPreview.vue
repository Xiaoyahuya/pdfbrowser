<script setup lang="ts">
import {
  GlobalWorkerOptions,
  getDocument,
  type OnProgressParameters,
  type PDFDocumentLoadingTask,
  type PDFDocumentProxy,
  type RenderTask,
} from 'pdfjs-dist'
import pdfWorkerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url'
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  reactive,
  ref,
  shallowRef,
  watch,
  type ComponentPublicInstance,
} from 'vue'
import { rawFileUrl, rawNasFileUrl } from '@/api'
import {
  loadPdfBookmarks,
  loadPdfReadingState,
  pdfDocumentId,
  savePdfBookmarks,
  savePdfReadingState,
} from '@/pdfReadingState'

GlobalWorkerOptions.workerSrc = pdfWorkerUrl

interface OutlineEntry {
  title: string
  page: number
  level: number
}

const DEFAULT_PAGE_RATIO = Math.SQRT2
const MIN_ZOOM = 0.6
const MAX_ZOOM = 3
const STREAM_FILE_LIMIT = 12 * 1024 * 1024

const props = defineProps<{ path: string; name: string; mountId?: string; size?: number | null }>()
const source = computed(() => props.mountId
  ? rawNasFileUrl(props.mountId, props.path)
  : rawFileUrl(props.path))
const documentId = computed(() => pdfDocumentId(props.path, props.mountId))
const useFullStream = computed(() => Boolean(props.size && props.size > 0 && props.size <= STREAM_FILE_LIMIT))
const loadingModeLabel = computed(() => useFullStream.value ? '单连接流式读取' : '按需 Range')
const viewerRoot = ref<HTMLElement | null>(null)
const scrollStage = ref<HTMLElement | null>(null)
const documentProxy = shallowRef<PDFDocumentProxy | null>(null)
const currentPage = ref(1)
const pageCount = ref(0)
const pageRatios = ref<number[]>([])
const fitPageWidth = ref(720)
const zoom = ref(1)
const loading = ref(false)
const progress = ref(0)
const error = ref('')
const bookmarks = ref<number[]>([])
const outline = ref<OutlineEntry[]>([])
const outlineLoading = ref(false)
const navigatorVisible = ref(false)
const toolbarVisible = ref(true)
const fullscreen = ref(false)
const renderedPages = reactive(new Set<number>())
const visiblePages = new Set<number>()
const pageShells = new Map<number, HTMLElement>()
const pageCanvases = new Map<number, HTMLCanvasElement>()
const renderTasks = new Map<number, RenderTask>()
const renderedSignatures = new Map<number, string>()
const pendingPages = new Set<string>()
let loadingTask: PDFDocumentLoadingTask | null = null
let intersectionObserver: IntersectionObserver | null = null
let resizeObserver: ResizeObserver | null = null
let generation = 0
let scrollFrame = 0
let rerenderTimer = 0
let saveTimer = 0
let restoring = false
let pinchDistance = 0
let pinchStartZoom = 1
let primaryTouchStart: { x: number; y: number; time: number } | null = null
let primaryTouchMoved = false
let oneFingerZoomActive = false
let oneFingerZoomMoved = false
let oneFingerZoomStartY = 0
let oneFingerZoomStartValue = 1
let lastTapAt = 0
let singleTapTimer = 0
let viewportFrame = 0
let activeDocumentId = ''

const pageNumbers = computed(() => Array.from({ length: pageCount.value }, (_, index) => index + 1))
const currentBookmarked = computed(() => bookmarks.value.includes(currentPage.value))

function clamp(value: number, minimum: number, maximum: number) {
  return Math.min(maximum, Math.max(minimum, value))
}

function setPageShell(page: number, element: Element | ComponentPublicInstance | null) {
  if (element instanceof HTMLElement) pageShells.set(page, element)
  else pageShells.delete(page)
}

function setPageCanvas(page: number, element: Element | ComponentPublicInstance | null) {
  if (element instanceof HTMLCanvasElement) pageCanvases.set(page, element)
  else pageCanvases.delete(page)
}

function pageStyle(page: number) {
  const width = Math.max(96, Math.round(fitPageWidth.value * zoom.value))
  const ratio = pageRatios.value[page - 1] || DEFAULT_PAGE_RATIO
  return {
    width: `${width}px`,
    height: `${Math.round(width * ratio)}px`,
  }
}

function compactViewport() {
  const visualWidth = window.visualViewport?.width || window.innerWidth
  return Math.min(window.innerWidth, visualWidth) <= 768
}

function updateFitWidth() {
  const stage = scrollStage.value
  if (!stage) return
  const availableWidths = [
    stage.clientWidth,
    viewerRoot.value?.clientWidth || 0,
    window.visualViewport?.width || 0,
    window.innerWidth,
  ].filter((width) => width > 0)
  const visibleWidth = Math.min(...availableWidths)
  const horizontalPadding = compactViewport() ? 8 : 48
  fitPageWidth.value = Math.max(96, Math.floor(visibleWidth - horizontalPadding))
}

function handleViewportResize() {
  window.cancelAnimationFrame(viewportFrame)
  viewportFrame = window.requestAnimationFrame(() => {
    updateFitWidth()
    scheduleVisibleRerender()
  })
}

function desiredSignature(page: number) {
  const shell = pageShells.get(page)
  const outputScale = Math.min(window.devicePixelRatio || 1, 2)
  return `${Math.round(shell?.clientWidth || fitPageWidth.value * zoom.value)}:${outputScale}`
}

async function renderPage(pageNumber: number) {
  const pdf = documentProxy.value
  const canvas = pageCanvases.get(pageNumber)
  const shell = pageShells.get(pageNumber)
  const expectedGeneration = generation
  const jobKey = `${expectedGeneration}:${pageNumber}`
  if (!pdf || !canvas || !shell || pendingPages.has(jobKey)) return

  const signature = desiredSignature(pageNumber)
  if (renderedSignatures.get(pageNumber) === signature) return
  pendingPages.add(jobKey)

  try {
    const page = await pdf.getPage(pageNumber)
    if (expectedGeneration !== generation) return
    const baseViewport = page.getViewport({ scale: 1 })
    const ratio = baseViewport.height / baseViewport.width
    if (Math.abs((pageRatios.value[pageNumber - 1] || 0) - ratio) > 0.001) {
      pageRatios.value[pageNumber - 1] = ratio
      await nextTick()
    }

    const cssWidth = pageShells.get(pageNumber)?.clientWidth || shell.clientWidth
    const viewport = page.getViewport({ scale: cssWidth / baseViewport.width })
    const outputScale = Math.min(window.devicePixelRatio || 1, 2)
    const context = canvas.getContext('2d', { alpha: false })
    if (!context) throw new Error('浏览器无法创建 PDF 画布')

    canvas.width = Math.max(1, Math.floor(viewport.width * outputScale))
    canvas.height = Math.max(1, Math.floor(viewport.height * outputScale))
    canvas.style.width = '100%'
    canvas.style.height = '100%'
    const task = page.render({
      canvas,
      canvasContext: context,
      viewport,
      transform: outputScale === 1 ? undefined : [outputScale, 0, 0, outputScale, 0, 0],
    })
    renderTasks.set(pageNumber, task)
    await task.promise
    if (expectedGeneration !== generation) return
    renderedSignatures.set(pageNumber, signature)
    renderedPages.add(pageNumber)
  } catch (reason) {
    if (!(reason instanceof Error) || reason.name !== 'RenderingCancelledException') {
      error.value = reason instanceof Error ? reason.message : `第 ${pageNumber} 页渲染失败`
    }
  } finally {
    renderTasks.delete(pageNumber)
    pendingPages.delete(jobKey)
    if (
      expectedGeneration === generation
      && visiblePages.has(pageNumber)
      && renderedSignatures.get(pageNumber) !== desiredSignature(pageNumber)
    ) {
      window.setTimeout(() => void renderPage(pageNumber), 0)
    }
  }
}

function releaseDistantCanvases(centerPage: number) {
  for (const page of [...renderedPages]) {
    if (Math.abs(page - centerPage) <= 5 || visiblePages.has(page)) continue
    const canvas = pageCanvases.get(page)
    if (canvas) {
      canvas.width = 1
      canvas.height = 1
    }
    renderedPages.delete(page)
    renderedSignatures.delete(page)
  }
}

function setupIntersectionObserver() {
  intersectionObserver?.disconnect()
  const stage = scrollStage.value
  if (!stage) return
  intersectionObserver = new IntersectionObserver((entries) => {
    for (const entry of entries) {
      const page = Number((entry.target as HTMLElement).dataset.page)
      if (!page) continue
      if (entry.isIntersecting) {
        visiblePages.add(page)
        void renderPage(page)
      } else {
        visiblePages.delete(page)
      }
    }
  }, {
    root: stage,
    rootMargin: '100% 0px 100% 0px',
    threshold: 0.01,
  })
  for (const shell of pageShells.values()) intersectionObserver.observe(shell)
}

function detectCurrentPage() {
  const stage = scrollStage.value
  if (!stage || !pageCount.value) return
  const stageRect = stage.getBoundingClientRect()
  const readingLine = stageRect.top + stage.clientHeight * 0.38
  const candidates = visiblePages.size ? [...visiblePages] : [currentPage.value]
  let bestPage = currentPage.value
  let bestDistance = Number.POSITIVE_INFINITY
  for (const page of candidates) {
    const shell = pageShells.get(page)
    if (!shell) continue
    const rect = shell.getBoundingClientRect()
    const distance = readingLine < rect.top
      ? rect.top - readingLine
      : readingLine > rect.bottom
        ? readingLine - rect.bottom
        : 0
    if (distance < bestDistance || (distance === bestDistance && page < bestPage)) {
      bestDistance = distance
      bestPage = page
    }
  }
  if (currentPage.value !== bestPage) currentPage.value = bestPage
  releaseDistantCanvases(bestPage)
}

function readingOffset(page: number) {
  const stage = scrollStage.value
  const shell = pageShells.get(page)
  if (!stage || !shell || shell.offsetHeight < 1) return 0
  return clamp((stage.scrollTop - shell.offsetTop) / shell.offsetHeight, 0, 1)
}

function persistReadingState() {
  if (!pageCount.value || restoring || !activeDocumentId) return
  savePdfReadingState(activeDocumentId, {
    page: currentPage.value,
    zoom: zoom.value,
    offset: readingOffset(currentPage.value),
  })
}

function queueProgressSave() {
  window.clearTimeout(saveTimer)
  saveTimer = window.setTimeout(persistReadingState, 250)
}

function handleScroll() {
  window.cancelAnimationFrame(scrollFrame)
  scrollFrame = window.requestAnimationFrame(() => {
    detectCurrentPage()
    queueProgressSave()
  })
}

function scrollToPage(page: number, smooth = true, offset = 0) {
  const normalized = clamp(Math.trunc(page) || 1, 1, pageCount.value || 1)
  const stage = scrollStage.value
  const shell = pageShells.get(normalized)
  currentPage.value = normalized
  if (!stage || !shell) return
  stage.scrollTo({
    top: Math.max(0, shell.offsetTop + shell.offsetHeight * clamp(offset, 0, 1) - 10),
    behavior: smooth ? 'smooth' : 'auto',
  })
  void renderPage(normalized)
  void renderPage(clamp(normalized + 1, 1, pageCount.value))
  queueProgressSave()
}

function scheduleVisibleRerender() {
  window.clearTimeout(rerenderTimer)
  rerenderTimer = window.setTimeout(() => {
    for (const page of visiblePages) {
      renderedSignatures.delete(page)
      renderTasks.get(page)?.cancel()
      void renderPage(page)
    }
  }, 120)
}

function applyZoom(nextZoom: number, clientX?: number, clientY?: number) {
  const stage = scrollStage.value
  const normalized = clamp(nextZoom, MIN_ZOOM, MAX_ZOOM)
  if (!stage || Math.abs(normalized - zoom.value) < 0.005) return
  const oldZoom = zoom.value
  const stageRect = stage.getBoundingClientRect()
  const localX = (clientX ?? stageRect.left + stage.clientWidth / 2) - stageRect.left
  const localY = (clientY ?? stageRect.top + stage.clientHeight * 0.42) - stageRect.top
  const contentX = stage.scrollLeft + localX
  const contentY = stage.scrollTop + localY
  zoom.value = normalized
  void nextTick(() => {
    const ratio = normalized / oldZoom
    stage.scrollLeft = Math.max(0, contentX * ratio - localX)
    stage.scrollTop = Math.max(0, contentY * ratio - localY)
    detectCurrentPage()
  })
  scheduleVisibleRerender()
  queueProgressSave()
}

function touchDistance(touches: TouchList) {
  const left = touches.item(0)
  const right = touches.item(1)
  return left && right ? Math.hypot(right.clientX - left.clientX, right.clientY - left.clientY) : 0
}

function touchCenter(touches: TouchList) {
  const left = touches.item(0)
  const right = touches.item(1)
  return left && right
    ? { x: (left.clientX + right.clientX) / 2, y: (left.clientY + right.clientY) / 2 }
    : null
}

function handleTouchStart(event: TouchEvent) {
  if (event.touches.length === 1) {
    const touch = event.touches.item(0)
    if (!touch) return
    const now = Date.now()
    if (lastTapAt && now - lastTapAt <= 320) {
      window.clearTimeout(singleTapTimer)
      oneFingerZoomActive = true
      oneFingerZoomMoved = false
      oneFingerZoomStartY = touch.clientY
      oneFingerZoomStartValue = zoom.value
      primaryTouchStart = null
      lastTapAt = 0
      return
    }
    primaryTouchStart = { x: touch.clientX, y: touch.clientY, time: now }
    primaryTouchMoved = false
    return
  }
  if (event.touches.length === 2) {
    window.clearTimeout(singleTapTimer)
    primaryTouchStart = null
    primaryTouchMoved = true
    oneFingerZoomActive = false
    pinchDistance = touchDistance(event.touches)
    pinchStartZoom = zoom.value
  }
}

function handleTouchMove(event: TouchEvent) {
  if (event.touches.length === 1) {
    const touch = event.touches.item(0)
    if (!touch) return
    if (oneFingerZoomActive) {
      event.preventDefault()
      const distance = oneFingerZoomStartY - touch.clientY
      if (Math.abs(distance) > 4) oneFingerZoomMoved = true
      applyZoom(oneFingerZoomStartValue * Math.exp(distance / 180), touch.clientX, touch.clientY)
      return
    }
    if (primaryTouchStart && Math.hypot(
      touch.clientX - primaryTouchStart.x,
      touch.clientY - primaryTouchStart.y,
    ) > 9) primaryTouchMoved = true
    return
  }
  if (event.touches.length === 2 && pinchDistance > 0) {
    event.preventDefault()
    const center = touchCenter(event.touches)
    if (!center) return
    applyZoom(pinchStartZoom * (touchDistance(event.touches) / pinchDistance), center.x, center.y)
  }
}

function handleTouchEnd(event: TouchEvent) {
  if (pinchDistance > 0 && event.touches.length < 2) {
    pinchDistance = 0
    primaryTouchStart = null
    scheduleVisibleRerender()
    return
  }
  if (event.touches.length > 0) return

  const touch = event.changedTouches.item(0)
  if (oneFingerZoomActive) {
    if (!oneFingerZoomMoved && touch) {
      applyZoom(zoom.value > 1.05 ? 1 : 2, touch.clientX, touch.clientY)
    }
    oneFingerZoomActive = false
    oneFingerZoomMoved = false
    scheduleVisibleRerender()
    return
  }

  const start = primaryTouchStart
  primaryTouchStart = null
  if (!start || !touch || primaryTouchMoved) return
  const elapsed = Date.now() - start.time
  const distance = Math.hypot(touch.clientX - start.x, touch.clientY - start.y)
  if (elapsed > 420 || distance > 9) return
  lastTapAt = Date.now()
  window.clearTimeout(singleTapTimer)
  singleTapTimer = window.setTimeout(() => {
    lastTapAt = 0
    toggleToolbar()
  }, 300)
}

function handleZoomSlider(event: Event) {
  const value = Number((event.target as HTMLInputElement).value)
  if (Number.isFinite(value)) applyZoom(value / 100)
}

function fitToScreen() {
  applyZoom(1)
  handleViewportResize()
}

function toolbarHint() {
  if (!compactViewport()) return '轻点正文可隐藏工具栏'
  return '轻点正文隐藏；双击放大；双击后按住并上下拖动可单指缩放'
}

function cancelTouchState() {
  primaryTouchStart = null
  primaryTouchMoved = false
  oneFingerZoomActive = false
  oneFingerZoomMoved = false
  pinchDistance = 0
}

function handleTouchCancel() {
  cancelTouchState()
  scheduleVisibleRerender()
}

function resetMobileZoom(savedZoom?: number) {
  zoom.value = compactViewport() ? 1 : savedZoom || 1
}

function clearTouchTimers() {
  window.clearTimeout(singleTapTimer)
  window.cancelAnimationFrame(viewportFrame)
  lastTapAt = 0
}

function addViewportListeners() {
  window.addEventListener('resize', handleViewportResize)
  window.addEventListener('orientationchange', handleViewportResize)
  window.visualViewport?.addEventListener('resize', handleViewportResize)
}

function removeViewportListeners() {
  window.removeEventListener('resize', handleViewportResize)
  window.removeEventListener('orientationchange', handleViewportResize)
  window.visualViewport?.removeEventListener('resize', handleViewportResize)
}

function restoreToolbarPreference() {
  try {
    toolbarVisible.value = window.localStorage.getItem('pdfbrowser:pdf-toolbar-visible') !== 'false'
  } catch {
    toolbarVisible.value = true
  }
}

function toggleBookmark() {
  const current = currentPage.value
  bookmarks.value = currentBookmarked.value
    ? bookmarks.value.filter((page) => page !== current)
    : [...bookmarks.value, current].sort((left, right) => left - right)
  savePdfBookmarks(activeDocumentId || documentId.value, bookmarks.value)
}

function toggleToolbar() {
  toolbarVisible.value = !toolbarVisible.value
  if (!toolbarVisible.value) navigatorVisible.value = false
  try {
    window.localStorage.setItem('pdfbrowser:pdf-toolbar-visible', String(toolbarVisible.value))
  } catch {
    // Ignore private-mode and quota errors.
  }
  void nextTick(() => {
    updateFitWidth()
    scheduleVisibleRerender()
  })
}

async function toggleFullscreen() {
  const root = viewerRoot.value
  if (!root) return
  if (document.fullscreenElement) await document.exitFullscreen()
  else await root.requestFullscreen()
}

async function resolveOutlinePage(pdf: PDFDocumentProxy, destination: string | unknown[] | null) {
  try {
    const explicit = typeof destination === 'string'
      ? await pdf.getDestination(destination)
      : destination
    if (!explicit?.length) return null
    const reference = explicit[0]
    if (typeof reference === 'number') return reference + 1
    return (await pdf.getPageIndex(reference)) + 1
  } catch {
    return null
  }
}

async function loadOutline(pdf: PDFDocumentProxy, expectedGeneration: number) {
  outlineLoading.value = true
  try {
    const tree = await pdf.getOutline()
    const flattened: Array<{ title: string; destination: string | unknown[] | null; level: number }> = []
    const visit = (items: typeof tree, level: number) => {
      for (const item of items) {
        flattened.push({ title: item.title || '未命名书签', destination: item.dest, level })
        if (item.items?.length) visit(item.items, level + 1)
      }
    }
    visit(tree, 0)
    const resolved = await Promise.all(flattened.map(async (item) => ({
      title: item.title,
      page: await resolveOutlinePage(pdf, item.destination),
      level: item.level,
    })))
    if (expectedGeneration !== generation) return
    outline.value = resolved.filter((item): item is OutlineEntry => item.page !== null)
  } finally {
    if (expectedGeneration === generation) outlineLoading.value = false
  }
}

function clearDocumentView() {
  intersectionObserver?.disconnect()
  resizeObserver?.disconnect()
  intersectionObserver = null
  resizeObserver = null
  visiblePages.clear()
  for (const task of renderTasks.values()) task.cancel()
  renderTasks.clear()
  pendingPages.clear()
  renderedPages.clear()
  renderedSignatures.clear()
  pageShells.clear()
  pageCanvases.clear()
  outline.value = []
}

async function loadPdf() {
  persistReadingState()
  const expectedGeneration = ++generation
  clearDocumentView()
  if (loadingTask) await loadingTask.destroy()
  loadingTask = null
  documentProxy.value = null
  currentPage.value = 1
  pageCount.value = 0
  pageRatios.value = []
  progress.value = 0
  error.value = ''
  loading.value = true
  restoring = true
  activeDocumentId = documentId.value

  try {
    const task = getDocument({
      url: source.value,
      withCredentials: true,
      rangeChunkSize: 512 * 1024,
      disableRange: useFullStream.value,
      disableAutoFetch: !useFullStream.value,
      disableStream: !useFullStream.value,
    })
    loadingTask = task
    task.onProgress = ({ loaded, total }: OnProgressParameters) => {
      progress.value = total > 0 ? Math.min(100, Math.round((loaded / total) * 100)) : 0
    }
    const pdf = await task.promise
    if (expectedGeneration !== generation) {
      await task.destroy()
      return
    }
    documentProxy.value = pdf
    loading.value = false
    pageCount.value = pdf.numPages
    pageRatios.value = Array.from({ length: pdf.numPages }, () => DEFAULT_PAGE_RATIO)
    const saved = loadPdfReadingState(activeDocumentId, pdf.numPages)
    currentPage.value = saved?.page || 1
    resetMobileZoom(saved?.zoom)
    bookmarks.value = loadPdfBookmarks(activeDocumentId, pdf.numPages)
    await nextTick()
    updateFitWidth()
    setupIntersectionObserver()
    resizeObserver = new ResizeObserver(() => {
      updateFitWidth()
      scheduleVisibleRerender()
    })
    if (scrollStage.value) resizeObserver.observe(scrollStage.value)
    scrollToPage(currentPage.value, false, saved?.offset || 0)
    void loadOutline(pdf, expectedGeneration)
    window.requestAnimationFrame(() => {
      if (expectedGeneration === generation) restoring = false
    })
  } catch (reason) {
    if (expectedGeneration !== generation) return
    error.value = reason instanceof Error ? reason.message : 'PDF 加载失败'
    restoring = false
  } finally {
    if (expectedGeneration === generation) loading.value = false
  }
}

function handleFullscreenChange() {
  fullscreen.value = document.fullscreenElement === viewerRoot.value
  void nextTick(() => {
    updateFitWidth()
    scheduleVisibleRerender()
  })
}

watch(() => [props.mountId, props.path], () => void loadPdf(), { immediate: true })

onMounted(() => {
  restoreToolbarPreference()
  addViewportListeners()
  document.addEventListener('fullscreenchange', handleFullscreenChange)
})

onBeforeUnmount(() => {
  persistReadingState()
  generation += 1
  window.clearTimeout(rerenderTimer)
  window.clearTimeout(saveTimer)
  clearTouchTimers()
  window.cancelAnimationFrame(scrollFrame)
  removeViewportListeners()
  document.removeEventListener('fullscreenchange', handleFullscreenChange)
  clearDocumentView()
  void loadingTask?.destroy()
  loadingTask = null
  documentProxy.value = null
})
</script>

<template>
  <div ref="viewerRoot" class="pdf-viewer" :class="{ 'toolbar-hidden': !toolbarVisible, 'is-fullscreen': fullscreen }">
    <div v-if="toolbarVisible" class="pdf-toolbar" role="toolbar" aria-label="PDF 阅读工具栏">
      <button type="button" class="pdf-toolbar-collapse" :title="toolbarHint()" @click="toggleToolbar">⌃<span> 隐藏</span></button>
      <div class="pdf-toolbar-group">
        <button type="button" title="上一页" :disabled="currentPage <= 1 || loading" @click="scrollToPage(currentPage - 1)">‹</button>
        <label class="pdf-page-control" title="跳转页码">
          <input
            :value="currentPage"
            type="number"
            min="1"
            :max="pageCount || 1"
            :disabled="loading || !pageCount"
            aria-label="当前页码"
            @change="scrollToPage(Number(($event.target as HTMLInputElement).value))"
          />
          <span>/ {{ pageCount || '—' }}</span>
        </label>
        <button type="button" title="下一页" :disabled="currentPage >= pageCount || loading" @click="scrollToPage(currentPage + 1)">›</button>
      </div>
      <div class="pdf-toolbar-group">
        <button type="button" title="缩小" :disabled="loading" @click="applyZoom(zoom - 0.15)">−</button>
        <button type="button" class="pdf-zoom-value" title="适合屏幕宽度" :disabled="loading" @click="fitToScreen">{{ Math.round(zoom * 100) }}%</button>
        <input
          class="pdf-zoom-slider"
          type="range"
          :min="MIN_ZOOM * 100"
          :max="MAX_ZOOM * 100"
          step="5"
          :value="Math.round(zoom * 100)"
          :disabled="loading"
          aria-label="单指拖动缩放 PDF"
          title="单指拖动缩放"
          @input="handleZoomSlider"
        />
        <button type="button" title="放大" :disabled="loading" @click="applyZoom(zoom + 0.15)">＋</button>
      </div>
      <span class="pdf-toolbar-spacer"></span>
      <span
        class="pdf-range-status"
        :title="useFullStream ? '小文件使用单连接顺序传输，避免远程网盘频繁随机请求' : '大文件只读取当前页附近需要的字节区间'"
      >{{ loadingModeLabel }}</span>
      <button type="button" :class="{ active: currentBookmarked }" :title="currentBookmarked ? '删除当前页书签' : '收藏当前页'" @click="toggleBookmark">
        {{ currentBookmarked ? '★' : '☆' }}<span class="pdf-button-label"> 书签</span>
      </button>
      <button type="button" title="查看书签和 PDF 目录" @click="navigatorVisible = !navigatorVisible">☰<span class="pdf-button-label"> 目录</span></button>
      <button type="button" :title="fullscreen ? '退出全屏' : '全屏阅读'" @click="toggleFullscreen">⛶<span class="pdf-button-label"> 全屏</span></button>
      <a :href="source" target="_blank" rel="noopener noreferrer" title="在浏览器新标签页打开">↗</a>
    </div>

    <button v-else type="button" class="pdf-toolbar-reveal" title="显示阅读工具栏" @click="toggleToolbar">工具</button>

    <aside v-if="navigatorVisible" class="pdf-navigator" :class="{ 'below-toolbar': toolbarVisible }" aria-label="PDF 书签与目录">
      <div class="pdf-navigator-header">
        <strong>书签与目录</strong>
        <button type="button" aria-label="关闭" @click="navigatorVisible = false">×</button>
      </div>
      <section>
        <h3>我的书签</h3>
        <p v-if="!bookmarks.length" class="pdf-navigator-empty">点击工具栏的 ☆ 收藏当前页。</p>
        <button v-for="page in bookmarks" :key="`bookmark-${page}`" type="button" @click="scrollToPage(page); navigatorVisible = false">
          <span>★ 第 {{ page }} 页</span><span>跳转</span>
        </button>
      </section>
      <section>
        <h3>PDF 自带目录</h3>
        <p v-if="outlineLoading" class="pdf-navigator-empty">正在读取目录…</p>
        <p v-else-if="!outline.length" class="pdf-navigator-empty">这个 PDF 没有内置目录。</p>
        <button
          v-for="(item, index) in outline"
          :key="`${item.page}-${index}`"
          type="button"
          :style="{ paddingLeft: `${12 + Math.min(item.level, 5) * 14}px` }"
          @click="scrollToPage(item.page); navigatorVisible = false"
        >
          <span>{{ item.title }}</span><span>{{ item.page }}</span>
        </button>
      </section>
    </aside>

    <div v-if="loading" class="preview-message">
      <span>正在{{ loadingModeLabel }}{{ progress ? `（已收到 ${progress}%）` : '…' }}</span>
      <small>{{ useFullStream ? '小文件一次顺序传输更适合高延迟网盘' : '大文件只传当前阅读所需的片段' }}；页面始终按屏幕附近懒渲染</small>
    </div>
    <div v-else-if="error && !documentProxy" class="preview-message error-message">
      <p>{{ error }}</p>
      <a :href="source" target="_blank" rel="noopener noreferrer">直接打开 {{ name }}</a>
    </div>
    <div
      v-else
      ref="scrollStage"
      class="pdf-scroll-stage"
      @scroll.passive="handleScroll"
      @touchstart="handleTouchStart"
      @touchmove="handleTouchMove"
      @touchend="handleTouchEnd"
      @touchcancel="handleTouchCancel"
    >
      <div class="pdf-pages">
        <section
          v-for="page in pageNumbers"
          :key="page"
          :ref="(element) => setPageShell(page, element)"
          class="pdf-page-shell"
          :class="{ rendered: renderedPages.has(page), current: currentPage === page }"
          :data-page="page"
          :style="pageStyle(page)"
          :aria-label="`${name} 第 ${page} 页`"
        >
          <canvas :ref="(element) => setPageCanvas(page, element)"></canvas>
          <span v-if="!renderedPages.has(page)" class="pdf-page-loading">第 {{ page }} 页</span>
          <span class="pdf-page-number">{{ page }}</span>
        </section>
      </div>
    </div>
  </div>
</template>
