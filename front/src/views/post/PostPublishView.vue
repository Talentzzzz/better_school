<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { onBeforeRouteLeave } from 'vue-router'
import { createPost, getPost, savePostDraft, submitPostDraft, updatePostDraft } from '@/api/community'
import type { PostPublishResult } from '@/types/api'
import { uploadImage } from '@/api/files'
import { toast } from '@/components/Toast'
import { confirmDialog } from '@/components/ConfirmDialog'
import NavBar from '@/components/NavBar.vue'
import ImageUrlPicker from '@/components/ImageUrlPicker.vue'

const router = useRouter()
const route = useRoute()

/** 草稿编辑模式：/publish/post?draft=<postId> */
const draftId = ref<string | null>(typeof route.query.draft === 'string' && route.query.draft ? route.query.draft : null)
const isDraftEdit = computed(() => draftId.value !== null)
const loadingDraft = ref(false)

const title = ref('')
const content = ref('')
const images = ref<string[]>([])
/** 独立封面：null = 未指定（展示回退相册第一张，均无即无封面帖） */
const cover = ref<string | null>(null)
const submitting = ref(false)
const savingDraft = ref(false)

const effectiveCover = computed(() => cover.value ?? images.value[0] ?? null)

// ---------- 脏态与退出存草稿 ----------

interface FormSnapshot {
  title: string
  content: string
  images: string[]
  cover: string | null
}

function currentSnapshot(): FormSnapshot {
  return {
    title: title.value,
    content: content.value,
    images: [...images.value],
    cover: cover.value
  }
}

const snapshot = ref<FormSnapshot>({ title: '', content: '', images: [], cover: null })

const dirty = computed(
  () =>
    title.value !== snapshot.value.title ||
    content.value !== snapshot.value.content ||
    cover.value !== snapshot.value.cover ||
    images.value.length !== snapshot.value.images.length ||
    images.value.some((v, i) => v !== snapshot.value.images[i])
)

/** 成功提交/加载失败兜底退出等场景跳过存草稿询问 */
const leaveQuietly = ref(false)

function formBody() {
  return {
    title: title.value.trim(),
    content: content.value.trim(),
    images: images.value,
    cover: cover.value
  }
}

async function persistDraft() {
  if (draftId.value === null) await savePostDraft(formBody())
  else await updatePostDraft(draftId.value, formBody())
}

// 中途退出弹窗询问是否存入草稿箱；保存失败留在本页避免内容丢失
onBeforeRouteLeave(async () => {
  if (leaveQuietly.value || submitting.value || !dirty.value) return true
  const ok = await confirmDialog('是否存入草稿箱？', isDraftEdit.value ? '确认将保存修改到草稿箱' : '确认后当前内容保存为草稿')
  if (!ok) return true
  try {
    await persistDraft()
    toast('已存入草稿箱')
    return true
  } catch {
    return false
  }
})

onMounted(async () => {
  if (draftId.value === null) return
  loadingDraft.value = true
  try {
    const post = await getPost(draftId.value)
    if (post.status !== 'DRAFT') {
      toast('该帖已不在草稿箱')
      leaveQuietly.value = true
      router.replace('/me/post-drafts')
      return
    }
    title.value = post.title ?? ''
    content.value = post.content ?? ''
    images.value = [...(post.images ?? [])]
    // 独立封面用原始值回填：展示口径（独立封面 ?? 相册第一张）会把兜底误固化成独立封面
    cover.value = post.independentCover ?? null
    snapshot.value = currentSnapshot()
  } catch {
    leaveQuietly.value = true
    router.replace('/me/post-drafts')
  } finally {
    loadingDraft.value = false
  }
})

async function onSaveDraft() {
  if (savingDraft.value) return
  savingDraft.value = true
  try {
    await persistDraft()
    snapshot.value = currentSnapshot()
    toast('已存入草稿箱')
  } catch {
    // toast 已处理
  } finally {
    savingDraft.value = false
  }
}

/** 封面候选：相册图 + 不在相册中的独立封面（独立上传后也有瓦片可点选/替换） */
const coverChoices = computed(() => {
  const c = cover.value
  return c && !images.value.includes(c) ? [c, ...images.value] : images.value
})

// 相册里删掉被选为封面的图时联动清空，避免提交出相册外孤图；独立封面不受相册编辑影响
watch(images, (vals, olds) => {
  if (cover.value && olds?.includes(cover.value) && !vals.includes(cover.value)) {
    cover.value = null
  }
})

const coverInput = ref<HTMLInputElement>()
const coverUploading = ref(false)

const ALLOWED_EXTENSIONS = ['jpg', 'jpeg', 'png', 'webp']
const MAX_SIZE_BYTES = 5 * 1024 * 1024

function pickCover() {
  coverInput.value?.click()
}

async function onCoverChange(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  const ext = file.name.split('.').pop()?.toLowerCase() ?? ''
  if (!ALLOWED_EXTENSIONS.includes(ext)) {
    toast('仅支持 jpg/png/webp')
    return
  }
  if (file.size > MAX_SIZE_BYTES) {
    toast('图片超过 5MB')
    return
  }
  coverUploading.value = true
  try {
    cover.value = await uploadImage(file)
  } catch {
    // 失败 toast 已由 http 拦截器提示
  } finally {
    coverUploading.value = false
  }
}

