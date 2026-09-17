<script setup lang="ts">
import type { HTMLAttributes } from "vue"
import { useRouter } from "vue-router"
import { register } from "@/api"
import { ref, computed } from "vue"
import { cn } from "@/lib/utils"
import { Button } from "@/components/ui/button/index"
import {
  Field,
  FieldDescription,
  FieldGroup,
  FieldLabel,
} from "@/components/ui/field"
import { Input } from "@/components/ui/input/index"

const props = defineProps<{
  class?: HTMLAttributes['class']
}>()

const router = useRouter()
const email = ref('')
const username = ref('')
const password = ref('')
const verificationCode = ref('')
const errorMessage = ref('')
const submitting = ref(false)
const sendingCode = ref(false)
const countdown = ref(0)
const emailTouched = ref(false)
const usernameTouched = ref(false)
const passwordTouched = ref(false)
const codeTouched = ref(false)
let countdownTimer: number | undefined

const emailPattern = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
const usernamePattern = /^[A-Za-z0-9._-]{3,100}$/

const emailInvalid = computed(() =>
  emailTouched.value && !emailPattern.test(email.value.trim()),
)
const usernameInvalid = computed(() =>
  usernameTouched.value && !usernamePattern.test(username.value.trim()),
)
const passwordInvalid = computed(() =>
  passwordTouched.value && (password.value.length < 12 || password.value.length > 128),
)
const codeInvalid = computed(() =>
  codeTouched.value && !/^\d{6}$/.test(verificationCode.value.trim()),
)
const canSendCode = computed(() =>
  emailPattern.test(email.value.trim())
  && !sendingCode.value
  && countdown.value === 0,
)

</script>

<template>
  <form :class="cn('flex flex-col gap-6', props.class)" @submit.prevent="submit">
    <FieldGroup>
      <div class="flex flex-col items-center gap-1 text-center">
        <h1 class="text-2xl font-bold">
          Login to your account
        </h1>
        <p class="text-muted-foreground text-sm text-balance">
          Enter your email below to login to your account
        </p>
      </div>
      <Field>
        <FieldLabel for="email">
          Email*
        </FieldLabel>
        <Input id="email" type="email" placeholder="Email" required v-model="email" @blur="emailTouched = true"
          :aria-invalid="emailInvalid" :class="{
            '!border-destructive !ring-destructive/20': emailInvalid
          }" />
        <FieldDescription v-if="emailInvalid" class="text-destructive">
          {{ emailErrorMessage }}
        </FieldDescription>
      </Field>
      <Field>
        <FieldLabel for="username">
          Username*
        </FieldLabel>
        <Input id="username" type="username" placeholder="Username" required v-model="username"
          @blur="usernameTouched = true" :aria-invalid="usernameInvalid" :class="{
            '!border-destructive !ring-destructive/20': usernameInvalid
          }" />
        <FieldDescription v-if="emailInvalid" class="text-destructive">
          please enter your username
        </FieldDescription>
      </Field>
      <Field>
        <div class="flex items-center">
          <FieldLabel for="passwd">
            Password*
          </FieldLabel>
        </div>
        <Input id="passwd" type="password" placeholder="Password" required v-model="passwd"
          @blur="passwd_touched = true" :aria-invalid="passwdInvalid" :class="{
            '!border-destructive !ring-destructive/20': passwdInvalid
          }" />
        <FieldDescription v-if="passwdInvalid" class="text-destructive">
          please enter your password
        </FieldDescription>
      </Field>
      <Field>
        <Button type="submit" >
          Register
        </Button>
      </Field>

    </FieldGroup>
  </form>
</template>
