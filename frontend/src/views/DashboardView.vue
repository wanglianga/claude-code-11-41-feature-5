<template>
  <div>
    <el-row :gutter="16">
      <el-col :span="4" v-for="card in cards" :key="card.label">
        <el-card shadow="hover" class="stat-card">
          <div class="num">{{ card.value }}</div>
          <div class="label">{{ card.label }}</div>
        </el-card>
      </el-col>
    </el-row>
    <el-row :gutter="16" style="margin-top: 16px">
      <el-col :span="10">
        <el-card>
          <template #header>组件身份状态分布</template>
          <div ref="chartRef" style="height: 300px"></div>
        </el-card>
      </el-col>
      <el-col :span="14">
        <el-card>
          <template #header>最新变动监测事件</template>
          <el-timeline v-if="events.length">
            <el-timeline-item v-for="e in events" :key="e.id" :timestamp="fmt(e.createdAt)">
              <el-tag size="small" style="margin-right: 6px">{{ typeLabel(e.eventType) }}</el-tag>
              {{ e.detail }}
            </el-timeline-item>
          </el-timeline>
          <el-empty v-else description="暂无监测事件" :image-size="60" />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import * as echarts from 'echarts'
import { get } from '../api/client'

const summary = ref<Record<string, number>>({})
const events = ref<any[]>([])
const chartRef = ref<HTMLElement>()

const cards = computed(() => [
  { label: '来源对象', value: summary.value.sources ?? '-' },
  { label: '组件身份', value: summary.value.identities ?? '-' },
  { label: '依赖边', value: summary.value.edges ?? '-' },
  { label: '构建事实', value: summary.value.buildFacts ?? '-' },
  { label: '下游视图', value: summary.value.views ?? '-' },
  { label: '监测事件', value: summary.value.monitorEvents ?? '-' }
])

function fmt(t: string) {
  return t ? new Date(t).toLocaleString() : ''
}
function typeLabel(t: string) {
  return ({ CATALOG_REVISION: '目录修订', SIGNATURE_REVOKED: '签名撤销', LATE_SOURCE: '补传来源' } as any)[t] || t
}

onMounted(async () => {
  summary.value = await get('/dashboard/summary')
  events.value = (await get('/monitor/events')).slice(0, 8)
  const chart = echarts.init(chartRef.value!)
  chart.setOption({
    tooltip: { trigger: 'item' },
    legend: { bottom: 0 },
    series: [{
      type: 'pie',
      radius: ['40%', '65%'],
      data: [
        { name: '已确认', value: summary.value.identitiesConfirmed, itemStyle: { color: '#67c23a' } },
        { name: '候选', value: summary.value.identitiesCandidate, itemStyle: { color: '#909399' } },
        { name: '来源冲突', value: summary.value.identitiesConflict, itemStyle: { color: '#f56c6c' } },
        { name: '证据不足', value: summary.value.identitiesInsufficient, itemStyle: { color: '#e6a23c' } }
      ]
    }]
  })
})
</script>

<style scoped>
.stat-card { text-align: center; }
.num { font-size: 28px; font-weight: 700; color: #303133; }
.label { color: #909399; font-size: 13px; margin-top: 4px; }
</style>
