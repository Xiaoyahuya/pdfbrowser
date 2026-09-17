<script setup lang="ts">
import type { HTMLAttributes } from 'vue'
import { cn } from '@/lib/utils'

const props = defineProps<{
  class?: HTMLAttributes['class']
}>()
</script>

<template>
  <div :class="cn('relative flex items-center justify-center text-xs uppercase text-slate-500', props.class)">
    <span class="relative z-10 bg-white px-2">
      <slot />
    </span>
    <span class="absolute inset-x-0 top-1/2 h-px bg-slate-200" aria-hidden="true" />
  </div>
</template>
