<template>
  <div>
    <el-card>
      <template #header>
        <div class="bar">
          <span>依赖事实编译（已证实边 vs 候选边）</span>
          <div>
            <el-select v-model="buildId" placeholder="选择构建" size="small" filterable
                       style="width: 260px; margin-right: 8px" @change="load">
              <el-option v-for="b in builds" :key="b" :label="b" :value="b" />
            </el-select>
            <el-button type="primary" size="small" :disabled="!buildId || !auth.isAdmin"
                       :loading="compiling" @click="compile">运行编译器</el-button>
          </div>
        </div>
      </template>
      <template v-if="buildId">
        <el-alert v-if="run" type="info" :closable="false" style="margin-bottom: 12px">
          最近编译：规则版本 {{ run.ruleVersion }} ｜ {{ run.summary }} ｜ {{ fmt(run.startedAt) }}
        </el-alert>
        <el-table :data="edges" size="small" stripe>
          <el-table-column prop="fromKey" label="依赖源" width="200" show-overflow-tooltip />
          <el-table-column prop="toLabel" label="目标组件" min-width="240" show-overflow-tooltip />
          <el-table-column prop="scope" label="范围" width="90" />
          <el-table-column label="边状态" width="110">
            <template #default="{ row }"><StatusTag :value="row.edgeStatus" /></template>
          </el-table-column>
          <el-table-column prop="evidenceSummary" label="证据摘要" min-width="300" show-overflow-tooltip />
        </el-table>
        <el-empty v-if="!edges.length" description="该构建暂无依赖边，请先摄入来源并运行编译器" />
      </template>
      <el-empty v-else description="请选择构建标识" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { get, post } from '../api/client'
import { useAuthStore } from '../stores/auth'
import StatusTag from '../components/StatusTag.vue'

const auth = useAuthStore()
const builds = ref<string[]>([])
const buildId = ref('')
const edges = ref<any[]>([])
const run = ref<any>(null)
const compiling = ref(false)

function fmt(t: string) {
  return t ? new Date(t).toLocaleString() : '-'
}

async function load() {
  if (!buildId.value) return
  edges.value = await get('/edges', { buildId: buildId.value })
  const runs = await get('/compile-runs', { buildId: buildId.value })
  run.value = runs[0] || null
}

async function compile() {
  compiling.value = true
  try {
    const result = await post(`/compile/${buildId.value}`)
    ElMessage.success(`编译完成：${result.run.summary}`)
    await load()
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || e.message)
  } finally {
    compiling.value = false
  }
}

onMounted(async () => {
  builds.value = await get('/dashboard/builds')
})
</script>

<style scoped>
.bar { display: flex; justify-content: space-between; align-items: center; }
</style>
