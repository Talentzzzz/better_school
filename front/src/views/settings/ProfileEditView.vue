<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { getPublicProfile, updateProfile } from '@/api/user'
import type { Gender } from '@/types/api'
import NavBar from '@/components/NavBar.vue'
import { toast } from '@/components/Toast'

const SIGNATURE_MAX = 40

const GENDERS: { key: Gender | null; label: string }[] = [
  { key: 'MALE', label: '男' },
  { key: 'FEMALE', label: '女' },
  { key: null, label: '不展示' }
]

const router = useRouter()
const auth = useAuthStore()

const signature = ref('')
const gender = ref<Gender | null>(null)
const saving = ref(false)

const remaining = computed(() => SIGNATURE_MAX - signature.value.length)

async function load() {
  if (!auth.userId) return
  const profile = await getPublicProfile(auth.userId)
  signature.value = profile.signature ?? ''
  gender.value = profile.gender
}

async function save() {
  if (saving.value) return
  saving.value = true
  try {
    await updateProfile({ signature: signature.value.trim(), gender: gender.value })
    toast('资料已更新')
    router.back()
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <NavBar title="编辑资料" />

    <div class="card">
      <div class="label">个性签名</div>
      <textarea
        v-model="signature"
        class="input"
        :maxlength="SIGNATURE_MAX"
        placeholder="介绍一下自己吧"
        rows="3"
      />
      <div class="counter">还可输入 {{ remaining }} 字</div>
    </div>

    <div class="card">
      <div class="label">性别</div>
      <div class="segments">
        <button
          v-for="option in GENDERS"
          :key="option.label"
          class="segment"
          :class="{ active: gender === option.key }"
          @click="gender = option.key"
        >
          {{ option.label }}
        </button>
      </div>
    </div>

    <button class="btn-primary save" :disabled="saving" @click="save">保存</button>
  </div>
</template>

<style scoped lang="scss">
.card {
  margin: 12px;
  padding: 14px 16px;
  border-radius: $radius-card;
  background: #fff;
}

.label {
  font-size: 14px;
  font-weight: 600;
  color: $text-1;
}

.input {
  width: 100%;
  margin-top: 10px;
  padding: 10px 12px;
  border-radius: $radius;
  background: $bg-search;
  font-size: 14px;
  line-height: 1.5;
  color: $text-1;
  resize: none;
}

.counter {
  margin-top: 6px;
  font-size: 12px;
  color: $text-3;
}

.segments {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}

.segment {
  flex: 1;
  height: 36px;
  border-radius: $radius-full;
  background: $bg-search;
  font-size: 14px;
  color: $text-2;

  &.active {
    background: $primary-light;
    color: $primary;
    font-weight: 600;
  }
}

.save {
  display: block;
  width: calc(100% - 24px);
  margin: 20px 12px;
}
</style>
