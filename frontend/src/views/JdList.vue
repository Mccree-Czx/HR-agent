<template>
  <div>
    <div class="toolbar">
      <el-button type="primary" @click="openCreate">新增岗位</el-button>
      <el-button type="warning" :loading="syncing" @click="handleSync">同步猎聘职位</el-button>
    </div>
    <el-table :data="rows" v-loading="loading" border>
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="title" label="岗位名称" min-width="150" />
      <el-table-column label="薪资" width="140">
        <template #default="{ row }">
          <span v-if="row.salaryMin || row.salaryMax">{{ (row.salaryMin || 0) / 1000 }}K - {{ (row.salaryMax || 0) / 1000 }}K·{{ row.salaryMonths || 13 }}薪</span>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column label="城市" width="110">
        <template #default="{ row }">
          <span>{{ row.city || '-' }}{{ row.district ? '-' + row.district : '' }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">
            {{ row.status === 'ACTIVE' ? '招聘中' : '已关闭' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="source" label="来源" width="95">
        <template #default="{ row }">
          <el-tag :type="row.source === 'SYNCED' ? 'warning' : 'info'" size="small">
            {{ row.source === 'SYNCED' ? '猎聘同步' : '系统创建' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="猎聘发布" width="150">
        <template #default="{ row }">
          <el-tooltip v-if="row.publishStatus === 'FAILED'" :content="row.publishError || '发布失败'">
            <el-tag type="danger" size="small">发布失败</el-tag>
          </el-tooltip>
          <el-tag v-else-if="row.publishStatus === 'PUBLISHED'" type="success" size="small">
            已发布 #{{ row.liepinJobId }}
          </el-tag>
          <el-tag v-else-if="row.publishStatus === 'PUBLISHING'" type="warning" size="small">发布中</el-tag>
          <el-tag v-else type="info" size="small">未发布</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="创建时间" width="160" />
      <el-table-column label="操作" width="300" fixed="right">
        <template #default="{ row }">
          <el-button link type="warning" :disabled="row.publishStatus === 'PUBLISHED' || row.publishStatus === 'PUBLISHING'" :loading="row._publishing" @click="handlePublish(row)">发布到猎聘</el-button>
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
        <el-form-item label="城市/区">
          <el-input v-model="form.city" placeholder="如:上海" style="width: 130px" />
          <span style="margin: 0 8px">-</span>
          <el-input v-model="form.district" placeholder="如:虹口区" style="width: 130px" />
        </el-form-item>
        <el-form-item label="猎聘类别编码">
          <el-input v-model="form.jobCategory" placeholder="如 N000330(招聘经理/主管)" style="width: 260px" />
          <span class="field-hint">发布到猎聘必填:招聘主管 N000330 / HRBP N000340 / 人力资源经理 N000328 / 薪酬绩效经理 N000334</span>
        </el-form-item>
        <el-form-item label="经验/学历">
          <el-input v-model="form.experienceReq" placeholder="经验要求,如 5-10年" style="width: 160px" />
          <el-input v-model="form.degreeReq" placeholder="学历,如 本科" style="width: 120px; margin-left: 8px" />
        </el-form-item>
        <el-form-item label="薪资范围">
          <el-input-number v-model="form.salaryMin" :min="0" :step="1000" placeholder="下限" />
          <span style="margin: 0 8px">~</span>
          <el-input-number v-model="form.salaryMax" :min="0" :step="1000" placeholder="上限" />
          <span style="margin: 0 8px">·</span>
          <el-input-number v-model="form.salaryMonths" :min="12" :max="24" />
          <span style="margin-left: 4px">薪</span>
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
const syncing = ref(false)
const dialogVisible = ref(false)
const editingId = ref(null)
const emptyForm = {
  title: '', externalJd: '', internalNotes: '',
  city: '', district: '', jobCategory: '', experienceReq: '', degreeReq: '',
  salaryMin: null, salaryMax: null, salaryMonths: 13, status: 'ACTIVE'
}
const form = reactive({ ...emptyForm })

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
  Object.assign(form, emptyForm)
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

async function handlePublish(row) {
  await ElMessageBox.confirm(
    `将把「${row.title}」发布到猎聘平台(对外公开可见,不可撤销),确定发布?`,
    '发布确认',
    { type: 'warning', confirmButtonText: '确认发布', cancelButtonText: '取消' }
  )
  row._publishing = true
  try {
    const res = await jdApi.publish(row.id)
    ElMessage.success(`已发布到猎聘,职位ID: ${res.data.liepinJobId}`)
    load(pageNo.value)
  } finally {
    row._publishing = false
  }
}

async function handleSync() {
  syncing.value = true
  try {
    const res = await jdApi.syncLiepin()
    ElMessage.success(`同步完成:新增 ${res.data.created} 个,更新 ${res.data.updated} 个(共 ${res.data.total} 个)`)
    load(1)
  } finally {
    syncing.value = false
  }
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
.field-hint {
  margin-left: 8px;
  color: #999;
  font-size: 12px;
}
</style>
