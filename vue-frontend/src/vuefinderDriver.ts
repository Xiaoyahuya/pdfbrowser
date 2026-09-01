import XHRUpload from '@uppy/xhr-upload'
import type {
  ArchiveParams,
  DeleteParams,
  DeleteResult,
  DirEntry,
  Driver,
  FileOperationResult,
  FsData,
  ListParams,
  RenameParams,
  SaveParams,
  SearchParams,
  TransferParams,
  UnarchiveParams,
  UploaderContext,
} from 'vuefinder'
import {
  getCapabilities,
  listFiles,
  listNasFiles,
  listNasMounts,
  loadMarkdown,
  loadNasMarkdown,
  rawFileUrl,
  rawNasFileUrl,
  searchFiles,
  searchNasFiles,
} from './api'
import {
  BOOKS_PATH,
  curateEntries,
  LEARNING_PATH,
  LIBRARY_ROOT,
  READING_PATH,
} from './library'
import type { DirectoryListing, FileEntry, NasMount, StorageCapabilities } from './types'

export const VUEFINDER_STORAGE = '资料库'
export const VIRTUAL_ROOT = `${VUEFINDER_STORAGE}://`
export const VIRTUAL_LEARNING = `${VIRTUAL_ROOT}学习资料`
export const VIRTUAL_BOOKS = `${VIRTUAL_ROOT}书籍`
export const VIRTUAL_READING = `${VIRTUAL_BOOKS}/阅读资料`

const READ_ONLY_MESSAGE = '当前存储为只读挂载，暂时不能修改文件'
const DIRECTORY_CACHE_MILLIS = 60_000
const MAX_DIRECTORY_CACHE_ENTRIES = 96

export interface BrowserResource {
  storage: string
  path: string
  mountId?: string
}

interface CachedDirectory {
  listing: DirectoryListing
  expiresAt: number
}

function appendPath(base: string, suffix: string): string {
  if (!suffix) return base
  return `${base}/${suffix.replace(/^\/+/, '')}`
}

function extensionOf(name: string): string {
  const index = name.lastIndexOf('.')
  return index > 0 ? name.slice(index + 1).toLowerCase() : ''
}

function mimeTypeOf(entry: FileEntry): string | null {
  if (entry.mimeType) return entry.mimeType
  if (entry.type === 'PDF') return 'application/pdf'
  if (entry.type === 'MARKDOWN') return 'text/markdown'
  return null
}

export class PdfBrowserVueFinderDriver implements Driver {
  private capabilities: StorageCapabilities = { writable: false, maxUploadBytes: 100 * 1024 * 1024 }
  private capabilitiesRequest: Promise<void> | null = null
  private mountsRequest: Promise<void> | null = null
  private mountsByStorage = new Map<string, NasMount>()
  private directoryCache = new Map<string, CachedDirectory>()
  private directoryRequests = new Map<string, Promise<DirectoryListing>>()

  get writable(): boolean {
    return this.capabilities.writable
  }

  get maxUploadBytes(): number {
    return this.capabilities.maxUploadBytes
  }

  get nasMounts(): NasMount[] {
    return [...this.mountsByStorage.values()]
  }

  private async ensureCapabilities(): Promise<void> {
    if (!this.capabilitiesRequest) {
      this.capabilitiesRequest = getCapabilities()
        .then((capabilities) => { this.capabilities = capabilities })
        .catch(() => { this.capabilities = { writable: false, maxUploadBytes: 100 * 1024 * 1024 } })
    }
    await this.capabilitiesRequest
  }

  private async ensureMounts(): Promise<void> {
    if (!this.mountsRequest) {
      this.mountsRequest = listNasMounts()
        .then((mounts) => {
          this.mountsByStorage = new Map(mounts.map((mount) => [mount.storageKey, mount]))
        })
        .catch(() => {
          // Keep the last successful list so a brief API failure never hides existing storage entries.
        })
    }
    await this.mountsRequest
  }

  async refreshNasMounts(): Promise<NasMount[]> {
    this.mountsRequest = null
    this.directoryCache.clear()
    this.directoryRequests.clear()
    await this.ensureMounts()
    return this.nasMounts
  }

