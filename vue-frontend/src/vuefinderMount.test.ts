import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, type App as VueApp } from 'vue'
import VueFinderPlugin, { VueFinder, type Driver } from 'vuefinder'
import zhCN from 'vuefinder/dist/locales/zhCN.js'

const list = vi.fn()
const rejectWrite = () => Promise.reject(new Error('read only'))

const driver: Driver = {
  list,
  delete: rejectWrite,
  rename: rejectWrite,
  copy: rejectWrite,
  move: rejectWrite,
  archive: rejectWrite,
  unarchive: rejectWrite,
  createFile: rejectWrite,
  createFolder: rejectWrite,
  getContent: async () => ({ content: '', mimeType: 'text/plain' }),
  getPreviewUrl: () => '/preview',
  getDownloadUrl: () => '/download',
  search: async () => [],
  save: rejectWrite,
}

let mountedApp: VueApp | null = null

beforeEach(() => {
  list.mockReset()
  list.mockResolvedValue({
    storages: ['资料库'],
    dirname: '资料库://',
    files: [],
    read_only: true,
  })
  if (!globalThis.ResizeObserver) {
    globalThis.ResizeObserver = class ResizeObserver {
      observe() {}
      unobserve() {}
      disconnect() {}
    }
  }
  if (!globalThis.IntersectionObserver) {
    globalThis.IntersectionObserver = class IntersectionObserver {
      root = null
      rootMargin = ''
      thresholds: number[] = []
      observe() {}
      unobserve() {}
      disconnect() {}
      takeRecords(): IntersectionObserverEntry[] { return [] }
    }
  }
})

afterEach(() => {
  mountedApp?.unmount()
  mountedApp = null
  document.body.replaceChildren()
  localStorage.clear()
})

describe('VueFinder runtime initialization', () => {
  it('mounts with the official plugin and requests the initial directory', async () => {
    const host = document.createElement('div')
    document.body.append(host)
    const Root = defineComponent({
      render: () => h(VueFinder, {
        id: 'vuefinder-runtime-smoke-test',
        driver,
        locale: 'zhCN',
        config: { initialPath: '资料库://', persist: false },
      }),
    })

    const app = createApp(Root)
    app.use(VueFinderPlugin, { locale: 'zhCN', i18n: { zhCN } })
    app.mount(host)
    mountedApp = app
    await vi.waitFor(() => expect(list).toHaveBeenCalled())

    expect(list).toHaveBeenCalledWith(expect.objectContaining({ path: '资料库://' }))
    expect(host.querySelector('.vuefinder')).not.toBeNull()
  })
})
