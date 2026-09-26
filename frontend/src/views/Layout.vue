<template>
  <el-container class="layout">
    <el-aside width="200px" class="aside">
      <div class="logo">HR Agent</div>
      <el-menu :default-active="$route.path" router background-color="#F2F3EF" text-color="#5C665F" active-text-color="#285E52">
        <el-menu-item index="/jd">岗位管理</el-menu-item>
        <el-menu-item index="/candidate">候选人台账</el-menu-item>
        <el-menu-item index="/account">账号管理</el-menu-item>
        <el-menu-item v-if="isAdmin" index="/user">用户管理</el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <span class="page-title">{{ $route.meta.title }}</span>
        <el-dropdown @command="handleCommand">
          <span class="user-name">{{ username }}</span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>
      <el-main>
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()
const username = localStorage.getItem('username') || ''
const isAdmin = computed(() => localStorage.getItem('role') === 'ADMIN')

function handleCommand(command) {
  if (command === 'logout') {
    localStorage.removeItem('token')
    localStorage.removeItem('role')
    localStorage.removeItem('username')
    router.push('/login')
  }
}
</script>

<style scoped>
.layout {
  height: 100vh;
  background: var(--hr-bg);
}
.aside {
  background: var(--hr-bg-alt);
  border-right: 1px solid var(--hr-border);
}
.logo {
  color: var(--hr-text-1);
  font-size: 18px;
  font-weight: 600;
  text-align: center;
  padding: 16px 0;
}
/* 侧边栏菜单:去掉默认右边框,激活项用主色+品牌软底(对齐目标站导航观感) */
.aside :deep(.el-menu) {
  border-right: none;
  padding: 0 8px;
}
.aside :deep(.el-menu-item) {
  border-radius: var(--hr-radius-sm);
  margin-bottom: 2px;
}
.aside :deep(.el-menu-item.is-active) {
  background: var(--hr-brand-soft);
  font-weight: 600;
}
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: var(--hr-surface);
  border-bottom: 1px solid var(--hr-border);
}
.page-title {
  font-size: 16px;
  font-weight: 600;
}
.user-name {
  cursor: pointer;
}
</style>