  private storageRoot(storage: string): string {
    return `${storage}://`
  }

  private normalizeVirtualPath(path?: string): string {
    const value = path?.trim() || VIRTUAL_ROOT
    if (value === VUEFINDER_STORAGE || value === VIRTUAL_ROOT) return VIRTUAL_ROOT
    const delimiter = value.indexOf('://')
    if (delimiter < 0) {
      if (this.mountsByStorage.has(value)) return this.storageRoot(value)
      throw new Error('存储路径格式无效')
    }
    const storage = value.slice(0, delimiter)
    if (storage !== VUEFINDER_STORAGE && !this.mountsByStorage.has(storage)) {
      throw new Error('该远程存储不存在或已卸载')
    }
    const root = this.storageRoot(storage)
    return value === root ? root : value.replace(/\/+$/, '')
  }

  resolveVirtualPath(path?: string): BrowserResource {
    const virtualPath = this.normalizeVirtualPath(path)
    if (virtualPath.startsWith(VIRTUAL_ROOT)) {
      return { storage: VUEFINDER_STORAGE, path: this.libraryPhysicalPath(virtualPath) }
    }
    const delimiter = virtualPath.indexOf('://')
    const storage = virtualPath.slice(0, delimiter)
    const mount = this.mountsByStorage.get(storage)
    if (!mount) throw new Error('该远程存储不存在或已卸载')
    const relative = virtualPath.slice(delimiter + 3).replace(/^\/+/, '')
    return { storage, path: relative, mountId: mount.id }
  }

  private libraryPhysicalPath(virtualPath: string): string {
    if (virtualPath === VIRTUAL_ROOT) return LIBRARY_ROOT
    if (virtualPath === VIRTUAL_LEARNING || virtualPath.startsWith(`${VIRTUAL_LEARNING}/`)) {
      return appendPath(LEARNING_PATH, virtualPath.slice(VIRTUAL_LEARNING.length))
    }
    if (virtualPath === VIRTUAL_READING || virtualPath.startsWith(`${VIRTUAL_READING}/`)) {
      return appendPath(READING_PATH, virtualPath.slice(VIRTUAL_READING.length))
    }
    if (virtualPath === VIRTUAL_BOOKS || virtualPath.startsWith(`${VIRTUAL_BOOKS}/`)) {
      return appendPath(BOOKS_PATH, virtualPath.slice(VIRTUAL_BOOKS.length))
    }
    throw new Error('路径不属于学习资料或书籍')
  }

  toPhysicalPath(path?: string): string {
    const resource = this.resolveVirtualPath(path)
    if (resource.mountId) throw new Error('远程挂载路径不属于固定资料库')
    return resource.path
  }

  toVirtualPath(physicalPath: string): string {
    if (physicalPath === LIBRARY_ROOT) return VIRTUAL_ROOT
    if (physicalPath === READING_PATH || physicalPath.startsWith(`${READING_PATH}/`)) {
      return appendPath(VIRTUAL_READING, physicalPath.slice(READING_PATH.length))
    }
    if (physicalPath === LEARNING_PATH || physicalPath.startsWith(`${LEARNING_PATH}/`)) {
      return appendPath(VIRTUAL_LEARNING, physicalPath.slice(LEARNING_PATH.length))
    }
    if (physicalPath === BOOKS_PATH || physicalPath.startsWith(`${BOOKS_PATH}/`)) {
      return appendPath(VIRTUAL_BOOKS, physicalPath.slice(BOOKS_PATH.length))
    }
    throw new Error('文件不在资料库可见范围内')
  }

  private toRemoteVirtualPath(storage: string, relativePath: string): string {
    const root = this.storageRoot(storage)
    return relativePath ? appendPath(root.replace(/\/$/, ''), relativePath) : root
  }

