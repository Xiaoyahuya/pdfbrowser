import { ApiRequestError } from './types'
import type {
  ApiErrorBody,
  AuthUserResponse,
  DirectoryListing,
  FileEntry,
  LoginRequest,
  NasMount,
  NasMountInput,
  PasswordChangeResponse,
  RegisterRequest,
  SearchResponse,
  StorageCapabilities,
  VerificationCodeRequest,
} from './types'

const baseUrl = (import.meta.env.VITE_API_BASE || '/api/files').replace(/\/$/, '')
const nasBaseUrl = (import.meta.env.VITE_NAS_API_BASE || '/api/nas').replace(/\/$/, '')
const accountBaseUrl = (import.meta.env.VITE_ACCOUNT_API_BASE || '/api/account').replace(/\/$/, '')
const authBaseUrl = (import.meta.env.VITE_AUTH_API_BASE || '/api/auth').replace(/\/$/, '')

const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS'])

let csrfToken: string | null = null
let csrfRequest: Promise<void> | null = null

function readCookie(name: string): string | null {
  if(typeof document === 'undefined') return null

  const prefix = name + '='
  const item = document.cookie
    .split('; ')
    .find((value) => value.startsWith(prefix))

  if(!item) return null;

  try {
    return decodeURIComponent(item.slice(prefix.length))
  } catch {
    return item.slice(prefix.length)
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

async function readResponseBody(response: Response): Promise<unknown> {
  const text = await response.text()
  if(!text) return undefined

  try {
    return JSON.parse(text)
  } catch {
    return text
  }
}

function createApiError(
  response: Response,
  fallback: string,
  body: unknown,
): ApiRequestError {
  const apiBody = isRecord(body) ? body as ApiErrorBody : undefined
  return new ApiRequestError(
    apiBody?.message || fallback + ' (' + response.status + ') ',
    response.status,
    apiBody?.code,
  )
}

async function ensureCsrf(signal?: AbortSignal): Promise<void> {
  csrfToken = csrfToken || readCookie('XSRF-TOKEN')
  if (csrfToken) return

  if (!csrfRequest) {
    csrfRequest = (async () => {
      const response = await fetch(authBaseUrl + '/csrf', {
        method: 'GET',
        credentials: 'include',
        signal,
      })
      const body = await readResponseBody(response)

      if (!response.ok) {
        throw createApiError(response, 'CSRF 初始化失败', body)
      }

      if (isRecord(body) && typeof body.token === 'string') {
        csrfToken = body.token
      }

      csrfToken = csrfToken || readCookie('XSRF-TOKEN')
      if (!csrfToken) {
        throw new ApiRequestError(
          'CSRF 初始化失败',
          response.status,
          'CSRF_TOKEN_MISSING',
        )
      }
    })().finally(() => {
      csrfRequest = null
    })
  }

  await csrfRequest
}

async function sessionFetch(
  url: string,
  init: RequestInit = {},
  signal?: AbortSignal,
): Promise<Response> {
  const method = (init.method || 'GET').toUpperCase()
  const headers = new Headers(init.headers)

  if (!SAFE_METHODS.has(method)) {
    await ensureCsrf(signal || init.signal || undefined)
    const token = csrfToken || readCookie('XSRF-TOKEN')
    if (token) headers.set('X-XSRF-TOKEN', token)
  }

  if (!headers.has('Accept')) {
    headers.set('Accept', 'application/json')
  }

  return fetch(url, {
    ...init,
    headers,
    credentials: 'include',
    signal: init.signal || signal,
  })
}

async function request<T>(
  url: string,
  signal?: AbortSignal,
  init: RequestInit = {},
): Promise<T> {
  const response = await sessionFetch(url, init, signal)
  const body = await readResponseBody(response)

  if (!response.ok) {
    throw createApiError(response, '请求失败', body)
  }

  return body as T
}

async function requestText(
  url: string,
  signal?: AbortSignal,
  init: RequestInit = {},
): Promise<string> {
  const response = await sessionFetch(url, init, signal)
  const text = await response.text()

  if (!response.ok) {
    let body: unknown = text
    try {
      body = text ? JSON.parse(text) : undefined
    } catch {
      // Keep plain text as the error body.
    }
    throw createApiError(response, '请求失败', body)
  }

  return text
}

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

export function listFiles(path: string, signal?: AbortSignal): Promise<DirectoryListing> {
  const query = new URLSearchParams({ path })
  return request<DirectoryListing>(baseUrl + '?' + query, signal)
}

export function searchFiles(path: string, queryText: string, signal?: AbortSignal): Promise<SearchResponse> {
  const query = new URLSearchParams({ path, q: queryText })
  return request<SearchResponse>(baseUrl + '/search?' + query, signal)
}

export function getCapabilities(signal?: AbortSignal): Promise<StorageCapabilities> {
  return request<StorageCapabilities>(baseUrl + '/capabilities', signal)
}

export function uploadFile(path: string, file: File, signal?: AbortSignal): Promise<FileEntry> {
  const query = new URLSearchParams({ path })
  const body = new FormData()
  body.append('file', file)
  return request<FileEntry>(baseUrl + '/upload?' + query, signal, {
    method: 'POST',
    body,
  })
}

export function loadMarkdown(path: string, signal?: AbortSignal): Promise<string> {
  const query = new URLSearchParams({ path })
  return requestText(baseUrl + '/markdown?' + query, signal, {
    cache: 'force-cache',
  })
}

export function rawFileUrl(path: string, download = false): string {
  const query = new URLSearchParams({ path, download: String(download) })
  return baseUrl + '/raw?' + query
}

export function listNasMounts(signal?: AbortSignal): Promise<NasMount[]> {
  return request<NasMount[]>(nasBaseUrl + '/mounts', signal)
}

export function createNasMount(input: NasMountInput, signal?: AbortSignal): Promise<NasMount> {
  return request<NasMount>(nasBaseUrl + '/mounts', signal, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-PDFBrowser-Action': 'nas-mount',
    },
    body: JSON.stringify(input),
  })
}

