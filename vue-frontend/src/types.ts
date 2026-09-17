export type EntryType = 'DIRECTORY' | 'PDF' | 'MARKDOWN' | 'OTHER'

export interface FileEntry {
  path: string
  name: string
  type: EntryType
  size: number | null
  lastModified: number
  mimeType: string | null
  previewable: boolean
  mountId?: string
}

export type NasMountStatus = 'CONNECTED' | 'DISCONNECTED' | 'MOUNTING' | 'ERROR'
export type NasMountProvider = 'SMB' | 'WEBDAV' | 'GOOGLE_DRIVE'

export interface NasMount {
  id: string
  name: string
  storageKey: string
  provider: NasMountProvider
  host: string
  share: string
  username: string
  domain: string
  url: string
  vendor: string
  clientId: string
  rootFolderId: string
  status: NasMountStatus
  createdAt: string
  lastError: string | null
  readOnly: boolean
  useProxy: boolean
}

export interface NasMountInput {
  name: string
  provider: NasMountProvider
  host: string
  share: string
  username: string
  password: string
  domain: string
  useProxy: boolean
  url: string
  vendor: string
  oauthToken: string
  clientId: string
  clientSecret: string
  rootFolderId: string
}

export interface DirectoryListing {
  path: string
  name: string
  parent: string | null
  entries: FileEntry[]
}

export interface SearchResponse {
  query: string
  path: string
  entries: FileEntry[]
  truncated: boolean
}

export interface ApiErrorBody {
  status?: number
  code?: string
  message?: string
  path?: string
}

export class ApiRequestError extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly code?: string,
  ) {
    super(message)
    this.name = 'ApiRequestError'
  }
}

export interface AuthUserResponse {
  id: number
  username: string
  email: string
  status: string
}

export interface VerificationCodeRequest {
  email: string
  purpose: 'REGISTER'
}

export interface RegisterRequest {
  username: string
  email: string
  password: string
  verificationCode: string
}

export interface LoginRequest {
  login: string
  password: string
}

export interface StorageCapabilities {
  writable: boolean
  maxUploadBytes: number
}

export interface PasswordChangeResponse {
  changed: boolean
  message: string
}