  toDirEntry(entry: FileEntry, directory?: string, storage = VUEFINDER_STORAGE): DirEntry {
    const remote = storage !== VUEFINDER_STORAGE
    const path = remote ? this.toRemoteVirtualPath(storage, entry.path) : this.toVirtualPath(entry.path)
    const root = this.storageRoot(storage)
    const remainder = path.slice(root.length)
    const separator = remainder.lastIndexOf('/')
    const dir = directory || (separator < 0 ? root : `${root}${remainder.slice(0, separator)}`)
    return {
      dir,
      basename: entry.name,
      extension: entry.type === 'DIRECTORY' ? '' : extensionOf(entry.name),
      path,
      storage,
      type: entry.type === 'DIRECTORY' ? 'dir' : 'file',
      file_size: entry.size,
      last_modified: entry.lastModified > 0 ? Math.floor(entry.lastModified / 1000) : null,
      mime_type: mimeTypeOf(entry),
      read_only: remote || !this.capabilities.writable,
      visibility: 'public',
    }
  }

  private storages(): string[] {
    return [VUEFINDER_STORAGE, ...this.nasMounts.map((mount) => mount.storageKey)]
  }

  private async cachedDirectory(
    cacheKey: string,
    loader: () => Promise<DirectoryListing>,
  ): Promise<DirectoryListing> {
    const now = Date.now()
    const cached = this.directoryCache.get(cacheKey)
    if (cached && cached.expiresAt > now) return cached.listing
    if (cached) this.directoryCache.delete(cacheKey)
    const pending = this.directoryRequests.get(cacheKey)
    if (pending) return pending
    const request = loader().then((listing) => {
      this.directoryCache.delete(cacheKey)
      this.directoryCache.set(cacheKey, {
        listing,
        expiresAt: Date.now() + DIRECTORY_CACHE_MILLIS,
      })
      while (this.directoryCache.size > MAX_DIRECTORY_CACHE_ENTRIES) {
        const oldest = this.directoryCache.keys().next().value
        if (!oldest) break
        this.directoryCache.delete(oldest)
      }
      return listing
    }).finally(() => this.directoryRequests.delete(cacheKey))
    this.directoryRequests.set(cacheKey, request)
    return request
  }

  async list(params?: ListParams): Promise<FsData> {
    await Promise.all([this.ensureCapabilities(), this.ensureMounts()])
    const virtualPath = this.normalizeVirtualPath(params?.path)
    const resource = this.resolveVirtualPath(virtualPath)
    if (resource.mountId) {
      const listing = await this.cachedDirectory(
        `${resource.mountId}:${resource.path}`,
        () => listNasFiles(resource.mountId!, resource.path, params?.signal),
      )
      return {
        storages: this.storages(),
        dirname: virtualPath,
        files: listing.entries.map((entry) => this.toDirEntry(entry, virtualPath, resource.storage)),
        read_only: true,
      }
    }

    const listing = await this.cachedDirectory(
      `library:${resource.path}`,
      () => listFiles(resource.path, params?.signal),
    )
    const entries = curateEntries(resource.path, listing.entries)
    return {
      storages: this.storages(),
      dirname: virtualPath,
      files: entries.map((entry) => this.toDirEntry(entry, virtualPath)),
      read_only: !this.capabilities.writable,
    }
  }

  private searchRoots(virtualPath: string): string[] {
    if (virtualPath === VIRTUAL_ROOT) return [LEARNING_PATH, BOOKS_PATH, READING_PATH]
    if (virtualPath === VIRTUAL_BOOKS) return [BOOKS_PATH, READING_PATH]
    return [this.toPhysicalPath(virtualPath)]
  }

  private visibleFrom(virtualPath: string, entry: FileEntry): boolean {
    if (virtualPath === VIRTUAL_LEARNING) {
      return entry.path !== READING_PATH && !entry.path.startsWith(`${READING_PATH}/`)
    }
    return true
  }

