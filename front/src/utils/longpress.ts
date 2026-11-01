import type { DirectiveBinding } from 'vue'

/**
 * v-longpress 指令：长按 500ms 触发回调；移动超 10px 取消。
 * contextmenu（桌面右键 / 部分移动端长按）兜底，与 touch 定时器去重（800ms 窗口）。
 */
const LONGPRESS_MS = 500
const MOVE_TOLERANCE = 10
const DEDUP_MS = 800

export const vLongpress = {
  mounted(el: HTMLElement, binding: DirectiveBinding<() => void>) {
    let timer: number | null = null
    let startX = 0
    let startY = 0
    let lastFired = 0

    const fire = () => {
      lastFired = Date.now()
      // 吞掉长按后随行的 click（capture 一次性），避免同时触发气泡的点击行为（如卡片跳转）
      el.addEventListener('click', (e) => e.stopImmediatePropagation(), { capture: true, once: true })
      binding.value()
    }

    const clear = () => {
      if (timer !== null) {
        clearTimeout(timer)
        timer = null
      }
    }

    el.addEventListener('touchstart', (e: TouchEvent) => {
      const touch = e.touches[0]
      startX = touch.clientX
      startY = touch.clientY
      clear()
      timer = window.setTimeout(() => {
        timer = null
        fire()
      }, LONGPRESS_MS)
    })
    el.addEventListener('touchmove', (e: TouchEvent) => {
      if (timer === null) return
      const touch = e.touches[0]
      if (Math.abs(touch.clientX - startX) > MOVE_TOLERANCE || Math.abs(touch.clientY - startY) > MOVE_TOLERANCE) {
        clear()
      }
    })
    el.addEventListener('touchend', clear)
    el.addEventListener('touchcancel', clear)
    // 桌面右键兜底；移动端部分浏览器长按也触发 contextmenu，与 touch 定时器去重
    el.addEventListener('contextmenu', (e: Event) => {
      e.preventDefault()
      if (Date.now() - lastFired > DEDUP_MS) fire()
    })
  }
}
