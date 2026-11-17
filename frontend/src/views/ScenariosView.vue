<template>
  <div>
    <el-alert type="info" :closable="false" style="margin-bottom: 16px">
      六个验收场景通过真实摄入—归并—编译—签发链路执行，每次运行使用独立构建标识，可重复验证。
    </el-alert>
    <el-row :gutter="16">
      <el-col :span="8" v-for="s in scenarios" :key="s.name" style="margin-bottom: 16px">
        <el-card shadow="hover">
          <template #header>
            <div class="bar">
              <b>{{ s.title }}</b>
              <el-button type="primary" size="small" :loading="running === s.name"
                         :disabled="!auth.isAdmin" @click="run(s.name)">运行</el-button>
            </div>
          </template>
          <p class="desc">{{ s.description }}</p>
          <template v-if="results[s.name]">
            <el-steps direction="vertical" :active="results[s.name].steps.length" style="height: auto">
              <el-step v-for="(st, i) in results[s.name].steps" :key="i" :title="st" />
            </el-steps>
            <el-divider />
            <div v-for="(c, i) in results[s.name].checks" :key="i" class="check">
              <el-icon v-if="c.passed" color="#67c23a"><CircleCheckFilled /></el-icon>
              <el-icon v-else color="#f56c6c"><CircleCloseFilled /></el-icon>
              <span :class="{ fail: !c.passed }">{{ c.name }}</span>
            </div>
            <el-tag :type="allPassed(s.name) ? 'success' : 'danger'" style="margin-top: 8px">
              {{ allPassed(s.name) ? '全部检查通过' : '存在未通过检查' }}
            </el-tag>
          </template>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { CircleCheckFilled, CircleCloseFilled } from '@element-plus/icons-vue'
import { get, post } from '../api/client'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const scenarios = ref<any[]>([])
const results = ref<Record<string, any>>({})
const running = ref('')

function allPassed(name: string) {
  const r = results.value[name]
  return r && r.checks.every((c: any) => c.passed)
}

async function run(name: string) {
  running.value = name
  try {
    results.value[name] = await post(`/scenarios/${name}/run`)
    if (allPassed(name)) ElMessage.success('场景检查全部通过')
    else ElMessage.warning('场景存在未通过检查')
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || e.message)
  } finally {
    running.value = ''
  }
}

onMounted(async () => {
  scenarios.value = await get('/scenarios')
})
</script>

<style scoped>
.bar { display: flex; justify-content: space-between; align-items: center; }
.desc { color: #606266; font-size: 13px; min-height: 60px; }
.check { display: flex; align-items: center; gap: 6px; font-size: 13px; margin: 4px 0; }
.check .fail { color: #f56c6c; }
:deep(.el-step__title) { font-size: 12px; line-height: 1.4; }
</style>
