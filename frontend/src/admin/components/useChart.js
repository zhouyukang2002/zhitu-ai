import * as echarts from 'echarts'
import { onMounted, onBeforeUnmount, watch } from 'vue'

// ECharts 轻封装：苹果风格默认项 + 容器自适应 + 卸载清理
export function useChart(elRef, optionRef, { deps = [] } = {}) {
  let chart = null
  const resize = () => chart?.resize()

  const render = () => {
    if (!elRef.value) return
    if (!chart) {
      chart = echarts.init(elRef.value)
      window.addEventListener('resize', resize)
    }
    chart.setOption({
      animationDuration: 400,
      textStyle: { fontFamily: '-apple-system, PingFang SC, Microsoft YaHei, sans-serif' },
      tooltip: {
        backgroundColor: 'rgba(29, 29, 31, 0.92)',
        borderWidth: 0,
        textStyle: { color: '#f5f5f7', fontSize: 12 },
        padding: [8, 12],
      },
      ...optionRef.value,
    })
  }

  onMounted(render)
  watch([optionRef, ...deps], render, { deep: true })
  onBeforeUnmount(() => {
    window.removeEventListener('resize', resize)
    chart?.dispose()
    chart = null
  })
  return { rerender: render }
}

// 项目色板（与设计令牌一致）
export const CHART_COLORS = {
  blue: '#0071e3',
  blueLight: '#4c9df0',
  green: '#34c759',
  orange: '#ff9500',
  red: '#ff3b30',
  purple: '#af52de',
  teal: '#30b0c7',
  indigo: '#5856d8',
  gray: '#8e8e93',
  grid: 'rgba(0,0,0,0.06)',
  label: '#6e6e73',
}
