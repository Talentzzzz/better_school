<script setup lang="ts">
import { computed, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useChatRoomsStore } from '@/stores/chatRooms'
import { useNoticesStore } from '@/stores/notices'
import { chatWs } from '@/utils/chatWs'
import TabBar from '@/components/TabBar.vue'
import ToastHost from '@/components/ToastHost.vue'
import ConfirmHost from '@/components/ConfirmHost.vue'
import NotifBanner from '@/components/NotifBanner.vue'

const route = useRoute()
const auth = useAuthStore()
const chatRooms = useChatRoomsStore()
const notices = useNoticesStore()

const showTabBar = computed(() => !!route.meta.tabbar)

// 登录即连/登出断开（spec §7.1）
watch(
  () => auth.isLoggedIn,
  (v) => {
    if (v) {
      chatRooms.init()
      notices.init()
      chatWs.connect()
    } else {
      chatRooms.reset()
      notices.reset()
      chatWs.disconnect()
    }
  },
  { immediate: true }
)
</script>

<template>
  <router-view />
  <TabBar v-if="showTabBar" />
  <ToastHost />
  <ConfirmHost />
  <NotifBanner />
</template>
