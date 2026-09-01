export interface Breadcrumb {
  label: string
  path: string
}

export function browseHref(path: string): string {
  const encoded = path.split('/').filter(Boolean).map(encodeURIComponent).join('/')
  return `#/browse/${encoded}`
}

export function pathFromHash(hash: string): string {
  const prefix = '#/browse/'
  if (!hash.startsWith(prefix)) return ''
  const encoded = hash.slice(prefix.length)
  if (!encoded) return ''
  try {
    return encoded.split('/').filter(Boolean).map(decodeURIComponent).join('/')
  } catch {
    return ''
  }
}

export function breadcrumbs(path: string): Breadcrumb[] {
  const parts = path.split('/').filter(Boolean)
  const result: Breadcrumb[] = [{ label: '根目录', path: '' }]
  parts.forEach((part, index) => {
    result.push({ label: part, path: parts.slice(0, index + 1).join('/') })
  })
  return result
}

export function formatBytes(size: number | null): string {
  if (size === null) return '文件夹'
  if (size < 1024) return `${size} B`
  const units = ['KB', 'MB', 'GB', 'TB']
  let value = size / 1024
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit += 1
  }
  return `${value >= 10 ? value.toFixed(0) : value.toFixed(1)} ${units[unit]}`
}

export function formatDate(timestamp: number): string {
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  }).format(new Date(timestamp))
}

export function resolveRelativePath(filePath: string, target: string): string | null {
  if (!target || target.startsWith('/') || /^[A-Za-z][A-Za-z0-9+.-]*:/.test(target)) return null
  const parts = filePath.split('/').filter(Boolean)
  parts.pop()
  for (const segment of target.split('/')) {
    if (!segment || segment === '.') continue
    if (segment === '..') {
      if (!parts.length) return null
      parts.pop()
    } else {
      parts.push(segment)
    }
  }
  return parts.join('/')
}
