<template>
  <div>
    <div class="toolbar">
      <el-button type="primary" @click="openCreate">新增岗位</el-button>
    </div>
    <el-table :data="rows" v-loading="loading" border>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="title" label="岗位名称" min-width="160" />
      <el-table-column label="薪资(元/月)" width="160">
        <template #default="{ row }">
          <span v-if="row.salaryMin || row.salaryMax">{{ row.salaryMin || '-' }} ~ {{ row.salaryMax || '-' }}</span>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'">
            {{ row.status === 'ACTIVE' ? '招聘中' : '已关闭' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="创建时间" width="180" />
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
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

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑岗位' : '新增岗位'" width="640px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="岗位名称" required>
          <el-input v-model="form.title" />
        </el-form-item>
        <el-form-item label="对外 JD">
          <el-input v-model="form.externalJd" type="textarea" :rows="6" placeholder="发布到平台的职位描述" />
        </el-form-item>
        <el-form-item label="对内寻源备注">
          <el-input v-model="form.internalNotes" type="textarea" :rows="3" placeholder="搜索关键词、排除信号等(不外发)" />
        </el-form-item>
        <el-form-item label="薪资范围">
          <el-input-number v-model="form.salaryMin" :min="0" :step="1000" placeholder="下限" />
          <span style="margin: 0 8px">~</span>
          <el-input-number v-model="form.salaryMax" :min="0" :step="1000" placeholder="上限" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio value="ACTIVE">招聘中</el-radio>
            <el-radio value="CLOSED">已关闭</el-radio>
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
import { jdApi } from '../api/modules'

const rows = ref([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = 10
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const editingId = ref(null)
const form = reactive({ title: '', externalJd: '', internalNotes: '', salaryMin: null, salaryMax: null, status: 'ACTIVE' })

async function load(page = 1) {
  pageNo.value = page
  loading.value = true
  try {
    const res = await jdApi.page({ pageNo: pageNo.value, pageSize })
    rows.value = res.data.records
    total.value = Number(res.data.total)
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  Object.assign(form, { title: '', externalJd: '', internalNotes: '', salaryMin: null, salaryMax: null, status: 'ACTIVE' })
  dialogVisible.value = true
}

function openEdit(row) {
  editingId.value = row.id
  Object.assign(form, row)
  dialogVisible.value = true
}

async function handleSave() {
  if (!form.title) {
    ElMessage.warning('请填写岗位名称')
    return
  }
  saving.value = true
  try {
    if (editingId.value) {
      await jdApi.update(editingId.value, form)
    } else {
      await jdApi.create(form)
    }
    ElMessage.success('保存成功')
    dialogVisible.value = false
    load(pageNo.value)
  } finally {
    saving.value = false
  }
}

async function handleDelete(row) {
  await ElMessageBox.confirm(`确定删除岗位「${row.title}」?`, '确认', { type: 'warning' })
  await jdApi.remove(row.id)
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
