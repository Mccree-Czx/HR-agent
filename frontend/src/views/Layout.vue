<template>
  <el-container class="layout">
    <el-aside width="200px" class="aside">
      <div class="logo">HR Agent</div>
      <el-menu :default-active="$route.path" router background-color="#001529" text-color="#a6adb4" active-text-color="#fff">
        <el-menu-item index="/jd">岗位管理</el-menu-item>
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
}
.aside {
  background: #001529;
}
.logo {
  color: #fff;
  font-size: 18px;
  font-weight: 600;
  text-align: center;
  padding: 16px 0;
}
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #eee;
}
.page-title {
  font-size: 16px;
  font-weight: 600;
}
.user-name {
  cursor: pointer;
}
</style>
