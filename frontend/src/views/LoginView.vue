<template>
  <div class="login-wrap">
    <el-card class="login-card">
      <h2>供应链元数据事实层</h2>
      <p class="sub">软件依赖清单数据质量与供应链元数据服务</p>
      <el-form @submit.prevent="onLogin">
        <el-form-item>
          <el-input v-model="username" placeholder="用户名" size="large" />
        </el-form-item>
        <el-form-item>
          <el-input v-model="password" type="password" placeholder="密码" size="large"
                    @keyup.enter="onLogin" />
        </el-form-item>
        <el-button type="primary" size="large" style="width: 100%" :loading="loading"
                   @click="onLogin">登录</el-button>
      </el-form>
      <p class="hint">演示账号：admin / admin123（管理），viewer / viewer123（只读）</p>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/auth'

const username = ref('admin')
const password = ref('')
const loading = ref(false)
const auth = useAuthStore()
const router = useRouter()

async function onLogin() {
  if (!username.value || !password.value) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    await auth.login(username.value, password.value)
    router.push('/dashboard')
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || e.message || '登录失败')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-wrap {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1f2d3d 0%, #2b4b6f 100%);
}
.login-card { width: 400px; padding: 12px; }
h2 { margin: 0 0 4px; }
.sub { color: #909399; font-size: 13px; margin-top: 0; }
.hint { color: #c0c4cc; font-size: 12px; text-align: center; margin-bottom: 0; }
</style>
