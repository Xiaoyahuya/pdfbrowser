<script setup lang="ts">
import { computed } from 'vue'
import { browseHref } from '@/utils'
import { libraryLabel, libraryTrail } from '@/library'

const props = withDefaults(defineProps<{ path: string; rootPath?: string }>(), { rootPath: '' })
const emit = defineEmits<{ navigate: [path: string] }>()
const items = computed(() => {
  const rootParts = props.rootPath.split('/').filter(Boolean)
  const result = [{ label: libraryLabel(props.rootPath, props.rootPath ? rootParts.at(-1) || '资料库' : '根目录'), path: props.rootPath }]
  libraryTrail(props.path).forEach((path) => {
    const fallback = path.split('/').filter(Boolean).at(-1) || path
    result.push({ label: libraryLabel(path, fallback), path })
  })
  return result
})
</script>

<template>
  <nav class="breadcrumb-bar" aria-label="当前位置">
    <div class="breadcrumbs">
      <template v-for="(item, index) in items" :key="item.path">
        <span v-if="index" class="separator">/</span>
        <a class="breadcrumb" :href="browseHref(item.path)" @click.prevent="emit('navigate', item.path)">{{ item.label }}</a>
      </template>
    </div>
  </nav>
</template>