function rebateText(result: PostPublishResult): string {
  switch (result.rebateState) {
    case 'CLAIMED': {
      const parts: string[] = []
      if (result.pointsRebate > 0) parts.push(`${result.pointsRebate} 积分`)
      if (result.drawCountRebate > 0) parts.push(`${result.drawCountRebate} 次抽奖`)
      return parts.length > 0 ? `获得 ${parts.join(' + ')}` : '发布成功'
    }
    case 'DENIED':
      return '今日发帖返利已领过'
    case 'CLAIM_ERROR':
      return '返利发放异常，稍后自动重试'
    default:
      return '发布成功'
  }
}

async function onSubmit() {
  if (loadingDraft.value) return
  if (!content.value.trim()) {
    toast('写点内容吧')
    return
  }
  submitting.value = true
  try {
    if (draftId.value === null) {
      const result = await createPost(formBody())
      toast(rebateText(result))
      leaveQuietly.value = true
      router.replace('/home')
    } else {
      // 先落草稿再发布：缺项等报错停留，修改后可重试
      await updatePostDraft(draftId.value, formBody())
      const result = await submitPostDraft(draftId.value)
      toast(rebateText(result))
      leaveQuietly.value = true
      router.replace('/me/post-drafts')
    }
  } catch {
    // toast 已处理
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="page--plain publish-form">
    <NavBar :title="isDraftEdit ? '草稿编辑' : '发布帖子'" />
    <div class="form-body">
      <div class="form-card">
        <div class="form-rows">
          <input v-model="title" class="form-input title-input" maxlength="50" placeholder="标题（可选）" />
          <textarea
            v-model="content"
            class="form-textarea"
            maxlength="5000"
            placeholder="分享你的校园日常…（内容必填）"
          />
        </div>
      </div>

      <div class="form-card">
        <div class="form-label">图片（可选，最多 9 张）</div>
        <ImageUrlPicker v-model="images" />

        <div class="form-label cover-label">封面（信息流展示主图）</div>
        <div class="cover-row">
          <button
            v-for="url in coverChoices"
            :key="url"
            type="button"
            class="cover-tile"
            :class="{ active: effectiveCover === url }"
            @click="cover = url"
          >
            <img :src="url" alt="" />
            <span v-if="cover === url" class="cover-badge">封面</span>
          </button>
          <button type="button" class="cover-add" :disabled="coverUploading" @click="pickCover">
            {{ coverUploading ? '上传中…' : '＋上传封面' }}
          </button>
        </div>
        <p class="cover-hint">
          <template v-if="cover">
            已指定独立封面。
            <button type="button" class="cover-clear" @click="cover = null">清除，回退第一张配图</button>
          </template>
          <template v-else-if="images.length">默认使用第一张配图，可点选其他配图或上传独立封面。</template>
          <template v-else>不添加配图与封面，发布后在主页以文字小卡展示。</template>
        </p>
        <input ref="coverInput" type="file" accept=".jpg,.jpeg,.png,.webp,image/jpeg,image/png,image/webp" hidden @change="onCoverChange" />
      </div>

      <div class="submit-row">
        <button
          v-if="isDraftEdit"
          class="btn-outline submit-secondary"
          :disabled="savingDraft || submitting"
          @click="onSaveDraft"
        >
          {{ savingDraft ? '保存中…' : '存草稿' }}
        </button>
        <button class="btn-primary submit-btn" :disabled="submitting || loadingDraft" @click="onSubmit">
          {{ submitting ? '发布中…' : '发布' }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.form-body {
  padding: 12px 12px 24px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.form-card {
  padding: 14px;
  border-radius: $radius;
  background: #fff;
}

.form-rows {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.form-input {
  font-size: 15px;

  &.title-input {
    font-weight: 600;
  }
}

.form-textarea {
  width: 100%;
  min-height: 140px;
  resize: vertical;
  font-size: 14px;
  line-height: 1.7;
}

.form-label {
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 600;
}

.submit-row {
  display: flex;
  gap: 10px;
  margin-top: 4px;
}

.submit-btn {
  flex: 1;
  height: 44px;
  font-size: 15px;
}

.submit-secondary {
  height: 44px;
  font-size: 15px;
}

.form-label + .image-picker {
  margin-top: -2px;
}

.cover-label {
  margin-top: 14px;
}

.cover-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.cover-tile {
  position: relative;
  width: 76px;
  height: 76px;
  border-radius: $radius-sm;
  overflow: hidden;
  border: 2px solid transparent;

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    display: block;
  }

  &.active {
    border-color: $primary;
  }
}

.cover-badge {
  position: absolute;
  left: 0;
  bottom: 0;
  padding: 1px 6px;
  background: rgba(0, 0, 0, 0.55);
  color: #fff;
  font-size: 10px;
  border-radius: 0 $radius-sm 0 0;
}

.cover-add {
  width: 76px;
  height: 76px;
  border-radius: $radius-sm;
  background: $bg-secondary;
  color: $text-3;
  font-size: 11px;
  line-height: 1.4;
  padding: 0 6px;
}

.cover-hint {
  margin-top: 8px;
  font-size: 12px;
  color: $text-4;
}

.cover-clear {
  color: $primary;
  font-size: 12px;
}
</style>
