<script setup lang="ts">
import { onMounted } from 'vue'
import { useConsoleStore } from '@/stores/console'
import TopBar from '@/components/TopBar.vue'
import SignalBanner from '@/components/SignalBanner.vue'
import TypeCounterBar from '@/components/TypeCounterBar.vue'
import TaskWorkbench from '@/components/TaskWorkbench.vue'
import SidePanel from '@/components/SidePanel.vue'
import Toast from '@/components/Toast.vue'

// 审核中台主视图（信息架构承票 07 原型：顶部信号条 + 类型计数条 + 主区单条聚焦 + 右栏状态/事件流）。
// 进入本视图前路由守卫已确保登录态与角色（AUDITOR/ADMIN），此处直接接真链路。
const store = useConsoleStore()
onMounted(() => store.init())
</script>

<template>
  <div class="app">
    <TopBar />
    <SignalBanner />
    <TypeCounterBar />
    <main class="body">
      <TaskWorkbench />
      <SidePanel />
    </main>
    <Toast />
  </div>
</template>

<style scoped lang="scss">
.app {
  height: 100vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: $bg;
  /* 仅桌面（spec §5.4：最小宽度 ~1200px），窄屏给出横向滚动而不是挤压布局 */
  min-width: 1200px;
}

.body {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 340px;
  gap: 12px;
  padding: 12px 18px 18px;
}
</style>
