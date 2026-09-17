<script setup lang="ts">
import { computed } from 'vue'
import type { HTMLAttributes } from 'vue'
import { cn } from '@/lib/utils'

defineOptions({ inheritAttrs: false })

const props = withDefaults(defineProps<{
  class?: HTMLAttributes['class']
  variant?: 'default' | 'outline' | 'secondary' | 'ghost' | 'link'
  type?: 'button' | 'submit' | 'reset'
}>(), {
  variant: 'default',
  type: 'button',
})

const classes = computed(() => cn(
  'inline-flex h-10 items-center justify-center gap-2 whitespace-nowrap rounded-md px-4 py-2 text-sm font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-slate-400 disabled:pointer-events-none disabled:opacity-50 [&_svg]:size-4 [&_svg]:shrink-0',
  {
    'bg-slate-900 text-white shadow hover:bg-slate-800': props.variant === 'default',
    'border border-slate-300 bg-white text-slate-900 shadow-sm hover:bg-slate-50': props.variant === 'outline',
    'bg-slate-100 text-slate-900 shadow-sm hover:bg-slate-200': props.variant === 'secondary',
    'text-slate-700 hover:bg-slate-100': props.variant === 'ghost',
    'text-slate-900 underline-offset-4 hover:underline': props.variant === 'link',
  },
  props.class,
))
</script>

<template>
  <button v-bind="$attrs" :type="props.type" :class="classes">
    <slot />
  </button>
</template>
