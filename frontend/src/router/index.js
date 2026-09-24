import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/login', name: 'login', component: () => import('../views/Login.vue') },
  {
    path: '/',
    component: () => import('../views/Layout.vue'),
    redirect: '/jd',
    children: [
      { path: 'jd', name: 'jd', component: () => import('../views/JdList.vue'), meta: { title: '岗位管理' } },
      { path: 'account', name: 'account', component: () => import('../views/AccountList.vue'), meta: { title: '账号管理' } },
      { path: 'user', name: 'user', component: () => import('../views/UserList.vue'), meta: { title: '用户管理', adminOnly: true } }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to) => {
  const token = localStorage.getItem('token')
  if (to.path !== '/login' && !token) {
    return { path: '/login' }
  }
  if (to.meta.adminOnly && localStorage.getItem('role') !== 'ADMIN') {
    return { path: '/jd' }
  }
  return true
})

export default router
