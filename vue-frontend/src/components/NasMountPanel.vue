<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  connectNasMount,
  createNasMount,
  listNasMounts,
  removeNasMount,
} from '@/api'
import type {
  NasMount,
  NasMountInput,
  NasMountProvider,
  NasMountStatus,
} from '@/types'

const emit = defineEmits<{ close: []; changed: [] }>()
const mounts = ref<NasMount[]>([])
const loading = ref(true)
const submitting = ref(false)
const actionId = ref('')
const error = ref('')
const secure = window.location.protocol === 'https:'
const secureHref = computed(() => {
  const url = new URL(window.location.href)
  url.protocol = 'https:'
  url.port = '18880'
  return url.toString()
})

function blankForm(provider: NasMountProvider = 'SMB'): NasMountInput {
  return {
    name: '',
    provider,
    host: '',
    share: '',
    username: '',
    password: '',
    domain: '',
    useProxy: provider !== 'WEBDAV',
    url: '',
    vendor: 'other',
    oauthToken: '',
    clientId: '',
    clientSecret: '',
    rootFolderId: '',
  }
}

const form = reactive<NasMountInput>(blankForm())

const statusText: Record<NasMountStatus, string> = {
  CONNECTED: '已连接',
  DISCONNECTED: '未连接',
  MOUNTING: '连接中',
  ERROR: '连接失败',
}

const providerText: Record<NasMountProvider, string> = {
  SMB: 'SMB / NAS',
  WEBDAV: 'WebDAV 网盘',
  GOOGLE_DRIVE: 'Google Drive',
}
const providers: NasMountProvider[] = ['SMB', 'WEBDAV', 'GOOGLE_DRIVE']

function switchProvider(provider: NasMountProvider) {
  const name = form.name
  Object.assign(form, blankForm(provider), { name })
}

function endpointOf(mount: NasMount): string {
  if (mount.provider === 'SMB') return `\\\\${mount.host}\\${mount.share}`
  if (mount.provider === 'WEBDAV') return mount.url
  return mount.rootFolderId ? `Google Drive / ${mount.rootFolderId}` : 'Google Drive / 我的云端硬盘'
}

function connectionText(mount: NasMount): string {
  if (!mount.useProxy) return '直连'
  return mount.provider === 'SMB' ? 'Tank 网络' : '服务器代理'
}

async function refresh() {
  loading.value = true
  error.value = ''
  try {
    mounts.value = await listNasMounts()
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '存储列表加载失败'
  } finally {
    loading.value = false
  }
}

async function submit() {
  if (!secure || submitting.value) return
  submitting.value = true
  error.value = ''
  try {
    const provider = form.provider
    await createNasMount({ ...form })
    Object.assign(form, blankForm(provider))
    await refresh()
    emit('changed')
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '远程存储挂载失败'
  } finally {
    submitting.value = false
  }
}

async function reconnect(mount: NasMount) {
  actionId.value = mount.id
  error.value = ''
  try {
    await connectNasMount(mount.id)
    await refresh()
    emit('changed')
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '远程存储重连失败'
    await refresh()
  } finally {
    actionId.value = ''
  }
}

async function remove(mount: NasMount) {
  if (!window.confirm(`确定卸载“${mount.name}”并删除服务器中保存的加密凭据吗？`)) return
  actionId.value = mount.id
  error.value = ''
  try {
    await removeNasMount(mount.id)
    await refresh()
    emit('changed')
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '远程存储卸载失败'
  } finally {
    actionId.value = ''
  }
}

onMounted(refresh)
</script>

