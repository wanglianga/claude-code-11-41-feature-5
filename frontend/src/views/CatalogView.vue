<template>
  <div>
    <el-row :gutter="16">
      <el-col :span="12">
        <el-card>
          <template #header>
            <div class="bar">
              <span>组件目录（可修订）</span>
              <el-button type="primary" size="small" :disabled="!auth.isAdmin"
                         @click="entryDialog = true">登记条目</el-button>
            </div>
          </template>
          <el-table :data="entries" size="small" stripe>
            <el-table-column label="坐标" min-width="160">
              <template #default="{ row }">
                <code>{{ row.ecosystem }}:{{ row.namespace ? row.namespace + '/' : '' }}{{ row.name }}</code>
              </template>
            </el-table-column>
            <el-table-column prop="publisher" label="发布者" width="130" show-overflow-tooltip />
            <el-table-column label="别名" min-width="130">
              <template #default="{ row }">{{ row.aliases }}</template>
            </el-table-column>
            <el-table-column prop="revision" label="修订" width="60" align="center" />
            <el-table-column label="操作" width="80">
              <template #default="{ row }">
                <el-button link type="primary" size="small" :disabled="!auth.isAdmin"
                           @click="openRevise(row)">修订</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card>
          <template #header>
            <div class="bar">
              <span>下游消费视图</span>
              <el-button type="primary" size="small" :disabled="!auth.isAdmin"
                         @click="openView">创建视图</el-button>
            </div>
          </template>
          <el-table :data="views" size="small" stripe>
            <el-table-column prop="name" label="名称" min-width="150" show-overflow-tooltip />
            <el-table-column label="类型" width="110">
              <template #default="{ row }">{{ viewTypeLabel(row.viewType) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }"><StatusTag :value="row.status" /></template>
            </el-table-column>
            <el-table-column prop="suspendReason" label="暂停原因" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">{{ row.suspendReason || '-' }}</template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <el-dialog v-model="entryDialog" title="登记目录条目" width="480px">
      <el-form label-width="90px">
        <el-form-item label="生态"><el-input v-model="entryForm.ecosystem" placeholder="npm / maven ..." /></el-form-item>
        <el-form-item label="命名空间"><el-input v-model="entryForm.namespace" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="entryForm.name" /></el-form-item>
        <el-form-item label="发布者"><el-input v-model="entryForm.publisher" /></el-form-item>
        <el-form-item label="别名"><el-input v-model="entryForm.aliases" placeholder='JSON 数组，如 ["a","b"]' /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="entryDialog = false">取消</el-button>
        <el-button type="primary" @click="createEntry">提交</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="reviseDialog" title="目录修订（触发变动监测）" width="480px">
      <el-alert type="warning" :closable="false" style="margin-bottom: 12px">
        修订别名或发布者归属将定位当前引用并暂停下游视图，历史分析保留当时身份版本。
      </el-alert>
      <el-form label-width="90px">
        <el-form-item label="发布者"><el-input v-model="reviseForm.publisher" /></el-form-item>
        <el-form-item label="别名"><el-input v-model="reviseForm.aliases" /></el-form-item>
        <el-form-item label="修订说明"><el-input v-model="reviseForm.summary" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reviseDialog = false">取消</el-button>
        <el-button type="warning" @click="revise">提交修订</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="viewDialog" title="创建下游视图" width="480px">
      <el-form label-width="90px">
        <el-form-item label="类型">
          <el-select v-model="viewForm.viewType" style="width: 100%">
            <el-option label="风险视图" value="RISK_VIEW" />
            <el-option label="资产清单" value="ASSET_INVENTORY" />
            <el-option label="审计视图" value="AUDIT_VIEW" />
          </el-select>
        </el-form-item>
        <el-form-item label="名称"><el-input v-model="viewForm.name" /></el-form-item>
        <el-form-item label="构建事实">
          <el-select v-model="viewForm.buildFactId" style="width: 100%" filterable>
            <el-option v-for="f in facts" :key="f.id"
                       :label="`${f.buildId} v${f.factVersion}`" :value="f.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="viewDialog = false">取消</el-button>
        <el-button type="primary" @click="createView">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { get, post } from '../api/client'
import { useAuthStore } from '../stores/auth'
import StatusTag from '../components/StatusTag.vue'

const auth = useAuthStore()
const entries = ref<any[]>([])
const views = ref<any[]>([])
const facts = ref<any[]>([])
const entryDialog = ref(false)
const reviseDialog = ref(false)
const viewDialog = ref(false)
const revisingId = ref('')
const entryForm = ref({ ecosystem: 'npm', namespace: '', name: '', publisher: '', aliases: '[]' })
const reviseForm = ref({ publisher: '', aliases: '', summary: '' })
const viewForm = ref({ viewType: 'RISK_VIEW', name: '', buildFactId: '' })

function viewTypeLabel(t: string) {
  return ({ RISK_VIEW: '风险视图', ASSET_INVENTORY: '资产清单', AUDIT_VIEW: '审计视图' } as any)[t] || t
}

async function load() {
  entries.value = await get('/catalog/entries')
  views.value = await get('/catalog/views')
  facts.value = await get('/build-facts')
}

async function createEntry() {
  try {
    await post('/catalog/entries', entryForm.value)
    ElMessage.success('已登记')
    entryDialog.value = false
    await load()
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || e.message)
  }
}

function openRevise(row: any) {
  revisingId.value = row.id
  reviseForm.value = { publisher: row.publisher || '', aliases: row.aliases || '[]', summary: '' }
  reviseDialog.value = true
}

async function revise() {
  try {
    await post(`/catalog/entries/${revisingId.value}/revise`, reviseForm.value)
    ElMessage.success('已修订，变动监测已触发')
    reviseDialog.value = false
    await load()
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || e.message)
  }
}

function openView() {
  viewDialog.value = true
}

async function createView() {
  try {
    await post('/catalog/views', viewForm.value)
    ElMessage.success('已创建')
    viewDialog.value = false
    await load()
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || e.message)
  }
}

onMounted(load)
</script>

<style scoped>
.bar { display: flex; justify-content: space-between; align-items: center; }
</style>
