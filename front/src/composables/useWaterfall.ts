import { onUnmounted, ref, watch, type Ref } from 'vue'

export interface WaterfallPage<T> {
  list: T[]
  total: number
  /** 非 null = 下一页必须带它走 keyset 续页（主页 feed / 市集推荐页）；undefined/null = 按 page 续页 */
  cursor?: string | null
}

export interface UseWaterfall<T> {
  list: Ref<T[]>
  loading: Ref<boolean>
  finished: Ref<boolean>
  /** 是否已成功加载过（页面据此判断「首次进入该 tab 才 load()」） */
  loaded: Ref<boolean>
  sentinel: Ref<HTMLDivElement | null>
  load: () => Promise<void>
  reset: () => void
}

/**
 * 无限滚动列表：每 tab 一个实例，各自持有哨兵 / observer / 分页 / finished。
 * 不在挂载时自动加载——由页面在「首次进入该 tab」时显式 load()。
 *
 * 游标分页（feed-index-uv 票 04，市集推荐页沿用 market-feed-uv 票 04）：fetchPage 收到 (page, cursor)，
 * 响应带 cursor 时下一页改走 keyset 续页（page 仍随行，供降级响应兜底）；total = -1（未知）时不参与 finished 判断。
 */
export function useWaterfall<T>(
  fetchPage: (page: number, cursor?: string) => Promise<WaterfallPage<T>>,
  opts: { pageSize?: number } = {}
): UseWaterfall<T> {
  const pageSize = opts.pageSize ?? 10

  const list = ref([]) as Ref<T[]>
  const loading = ref(false)
  const finished = ref(false)
  const loaded = ref(false)
  const sentinel = ref<HTMLDivElement | null>(null)

  let page = 1
  let cursor: string | null = null
  let observer: IntersectionObserver | null = null

  async function load() {
    if (loading.value || finished.value) return
    loading.value = true
    try {
      const res = await fetchPage(page, cursor ?? undefined)
      cursor = res.cursor ?? null
      list.value = page === 1 ? res.list : [...list.value, ...res.list]
      finished.value = res.list.length < pageSize || (res.total >= 0 && list.value.length >= res.total)
      if (res.list.length > 0) page += 1
      loaded.value = true
    } finally {
      loading.value = false
    }
  }

  /** 筛选/排序变更：清空分页重拉，并把 window 滚动归零 */
  function reset() {
    page = 1
    cursor = null
    list.value = []
    finished.value = false
    loaded.value = false
    window.scrollTo(0, 0)
  }

  if (typeof IntersectionObserver !== 'undefined') {
    observer = new IntersectionObserver(
      (entries) => {
        if (entries[0]?.isIntersecting) load()
      },
      { rootMargin: '250px' }
    )
    // 哨兵随 tab 切换增删（v-if），出现即重新观察
    watch(
      sentinel,
      (el) => {
        observer?.disconnect()
        if (el) observer?.observe(el)
      },
      { immediate: true }
    )
  }
  onUnmounted(() => observer?.disconnect())

  return { list, loading, finished, loaded, sentinel, load, reset }
}
