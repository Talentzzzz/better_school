<script setup lang="ts">
import { useRouter } from 'vue-router'

defineProps<{ title?: string; transparent?: boolean }>()

const router = useRouter()
const goBack = () => {
  if (window.history.length > 1) router.back()
  else router.push('/home')
}
</script>

<template>
  <header class="navbar" :class="{ 'navbar--transparent': transparent }">
    <div class="navbar-side">
      <button class="navbar-back" aria-label="返回" @click="goBack">
        <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <path d="M15 5l-7 7 7 7" />
        </svg>
      </button>
    </div>
    <div class="navbar-title">{{ title }}</div>
    <div class="navbar-side navbar-side--right">
      <slot name="right" />
    </div>
  </header>
</template>

<style scoped lang="scss">
.navbar {
  position: sticky;
  top: 0;
  z-index: 100;
  display: grid;
  grid-template-columns: 64px 1fr 64px;
  align-items: center;
  height: 48px;
  padding: 0 8px;
  background: #fff;
  border-bottom: 0.5px solid $line;

  &--transparent {
    background: transparent;
    border-bottom: none;
  }
}

.navbar-side {
  display: flex;
  align-items: center;
  height: 100%;

  &--right {
    justify-content: flex-end;
  }
}

.navbar-back {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  color: $text-1;

  &:active {
    background: $bg-secondary;
  }
}

.navbar-title {
  text-align: center;
  font-size: 17px;
  font-weight: 600;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
</style>