export function connectNasMount(id: string, signal?: AbortSignal): Promise<NasMount> {
  return request<NasMount>(nasBaseUrl + '/mounts/' + encodeURIComponent(id) + '/connect', signal, {
    method: 'POST',
    headers: { 'X-PDFBrowser-Action': 'nas-mount' },
  })
}

export function removeNasMount(id: string, signal?: AbortSignal): Promise<void> {
  return request<void>(nasBaseUrl + '/mounts/' + encodeURIComponent(id), signal, {
    method: 'DELETE',
    headers: { 'X-PDFBrowser-Action': 'nas-mount' },
  })
}

function nasFilesBase(mountId: string): string {
  return nasBaseUrl + '/mounts/' + encodeURIComponent(mountId) + '/files'
}

export function listNasFiles(mountId: string, path: string, signal?: AbortSignal): Promise<DirectoryListing> {
  const query = new URLSearchParams({ path })
  return request<DirectoryListing>(nasFilesBase(mountId) + '?' + query, signal)
}

export function searchNasFiles(
  mountId: string,
  path: string,
  queryText: string,
  signal?: AbortSignal,
): Promise<SearchResponse> {
  const query = new URLSearchParams({ path, q: queryText })
  return request<SearchResponse>(nasFilesBase(mountId) + '/search?' + query, signal)
}

export function loadNasMarkdown(mountId: string, path: string, signal?: AbortSignal): Promise<string> {
  const query = new URLSearchParams({ path })
  return requestText(nasFilesBase(mountId) + '/markdown?' + query, signal, {
    cache: 'force-cache',
  })
}

export function rawNasFileUrl(mountId: string, path: string, download = false): string {
  const query = new URLSearchParams({ path, download: String(download) })
  return nasFilesBase(mountId) + '/raw?' + query
}

export function changeAccountPassword(
  currentPassword: string,
  newPassword: string,
  signal?: AbortSignal,
): Promise<PasswordChangeResponse> {
  return request<PasswordChangeResponse>(accountBaseUrl + '/password', signal, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-PDFBrowser-Action': 'password-change',
    },
    body: JSON.stringify({ currentPassword, newPassword }),
  })
}

export function sendVerificationCode(
  input: VerificationCodeRequest,
  signal?: AbortSignal,
): Promise<{ message: string }> {
  return request<{ message: string }>(authBaseUrl + '/verification-codes', signal, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  })
}

export function register(
  input: RegisterRequest,
  signal?: AbortSignal,
): Promise<void> {
  return request<void>(authBaseUrl + '/register', signal, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  })
}

export function login(
  input: LoginRequest,
  signal?: AbortSignal,
): Promise<AuthUserResponse> {
  return request<AuthUserResponse>(authBaseUrl + '/login', signal, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  })
}

export function logout(signal?: AbortSignal): Promise<void> {
  return request<void>(authBaseUrl + '/logout', signal, {
    method: 'POST',
  })
}

export function getCurrentUser(signal?: AbortSignal): Promise<AuthUserResponse> {
  return request<AuthUserResponse>(authBaseUrl + '/me', signal)
}