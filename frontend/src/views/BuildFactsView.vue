<template>
  <div>
    <el-card>
      <template #header>
        <div class="bar">
          <span>构建事实签发（截止点冻结，不可改写）</span>
          <el-button type="primary" size="small" :disabled="!auth.isAdmin" @click="openIssue">
            签发构建事实
          </el-button>
        </div>
      </template>
      <el-table :data="facts" size="small" stripe>
        <el-table-column prop="buildId" label="构建标识" min-width="180" show-overflow-tooltip />
        <el-table-column label="事实版本" width="90" align="center">
          <template #default="{ row }">v{{ row.factVersion }}</template>
        </el-table-column>
        <el-table-column label="截止点" width="170">
          <template #default="{ row }">{{ fmt(row.cutoffTime) }}</template>
        </el-table-column>
        <el-table-column label="签发时间" width="170">
          <template #default="{ row }">{{ fmt(row.issuedAt) }}</template>
        </el-table-column>
        <el-table-column prop="ruleVersion" label="规则版本" width="110" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }"><StatusTag :value="row.status" /></template>
        </el-table-column>
        <el-table-column label="后续差异来源" width="110" align="center">
          <template #default="{ row }">{{ lateCount(row) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openTrace(row)">追溯</el-button>
            <el-button link type="primary" size="small" @click="openExplain(row)">解释</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="issueDialog" title="签发构建事实" width="480px">
      <el-form label-width="90px">
        <el-form-item label="构建标识">
          <el-select v-model="issueBuild" filterable allow-create style="width: 100%">
            <el-option v-for="b in builds" :key="b" :label="b" :value="b" />
          </el-select>
        </el-form-item>
        <el-form-item label="截止点">
          <el-input v-model="issueCutoff" placeholder="ISO-8601，留空为当前时间" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="issueDialog = false">取消</el-button>
        <el-button type="primary" :loading="issuing" @click="issue">签发</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="traceDrawer" title="构建事实全链路追溯" size="70%">
      <template v-if="trace">
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="构建">{{ trace.fact.buildId }}</el-descriptions-item>
          <el-descriptions-item label="事实版本">v{{ trace.fact.factVersion }}</el-descriptions-item>
          <el-descriptions-item label="规则版本">{{ trace.fact.ruleVersion }}</el-descriptions-item>
          <el-descriptions-item label="截止点">{{ fmt(trace.fact.cutoffTime) }}</el-descriptions-item>
          <el-descriptions-item label="签发时间">{{ fmt(trace.fact.issuedAt) }}</el-descriptions-item>
          <el-descriptions-item label="状态"><StatusTag :value="trace.fact.status" /></el-descriptions-item>
        </el-descriptions>

        <el-tabs style="margin-top: 12px">
          <el-tab-pane :label="`依赖边 (${trace.edges.length})`">
            <el-table :data="trace.edges" size="small" max-height="300">
              <el-table-column prop="toLabel" label="目标组件" min-width="220" show-overflow-tooltip />
              <el-table-column label="状态" width="100">
                <template #default="{ row }"><StatusTag :value="row.edgeStatus" /></template>
              </el-table-column>
              <el-table-column prop="evidenceSummary" label="证据摘要" min-width="280" show-overflow-tooltip />
            </el-table>
          </el-tab-pane>
          <el-tab-pane :label="`冻结身份 (${trace.identities.length})`">
            <el-table :data="trace.identities" size="small" max-height="300">
              <el-table-column label="坐标" min-width="200">
                <template #default="{ row }"><code>{{ row.frozen.coordinate }}</code></template>
              </el-table-column>
              <el-table-column label="冻结状态" width="100">
                <template #default="{ row }"><StatusTag :value="row.frozen.status" /></template>
              </el-table-column>
              <el-table-column label="冻结版本" width="90" align="center">
                <template #default="{ row }">v{{ row.frozen.versionNo }}</template>
              </el-table-column>
              <el-table-column label="当前状态" width="100">
                <template #default="{ row }">
                  <StatusTag v-if="row.current" :value="row.current.status" />
                </template>
              </el-table-column>
              <el-table-column label="声明数" width="80" align="center">
                <template #default="{ row }">{{ row.claims?.length ?? 0 }}</template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
          <el-tab-pane :label="`来源对象 (${trace.sources.length})`">
            <el-table :data="trace.sources" size="small" max-height="300">
              <el-table-column label="角色" width="170">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.role === 'post-cutoff-difference' ? 'warning' : 'success'">
                    {{ row.role === 'post-cutoff-difference' ? '后续差异来源' : '截止点可见' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="artifact.type" label="类型" width="140" />
              <el-table-column label="哈希" width="130">
                <template #default="{ row }"><code>{{ row.artifact.payloadHash.substring(0, 12) }}</code></template>
              </el-table-column>
              <el-table-column label="采集时间" width="170">
                <template #default="{ row }">{{ fmt(row.artifact.collectedAt) }}</template>
              </el-table-column>
              <el-table-column prop="artifact.sourceUri" label="来源地址" min-width="200" show-overflow-tooltip />
            </el-table>
          </el-tab-pane>
          <el-tab-pane :label="`复审事件 (${trace.reviewEvents.length})`">
            <el-timeline style="padding-left: 4px">
              <el-timeline-item v-for="(e, i) in trace.reviewEvents" :key="i"
                                :timestamp="fmt(e.createdAt)">
                <el-tag size="small" style="margin-right: 6px">{{ e.subjectType }}</el-tag>
                <b>{{ e.action }}</b> — {{ e.detail }}
              </el-timeline-item>
            </el-timeline>
          </el-tab-pane>
          <el-tab-pane :label="`下游引用 (${trace.consumerViews.length})`">
            <el-table :data="trace.consumerViews" size="small">
              <el-table-column prop="name" label="视图名称" min-width="180" />
              <el-table-column label="类型" width="140">
                <template #default="{ row }">{{ viewTypeLabel(row.viewType) }}</template>
              </el-table-column>
              <el-table-column label="状态" width="100">
                <template #default="{ row }"><StatusTag :value="row.status" /></template>
              </el-table-column>
              <el-table-column prop="suspendReason" label="暂停原因" min-width="220" show-overflow-tooltip />
            </el-table>
            <el-empty v-if="!trace.consumerViews.length" description="暂无下游引用" :image-size="60" />
          </el-tab-pane>
        </el-tabs>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { get, post } from '../api/client'
import { useAuthStore } from '../stores/auth'
import StatusTag from '../components/StatusTag.vue'

const auth = useAuthStore()
const router = useRouter()
const facts = ref<any[]>([])
const builds = ref<string[]>([])
const issueDialog = ref(false)
const issueBuild = ref('')
const issueCutoff = ref('')
const issuing = ref(false)
const traceDrawer = ref(false)
const trace = ref<any>(null)

function fmt(t: string) {
  return t ? new Date(t).toLocaleString() : '-'
}
function lateCount(row: any) {
  try {
    return row.postCutoffSources ? JSON.parse(row.postCutoffSources).length : 0
  } catch { return 0 }
}
function viewTypeLabel(t: string) {
  return ({ RISK_VIEW: '风险视图', ASSET_INVENTORY: '资产清单', AUDIT_VIEW: '审计视图' } as any)[t] || t
}

async function load() {
  facts.value = await get('/build-facts')
  builds.value = await get('/dashboard/builds')
}

async function openIssue() {
  issueDialog.value = true
  if (!builds.value.length) builds.value = await get('/dashboard/builds')
}

async function issue() {
  if (!issueBuild.value) {
    ElMessage.warning('请选择构建标识')
    return
  }
  issuing.value = true
  try {
    await post('/build-facts/issue', {
      buildId: issueBuild.value,
      cutoffTime: issueCutoff.value || undefined
    })
    ElMessage.success('已签发')
    issueDialog.value = false
    await load()
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || e.message)
  } finally {
    issuing.value = false
  }
}

async function openTrace(row: any) {
  trace.value = await get(`/build-facts/${row.id}/trace`)
  traceDrawer.value = true
}

/** 从历史结果的一行进入时点复演与解释。 */
function openExplain(row: any) {
  router.push({ path: '/replay', query: { buildId: row.buildId, cutoff: row.cutoffTime } })
}

onMounted(load)
</script>

<style scoped>
.bar { display: flex; justify-content: space-between; align-items: center; }
</style>
