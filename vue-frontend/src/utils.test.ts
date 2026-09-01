import { describe, expect, it } from 'vitest'
import { breadcrumbs, browseHref, formatBytes, pathFromHash, resolveRelativePath } from './utils'

describe('browser routes', () => {
  it('round-trips Unicode and spaces through a shareable hash route', () => {
    const path = 'PDFBrowser资料库/思考快与慢/思考，快与慢.pdf'
    const hash = browseHref(path)

    expect(hash).toBe('#/browse/PDFBrowser%E8%B5%84%E6%96%99%E5%BA%93/%E6%80%9D%E8%80%83%E5%BF%AB%E4%B8%8E%E6%85%A2/%E6%80%9D%E8%80%83%EF%BC%8C%E5%BF%AB%E4%B8%8E%E6%85%A2.pdf')
    expect(pathFromHash(hash)).toBe(path)
  })

  it('ignores unrelated or malformed hashes', () => {
    expect(pathFromHash('#section')).toBe('')
    expect(pathFromHash('#/browse/%E0%A4%A')).toBe('')
  })
})

describe('breadcrumbs', () => {
  it('builds navigable paths from a logical path', () => {
    expect(breadcrumbs('docs/linux/kernel')).toEqual([
      { label: '根目录', path: '' },
      { label: 'docs', path: 'docs' },
      { label: 'linux', path: 'docs/linux' },
      { label: 'kernel', path: 'docs/linux/kernel' },
    ])
  })
})

describe('formatBytes', () => {
  it('formats folders and binary units', () => {
    expect(formatBytes(null)).toBe('文件夹')
    expect(formatBytes(512)).toBe('512 B')
    expect(formatBytes(1536)).toBe('1.5 KB')
  })
})

describe('resolveRelativePath', () => {
  it('resolves assets relative to a markdown file without escaping root', () => {
    expect(resolveRelativePath('docs/guide/readme.md', '../images/cover.png')).toBe('docs/images/cover.png')
    expect(resolveRelativePath('readme.md', '../secret.png')).toBeNull()
    expect(resolveRelativePath('readme.md', 'https://example.com/image.png')).toBeNull()
  })
})
