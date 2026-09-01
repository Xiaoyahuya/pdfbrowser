import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App as VueApp } from 'vue'
import NasMountPanel from './NasMountPanel.vue'

const api = vi.hoisted(() => ({
  listNasMounts: vi.fn().mockResolvedValue([]),
  createNasMount: vi.fn(),
  connectNasMount: vi.fn(),
  removeNasMount: vi.fn(),
}))

vi.mock('@/api', () => api)

let app: VueApp | null = null

afterEach(() => {
  app?.unmount()
  app = null
  document.body.replaceChildren()
})

describe('remote storage mount panel', () => {
  it('renders SMB credentials and blocks submission on plain HTTP', async () => {
    const host = document.createElement('div')
    document.body.append(host)
    app = createApp(NasMountPanel)
    app.mount(host)
    await vi.waitFor(() => expect(api.listNasMounts).toHaveBeenCalled())

    expect(host.textContent).toContain('挂载远程存储')
    expect(host.querySelector('input[type="password"]')).not.toBeNull()
    expect(host.textContent).toContain('通过 Tank 网络连接')
    const submit = [...host.querySelectorAll('button')]
      .find((button) => button.textContent?.includes('连接并挂载'))
    expect(submit?.disabled).toBe(true)
  })

  it('switches to Google Drive token fields without reloading the panel', async () => {
    const host = document.createElement('div')
    document.body.append(host)
    app = createApp(NasMountPanel)
    app.mount(host)
    const googleButton = [...host.querySelectorAll('button')]
      .find((button) => button.textContent?.includes('Google Drive'))
    googleButton?.click()
    await nextTick()

    expect(host.querySelector('textarea')).not.toBeNull()
    expect(host.textContent).toContain('rclone authorize drive')
    expect(host.textContent).toContain('不保存远端文件内容')
  })
})
