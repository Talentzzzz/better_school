<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { createErrandOrder, saveErrandDraft } from '@/api/errand'
import { getMyCredit } from '@/api/credit'
import { getBalance } from '@/api/wallet'
import type { ErrandType } from '@/types/api'
import { toast } from '@/components/Toast'
import NavBar from '@/components/NavBar.vue'
import ImageUrlPicker from '@/components/ImageUrlPicker.vue'

const router = useRouter()

const type = ref<ErrandType>('EXPRESS_PICKUP')
const title = ref('')
const description = ref('')
const pickupLocation = ref('')
const deliveryLocation = ref('')
const reward = ref('20')
const deadline = ref('')
const images = ref<string[]>([])
const submitting = ref(false)
const checking = ref(true)
const creditOk = ref(true)

const types: { value: ErrandType; label: string }[] = [
  { value: 'EXPRESS_PICKUP', label: '代取快递' },
  { value: 'BUY', label: '代买' },
  { value: 'DELIVER', label: '代送' },
  { value: 'OTHER', label: '其他' }
]

/** 截止时间合法区间（与服务端数据库时钟校验同口径，这里做客户端粗校验） */
const MIN_LEAD_MINUTES = 15
const MAX_LEAD_DAYS = 7

/** datetime-local 值即 ISO 本地时刻（YYYY-MM-DDTHH:mm），后端 LocalDateTime 直解；无值返回 undefined */
function deadlineIsoOrUndefined(): string | undefined {
  return deadline.value || undefined
}

onMounted(async () => {
  try {
    const credit = await getMyCredit()
    creditOk.value = credit.creditScore >= 60
  } catch {
    creditOk.value = false
  } finally {
    checking.value = false
  }
})

/** 存草稿：草稿为服务端私有态（可缺省字段、不付款不对外），稍后可在「我的-跑腿」继续处理 */
async function onSaveDraft() {
  if (submitting.value) return
  submitting.value = true
  try {
    await saveErrandDraft({
      type: type.value,
      title: title.value.trim() || undefined,
      description: description.value.trim() || undefined,
      pickupLocation: pickupLocation.value.trim() || undefined,
      deliveryLocation: deliveryLocation.value.trim() || undefined,
      reward: reward.value ? Number(reward.value) : undefined,
      deadline: deadlineIsoOrUndefined(),
      images: images.value
    })
    toast('已存草稿，可在「我的-跑腿」继续处理')
    router.replace('/me/orders?tab=errand')
  } catch {
    // toast 已处理
  } finally {
    submitting.value = false
  }
}

