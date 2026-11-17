<template>
  <div>
    <el-row :gutter="16">
      <el-col :span="14">
        <el-card>
          <template #header>变动监测事件</template>
          <el-timeline v-if="events.length" style="padding-left: 4px">
            <el-timeline-item v-for="e in events" :key="e.id" :timestamp="fmt(e.createdAt)"
                              :type="timelineType(e.eventType)">
              <el-tag size="small" style="margin-right: 6px">{{ typeLabel(e.eventType) }}</el-tag>
              {{ e.detail }}
              <div v-if="affected(e).length" style="margin-top: 4px; color: #909399; font-size: 12px">
                受影响视图：{{ affected(e).length }} 个
              </div>
            </el-timeline-item>
          </el-timeline>
          <el-empty v-else description="暂无监测事件" />
        </el-card>
      </el-col>
      <el-col :span="10">
        <el-card>
          <template #header>签名索引（可撤销）</template>
          <el-table :data="signatures" size="small" stripe>
            <el-table-column label="主体哈希" width="120">
              <template #default="{ row }"><code>{{ row.subjectHash.substring(0, 10) }}</code></template>
            </el-table-column>
            <el-table-column prop="signer" label="签名者" min-width="120" show-overflow-tooltip />
            <el-table-column label="状态" width="90">
              <template #default="{ row }"><StatusTag :value="row.status" /></template>
            </el-table-column>
            <el-table-column label="操作" width="80">
              <template #default="{ row }">
                <el-button link type="danger" size="small"
                           :disabled="!auth.isAdmin || row.status === 'REVOKED'"
                           @click="revoke(row)">撤销</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!signatures.length" description="暂无签名记录" :image-size="60" />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { get, post } from '../api/client'
import { useAuthStore } from '../stores/auth'
import StatusTag from '../components/StatusTag.vue'

const auth = useAuthStore()
const events = ref<any[]>([])
const signatures = ref<any[]>([])

function fmt(t: string) {
  return t ? new Date(t).toLocaleString() : ''
}
function typeLabel(t: string) {
  return ({ CATALOG_REVISION: '目录修订', SIGNATURE_REVOKED: '签名撤销', LATE_SOURCE: '补传来源' } as any)[t] || t
}
function timelineType(t: string) {
  return t === 'SIGNATURE_REVOKED' ? 'danger' : t === 'CATALOG_REVISION' ? 'warning' : 'primary'
}
function affected(e: any): string[] {
  try { return JSON.parse(e.affectedViews || '[]') } catch { return [] }
}

async function load() {
  events.value = await get('/monitor/events')
  signatures.value = await get('/signatures')
}

async function revoke(row: any) {
  await ElMessageBox.confirm(
    '撤销签名将使以其为唯一依据的组件降为证据不足，且新事实不能继续显示已证实。确认撤销？',
    '撤销签名', { type: 'warning' })
  await post(`/signatures/${row.id}/revoke`)
  ElMessage.success('已撤销，监测事件已记录')
  await load()
}

onMounted(load)
</script>
