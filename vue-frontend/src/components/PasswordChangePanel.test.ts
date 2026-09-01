import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App as VueApp } from 'vue'
import PasswordChangePanel from './PasswordChangePanel.vue'

const api = vi.hoisted(() => ({ changeAccountPassword: vi.fn() }))
vi.mock('@/api', () => api)

let app: VueApp | null = null

afterEach(() => {
  app?.unmount()
  app = null
  document.body.replaceChildren()
  vi.clearAllMocks()
})

describe('password change panel', () => {
  it('asks for the current password and two matching new passwords', () => {
    const host = document.createElement('div')
    document.body.append(host)
    app = createApp(PasswordChangePanel)
    app.mount(host)

    expect(host.textContent).toContain('修改登录密码')
    expect(host.querySelectorAll('input[type="password"]')).toHaveLength(3)
    expect(host.textContent).toContain('不会改变 SSH、NAS 或 Google Drive 密码')
  })

  it('shows a clear validation message before sending a weak password', async () => {
    const host = document.createElement('div')
    document.body.append(host)
    app = createApp(PasswordChangePanel)
    app.mount(host)

    const fields = [...host.querySelectorAll<HTMLInputElement>('input[type="password"]')]
    fields[0].value = 'Current-Password-123'
    fields[0].dispatchEvent(new Event('input'))
    fields[1].value = 'short'
    fields[1].dispatchEvent(new Event('input'))
    fields[2].value = 'short'
    fields[2].dispatchEvent(new Event('input'))
    await nextTick()

    expect(host.textContent).toContain('新密码至少需要 12 个字符')
    expect(api.changeAccountPassword).not.toHaveBeenCalled()
  })
})