<template>
  <div class="nas-overlay" role="presentation" @click.self="emit('close')">
    <section class="nas-panel" role="dialog" aria-modal="true" aria-labelledby="nas-title">
      <header class="nas-panel-header">
        <div>
          <h2 id="nas-title">挂载远程存储</h2>
          <p>SMB、WebDAV 与 Google Drive 都以只读方式挂载；文件内容不缓存到服务器磁盘。</p>
        </div>
        <button class="icon-button" type="button" aria-label="关闭" @click="emit('close')">×</button>
      </header>

      <div class="nas-panel-body">
        <div v-if="!secure" class="nas-security-warning">
          为保护账号、密码与 OAuth Token，只能从 HTTPS 页面提交。
          <a :href="secureHref">打开安全入口</a>
        </div>

        <div class="storage-provider-tabs" role="tablist" aria-label="存储类型">
          <button
            v-for="provider in providers"
            :key="provider"
            type="button"
            :class="{ active: form.provider === provider }"
            @click="switchProvider(provider)"
          >
            {{ providerText[provider] }}
          </button>
        </div>

        <form class="nas-form" autocomplete="off" @submit.prevent="submit">
          <label>
            显示名称
            <input v-model.trim="form.name" maxlength="40" placeholder="例如：家中资料盘" required />
          </label>

          <template v-if="form.provider === 'SMB'">
            <label>
              NAS IP
              <input
                v-model.trim="form.host"
                maxlength="64"
                inputmode="decimal"
                placeholder="例如：192.168.1.20"
                required
              />
            </label>
            <label>
              SMB 共享名
              <input v-model.trim="form.share" maxlength="128" placeholder="例如：documents" required />
            </label>
            <label>
              账号
              <input v-model.trim="form.username" maxlength="128" autocomplete="off" required />
            </label>
            <label>
              密码
              <input v-model="form.password" type="password" maxlength="512" autocomplete="new-password" required />
            </label>
            <label>
              域（可选）
              <input v-model.trim="form.domain" maxlength="128" placeholder="例如：WORKGROUP" />
            </label>
          </template>

          <template v-else-if="form.provider === 'WEBDAV'">
            <label class="span-2">
              WebDAV 地址
              <input
                v-model.trim="form.url"
                type="url"
                maxlength="2048"
                placeholder="例如：https://dav.example.com/remote.php/dav/files/me"
                required
              />
            </label>
            <label>
              账号
              <input v-model.trim="form.username" maxlength="128" autocomplete="off" required />
            </label>
            <label>
              密码或应用专用密码
              <input v-model="form.password" type="password" maxlength="512" autocomplete="new-password" required />
            </label>
            <label>
              服务类型
              <select v-model="form.vendor">
                <option value="other">通用 WebDAV</option>
                <option value="nextcloud">Nextcloud</option>
                <option value="owncloud">ownCloud</option>
                <option value="sharepoint">SharePoint</option>
                <option value="fastmail">Fastmail</option>
                <option value="rclone">rclone WebDAV</option>
              </select>
            </label>
          </template>

          <template v-else>
            <label>
              Google Client ID（可选）
              <input
                v-model.trim="form.clientId"
                maxlength="256"
                autocomplete="off"
                placeholder="推荐使用自己的 OAuth Client ID"
              />
            </label>
            <label>
              Google Client Secret（可选）
              <input
                v-model="form.clientSecret"
                type="password"
                maxlength="512"
                autocomplete="new-password"
                placeholder="必须与 Client ID 同时填写"
              />
            </label>
            <label class="span-2">
              OAuth Token JSON
              <textarea
                v-model.trim="form.oauthToken"
                rows="5"
                maxlength="32768"
                autocomplete="off"
                placeholder='粘贴 rclone authorize drive 输出的 {"access_token": ...} JSON'
                required
              ></textarea>
            </label>
            <label class="span-2">
              根目录 ID（可选）
              <input
                v-model.trim="form.rootFolderId"
                maxlength="256"
                placeholder="留空挂载整个“我的云端硬盘”；填写后只显示指定目录"
              />
            </label>
          </template>

          <label class="nas-checkbox span-2">
            <input v-model="form.useProxy" type="checkbox" />
            {{ form.provider === 'SMB' ? '通过 Tank 网络连接' : '通过服务器代理连接' }}
          </label>

          <div class="nas-form-note span-2">
            <template v-if="form.provider === 'GOOGLE_DRIVE'">
              默认可运行 <code>rclone authorize drive</code>；若使用自己的 OAuth 应用，请运行
              <code>rclone authorize drive CLIENT_ID CLIENT_SECRET</code>，并在上方填写同一组 Client ID 与 Secret。
            </template>
            <template v-else-if="form.provider === 'SMB'">
              Tank 适合远端 192.168.1.x NAS；如果服务器能直接访问 NAS，可取消勾选。
            </template>
            <template v-else>
              支持 Nextcloud、ownCloud、坚果云、AList 等提供 WebDAV 的网盘；公网地址必须使用 HTTPS。
            </template>
            凭据经 TLS 传输并用 AES-256-GCM 加密保存；服务器只保存挂载配置，不保存远端文件内容。
          </div>

          <button class="primary-button span-2" type="submit" :disabled="!secure || submitting">
            {{ submitting ? '正在验证并挂载…' : '连接并挂载' }}
          </button>
        </form>

        <div class="nas-mount-list">
          <div class="nas-list-heading">
            <h3>已添加的存储</h3>
            <button class="secondary-button" type="button" :disabled="loading" @click="refresh">刷新</button>
          </div>
          <p v-if="loading" class="nas-empty">正在读取…</p>
          <p v-else-if="!mounts.length" class="nas-empty">还没有添加远程存储。</p>
          <article v-for="mount in mounts" v-else :key="mount.id" class="nas-mount-card">
            <div class="nas-mount-main">
              <div class="nas-mount-title">
                <strong>{{ mount.name }}</strong>
                <span class="storage-provider-badge">{{ providerText[mount.provider] }}</span>
                <span class="nas-state" :class="`state-${mount.status.toLowerCase()}`">
                  {{ statusText[mount.status] }}
                </span>
              </div>
              <code>{{ endpointOf(mount) }}</code>
              <small>
                {{ mount.username || 'OAuth 授权' }} · 只读 · {{ connectionText(mount) }} · 零磁盘文件缓存
              </small>
              <p v-if="mount.lastError" class="nas-card-error">{{ mount.lastError }}</p>
            </div>
            <div class="nas-card-actions">
              <button
                v-if="mount.status !== 'CONNECTED'"
                class="secondary-button"
                type="button"
                :disabled="actionId === mount.id || !secure"
                @click="reconnect(mount)"
              >
                重连
              </button>
              <button
                class="danger-button"
                type="button"
                :disabled="actionId === mount.id || !secure"
                @click="remove(mount)"
              >
                卸载
              </button>
            </div>
          </article>
        </div>

        <p v-if="error" class="nas-error" role="alert">{{ error }}</p>
      </div>
    </section>
  </div>
</template>
