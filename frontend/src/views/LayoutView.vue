<template>
  <el-container class="layout">
    <el-aside width="230px">
      <div class="logo">供应链元数据事实层</div>
      <el-menu :default-active="$route.path" router background-color="#1f2d3d"
               text-color="#bfcbd9" active-text-color="#409eff">
        <el-menu-item index="/dashboard">总览</el-menu-item>
        <el-menu-item index="/sources">来源保全册</el-menu-item>
        <el-menu-item index="/identities">身份工作区</el-menu-item>
        <el-menu-item index="/dependencies">依赖事实编译</el-menu-item>
        <el-menu-item index="/build-facts">构建事实签发</el-menu-item>
        <el-menu-item index="/replay">时点复演与解释</el-menu-item>
        <el-menu-item index="/catalog">消费目录</el-menu-item>
        <el-menu-item index="/monitor">变动监测</el-menu-item>
        <el-menu-item index="/scenarios">验收场景</el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <span>{{ $route.meta.title || '' }}</span>
        <div>
          <el-tag size="small" style="margin-right: 8px">{{ auth.role }}</el-tag>
          <span style="margin-right: 12px">{{ auth.username }}</span>
          <el-button size="small" @click="logout">退出</el-button>
        </div>
      </el-header>
      <el-main>
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const router = useRouter()
function logout() {
  auth.logout()
  router.push('/login')
}
</script>

<style scoped>
.layout { height: 100vh; }
.el-aside { background: #1f2d3d; }
.logo {
  color: #fff;
  font-weight: 600;
  padding: 18px 16px;
  font-size: 15px;
  border-bottom: 1px solid #2b3a4d;
}
.el-menu { border-right: none; }
.header {
  background: #fff;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #e4e7ed;
  font-weight: 600;
}
</style>
