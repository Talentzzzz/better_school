<script setup lang="ts">
import { ref } from 'vue'
import AppImage from './AppImage.vue'
import { uploadImage } from '@/api/files'
import { toast } from '@/components/Toast'

const props = withDefaults(defineProps<{ modelValue: string[]; max?: number }>(), { max: 9 })
const emit = defineEmits<{ 'update:modelValue': [string[]] }>()

const fileInput = ref<HTMLInputElement>()
const uploading = ref(false)

const ALLOWED_EXTENSIONS = ['jpg', 'jpeg', 'png', 'webp']
const MAX_SIZE_BYTES = 5 * 1024 * 1024

function pick() {
  fileInput.value?.click()
}

async function onChange(e: Event) {
  const input = e.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  if (!files.length) return

  const remain = props.max - props.modelValue.length
  if (remain <= 0) return
  const urls: string[] = []
  uploading.value = true
  try {
    for (const file of files.slice(0, remain)) {
      const ext = file.name.split('.').pop()?.toLowerCase() ?? ''
      if (!ALLOWED_EXTENSIONS.includes(ext)) {
        toast(`仅支持 jpg/png/webp：${file.name}`)
        continue
      }
      if (file.size > MAX_SIZE_BYTES) {
        toast(`图片超过 5MB：${file.name}`)
        continue
      }
      try {
        urls.push(await uploadImage(file))
      } catch {
        // 失败 toast 已由 http 拦截器提示
      }
    }
  } finally {
    uploading.value = false
  }
  if (urls.length) {
    emit('update:modelValue', [...props.modelValue, ...urls])
  }
}

function remove(i: number) {
  const next = props.modelValue.slice()
  next.splice(i, 1)
  emit('update:modelValue', next)
}
</script>

<template>
  <div class="image-picker">
    <div v-for="(url, i) in modelValue" :key="url" class="image-item">
      <AppImage :src="url" :seed="i" ratio="1:1" />
      <button class="image-remove" aria-label="删除" @click="remove(i)">×</button>
    </div>
    <div v-if="uploading" class="image-item image-uploading">上传中…</div>
    <button
      v-if="modelValue.length < max && !uploading"
      class="image-add-tile"
      type="button"
      @click="pick"
    >
      ＋<span>选择图片</span>
    </button>
    <input
      ref="fileInput"
      type="file"
      accept=".jpg,.jpeg,.png,.webp,image/jpeg,image/png,image/webp"
      multiple
      hidden
      @change="onChange"
    />
  </div>
</template>

<style scoped lang="scss">
.image-picker {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.image-item {
  position: relative;
  width: 76px;
  height: 76px;
  border-radius: $radius-sm;
  overflow: hidden;
}

.image-uploading {
  display: flex;
  align-items: center;
  justify-content: center;
  background: $bg-secondary;
  color: $text-3;
  font-size: 11px;
}

.image-remove {
  position: absolute;
  top: 2px;
  right: 2px;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.55);
  color: #fff;
  font-size: 13px;
  line-height: 1;
}

.image-add-tile {
  width: 76px;
  height: 76px;
  border-radius: $radius-sm;
  background: $bg-secondary;
  color: $text-3;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  font-size: 18px;
  line-height: 1;

  span {
    font-size: 11px;
  }
}
</style>
