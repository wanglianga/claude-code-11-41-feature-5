<template>
  <div>
    <el-card>
      <template #header>
        <div class="bar">
          <span>新建复演（固定业务时点、资料截止时间、规则与查询条件）</span>
        </div>
      </template>
      <el-form label-width="110px" label-position="left">
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="构建标识">
              <el-select v-model="form.buildId" filterable allow-create style="width: 100%">
                <el-option v-for="b in builds" :key="b" :label="b" :value="b" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="业务时点">
              <el-input v-model="form.businessTime" placeholder="ISO-8601，观察哪时的事实" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="资料截止时间">
              <el-input v-model="form.knowledgeCutoff" placeholder="ISO-8601，当时已收到哪些依据" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="缺席对象">
          <el-input v-model="form.queries" type="textarea" :rows="2"
                    placeholder="每行一个坐标：eco:ns/name@version（eco/版本可省），追问该组件为何没有出现" />
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="11">
            <el-form-item label="否定筛选">
              <el-input v-model="form.excludeScopes" placeholder="排除的 scope，逗号分隔，如 test" />
            </el-form-item>
          </el-col>
          <el-col :span="11">
            <el-form-item label="必填字段">
              <el-input v-model="form.requireFields" placeholder="逗号分隔，如 publisher,hashValue" />
            </el-form-item>
          </el-col>
          <el-col :span="2">
            <el-button type="primary" :loading="running" :disabled="!auth.isAdmin"
                       style="width: 100%" @click="runReplay">复演</el-button>
          </el-col>
        </el-row>
      </el-form>
    </el-card>

    <el-card style="margin-top: 12px">
      <template #header>
        <div class="bar">
          <span>复演任务（原先签发的结果保持可回查）</span>
          <el-button size="small" @click="loadTasks">刷新</el-button>
        </div>
      </template>
      <el-table :data="tasks" size="small" stripe @row-click="selectTask" highlight-current-row>
        <el-table-column prop="buildId" label="构建标识" min-width="150" show-overflow-tooltip />
        <el-table-column label="业务时点" width="165">
          <template #default="{ row }">{{ fmt(row.businessTime) }}</template>
        </el-table-column>
        <el-table-column label="资料截止" width="165">
          <template #default="{ row }">{{ fmt(row.knowledgeCutoff) }}</template>
        </el-table-column>
        <el-table-column prop="summary" label="结论汇总" min-width="240" show-overflow-tooltip />
        <el-table-column label="创建时间" width="165">
          <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="180">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click.stop="selectTask(row)">结果</el-button>
            <el-button link type="primary" size="small" @click.stop="openCompare(row)">对比</el-button>
            <el-button link type="primary" size="small" @click.stop="exportTask(row)">导出</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card v-if="currentTask" style="margin-top: 12px">
      <template #header>
        <div class="bar">
          <span>复演结果：{{ currentTask.buildId }} @ 业务时点 {{ fmt(currentTask.businessTime) }}，
            资料截止 {{ fmt(currentTask.knowledgeCutoff) }}</span>
        </div>
      </template>
      <el-table :data="items" size="small" stripe>
        <el-table-column label="结论" width="90">
          <template #default="{ row }"><StatusTag :value="row.itemType" /></template>
        </el-table-column>
        <el-table-column prop="coordinate" label="坐标" min-width="200" show-overflow-tooltip />
        <el-table-column label="原因分类" width="140">
          <template #default="{ row }">
            <StatusTag v-if="row.reasonCode" :value="row.reasonCode" />
          </template>
        </el-table-column>
        <el-table-column prop="reasonDetail" label="说明" min-width="320" show-overflow-tooltip />
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openExplain(row)">解释</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-drawer v-model="explainDrawer" size="72%" :title="`逐项解释：${explain?.item?.coordinate || ''}`">
      <template v-if="explain">
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="结论">
            <StatusTag :value="explain.item.itemType" />
          </el-descriptions-item>
          <el-descriptions-item label="原因分类">
            <StatusTag v-if="explain.item.reasonCode" :value="explain.item.reasonCode" />
          </el-descriptions-item>
          <el-descriptions-item label="规则版本">{{ explain.task.ruleVersion }}</el-descriptions-item>
          <el-descriptions-item label="业务时点">{{ fmt(explain.task.businessTime) }}</el-descriptions-item>
          <el-descriptions-item label="资料截止">{{ fmt(explain.task.knowledgeCutoff) }}</el-descriptions-item>
          <el-descriptions-item label="构建">{{ explain.task.buildId }}</el-descriptions-item>
        </el-descriptions>
        <el-alert type="info" :closable="false" style="margin-top: 10px">
          {{ explain.item.reasonDetail || '已证实出现' }}
        </el-alert>

        <el-tabs style="margin-top: 10px">
          <el-tab-pane label="精简组合（默认）">
            <el-alert :type="explain.minimal.exhaustive ? 'success' : 'warning'" :closable="false"
                      style="margin-bottom: 8px">
              {{ explain.minimal.coverageNote }}
            </el-alert>
            <div v-if="!explain.minimal.combinations.length" class="muted">
              未在预算内找到精简组合；这不否定已算出的记录，完整推导见「全部推导」。
            </div>
            <el-card v-for="(combo, ci) in explain.minimal.combinations" :key="ci"
                     shadow="never" class="combo">
              <template #header>
                <div class="bar">
                  <span>充分证据组合 #{{ ci + 1 }}（{{ combo.size }} 项，已核实不可再删减）</span>
                  <el-tag size="small" type="success">verified</el-tag>
                </div>
              </template>
              <div v-for="leaf in combo.leaves" :key="leaf.id" class="leaf">
                <el-tag size="small" effect="plain">{{ roleLabel(leaf.role) }}</el-tag>
                <span class="leaf-label">{{ leaf.label }}</span>
                <div class="leaf-meta">
                  <code v-if="leaf.location">{{ leaf.location }}</code>
                  <span v-if="leaf.interval"> 区间 {{ leaf.interval }}</span>
                </div>
              </div>
            </el-card>
            <div v-if="explain.minimal.uncheckedBranches?.length" class="unchecked">
              <b>尚未检查的分支（{{ explain.minimal.uncheckedBranches.length }}）：</b>
              <div v-for="(u, i) in explain.minimal.uncheckedBranches" :key="i" class="muted">
                · {{ u }}
              </div>
            </div>
            <el-alert type="info" :closable="false" style="margin-top: 8px">
              不可再删减不代表全局最短或唯一解释；精简只裁剪展示依据，不改变计算输入。
            </el-alert>
          </el-tab-pane>

          <el-tab-pane label="全部推导">
            <el-alert type="info" :closable="false" style="margin-bottom: 8px">
              完整推导图：AND 为共同必需（缺一不可），OR 为可相互替代（任一成立），
              互斥候选各自成支，可展开到原始位置。
            </el-alert>
            <el-tree :data="derivationTree" :props="{ label: 'label' }" default-expand-all
                     node-key="id">
              <template #default="{ data }">
                <span>
                  <el-tag v-if="data.type === 'ALL'" size="small" type="danger" effect="plain">
                    共同必需</el-tag>
                  <el-tag v-else-if="data.type === 'ANY'" size="small" type="success" effect="plain">
                    可相互替代{{ data.exclusive ? '·互斥' : '' }}</el-tag>
                  <el-tag v-else size="small" type="info" effect="plain">
                    {{ roleLabel(data.role) }}</el-tag>
                  <span style="margin: 0 6px">{{ data.label }}</span>
                  <code v-if="data.location" class="loc">{{ data.location }}</code>
                </span>
              </template>
            </el-tree>
          </el-tab-pane>

          <el-tab-pane :label="`适用资料 (${explain.applicableSources.length})`">
            <el-table :data="explain.applicableSources" size="small" max-height="420">
              <el-table-column label="角色" width="160">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.role === '可用' ? 'success' : 'info'">
                    {{ row.role }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="type" label="类型" width="130" />
              <el-table-column label="哈希" width="110">
                <template #default="{ row }"><code>{{ row.payloadHash?.substring(0, 10) }}</code></template>
              </el-table-column>
              <el-table-column label="完整性" width="90">
                <template #default="{ row }">{{ row.completeness || '-' }}</template>
              </el-table-column>
              <el-table-column label="收集时间" width="165">
                <template #default="{ row }">{{ fmt(row.collectedAt) }}</template>
              </el-table-column>
              <el-table-column prop="sourceUri" label="来源地址" min-width="180" show-overflow-tooltip />
            </el-table>
          </el-tab-pane>

          <el-tab-pane :label="`条件判断 (${explain.conditions.length})`">
            <div v-for="(c, i) in explain.conditions" :key="i" class="cond">· {{ c }}</div>
            <div v-if="!explain.conditions.length" class="muted">无额外条件</div>
          </el-tab-pane>

          <el-tab-pane :label="`反证与互斥 (${explain.counterevidence.length})`">
            <el-alert v-if="explain.counterevidence.length" type="warning" :closable="false"
                      style="margin-bottom: 8px">
              反证与互斥条件不在精简中隐藏，随解释一并展示。
            </el-alert>
            <div v-for="(c, i) in explain.counterevidence" :key="i" class="cond">
              <el-tag size="small" type="danger" effect="plain">{{ c.kind }}</el-tag> {{ c.detail }}
            </div>
            <div v-if="!explain.counterevidence.length" class="muted">无</div>
          </el-tab-pane>
        </el-tabs>
      </template>
    </el-drawer>

    <el-dialog v-model="compareDialog" title="双时点对比：同一业务时点，切换资料截止时间" width="78%">
      <div v-if="compareTask" class="compare-head">
        <span>业务时点 <code>{{ fmt(compareTask.businessTime) }}</code>，原资料截止
          <code>{{ fmt(compareTask.knowledgeCutoff) }}</code>，对比截止：</span>
        <el-input v-model="compareCutoff" placeholder="ISO-8601" style="width: 240px" />
        <el-button type="primary" size="small" :loading="comparing" @click="runCompare"
                   :disabled="!auth.isAdmin">对比</el-button>
      </div>
      <template v-if="compareResult">
        <el-alert type="info" :closable="false" style="margin-bottom: 8px">
          {{ compareResult.note }}；未变化 {{ compareResult.unchanged }} 项。
          两次复演（{{ compareResult.task1Id.substring(0, 8) }} /
          {{ compareResult.task2Id.substring(0, 8) }}）均已保存可回查。
        </el-alert>
        <el-table :data="compareResult.changes" size="small">
          <el-table-column prop="coordinate" label="坐标" min-width="160" show-overflow-tooltip />
          <el-table-column label="原截止结论" min-width="170">
            <template #default="{ row }"><code>{{ row.before }}</code></template>
          </el-table-column>
          <el-table-column label="新截止结论" min-width="170">
            <template #default="{ row }"><code>{{ row.after }}</code></template>
          </el-table-column>
          <el-table-column label="变化来自哪份补证或修订" min-width="280">
            <template #default="{ row }">
              <div v-for="(a, i) in row.attributions" :key="i" class="cond">
                <el-tag size="small" effect="plain">{{ a.kind }}</el-tag> {{ a.detail }}
              </div>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!compareResult.changes.length" description="两个资料截止下结论一致" />
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { get, post } from '../api/client'
import { useAuthStore } from '../stores/auth'
import StatusTag from '../components/StatusTag.vue'

const auth = useAuthStore()
const route = useRoute()

const builds = ref<string[]>([])
const tasks = ref<any[]>([])
const currentTask = ref<any>(null)
const items = ref<any[]>([])
const running = ref(false)

const form = ref({
  buildId: (route.query.buildId as string) || '',
  businessTime: '',
  knowledgeCutoff: (route.query.cutoff as string) || '',
  queries: '',
  excludeScopes: '',
  requireFields: ''
})

const explainDrawer = ref(false)
const explain = ref<any>(null)

const compareDialog = ref(false)
const compareTask = ref<any>(null)
const compareCutoff = ref('')
const comparing = ref(false)
const compareResult = ref<any>(null)

function fmt(s: string) {
  return s ? s.replace('T', ' ').substring(0, 19) : '-'
}

function roleLabel(role: string) {
  const map: Record<string, string> = {
    EDGE: '结论', SUPPORT: '独立支持', BUILD_RECORD: '构建记录', RELATION_TEXT: '关系原文',
    IDENTITY: '包身份', VALIDITY: '有效区间', CONDITION: '条件', ABSENCE: '缺席判断', SOURCE: '来源'
  }
  return map[role] || role || '证据'
}

/** 解析缺席对象查询行：eco:ns/name@version（eco 与版本可省）。 */
function parseQueryLine(line: string) {
  let rest = line.trim()
  let ecosystem: string | null = null
  const colon = rest.indexOf(':')
  if (colon > 0 && !rest.includes('/')) {
    ecosystem = rest.substring(0, colon)
    rest = rest.substring(colon + 1)
  } else if (colon > 0 && rest.indexOf('/') > colon) {
    ecosystem = rest.substring(0, colon)
    rest = rest.substring(colon + 1)
  }
  let version: string | null = null
  const at = rest.lastIndexOf('@')
  if (at > 0) {
    version = rest.substring(at + 1)
    rest = rest.substring(0, at)
  }
  let namespace: string | null = null
  const slash = rest.lastIndexOf('/')
  if (slash > 0) {
    namespace = rest.substring(0, slash)
    rest = rest.substring(slash + 1)
  }
  return { ecosystem, namespace, name: rest, version }
}

async function runReplay() {
  if (!form.value.buildId || !form.value.businessTime || !form.value.knowledgeCutoff) {
    ElMessage.warning('构建标识、业务时点、资料截止时间均必填')
    return
  }
  running.value = true
  try {
    const body: any = {
      buildId: form.value.buildId,
      businessTime: form.value.businessTime,
      knowledgeCutoff: form.value.knowledgeCutoff,
      queries: form.value.queries.split('\n').map(l => l.trim()).filter(l => l)
        .map(parseQueryLine),
      excludeScopes: form.value.excludeScopes.split(',').map(s => s.trim()).filter(s => s),
      requireFields: form.value.requireFields.split(',').map(s => s.trim()).filter(s => s)
    }
    const result = await post('/explain/replay', body)
    ElMessage.success('复演完成：' + result.task.summary)
    await loadTasks()
    selectTask({ id: result.task.id })
  } catch (e: any) {
    ElMessage.error(e.message || '复演失败')
  } finally {
    running.value = false
  }
}

async function loadTasks() {
  tasks.value = await get('/explain/replays')
}

async function selectTask(row: any) {
  const detail = await get(`/explain/replays/${row.id}`)
  currentTask.value = detail.task
  items.value = detail.items
}

async function openExplain(row: any) {
  explain.value = await get(`/explain/items/${row.id}/explanation`)
  explainDrawer.value = true
}

const derivationTree = computed(() => {
  if (!explain.value?.derivation) return []
  let counter = 0
  const assign = (node: any): any => {
    node.id = 'n' + (++counter)
    if (node.children) node.children = node.children.map(assign)
    return node
  }
  return [assign(JSON.parse(JSON.stringify(explain.value.derivation)))]
})

function openCompare(row: any) {
  compareTask.value = row
  compareCutoff.value = new Date().toISOString()
  compareResult.value = null
  compareDialog.value = true
}

async function runCompare() {
  comparing.value = true
  try {
    compareResult.value = await post(`/explain/replays/${compareTask.value.id}/compare`, {
      otherCutoff: compareCutoff.value
    })
    await loadTasks()
  } catch (e: any) {
    ElMessage.error(e.message || '对比失败')
  } finally {
    comparing.value = false
  }
}

async function exportTask(row: any) {
  const data = await get(`/explain/replays/${row.id}/export`)
  const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `replay-export-${row.buildId}-${row.id.substring(0, 8)}.json`
  a.click()
  URL.revokeObjectURL(a.href)
  ElMessage.success('已导出；使用其中 inputs 作为复演请求体可用相同输入重新计算')
}

onMounted(async () => {
  builds.value = await get('/dashboard/builds')
  await loadTasks()
})
</script>

<style scoped>
.bar { display: flex; justify-content: space-between; align-items: center; }
.muted { color: #909399; font-size: 12px; }
.combo { margin-bottom: 8px; }
.leaf { padding: 4px 0; border-bottom: 1px dashed #e4e7ed; }
.leaf-label { margin-left: 6px; }
.leaf-meta { margin-left: 76px; color: #909399; font-size: 12px; }
.unchecked { margin-top: 8px; }
.cond { padding: 3px 0; }
.loc { margin-left: 8px; color: #909399; font-size: 11px; }
.compare-head { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
</style>