async function onSubmit() {
  if (!title.value.trim()) {
    toast('请填写订单标题')
    return
  }
  if (!description.value.trim()) {
    toast('请填写任务描述')
    return
  }
  if (!pickupLocation.value.trim() || !deliveryLocation.value.trim()) {
    toast('请填写取货点和送达点')
    return
  }
  const rewardNum = Number(reward.value)
  if (!rewardNum || rewardNum < 10) {
    toast('悬赏不低于 10 校园币')
    return
  }
  if (!deadline.value) {
    toast('请选择截止时间')
    return
  }
  // 客户端粗校验（服务端以数据库时钟为准再校验一次）
  const leadMs = new Date(deadline.value).getTime() - Date.now()
  if (leadMs < MIN_LEAD_MINUTES * 60000) {
    toast('截止时间至少在 15 分钟之后')
    return
  }
  if (leadMs > MAX_LEAD_DAYS * 24 * 60 * 60000) {
    toast('截止时间最远不超过 7 天')
    return
  }
  submitting.value = true
  try {
    // 发布前校验余额（mock 新用户赠送 1000）
    try {
      const bal = await getBalance()
      if (bal.balance < rewardNum) {
        toast('余额不足，请先到钱包充值')
        router.push('/me/wallet')
        return
      }
    } catch {
      // 余额查询失败不阻塞发布（后端会校验）
    }
    const orderId = await createErrandOrder({
      type: type.value,
      title: title.value.trim(),
      description: description.value.trim(),
      pickupLocation: pickupLocation.value.trim(),
      deliveryLocation: deliveryLocation.value.trim(),
      reward: rewardNum,
      deadline: deadline.value,
      images: images.value
    })
    toast('发布成功，支付后自动上架')
    router.replace(`/errand/${orderId}`)
  } catch {
    // toast 已处理
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="page--plain publish-form">
    <NavBar title="发布跑腿" />
    <div class="form-body">
      <div v-if="!checking && !creditOk" class="credit-warn">
        你的信用分低于 60，暂不能发布跑腿。可在「我的-信用分」中查看原因。
      </div>
      <div class="form-card">
        <div class="form-label">跑腿类型</div>
        <div class="form-chips">
          <button
            v-for="t in types"
            :key="t.value"
            class="form-chip"
            :class="{ active: type === t.value }"
            @click="type = t.value"
          >
            {{ t.label }}
          </button>
        </div>
        <div class="form-label" style="margin-top: 14px">订单标题</div>
        <input v-model="title" class="form-input" maxlength="50" placeholder="一句话说清任务，例如：帮取两个快递送 6 号楼" />
        <div class="form-label" style="margin-top: 14px">任务描述</div>
        <textarea
          v-model="description"
          class="form-textarea"
          maxlength="200"
          placeholder="例如：帮取菜鸟驿站两个快递，送到 6 号楼楼下，轻拿轻放"
        />
        <div class="form-label" style="margin-top: 14px">配图（最多 6 张）</div>
        <ImageUrlPicker v-model="images" :max="6" />
      </div>

      <div class="form-card">
        <div class="form-label">取货点</div>
        <input v-model="pickupLocation" class="form-input" maxlength="100" placeholder="例如：菜鸟驿站" />
        <div class="form-label" style="margin-top: 14px">送达点</div>
        <input v-model="deliveryLocation" class="form-input" maxlength="100" placeholder="例如：6 号楼楼下" />
      </div>

      <div class="form-card">
        <div class="reward-row">
          <span class="form-label reward-label">悬赏（校园币）</span>
          <div class="reward-input-wrap">
            <span class="reward-symbol">¥</span>
            <input v-model="reward" class="reward-input" type="number" min="10" step="5" />
          </div>
        </div>
        <div class="reward-hint">支付后冻结托管，完成后自动打给接单者；最低 10</div>
        <div class="form-label" style="margin-top: 14px">截止时间</div>
        <input v-model="deadline" class="form-input" type="datetime-local" />
        <div class="reward-hint">到期自动下架并结算，也是接单者的完工时限；须在 15 分钟 ~ 7 天之后</div>
      </div>

      <div class="publish-actions">
        <button class="btn-outline save-draft-btn" :disabled="submitting" @click="onSaveDraft">存草稿</button>
        <button class="btn-primary submit-btn" :disabled="submitting || checking || !creditOk" @click="onSubmit">
          {{ submitting ? '发布中…' : checking ? '检查信用分…' : '发布并去支付' }}
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

.credit-warn {
  padding: 10px 14px;
  border-radius: $radius;
  background: #fff5f5;
  color: #c0392b;
  font-size: 13px;
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

.form-textarea {
  width: 100%;
  min-height: 88px;
  resize: vertical;
  font-size: 14px;
  line-height: 1.7;
}

.form-input {
  width: 100%;
  font-size: 14px;
}

.reward-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.reward-label {
  margin-bottom: 0;
}

.reward-input-wrap {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 8px 12px;
  border-radius: $radius;
  background: $bg-search;
}

.reward-symbol {
  color: $primary;
  font-weight: 600;
}

.reward-input {
  width: 72px;
  font-size: 17px;
  font-weight: 600;
  color: $primary;
}

.reward-hint {
  margin-top: 8px;
  font-size: 11px;
  color: $text-3;
}

.submit-btn {
  flex: 1;
  height: 44px;
  font-size: 15px;
}

.publish-actions {
  display: flex;
  gap: 10px;
  margin-top: 4px;
}

.save-draft-btn {
  flex-shrink: 0;
  height: 44px;
  padding: 0 18px;
  font-size: 15px;
}
</style>
