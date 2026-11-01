<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { getPublicProfile, updatePrivacy } from '@/api/user'
import NavBar from '@/components/NavBar.vue'
import { toast } from '@/components/Toast'

const auth = useAuthStore()

const showLiked = ref(true)
const showComments = ref(true)
const saving = ref(false)

async function load() {
  if (!auth.userId) return
  const profile = await getPublicProfile(auth.userId)
  showLiked.value = profile.showLiked
  showComments.value = profile.showComments
}

/** 开关仅他视生效：关闭后他人看对应 tab 是空态，本人不受影响 */
async function toggle(field: 'showLiked' | 'showComments') {
  if (saving.value) return
  saving.value = true
  const next = !(field === 'showLiked' ? showLiked.value : showComments.value)
  try {
    const result = await updatePrivacy({ [field]: next })
    showLiked.value = result.showLiked
    showComments.value = result.showComments
    toast(next ? '已公开' : '已隐藏（本人仍可见）')
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <NavBar title="隐私设置" />

    <div class="card">
      <button class="row" @click="toggle('showLiked')">
        <span class="row-label">公开我的赞过</span>
        <span class="switch" :class="{ on: showLiked }" />
      </button>
      <button class="row" @click="toggle('showComments')">
        <span class="row-label">公开我的评论</span>
        <span class="switch" :class="{ on: showComments }" />
      </button>
    </div>

    <p class="hint">关闭后，他人访问你的主页看不到对应内容，你自己查看不受影响。</p>
  </div>
</template>

<style scoped lang="scss">
.card {
  margin: 12px;
  border-radius: $radius-card;
  background: #fff;
  overflow: hidden;
}

.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  height: 50px;
  padding: 0 16px;
  border-bottom: 0.5px solid $line;
  font-size: 15px;
  color: $text-1;

  &:last-child {
    border-bottom: 0;
  }

  &:active {
    background: $bg-secondary;
  }
}

.switch {
  position: relative;
  width: 44px;
  height: 26px;
  border-radius: $radius-full;
  background: #d9d9de;
  transition: background 0.15s;

  &::after {
    content: '';
    position: absolute;
    top: 2px;
    left: 2px;
    width: 22px;
    height: 22px;
    border-radius: 50%;
    background: #fff;
    box-shadow: 0 1px 3px rgba(0, 0, 0, 0.2);
    transition: transform 0.15s;
  }

  &.on {
    background: $primary;

    &::after {
      transform: translateX(18px);
    }
  }
}

.hint {
  padding: 0 16px;
  font-size: 12px;
  line-height: 1.5;
  color: $text-3;
}
</style>
