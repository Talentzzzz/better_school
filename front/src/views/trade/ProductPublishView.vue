<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { createProduct } from '@/api/trade'
import type { TradeCategory } from '@/types/api'
import { TRADE_CATEGORIES } from '@/utils/trade'
import { toast } from '@/components/Toast'
import NavBar from '@/components/NavBar.vue'
import ImageUrlPicker from '@/components/ImageUrlPicker.vue'

const router = useRouter()

const title = ref('')
const description = ref('')
const price = ref('')
const tradeLocation = ref('')
const category = ref<TradeCategory>('DIGITAL')
const images = ref<string[]>([])
const submitting = ref(false)

const categories = TRADE_CATEGORIES

async function onSubmit() {
  if (!title.value.trim()) {
    toast('请填写商品标题')
    return
  }
  const priceNum = Number(price.value)
  if (!priceNum || priceNum <= 0) {
    toast('价格需大于 0')
    return
  }
  if (!tradeLocation.value.trim()) {
    toast('请填写交易地点')
    return
  }
  submitting.value = true
  try {
    const id = await createProduct({
      title: title.value.trim(),
      description: description.value.trim(),
      images: images.value,
      price: priceNum,
      tradeLocation: tradeLocation.value.trim(),
      category: category.value
    })
    toast('发布成功')
    router.replace(`/product/${id}`)
  } catch {
    // toast 已处理
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="page--plain publish-form">
    <NavBar title="发布闲置" />
    <div class="form-body">
      <div class="form-card">
        <div class="form-label">标题</div>
        <input v-model="title" class="form-input" maxlength="30" placeholder="例如：考研数学全套资料" />
        <div class="form-label" style="margin-top: 14px">描述</div>
        <textarea
          v-model="description"
          class="form-textarea"
          maxlength="500"
          placeholder="描述宝贝状况、入手渠道、转手原因等（可选）"
        />
      </div>

      <div class="form-card">
        <div class="form-label">分类</div>
        <div class="form-chips">
          <button
            v-for="c in categories"
            :key="c.value"
            class="form-chip"
            :class="{ active: category === c.value }"
            @click="category = c.value"
          >
            {{ c.label }}
          </button>
        </div>
      </div>

      <div class="form-card">
        <div class="price-row">
          <span class="form-label price-label">价格（元）</span>
          <div class="price-input-wrap">
            <span class="price-symbol">¥</span>
            <input v-model="price" class="price-input" type="number" min="0.01" step="0.01" placeholder="0.00" />
          </div>
        </div>
        <div class="form-label" style="margin-top: 14px">交易地点</div>
        <input v-model="tradeLocation" class="form-input" maxlength="100" placeholder="例如：图书馆一楼" />
      </div>

      <div class="form-card">
        <div class="form-label">图片（可选，粘贴图片链接）</div>
        <ImageUrlPicker v-model="images" />
      </div>

      <button class="btn-primary submit-btn" :disabled="submitting" @click="onSubmit">
        {{ submitting ? '发布中…' : '发布' }}
      </button>
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

.form-label {
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 600;
}

.form-chips {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.form-chip {
  padding: 6px 14px;
  border-radius: $radius-full;
  background: $bg-secondary;
  color: $text-2;
  font-size: 13px;

  &.active {
    background: $primary;
    color: #fff;
    font-weight: 500;
  }
}

.form-input {
  width: 100%;
  font-size: 14px;
}

.form-textarea {
  width: 100%;
  min-height: 88px;
  resize: vertical;
  font-size: 14px;
  line-height: 1.7;
}

.price-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.price-label {
  margin-bottom: 0;
}

.price-input-wrap {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 8px 12px;
  border-radius: $radius;
  background: $bg-search;
}

.price-symbol {
  color: $primary;
  font-weight: 600;
}

.price-input {
  width: 96px;
  font-size: 17px;
  font-weight: 600;
  color: $primary;
}

.submit-btn {
  margin-top: 4px;
  width: 100%;
  height: 44px;
  font-size: 15px;
}
</style>
