<template>
  <div>
    <el-card>
      <template #header>
        <div class="bar">
          <span>来源保全册（原始载荷不可改写）</span>
          <div>
            <el-select v-model="filterType" placeholder="全部类型" clearable size="small"
                       style="width: 180px; margin-right: 8px" @change="load">
              <el-option v-for="t in types" :key="t" :label="typeLabel(t)" :value="t" />
            </el-select>
            <el-input v-model="filterBuild" placeholder="按构建标识过滤" size="small" clearable
                      style="width: 200px; margin-right: 8px" @change="load" />
            <el-button type="primary" size="small" :disabled="!auth.isAdmin" @click="dialog = true">
              摄入来源
            </el-button>
          </div>
        </div>
      </template>
      <el-table :data="rows" size="small" stripe>
        <el-table-column label="类型" width="150">
          <template #default="{ row }">{{ typeLabel(row.type) }}</template>
        </el-table-column>
        <el-table-column prop="ecosystem" label="生态" width="80">
          <template #default="{ row }">{{ row.ecosystem || '-' }}</template>
        </el-table-column>
        <el-table-column prop="buildId" label="构建标识" width="180" show-overflow-tooltip />
        <el-table-column label="载荷哈希" width="130">
          <template #default="{ row }">
            <code>{{ row.payloadHash?.substring(0, 12) }}</code>
          </template>
        </el-table-column>
        <el-table-column label="接收状态" width="120">
          <template #default="{ row }"><StatusTag :value="row.ingestStatus" /></template>
        </el-table-column>
        <el-table-column prop="parserVersion" label="解析器" width="100" />
        <el-table-column label="采集时间" width="170">
          <template #default="{ row }">{{ fmt(row.collectedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialog" title="摄入来源" width="640px">
      <el-form label-width="90px">
        <el-form-item label="类型">
          <el-select v-model="form.type" style="width: 100%">
            <el-option v-for="t in types" :key="t" :label="typeLabel(t)" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="生态">
          <el-input v-model="form.ecosystem" placeholder="npm / maven / pypi / golang，可留空" />
        </el-form-item>
        <el-form-item label="构建标识">
          <el-input v-model="form.buildId" placeholder="关联的构建 ID" />
        </el-form-item>
        <el-form-item label="来源地址">
          <el-input v-model="form.sourceUri" placeholder="https://..." />
        </el-form-item>
        <el-form-item label="事件时间">
          <el-input v-model="form.eventTime" placeholder="ISO-8601，留空为当前时间" />
        </el-form-item>
        <el-form-item label="原始载荷">
          <el-input v-model="form.payload" type="textarea" :rows="8"
                    placeholder="SBOM / 锁文件 / 构建日志等原始内容" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">提交</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="drawer" title="来源详情" size="55%">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="类型">{{ typeLabel(detail.artifact.type) }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <StatusTag :value="detail.artifact.ingestStatus" />
          </el-descriptions-item>
          <el-descriptions-item label="载荷哈希" :span="2">
            <code>{{ detail.artifact.payloadHash }}</code>
          </el-descriptions-item>
          <el-descriptions-item label="来源地址" :span="2">
            {{ detail.artifact.sourceUri || '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="事件时间">{{ fmt(detail.artifact.eventTime) }}</el-descriptions-item>
          <el-descriptions-item label="采集时间">{{ fmt(detail.artifact.collectedAt) }}</el-descriptions-item>
          <el-descriptions-item label="解析器版本">{{ detail.artifact.parserVersion }}</el-descriptions-item>
          <el-descriptions-item label="构建标识">{{ detail.artifact.buildId || '-' }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.artifact.note" label="待核项/警告" :span="2">
            <el-text type="warning">{{ detail.artifact.note }}</el-text>
          </el-descriptions-item>
        </el-descriptions>
        <h4>提取的组件声明（{{ detail.claims.length }}）</h4>
        <el-table :data="detail.claims" size="small" max-height="260">
          <el-table-column prop="name" label="名称" width="140" />
          <el-table-column prop="version" label="版本" width="90" />
          <el-table-column prop="publisher" label="发布者" width="130" show-overflow-tooltip />
          <el-table-column label="哈希" width="110">
            <template #default="{ row }">
              <code v-if="row.hashValue">{{ row.hashValue.substring(0, 10) }}</code><span v-else>-</span>
            </template>
          </el-table-column>
          <el-table-column label="证据类型" width="100">
            <template #default="{ row }"><StatusTag :value="row.kind" /></template>
          </el-table-column>
        </el-table>
        <h4>原始载荷</h4>
        <pre class="payload">{{ detail.artifact.payload }}</pre>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { get, post } from '../api/client'
import { useAuthStore } from '../stores/auth'
import StatusTag from '../components/StatusTag.vue'

const auth = useAuthStore()
const types = ['SBOM', 'LOCKFILE', 'BUILD_LOG', 'REPO_METADATA', 'SOURCE_SCAN',
  'SIGNATURE_INDEX', 'COMPONENT_CATALOG', 'VULN_ADVISORY']
const labels: Record<string, string> = {
  SBOM: 'SBOM', LOCKFILE: '锁文件', BUILD_LOG: '构建日志', REPO_METADATA: '包仓元数据',
  SOURCE_SCAN: '源码扫描', SIGNATURE_INDEX: '签名索引', COMPONENT_CATALOG: '组件目录',
  VULN_ADVISORY: '漏洞通告'
}
const typeLabel = (t: string) => labels[t] || t

const rows = ref<any[]>([])
const filterType = ref('')
const filterBuild = ref('')
const dialog = ref(false)
const drawer = ref(false)
const submitting = ref(false)
const detail = ref<any>(null)
const form = ref({ type: 'SBOM', ecosystem: '', buildId: '', sourceUri: '', eventTime: '', payload: '' })

function fmt(t: string) {
  return t ? new Date(t).toLocaleString() : '-'
}

async function load() {
  rows.value = await get('/sources', {
    type: filterType.value || undefined,
    buildId: filterBuild.value || undefined
  })
}

async function openDetail(row: any) {
  detail.value = await get(`/sources/${row.id}`)
  drawer.value = true
}

async function submit() {
  submitting.value = true
  try {
    await post('/sources', { ...form.value, eventTime: form.value.eventTime || undefined })
    ElMessage.success('已摄入并保全')
    dialog.value = false
    form.value.payload = ''
    await load()
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || e.message)
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.bar { display: flex; justify-content: space-between; align-items: center; }
.payload {
  background: #1f2d3d;
  color: #c0e0ff;
  padding: 12px;
  border-radius: 4px;
  font-size: 12px;
  overflow: auto;
  max-height: 300px;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
