<template>
  <div>
    <el-card>
      <template #header>
        <div class="bar">
          <span>组件身份工作区（声明 → 候选 → 已确认）</span>
          <el-radio-group v-model="status" size="small" @change="load">
            <el-radio-button value="">全部</el-radio-button>
            <el-radio-button value="CONFIRMED">已确认</el-radio-button>
            <el-radio-button value="CANDIDATE">候选</el-radio-button>
            <el-radio-button value="CONFLICT">来源冲突</el-radio-button>
            <el-radio-button value="INSUFFICIENT_EVIDENCE">证据不足</el-radio-button>
          </el-radio-group>
        </div>
      </template>
      <el-table :data="rows" size="small" stripe>
        <el-table-column label="坐标" min-width="220">
          <template #default="{ row }">
            <code>{{ coord(row) }}</code>
          </template>
        </el-table-column>
        <el-table-column prop="publisher" label="发布者" width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.publisher || '-' }}</template>
        </el-table-column>
        <el-table-column label="哈希" width="120">
          <template #default="{ row }">
            <code v-if="row.hashValue">{{ row.hashValue.substring(0, 10) }}</code><span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }"><StatusTag :value="row.status" /></template>
        </el-table-column>
        <el-table-column prop="versionNo" label="身份版本" width="80" align="center" />
        <el-table-column label="冲突原因" min-width="200">
          <template #default="{ row }">
            <el-text v-if="row.conflictReason" type="danger" size="small">{{ row.conflictReason }}</el-text>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">声明</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-drawer v-model="drawer" title="身份与声明关系" size="50%">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="坐标" :span="2">
            <code>{{ coord(detail.identity) }}</code>
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <StatusTag :value="detail.identity.status" />
          </el-descriptions-item>
          <el-descriptions-item label="身份版本">v{{ detail.identity.versionNo }}</el-descriptions-item>
          <el-descriptions-item label="发布者">{{ detail.identity.publisher || '-' }}</el-descriptions-item>
          <el-descriptions-item label="哈希">
            <code>{{ detail.identity.hashValue || '-' }}</code>
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.identity.conflictReason" label="冲突原因" :span="2">
            <el-text type="danger">{{ detail.identity.conflictReason }}</el-text>
          </el-descriptions-item>
        </el-descriptions>
        <h4>支撑声明（{{ detail.claims.length }}）</h4>
        <el-table :data="detail.claims" size="small">
          <el-table-column label="来源" width="120">
            <template #default="{ row }"><code>{{ row.sourceArtifactId.substring(0, 8) }}</code></template>
          </el-table-column>
          <el-table-column label="证据类型" width="100">
            <template #default="{ row }"><StatusTag :value="row.kind" /></template>
          </el-table-column>
          <el-table-column prop="publisher" label="发布者" width="130">
            <template #default="{ row }">{{ row.publisher || '-' }}</template>
          </el-table-column>
          <el-table-column label="哈希" min-width="110">
            <template #default="{ row }">
              <code v-if="row.hashValue">{{ row.hashValue.substring(0, 12) }}</code><span v-else>-</span>
            </template>
          </el-table-column>
          <el-table-column prop="buildContext" label="构建上下文" min-width="140" show-overflow-tooltip />
        </el-table>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { get } from '../api/client'
import StatusTag from '../components/StatusTag.vue'

const rows = ref<any[]>([])
const status = ref('')
const drawer = ref(false)
const detail = ref<any>(null)

function coord(i: any) {
  return `${i.ecosystem || 'unknown'}:${i.namespace ? i.namespace + '/' : ''}${i.name}@${i.version || '?'}`
}

async function load() {
  rows.value = await get('/identities', { status: status.value || undefined })
}

async function openDetail(row: any) {
  detail.value = await get(`/identities/${row.id}`)
  drawer.value = true
}

onMounted(load)
</script>

<style scoped>
.bar { display: flex; justify-content: space-between; align-items: center; }
</style>
