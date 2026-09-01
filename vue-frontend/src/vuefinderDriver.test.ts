import { beforeEach, describe, expect, it, vi } from 'vitest'
import { BOOKS_PATH, LEARNING_PATH, LIBRARY_ROOT, READING_PATH } from './library'
import type { DirectoryListing, FileEntry } from './types'
import {
  PdfBrowserVueFinderDriver,
  VIRTUAL_BOOKS,
  VIRTUAL_LEARNING,
  VIRTUAL_READING,
  VIRTUAL_ROOT,
} from './vuefinderDriver'

const api = vi.hoisted(() => ({
  getCapabilities: vi.fn(),
  listFiles: vi.fn(),
  listNasFiles: vi.fn(),
  listNasMounts: vi.fn(),
  loadMarkdown: vi.fn(),
  loadNasMarkdown: vi.fn(),
  rawFileUrl: vi.fn((path: string, download = false) => `/raw?path=${encodeURIComponent(path)}&download=${download}`),
  rawNasFileUrl: vi.fn((mountId: string, path: string, download = false) =>
    `/nas/${mountId}/raw?path=${encodeURIComponent(path)}&download=${download}`),
  searchFiles: vi.fn(),
  searchNasFiles: vi.fn(),
}))

vi.mock('./api', () => api)

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

function file(path: string, type: FileEntry['type'] = 'MARKDOWN'): FileEntry {
  return {
    path,
    name: path.split('/').at(-1) || path,
    type,
    size: 1024,
    lastModified: 1_700_000_000_000,
    mimeType: type === 'PDF' ? 'application/pdf' : 'text/markdown',
    previewable: true,
  }
}

beforeEach(() => {
  api.getCapabilities.mockReset()
  api.getCapabilities.mockResolvedValue({ writable: false, maxUploadBytes: 100 * 1024 * 1024 })
  api.listFiles.mockReset()
  api.listNasFiles.mockReset()
  api.listNasMounts.mockReset()
  api.listNasMounts.mockResolvedValue([])
  api.searchFiles.mockReset()
  api.searchNasFiles.mockReset()
  api.loadMarkdown.mockReset()
  api.loadNasMarkdown.mockReset()
})

