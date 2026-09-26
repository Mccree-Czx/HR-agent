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
      <el-table-column label="评分门槛" width="130">
        <template #default="{ row }">
          <el-tag v-if="row.thresholdConfirmedAt" type="success" size="small">已确认 {{ row.scoreThreshold }} 分</el-tag>
          <el-tag v-else type="warning" size="small">门槛待确认</el-tag>
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
      <el-table-column label="操作" width="420" fixed="right">
        <template #default="{ row }">
          <el-button link type="warning" :disabled="row.publishStatus === 'PUBLISHED' || row.publishStatus === 'PUBLISHING'" :loading="row._publishing" @click="handlePublish(row)">发布到猎聘</el-button>
          <el-button link type="primary" @click="openThreshold(row)">门槛</el-button>
          <el-button link type="primary" @click="openCommunication(row)">筛选与沟通</el-button>
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" :loading="row._deleting" @click="handleDelete(row)">删除</el-button>
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

    <el-dialog v-model="thresholdDialogVisible" :title="`评分门槛 - ${thresholdRow?.title || ''}`" width="560px">
      <div v-if="thresholdRow">
        <el-alert
          v-if="thresholdRow.thresholdConfirmedAt"
          type="success"
          :closable="false"
          show-icon
          :title="`已确认门槛: ${thresholdRow.scoreThreshold} 分`"
          description="门槛已确认,该岗位允许自动外发;重新确认将覆盖当前门槛。"
          style="margin-bottom: 12px"
        />
        <el-alert
          v-else
          type="warning"
          :closable="false"
          show-icon
          title="门槛待确认"
          description="未确认门槛的岗位禁止一切自动外发(仅收集来信与附件)。"
          style="margin-bottom: 12px"
        />
        <el-form label-width="90px">
          <el-form-item label="AI 建议">
            <div style="width: 100%">
              <span v-if="thresholdRow.thresholdSuggestion">{{ thresholdRow.thresholdSuggestion }}</span>
              <span v-else style="color: var(--hr-text-3)">暂无建议,可点击「生成建议」</span>
              <el-button link type="primary" :loading="suggesting" style="margin-left: 8px" @click="handleSuggest">生成建议</el-button>
            </div>
          </el-form-item>
          <el-form-item label="门槛分数">
            <el-input-number v-model="thresholdInput" :min="1" :max="100" />
            <span class="field-hint">1-100,默认取 AI 建议中的数字,否则 60</span>
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="thresholdDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="confirming" @click="handleConfirmThreshold">确认门槛</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="drawerVisible" :title="`筛选与沟通 - ${drawerRow?.title || ''}`" size="62%">
      <el-table :data="drawerRows" v-loading="drawerLoading" border>
        <el-table-column prop="candidate.name" label="姓名" width="100" />
        <el-table-column label="评分" width="110">
          <template #default="{ row }">
            <el-tag
              v-if="row.latestScore"
              :type="row.candidate.passStatus === 'PASS' ? 'success' : row.candidate.passStatus === 'FAIL' ? 'danger' : 'info'"
              size="small"
            >
              {{ row.latestScore.score }} 分
            </el-tag>
            <el-tag v-else type="info" size="small">待评分</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="pass状态" width="100">
          <template #default="{ row }">
            <el-tag :type="passTagType(row.candidate.passStatus)" size="small">{{ passText(row.candidate.passStatus) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="职能校验" min-width="160">
          <template #default="{ row }">
            <el-tooltip v-if="hasJobMismatch(row)" :content="row.latestScore.reason">
              <el-tag type="warning" size="small">职能待确认</el-tag>
            </el-tooltip>
            <span v-else style="color: var(--hr-text-3)">-</span>
          </template>
        </el-table-column>
        <el-table-column label="打招呼状态" width="130">
          <template #default="{ row }">
            <el-tag v-if="row.greeting" :type="greetTagType(row.greeting.status)" size="small">{{ greetText(row.greeting.status) }}</el-tag>
            <el-tag v-else type="info" size="small">未联系</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="附件状态" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.resumeFile" type="success" size="small">已入库</el-tag>
            <el-tag v-else type="info" size="small">未入库</el-tag>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        class="pagination"
        layout="total, prev, pager, next"
        :total="drawerTotal"
        :page-size="drawerPageSize"
        :current-page="drawerPage"
        @current-change="loadDrawer"
      />
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { jdApi, candidateApi } from '../api/modules'

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

// 门槛确认弹窗
const thresholdDialogVisible = ref(false)
const thresholdRow = ref(null)
const thresholdInput = ref(60)
const suggesting = ref(false)
const confirming = ref(false)

// 筛选与沟通抽屉(该岗位候选人)
const drawerVisible = ref(false)
const drawerRow = ref(null)
const drawerRows = ref([])
const drawerTotal = ref(0)
const drawerPage = ref(1)
const drawerPageSize = 10
const drawerLoading = ref(false)

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
  const liepinTip = row.liepinJobId
    ? `\n将同时删除猎聘上的职位(#${row.liepinJobId}),此操作不可撤销!`
    : ''
  await ElMessageBox.confirm(
    `确定删除岗位「${row.title}」?${liepinTip}`,
    '删除确认',
    { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' }
  )
  row._deleting = true
  try {
    await jdApi.remove(row.id)
    ElMessage.success(row.liepinJobId ? '已删除(含猎聘职位)' : '已删除')
    load(pageNo.value)
  } finally {
    row._deleting = false
  }
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

/** 从「建议{N}分:...」中提取数字;越界或缺失返回 null */
function extractThreshold(text) {
  if (!text) return null
  const matched = String(text).match(/\d+/)
  if (!matched) return null
  const value = Number(matched[0])
  return value >= 1 && value <= 100 ? value : null
}

function openThreshold(row) {
  thresholdRow.value = row
  thresholdInput.value = row.scoreThreshold || extractThreshold(row.thresholdSuggestion) || 60
  thresholdDialogVisible.value = true
}

async function handleSuggest() {
  if (!thresholdRow.value) return
  suggesting.value = true
  try {
    const res = await jdApi.suggestThreshold(thresholdRow.value.id)
    thresholdRow.value.thresholdSuggestion = res.data
    const suggested = extractThreshold(res.data)
    if (suggested) {
      thresholdInput.value = suggested
    }
    ElMessage.success('已生成建议门槛(仅建议,需人工确认)')
  } finally {
    suggesting.value = false
  }
}

async function handleConfirmThreshold() {
  if (!thresholdRow.value) return
  confirming.value = true
  try {
    const res = await jdApi.confirmThreshold(thresholdRow.value.id, thresholdInput.value)
    ElMessage.success(`门槛已确认: ${res.data.scoreThreshold} 分`)
    thresholdDialogVisible.value = false
    load(pageNo.value)
  } finally {
    confirming.value = false
  }
}

async function openCommunication(row) {
  drawerRow.value = row
  drawerVisible.value = true
  await loadDrawer(1)
}

async function loadDrawer(page = 1) {
  if (!drawerRow.value) return
  drawerPage.value = page
  drawerLoading.value = true
  try {
    const res = await candidateApi.page({
      pageNo: drawerPage.value,
      pageSize: drawerPageSize,
      jdId: drawerRow.value.id
    })
    drawerRows.value = res.data.records
    drawerTotal.value = Number(res.data.total)
  } finally {
    drawerLoading.value = false
  }
}

/** 评分理由含「职能待确认」时提示人工复核 */
function hasJobMismatch(row) {
  const reason = row.latestScore && row.latestScore.reason
  return typeof reason === 'string' && reason.includes('职能待确认')
}

function passTagType(status) {
  return { PASS: 'success', FAIL: 'danger', PENDING: 'info' }[status] || 'info'
}
function passText(status) {
  return { PASS: '通过', FAIL: '未通过', PENDING: '待评分' }[status] || status
}
function greetTagType(status) {
  return { SENT: 'primary', AGREED: 'success', REQUESTED: 'warning', PENDING_CONFIRM: 'info', SEND_FAILED: 'danger' }[status] || 'info'
}
function greetText(status) {
  return { SENT: '已打招呼', AGREED: '候选人已同意', REQUESTED: '已索要简历', PENDING_CONFIRM: '待确认', SEND_FAILED: '发送失败' }[status] || status
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
  color: var(--hr-text-3);
  font-size: 12px;
}
</style>