  async search(params: SearchParams): Promise<DirEntry[]> {
    await Promise.all([this.ensureCapabilities(), this.ensureMounts()])
    const virtualPath = this.normalizeVirtualPath(params.path)
    const resource = this.resolveVirtualPath(virtualPath)
    if (resource.mountId) {
      const response = await searchNasFiles(resource.mountId, resource.path, params.filter, params.signal)
      return this.filterSize(
        response.entries.map((entry) => this.toDirEntry(entry, undefined, resource.storage)),
        params.size,
      )
    }

    const responses = await Promise.all(
      this.searchRoots(virtualPath).map((path) => searchFiles(path, params.filter, params.signal)),
    )
    const unique = new Map<string, DirEntry>()
    for (const response of responses) {
      for (const entry of response.entries) {
        if (!this.visibleFrom(virtualPath, entry)) continue
        try {
          const converted = this.toDirEntry(entry)
          unique.set(converted.path, converted)
        } catch {
          // Ignore API results outside the curated library sections.
        }
      }
    }
    return this.filterSize([...unique.values()], params.size)
  }

  private filterSize(entries: DirEntry[], sizeFilter?: string): DirEntry[] {
    return entries.filter((entry) => {
      if (!sizeFilter || sizeFilter === 'all' || entry.type === 'dir') return true
      const size = entry.file_size || 0
      if (sizeFilter === 'small') return size < 1024 * 1024
      if (sizeFilter === 'medium') return size >= 1024 * 1024 && size < 10 * 1024 * 1024
      return size >= 10 * 1024 * 1024
    })
  }

  async getContent(params: { path: string; signal?: AbortSignal }): Promise<{ content: string; mimeType?: string }> {
    await this.ensureMounts()
    const resource = this.resolveVirtualPath(params.path)
    if (/\.(md|markdown)$/i.test(resource.path)) {
      const content = resource.mountId
        ? await loadNasMarkdown(resource.mountId, resource.path, params.signal)
        : await loadMarkdown(resource.path, params.signal)
      return { content, mimeType: 'text/markdown' }
    }
    const response = await fetch(this.resourceUrl(resource), { signal: params.signal })
    if (!response.ok) throw new Error(`文件读取失败（${response.status}）`)
    return {
      content: await response.text(),
      mimeType: response.headers.get('content-type') || undefined,
    }
  }

  getPreviewUrl(params: { path: string }): string {
    return this.resourceUrl(this.resolveVirtualPath(params.path))
  }

  getDownloadUrl(params: { path: string }): string {
    return this.resourceUrl(this.resolveVirtualPath(params.path), true)
  }

  private resourceUrl(resource: BrowserResource, download = false): string {
    return resource.mountId
      ? rawNasFileUrl(resource.mountId, resource.path, download)
      : rawFileUrl(resource.path, download)
  }

  configureUploader(uppy: any, context: UploaderContext): void {
    uppy.use(XHRUpload, {
      endpoint: '/api/files/upload',
      fieldName: 'file',
      bundle: false,
      formData: true,
    })
    uppy.on('upload', () => {
      const resource = this.resolveVirtualPath(context.getTargetPath())
      if (resource.mountId) throw new Error(READ_ONLY_MESSAGE)
      uppy.getFiles().forEach((file: { id: string }) => {
        uppy.setFileMeta(file.id, { path: resource.path })
      })
    })
  }

  private rejectWrite<T>(): Promise<T> {
    return Promise.reject(new Error(READ_ONLY_MESSAGE))
  }

  delete(_params: DeleteParams): Promise<DeleteResult> { return this.rejectWrite() }
  rename(_params: RenameParams): Promise<FileOperationResult> { return this.rejectWrite() }
  copy(_params: TransferParams): Promise<FileOperationResult> { return this.rejectWrite() }
  move(_params: TransferParams): Promise<FileOperationResult> { return this.rejectWrite() }
  archive(_params: ArchiveParams): Promise<FileOperationResult> { return this.rejectWrite() }
  unarchive(_params: UnarchiveParams): Promise<FileOperationResult> { return this.rejectWrite() }
  createFile(_params: { path: string; name: string }): Promise<FileOperationResult> { return this.rejectWrite() }
  createFolder(_params: { path: string; name: string }): Promise<FileOperationResult> { return this.rejectWrite() }
  save(_params: SaveParams): Promise<string> { return this.rejectWrite() }
}
