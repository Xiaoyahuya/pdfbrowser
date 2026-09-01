export interface PdfReadingState {
  page: number
  zoom: number
  offset: number
  updatedAt: number
}

const STATE_PREFIX = 'pdfbrowser:pdf-state:v1:'
const BOOKMARK_PREFIX = 'pdfbrowser:pdf-bookmarks:v1:'

function browserStorage(): Storage | null {
  try {
    return typeof window === 'undefined' ? null : window.localStorage
  } catch {
    return null
  }
}

function clamp(value: number, minimum: number, maximum: number) {
  return Math.min(maximum, Math.max(minimum, value))
}

export function pdfDocumentId(path: string, mountId?: string) {
  return `${mountId || 'library'}:${path}`
}

export function loadPdfReadingState(
  documentId: string,
  pageCount: number,
  storage: Storage | null = browserStorage(),
): PdfReadingState | null {
  if (!storage || pageCount < 1) return null
  try {
    const parsed = JSON.parse(storage.getItem(`${STATE_PREFIX}${documentId}`) || 'null') as Partial<PdfReadingState> | null
    if (!parsed || !Number.isFinite(parsed.page)) return null
    return {
      page: clamp(Math.trunc(parsed.page || 1), 1, pageCount),
      zoom: clamp(Number(parsed.zoom) || 1, 0.6, 3),
      offset: clamp(Number(parsed.offset) || 0, 0, 1),
      updatedAt: Number(parsed.updatedAt) || 0,
    }
  } catch {
    return null
  }
}

export function savePdfReadingState(
  documentId: string,
  state: Omit<PdfReadingState, 'updatedAt'>,
  storage: Storage | null = browserStorage(),
) {
  if (!storage) return
  try {
    storage.setItem(`${STATE_PREFIX}${documentId}`, JSON.stringify({
      page: Math.max(1, Math.trunc(state.page) || 1),
      zoom: clamp(Number(state.zoom) || 1, 0.6, 3),
      offset: clamp(Number(state.offset) || 0, 0, 1),
      updatedAt: Date.now(),
    } satisfies PdfReadingState))
  } catch {
    // Reading progress is a convenience; storage quotas must never block reading.
  }
}

export function loadPdfBookmarks(
  documentId: string,
  pageCount: number,
  storage: Storage | null = browserStorage(),
) {
  if (!storage || pageCount < 1) return [] as number[]
  try {
    const parsed = JSON.parse(storage.getItem(`${BOOKMARK_PREFIX}${documentId}`) || '[]')
    if (!Array.isArray(parsed)) return [] as number[]
    return [...new Set(parsed
      .map(Number)
      .filter((page) => Number.isInteger(page) && page >= 1 && page <= pageCount))]
      .sort((left, right) => left - right)
  } catch {
    return [] as number[]
  }
}

export function savePdfBookmarks(
  documentId: string,
  bookmarks: number[],
  storage: Storage | null = browserStorage(),
) {
  if (!storage) return
  try {
    const normalized = [...new Set(bookmarks
      .map(Number)
      .filter((page) => Number.isInteger(page) && page >= 1))]
      .sort((left, right) => left - right)
    storage.setItem(`${BOOKMARK_PREFIX}${documentId}`, JSON.stringify(normalized))
  } catch {
    // Ignore unavailable or full storage.
  }
}