describe('VueFinder Spring driver', () => {
  it('round-trips the virtual learning, books and reading paths', () => {
    const driver = new PdfBrowserVueFinderDriver()

    expect(driver.toPhysicalPath(`${VIRTUAL_LEARNING}/Vue/入门.md`)).toBe(`${LEARNING_PATH}/Vue/入门.md`)
    expect(driver.toPhysicalPath(`${VIRTUAL_READING}/论文.pdf`)).toBe(`${READING_PATH}/论文.pdf`)
    expect(driver.toVirtualPath(`${BOOKS_PATH}/思考快与慢.pdf`)).toBe(`${VIRTUAL_BOOKS}/思考快与慢.pdf`)
    expect(driver.toVirtualPath(`${READING_PATH}/论文.pdf`)).toBe(`${VIRTUAL_READING}/论文.pdf`)
  })

  it('shows only learning materials and books at the VueFinder root', async () => {
    const listing: DirectoryListing = {
      path: LIBRARY_ROOT,
      name: LIBRARY_ROOT,
      parent: '',
      entries: [directory(`${LIBRARY_ROOT}/其它`), directory(LEARNING_PATH), directory(BOOKS_PATH)],
    }
    api.listFiles.mockResolvedValue(listing)

    const result = await new PdfBrowserVueFinderDriver().list({ path: VIRTUAL_ROOT })

    expect(result.read_only).toBe(true)
    expect(result.files.map(({ basename, path }) => ({ basename, path }))).toEqual([
      { basename: '学习资料', path: VIRTUAL_LEARNING },
      { basename: '书籍', path: VIRTUAL_BOOKS },
    ])
  })

  it('injects reading materials into the books directory', async () => {
    api.listFiles.mockResolvedValue({
      path: BOOKS_PATH,
      name: '思考快与慢',
      parent: LIBRARY_ROOT,
      entries: [file(`${BOOKS_PATH}/思考快与慢.pdf`, 'PDF')],
    })

    const result = await new PdfBrowserVueFinderDriver().list({ path: VIRTUAL_BOOKS })

    expect(result.files.map(({ basename, path }) => ({ basename, path }))).toEqual([
      { basename: '阅读资料', path: VIRTUAL_READING },
      { basename: '思考快与慢.pdf', path: `${VIRTUAL_BOOKS}/思考快与慢.pdf` },
    ])
  })

  it('coalesces simultaneous tree and file-list requests for the same directory', async () => {
    let finishListing: ((listing: DirectoryListing) => void) | undefined
    api.listFiles.mockReturnValue(new Promise<DirectoryListing>((resolve) => {
      finishListing = resolve
    }))
    const driver = new PdfBrowserVueFinderDriver()

    const treeRequest = driver.list({ path: VIRTUAL_ROOT })
    const fileListRequest = driver.list({ path: VIRTUAL_ROOT })
    await vi.waitFor(() => expect(api.listFiles).toHaveBeenCalledTimes(1))
    finishListing?.({
      path: LIBRARY_ROOT,
      name: LIBRARY_ROOT,
      parent: '',
      entries: [directory(LEARNING_PATH), directory(BOOKS_PATH)],
    })

    const [tree, fileList] = await Promise.all([treeRequest, fileListRequest])
    expect(tree.files).toEqual(fileList.files)
    expect(api.listFiles).toHaveBeenCalledTimes(1)
  })

  it('searches both the physical books and reading-material roots', async () => {
    api.searchFiles
      .mockResolvedValueOnce({ query: '并发', path: BOOKS_PATH, entries: [], truncated: false })
      .mockResolvedValueOnce({
        query: '并发',
        path: READING_PATH,
        entries: [file(`${READING_PATH}/并发论文.md`)],
        truncated: false,
      })

    const result = await new PdfBrowserVueFinderDriver().search({ path: VIRTUAL_BOOKS, filter: '并发' })

    expect(api.searchFiles).toHaveBeenCalledTimes(2)
    expect(result[0]?.path).toBe(`${VIRTUAL_READING}/并发论文.md`)
  })

  it('translates preview and download URLs back to physical paths', () => {
    const driver = new PdfBrowserVueFinderDriver()
    const virtualPath = `${VIRTUAL_BOOKS}/思考快与慢.pdf`

    expect(driver.getPreviewUrl({ path: virtualPath })).toContain(encodeURIComponent(`${BOOKS_PATH}/思考快与慢.pdf`))
    expect(driver.getDownloadUrl({ path: virtualPath })).toContain('download=true')
  })

  it('adds a connected NAS as a separate VueFinder storage', async () => {
    api.listNasMounts.mockResolvedValue([{
      id: '12345678-1234-1234-1234-123456789abc',
      name: '家庭资料',
      storageKey: 'NAS-家庭资料-12345678',
      host: '192.168.1.20',
      share: 'docs',
      username: 'reader',
      domain: '',
      status: 'CONNECTED',
      createdAt: '2026-08-09T00:00:00Z',
      lastError: null,
      readOnly: true,
      useProxy: true,
    }])
    api.listFiles.mockResolvedValue({
      path: LIBRARY_ROOT,
      name: LIBRARY_ROOT,
      parent: '',
      entries: [],
    })
    const driver = new PdfBrowserVueFinderDriver()

    const root = await driver.list({ path: VIRTUAL_ROOT })
    expect(root.storages).toContain('NAS-家庭资料-12345678')

    api.listNasFiles.mockResolvedValue({
      path: '',
      name: '',
      parent: null,
      entries: [file('说明.md')],
    })
    const nas = await driver.list({ path: 'NAS-家庭资料-12345678://' })
    expect(nas.read_only).toBe(true)
    expect(nas.files[0]?.path).toBe('NAS-家庭资料-12345678://说明.md')
  })

  it('keeps disconnected and last-known mounts visible instead of hiding other storage entries', async () => {
    const storageKey = 'WebDAV-研究网盘-87654321'
    api.listNasMounts.mockResolvedValueOnce([{
      id: '87654321-1234-1234-1234-123456789abc',
      name: '研究网盘',
      storageKey,
      provider: 'WEBDAV',
      host: '',
      share: '',
      username: 'reader',
      domain: '',
      url: 'https://dav.example.com/files',
      vendor: 'other',
      rootFolderId: '',
      status: 'ERROR',
      createdAt: '2026-08-09T00:00:00Z',
      lastError: 'temporary network error',
      readOnly: true,
      useProxy: false,
    }])
    api.listFiles.mockResolvedValue({
      path: LIBRARY_ROOT,
      name: LIBRARY_ROOT,
      parent: '',
      entries: [],
    })
    const driver = new PdfBrowserVueFinderDriver()

    const first = await driver.list({ path: VIRTUAL_ROOT })
    expect(first.storages).toContain(storageKey)

    api.listNasMounts.mockRejectedValueOnce(new Error('temporary API failure'))
    await driver.refreshNasMounts()
    const afterFailure = await driver.list({ path: VIRTUAL_ROOT })
    expect(afterFailure.storages).toContain(storageKey)
  })
})
