<script setup lang="ts">
import { computed, ref } from 'vue'
import { changeAccountPassword } from '@/api'

const emit = defineEmits<{ close: [] }>()
const currentPassword = ref('')
const newPassword = ref('')
const confirmation = ref('')
const submitting = ref(false)
const error = ref('')
const success = ref('')
const secure = window.location.protocol === 'https:'

const validationMessage = computed(() => {
  if (!newPassword.value && !confirmation.value) return ''
  if (newPassword.value.length < 12) return '新密码至少需要 12 个字符。'
  if (newPassword.value.length > 128) return '新密码不能超过 128 个字符。'
  if (newPassword.value !== confirmation.value) return '两次输入的新密码不一致。'
  if (currentPassword.value && newPassword.value === currentPassword.value) return '新密码不能与当前密码相同。'
  return ''
})

async function submit() {
  if (!secure || submitting.value || validationMessage.value) return
  submitting.value = true
  error.value = ''
  success.value = ''
  try {
    const response = await changeAccountPassword(currentPassword.value, newPassword.value)
    currentPassword.value = ''
    newPassword.value = ''
    confirmation.value = ''
    success.value = response.message
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '密码修改失败'
  } finally {
    submitting.value = false
  }
}

function reloadForLogin() {
  window.location.reload()
}
</script>

<template>
  <div class="nas-overlay" role="presentation" @click.self="emit('close')">
    <section class="nas-panel account-panel" role="dialog" aria-modal="true" aria-labelledby="password-title">
      <header class="nas-panel-header">
        <div>
          <h2 id="password-title">修改登录密码</h2>
          <p>修改 PDFBrowser 的网页登录密码，不会改变 SSH、NAS 或 Google Drive 密码。</p>
        </div>
        <button class="icon-button" type="button" aria-label="关闭" @click="emit('close')">×</button>
      </header>

      <div class="nas-panel-body">
        <div v-if="!secure" class="nas-security-warning">只能从 HTTPS 页面修改登录密码。</div>
        <div v-if="success" class="password-success" role="status">
          <strong>{{ success }}</strong>
          <span>浏览器会继续缓存旧的 Basic Auth 凭据。点击重新登录后，请在弹出的登录框中输入新密码。</span>
          <button class="primary-button" type="button" @click="reloadForLogin">使用新密码重新登录</button>
        </div>

        <form v-else class="nas-form account-form" autocomplete="off" @submit.prevent="submit">
          <label>
            当前密码
            <input
              v-model="currentPassword"
              type="password"
              maxlength="128"
              autocomplete="current-password"
              required
            />
          </label>
          <label>
            新密码
            <input
              v-model="newPassword"
              type="password"
              minlength="12"
              maxlength="128"
              autocomplete="new-password"
              required
            />
          </label>
          <label>
            再次输入新密码
            <input
              v-model="confirmation"
              type="password"
              minlength="12"
              maxlength="128"
              autocomplete="new-password"
              required
            />
          </label>
          <p class="nas-form-note">建议至少 16 个字符，并混合使用大小写字母、数字和符号。</p>
          <p v-if="validationMessage" class="password-validation">{{ validationMessage }}</p>
          <p v-if="error" class="nas-error password-error" role="alert">{{ error }}</p>
          <button
            class="primary-button"
            type="submit"
            :disabled="!secure || submitting || !currentPassword || !newPassword || !confirmation || Boolean(validationMessage)"
          >{{ submitting ? '正在修改…' : '确认修改密码' }}</button>
        </form>
      </div>
    </section>
  </div>
</template>
