import type {
  ApiErrorBody,
  DirectoryListing,
  FileEntry,
  NasMount,
  NasMountInput,
  PasswordChangeResponse,
  SearchResponse,
  StorageCapabilities,
} from './types'

const baseUrl = (import.meta.env.VITE_API_BASE || '/api/files').replace(/\/$/, '')
const nasBaseUrl = (import.meta.env.VITE_NAS_API_BASE || '/api/nas').replace(/\/$/, '')
const accountBaseUrl = (import.meta.env.VITE_ACCOUNT_API_BASE || '/api/account').replace(/\/$/, '')

async function responseError(response: Response, fallback: string): Promise<Error> {
  let message = `${fallback}（${response.status}）`
  try {
    const error = (await response.json()) as ApiErrorBody
    if (error.message) message = error.message
  } catch {
    // Keep the status-based fallback for non-JSON errors.
  }
  return new Error(message)
}

async function request<T>(url: string, signal?: AbortSignal, init?: RequestInit): Promise<T> {
  const response = await fetch(url, { ...init, signal })
  if (!response.ok) throw await responseError(response, '请求失败')
  return (await response.json()) as T
}

export function listFiles(path: string, signal?: AbortSignal): Promise<DirectoryListing> {
  const query = new URLSearchParams({ path })
  return request<DirectoryListing>(`${baseUrl}?${query}`, signal)
}

export function searchFiles(path: string, queryText: string, signal?: AbortSignal): Promise<SearchResponse> {
  const query = new URLSearchParams({ path, q: queryText })
  return request<SearchResponse>(`${baseUrl}/search?${query}`, signal)
}

export function getCapabilities(signal?: AbortSignal): Promise<StorageCapabilities> {
  return request<StorageCapabilities>(`${baseUrl}/capabilities`, signal)
}

export async function uploadFile(path: string, file: File, signal?: AbortSignal): Promise<FileEntry> {
  const query = new URLSearchParams({ path })
  const body = new FormData()
  body.append('file', file)
  const response = await fetch(`${baseUrl}/upload?${query}`, { method: 'POST', body, signal })
  if (!response.ok) throw await responseError(response, '上传失败')
  return (await response.json()) as FileEntry
}

export async function loadMarkdown(path: string, signal?: AbortSignal): Promise<string> {
  const query = new URLSearchParams({ path })
  const response = await fetch(`${baseUrl}/markdown?${query}`, { signal, cache: 'force-cache' })
  if (!response.ok) throw await responseError(response, 'Markdown 加载失败')
  return await response.text()
}

export function rawFileUrl(path: string, download = false): string {
  const query = new URLSearchParams({ path, download: String(download) })
  return `${baseUrl}/raw?${query}`
}

export function listNasMounts(signal?: AbortSignal): Promise<NasMount[]> {
  return request<NasMount[]>(`${nasBaseUrl}/mounts`, signal)
}

export function createNasMount(input: NasMountInput, signal?: AbortSignal): Promise<NasMount> {
  return request<NasMount>(`${nasBaseUrl}/mounts`, signal, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-PDFBrowser-Action': 'nas-mount',
    },
    body: JSON.stringify(input),
  })
}

export function connectNasMount(id: string, signal?: AbortSignal): Promise<NasMount> {
  return request<NasMount>(`${nasBaseUrl}/mounts/${encodeURIComponent(id)}/connect`, signal, {
    method: 'POST',
    headers: { 'X-PDFBrowser-Action': 'nas-mount' },
  })
}

export async function removeNasMount(id: string, signal?: AbortSignal): Promise<void> {
  const response = await fetch(`${nasBaseUrl}/mounts/${encodeURIComponent(id)}`, {
    method: 'DELETE',
    headers: { 'X-PDFBrowser-Action': 'nas-mount' },
    signal,
  })
  if (!response.ok) throw await responseError(response, '卸载失败')
}

function nasFilesBase(mountId: string): string {
  return `${nasBaseUrl}/mounts/${encodeURIComponent(mountId)}/files`
}

export function listNasFiles(mountId: string, path: string, signal?: AbortSignal): Promise<DirectoryListing> {
  const query = new URLSearchParams({ path })
  return request<DirectoryListing>(`${nasFilesBase(mountId)}?${query}`, signal)
}

export function searchNasFiles(
  mountId: string,
  path: string,
  queryText: string,
  signal?: AbortSignal,
): Promise<SearchResponse> {
  const query = new URLSearchParams({ path, q: queryText })
  return request<SearchResponse>(`${nasFilesBase(mountId)}/search?${query}`, signal)
}

export async function loadNasMarkdown(mountId: string, path: string, signal?: AbortSignal): Promise<string> {
  const query = new URLSearchParams({ path })
  const response = await fetch(`${nasFilesBase(mountId)}/markdown?${query}`, {
    signal,
    cache: 'force-cache',
  })
  if (!response.ok) throw await responseError(response, '远程 Markdown 加载失败')
  return await response.text()
}

export function rawNasFileUrl(mountId: string, path: string, download = false): string {
  const query = new URLSearchParams({ path, download: String(download) })
  return `${nasFilesBase(mountId)}/raw?${query}`
}

export function changeAccountPassword(
  currentPassword: string,
  newPassword: string,
  signal?: AbortSignal,
): Promise<PasswordChangeResponse> {
  return request<PasswordChangeResponse>(`${accountBaseUrl}/password`, signal, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-PDFBrowser-Action': 'password-change',
    },
    body: JSON.stringify({ currentPassword, newPassword }),
  })
}
