<script setup lang="ts">
import type { HTMLAttributes } from 'vue'
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { login } from '@/api'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button/index'
import {
  Field,
  FieldDescription,
  FieldGroup,
  FieldLabel,
} from '@/components/ui/field'
import { Input } from '@/components/ui/input/index'

const props = defineProps<{
  class?: HTMLAttributes["class"]
}>()

const router = useRouter()
const route = useRoute()
const loginName = ref('')
const password = ref('')
const submitting = ref(false)
const errorMessage = ref('')

async function submit() {
  errorMessage.value = ''

  if (!loginName.value.trim() || !password.value) {
    errorMessage.value = '请输入用户名或邮箱，以及密码。'
    return
  }

  submitting.value = true

  try {
    await login({
      login: loginName.value.trim(),
      password: password.value,
    })

    const redirect = route.query.redirect
    await router.push(
      typeof redirect === 'string'
        ? redirect
        : { name: 'home' },
    )
  } catch (error) {
    errorMessage.value =
      error instanceof Error ? error.message : '登录失败'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <form
    :class="cn('flex flex-col gap-6', props.class)"
    @submit.prevent="submit"
  >
    <FieldGroup>
      <div class="flex flex-col items-center gap-1 text-center">
        <h1 class="text-2xl font-bold">登录 PDFBrowser</h1>
        <p class="text-muted-foreground text-sm text-balance">
          使用用户名或邮箱登录
        </p>
      </div>

      <Field>
        <FieldLabel for="login">用户名或邮箱</FieldLabel>
        <Input
          id="login"
          v-model="loginName"
          type="text"
          autocomplete="username"
          required
        />
      </Field>

      <Field>
        <FieldLabel for="password">密码</FieldLabel>
        <Input
          id="password"
          v-model="password"
          type="password"
          autocomplete="current-password"
          required
        />
      </Field>

      <p v-if="errorMessage" class="text-destructive text-sm" role="alert">
        {{ errorMessage }}
      </p>

      <Button type="submit" :disabled="submitting">
        {{ submitting ? '登录中…' : '登录' }}
      </Button>

      <FieldDescription class="text-center">
        还没有账号？
        <RouterLink to="/register">注册</RouterLink>
      </FieldDescription>
    </FieldGroup>
  </form>
</template>
