import { describe, expect, it, vi } from 'vitest'
import {
  loadPdfBookmarks,
  loadPdfReadingState,
  pdfDocumentId,
  savePdfBookmarks,
  savePdfReadingState,
} from './pdfReadingState'

class MemoryStorage implements Storage {
  private readonly values = new Map<string, string>()

  get length() { return this.values.size }
  clear() { this.values.clear() }
  getItem(key: string) { return this.values.get(key) ?? null }
  key(index: number) { return [...this.values.keys()][index] ?? null }
  removeItem(key: string) { this.values.delete(key) }
  setItem(key: string, value: string) { this.values.set(key, value) }
}

describe('PDF reading state', () => {
  it('keeps progress isolated by storage and path', () => {
    const storage = new MemoryStorage()
    const left = pdfDocumentId('books/linux.pdf', 'nas-a')
    const right = pdfDocumentId('books/linux.pdf', 'nas-b')

    vi.spyOn(Date, 'now').mockReturnValue(123456)
    savePdfReadingState(left, { page: 47, zoom: 1.4, offset: 0.35 }, storage)

    expect(loadPdfReadingState(left, 100, storage)).toEqual({
      page: 47,
      zoom: 1.4,
      offset: 0.35,
      updatedAt: 123456,
    })
    expect(loadPdfReadingState(right, 100, storage)).toBeNull()
    vi.restoreAllMocks()
  })

  it('clamps stale progress when a PDF changes', () => {
    const storage = new MemoryStorage()
    const id = pdfDocumentId('changing.pdf')
    savePdfReadingState(id, { page: 500, zoom: 9, offset: -2 }, storage)

    expect(loadPdfReadingState(id, 20, storage)).toMatchObject({
      page: 20,
      zoom: 3,
      offset: 0,
    })
  })

  it('deduplicates, sorts and validates page bookmarks', () => {
    const storage = new MemoryStorage()
    const id = pdfDocumentId('book.pdf')
    savePdfBookmarks(id, [8, 2, 8, -1, 4], storage)

    expect(loadPdfBookmarks(id, 6, storage)).toEqual([2, 4])
  })

  it('ignores malformed stored values', () => {
    const storage = new MemoryStorage()
    const id = pdfDocumentId('broken.pdf')
    storage.setItem(`pdfbrowser:pdf-state:v1:${id}`, '{broken')
    storage.setItem(`pdfbrowser:pdf-bookmarks:v1:${id}`, 'null')

    expect(loadPdfReadingState(id, 10, storage)).toBeNull()
    expect(loadPdfBookmarks(id, 10, storage)).toEqual([])
  })
})
