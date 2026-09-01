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
  code?: string
  message?: string
}

export interface StorageCapabilities {
  writable: boolean
  maxUploadBytes: number
}

export interface PasswordChangeResponse {
  changed: boolean
  message: string
}
