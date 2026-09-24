<template>
  <div>
    <div class="toolbar">
      <el-button type="primary" @click="openCreate">新增用户</el-button>
    </div>
    <el-table :data="rows" v-loading="loading" border>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="username" label="用户名" min-width="140" />
      <el-table-column prop="role" label="角色" width="110">
        <template #default="{ row }">
          <el-tag :type="row.role === 'ADMIN' ? 'danger' : 'info'">
            {{ row.role === 'ADMIN' ? '管理员' : '普通HR' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="创建时间" width="180" />
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" :disabled="row.username === username" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination
      class="pagination"
      layout="total, prev, pager, next"
      :total="total"
      :page-size="pageSize"
      :current-page="pageNo"
      @current-change="load"
    />

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑用户' : '新增用户'" width="480px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="用户名" required>
          <el-input v-model="form.username" :disabled="!!editingId" />
        </el-form-item>
        <el-form-item :label="editingId ? '重置密码' : '密码'" :required="!editingId">
          <el-input v-model="form.password" type="password" show-password :placeholder="editingId ? '留空则不修改' : ''" />
        </el-form-item>
        <el-form-item label="角色">
          <el-radio-group v-model="form.role">
            <el-radio value="HR">普通HR</el-radio>
            <el-radio value="ADMIN">管理员</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { userApi } from '../api/modules'

const rows = ref([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = 10
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const editingId = ref(null)
const username = localStorage.getItem('username') || ''
const form = reactive({ username: '', password: '', role: 'HR' })

async function load(page = 1) {
  pageNo.value = page
  loading.value = true
  try {
    const res = await userApi.page({ pageNo: pageNo.value, pageSize })
    rows.value = res.data.records
    total.value = Number(res.data.total)
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  Object.assign(form, { username: '', password: '', role: 'HR' })
  dialogVisible.value = true
}

function openEdit(row) {
  editingId.value = row.id
  Object.assign(form, { username: row.username, password: '', role: row.role })
  dialogVisible.value = true
}

async function handleSave() {
  if (!form.username || (!editingId.value && !form.password)) {
    ElMessage.warning('请完整填写表单')
    return
  }
  saving.value = true
  try {
    if (editingId.value) {
      await userApi.update(editingId.value, form)
    } else {
      await userApi.create(form)
    }
    ElMessage.success('保存成功')
    dialogVisible.value = false
    load(pageNo.value)
  } finally {
    saving.value = false
  }
}

async function handleDelete(row) {
  await ElMessageBox.confirm(`确定删除用户「${row.username}」?`, '确认', { type: 'warning' })
  await userApi.remove(row.id)
  ElMessage.success('已删除')
  load(pageNo.value)
}

onMounted(() => load())
</script>

<style scoped>
.toolbar {
  margin-bottom: 12px;
}
.pagination {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>
