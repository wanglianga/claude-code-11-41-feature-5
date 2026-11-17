import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('../views/LoginView.vue') },
    {
      path: '/',
      component: () => import('../views/LayoutView.vue'),
      children: [
        { path: '', redirect: '/dashboard' },
        { path: 'dashboard', component: () => import('../views/DashboardView.vue'), meta: { title: '总览' } },
        { path: 'sources', component: () => import('../views/SourcesView.vue'), meta: { title: '来源保全册' } },
        { path: 'identities', component: () => import('../views/IdentitiesView.vue'), meta: { title: '身份工作区' } },
        { path: 'dependencies', component: () => import('../views/DependenciesView.vue'), meta: { title: '依赖事实编译' } },
        { path: 'build-facts', component: () => import('../views/BuildFactsView.vue'), meta: { title: '构建事实' } },
        { path: 'replay', component: () => import('../views/ReplayView.vue'), meta: { title: '时点复演与解释' } },
        { path: 'catalog', component: () => import('../views/CatalogView.vue'), meta: { title: '消费目录' } },
        { path: 'monitor', component: () => import('../views/MonitorView.vue'), meta: { title: '变动监测' } },
        { path: 'scenarios', component: () => import('../views/ScenariosView.vue'), meta: { title: '验收场景' } }
      ]
    }
  ]
})

router.beforeEach((to) => {
  const token = localStorage.getItem('scf-token')
  if (to.path !== '/login' && !token) return '/login'
  return true
})

export default router
