import { describe, expect, it } from 'vitest'
import type { FileEntry } from './types'
import {
  BOOKS_PATH,
  curateEntries,
  LEARNING_PATH,
  LIBRARY_ROOT,
  libraryParent,
  libraryTrail,
  READING_PATH,
} from './library'

function directory(path: string, name = path.split('/').at(-1) || path): FileEntry {
  return {
    path,
    name,
    type: 'DIRECTORY',
    size: null,
    lastModified: 0,
    mimeType: null,
    previewable: false,
  }
}

describe('curated library structure', () => {
  it('shows only learning materials and books at the library root', () => {
    const entries = curateEntries(LIBRARY_ROOT, [
      directory('PDFBrowser资料库/其它'),
      directory(LEARNING_PATH, '教程文档'),
      directory(BOOKS_PATH, '思考快与慢'),
    ])

    expect(entries.map(({ name, path }) => ({ name, path }))).toEqual([
      { name: '学习资料', path: LEARNING_PATH },
      { name: '书籍', path: BOOKS_PATH },
    ])
  })

  it('moves reading materials into the books view without changing their physical path', () => {
    expect(curateEntries(LEARNING_PATH, [directory(READING_PATH)])).toEqual([])

    const books = curateEntries(BOOKS_PATH, [])
    expect(books).toHaveLength(1)
    expect(books[0]).toMatchObject({ path: READING_PATH, name: '阅读资料', type: 'DIRECTORY' })
  })

  it('uses the virtual books hierarchy for breadcrumb and parent navigation', () => {
    expect(libraryTrail(`${READING_PATH}/论文`)).toEqual([
      BOOKS_PATH,
      READING_PATH,
      `${READING_PATH}/论文`,
    ])
    expect(libraryParent(READING_PATH, LEARNING_PATH)).toBe(BOOKS_PATH)
  })
})
