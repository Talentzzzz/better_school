<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listFavorites } from '@/api/idle'
import type { Product } from '@/types/api'
import ProductCard from '@/components/ProductCard.vue'
import EmptyState from '@/components/EmptyState.vue'
import NavBar from '@/components/NavBar.vue'

const router = useRouter()
const list = ref<Product[]>([])
const loading = ref(true)

async function load() {
  loading.value = true
  try {
    const res = await listFavorites(1, 50)
    list.value = res.list
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page--plain favorites-page">
    <NavBar title="我的收藏" />
    <div v-if="list.length" class="waterfall">
      <ProductCard v-for="p in list" :key="p.productId" :product="p" />
    </div>
    <EmptyState
      v-else-if="!loading"
      text="还没有收藏任何商品"
      button-text="去市集逛逛"
      @click="router.push('/market')"
    />
  </div>
</template>

<style scoped lang="scss">
.waterfall {
  columns: 2;
  column-gap: 8px;
  padding: 0 8px;

  > * {
    break-inside: avoid;
    margin-bottom: 8px;
  }
}
</style>
