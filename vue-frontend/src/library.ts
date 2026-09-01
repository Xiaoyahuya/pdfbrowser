import type { FileEntry } from './types'

export const LIBRARY_ROOT = 'PDFBrowser资料库'
export const LEARNING_PATH = `${LIBRARY_ROOT}/教程文档`
export const BOOKS_PATH = `${LIBRARY_ROOT}/思考快与慢`
export const READING_PATH = `${LEARNING_PATH}/04_阅读资料`

const sectionNames = new Map<string, string>([
  [LEARNING_PATH, '学习资料'],
  [BOOKS_PATH, '书籍'],
  [READING_PATH, '阅读资料'],
])

export function libraryLabel(path: string, fallback: string): string {
  if (path === LIBRARY_ROOT) return '资料库'
  return sectionNames.get(path) || fallback
}

export function curateEntries(directory: string, entries: FileEntry[]): FileEntry[] {
  if (directory === LIBRARY_ROOT) {
    return entries
      .filter((entry) => entry.path === LEARNING_PATH || entry.path === BOOKS_PATH)
      .map((entry) => ({ ...entry, name: libraryLabel(entry.path, entry.name) }))
  }
  if (directory === LEARNING_PATH) return entries.filter((entry) => entry.path !== READING_PATH)
  if (directory === BOOKS_PATH && !entries.some((entry) => entry.path === READING_PATH)) {
    const reading: FileEntry = {
      path: READING_PATH,
      name: '阅读资料',
      type: 'DIRECTORY',
      size: null,
      lastModified: 0,
      mimeType: null,
      previewable: false,
    }
    return [reading, ...entries]
  }
  return entries
}

export function libraryTrail(path: string): string[] {
  if (path === LIBRARY_ROOT) return []
  if (path === READING_PATH || path.startsWith(`${READING_PATH}/`)) {
    const trail = [BOOKS_PATH, READING_PATH]
    const remainder = path.slice(READING_PATH.length).split('/').filter(Boolean)
    remainder.forEach((_, index) => trail.push(`${READING_PATH}/${remainder.slice(0, index + 1).join('/')}`))
    return trail
  }
  const rootParts = LIBRARY_ROOT.split('/').filter(Boolean)
  const parts = path.split('/').filter(Boolean).slice(rootParts.length)
  return parts.map((_, index) => [...rootParts, ...parts.slice(0, index + 1)].join('/'))
}

export function libraryParent(path: string, physicalParent: string | null): string | null {
  if (path === LIBRARY_ROOT) return null
  if (path === READING_PATH) return BOOKS_PATH
  return physicalParent
}
