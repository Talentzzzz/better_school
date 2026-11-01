<script setup lang="ts">
import { onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { login, register, sendCode } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import { toast } from '@/components/Toast'
import { isCode, isPhone } from '@/utils/validate'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const mode = ref<'login' | 'register'>('login')
const phone = ref('')
const code = ref('')
const nickname = ref('')
const counting = ref(0)
const submitting = ref(false)
let timer: number | null = null

function clearTimer() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}
onUnmounted(clearTimer)

function startCount() {
  counting.value = 60
  timer = window.setInterval(() => {
    counting.value--
    if (counting.value <= 0) clearTimer()
  }, 1000)
}

async function onSendCode() {
  if (!isPhone(phone.value)) {
    toast('请输入正确的手机号')
    return
  }
  try {
    await sendCode(phone.value)
    toast('验证码已发送（演示环境请查看后端日志 [SMS-mock]）')
    startCount()
  } catch {
    // toast 已弹
  }
}

async function onSubmit() {
  if (!isPhone(phone.value)) {
    toast('请输入正确的手机号')
    return
  }
  if (!isCode(code.value)) {
    toast('请输入 6 位验证码')
    return
  }
  if (mode.value === 'register' && !nickname.value.trim()) {
    toast('请输入昵称')
    return
  }
  submitting.value = true
  try {
    const res =
      mode.value === 'login'
        ? await login(phone.value, code.value)
        : await register(phone.value, code.value, nickname.value.trim())
    auth.setAuth(res.token, res.userInfo)
    toast('登录成功')
    const redirect = (route.query.redirect as string) || '/home'
    router.replace(redirect)
  } catch {
    // 错误 toast 已由 http 层处理
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="page--plain login-page">
    <div class="login-header">
      <div class="login-logo">
        <svg viewBox="0 0 48 48" width="44" height="44">
          <rect x="4" y="4" width="40" height="40" rx="12" fill="#FF2442" />
          <path d="M14 26c4 6 8 8 10 0s6-6 10 0" stroke="#fff" stroke-width="3.4" fill="none" stroke-linecap="round" />
        </svg>
      </div>
      <h1 class="login-title">校园生活</h1>
      <p class="login-sub">{{ mode === 'login' ? '登录后发布内容、接单跑腿' : '注册新账号，开启校园生活' }}</p>
    </div>

    <div class="login-form">
      <div class="field">
        <input v-model="phone" type="tel" maxlength="11" placeholder="请输入手机号" @keyup.enter="onSubmit" />
      </div>
      <div class="field field--code">
        <input v-model="code" type="tel" maxlength="6" placeholder="请输入 6 位验证码" @keyup.enter="onSubmit" />
        <button class="code-btn" :disabled="counting > 0" @click="onSendCode">
          {{ counting > 0 ? `${counting}s` : '获取验证码' }}
        </button>
      </div>
      <div v-if="mode === 'register'" class="field">
        <input v-model="nickname" maxlength="20" placeholder="给自己起个昵称（20 字以内）" />
      </div>

      <button class="btn-primary submit-btn" :disabled="submitting" @click="onSubmit">
        {{ submitting ? '提交中…' : mode === 'login' ? '登录' : '注册并登录' }}
      </button>

      <div class="mode-switch">
        <template v-if="mode === 'login'">没有账号？
          <button class="mode-link" @click="mode = 'register'">去注册</button>
        </template>
        <template v-else>已有账号？
          <button class="mode-link" @click="mode = 'login'">去登录</button>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.login-page {
  display: flex;
  flex-direction: column;
  padding: 0 32px;
}

.login-header {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 84px 0 40px;
}

.login-title {
  margin-top: 14px;
  font-size: 24px;
  font-weight: 700;
}

.login-sub {
  margin-top: 8px;
  color: $text-3;
  font-size: 13px;
}

.login-form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.field {
  display: flex;
  align-items: center;
  height: 48px;
  padding: 0 16px;
  border-radius: $radius;
  background: $bg-search;

  input {
    flex: 1;
    min-width: 0;
    font-size: 15px;
  }
}

.code-btn {
  flex-shrink: 0;
  padding: 6px 12px;
  color: $primary;
  font-size: 13px;
  font-weight: 500;

  &:disabled {
    color: $text-4;
  }
}

.submit-btn {
  margin-top: 10px;
  width: 100%;
  height: 46px;
  font-size: 16px;
}

.mode-switch {
  text-align: center;
  color: $text-3;
  font-size: 13px;
}

.mode-link {
  color: $primary;
  font-weight: 500;
}
</style>
