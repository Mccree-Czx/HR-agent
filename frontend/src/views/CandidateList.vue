<template>
  <div>
    <el-tabs v-model="activeTab" @tab-change="onTabChange">
      <el-tab-pane label="已收简历" name="received" />
      <el-tab-pane v-if="isAdmin" label="待分配" name="unassigned" />
    </el-tabs>
    <div class="toolbar">
      <el-select v-if="activeTab === 'received'" v-model="selectedJdId" placeholder="选择岗位" clearable style="width: 220px" @change="load()">
        <el-option v-for="jd in jds" :key="jd.id" :label="jd.title" :value="jd.id" />
      </el-select>
      <el-select v-model="filterStatus" placeholder="评分状态" clearable style="width: 140px" @change="load()">
        <el-option label="待评分" value="PENDING" />
        <el-option label="已通过" value="PASS" />
        <el-option label="未通过" value="FAIL" />
      </el-select>
      <template v-if="activeTab === 'received'">
        <el-button type="primary" :disabled="!selectedJdId" @click="runScore">批量评分(10人)</el-button>
        <el-button type="success" :disabled="!selectedJdId" @click="runGreet">批量打招呼(10人)</el-button>
        <el-button :disabled="!selectedJdId" @click="runCollect">检测回复并索要简历</el-button>
        <el-button type="warning" :disabled="!selectedJdId" :loading="recommendLoading" @click="runRecommend">拉取平台推荐</el-button>
      </template>
      <span v-else class="tab-hint">待分配:无有效岗位的候选人(含已入库附件),需人工指派岗位</span>
    </div>

    <el-table :data="rows" v-loading="loading" border>
      <el-table-column prop="candidate.id" label="ID" width="60" />
      <el-table-column prop="candidate.name" label="候选人" width="100" />
      <el-table-column label="简历快照" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">
          <span v-if="row.candidate.snapshot">{{ snapshotSummary(row.candidate.snapshot) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="评分" width="150">
        <template #default="{ row }">
          <template v-if="row.latestScore">
            <el-tag :type="row.candidate.passStatus === 'PASS' ? 'success' : 'danger'" size="small">
              {{ row.latestScore.score }} 分
            </el-tag>
            <el-tooltip :content="row.latestScore.reason">
              <span class="score-reason">{{ row.latestScore.reason }}</span>
            </el-tooltip>
          </template>
          <el-tag v-else type="info" size="small">待评分</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="打招呼" width="150">
        <template #default="{ row }">
          <template v-if="row.greeting">
            <el-tooltip :content="`关联猎聘职位: ${row.greeting.liepinJobId || '无(历史记录)'}`">
              <el-tag :type="greetTagType(row.greeting.status)" size="small">{{ greetText(row.greeting.status) }}</el-tag>
            </el-tooltip>
          </template>
          <el-tag v-else type="info" size="small">未联系</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="简历" width="100">
        <template #default="{ row }">
          <el-tag v-if="row.resumeFile" type="success" size="small">已入库</el-tag>
          <el-tag v-else type="info" size="small">未入库</el-tag>
        </template>
      </el-table-column>
      <el-table-column v-if="activeTab === 'received'" label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="redo(row.candidate.id)">重新打分</el-button>
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
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { jdApi, candidateApi, recruitApi, searchTaskApi } from '../api/modules'

// 待分配视图仅 ADMIN 可见:后端 unassigned=true 亦仅对 ADMIN 生效(评审 I-1)
const isAdmin = computed(() => localStorage.getItem('role') === 'ADMIN')

const rows = ref([])
const jds = ref([])
const selectedJdId = ref(null)
const filterStatus = ref('')
const activeTab = ref('received')
const total = ref(0)
const pageNo = ref(1)
const pageSize = 10
const loading = ref(false)

async function loadJds() {
  const res = await jdApi.page({ pageNo: 1, pageSize: 100 })
  jds.value = res.data.records
  if (jds.value.length > 0 && !selectedJdId.value) {
    selectedJdId.value = jds.value[0].id
  }
}

function onTabChange() {
  load(1)
}

async function load(page = 1) {
  pageNo.value = page
  loading.value = true
  try {
    const params = {
      pageNo: pageNo.value,
      pageSize,
      passStatus: filterStatus.value || undefined
    }
    if (activeTab.value === 'received') {
      // 已收简历:仅入库成功者;可按岗位/评分状态筛选
      params.hasResumeFile = true
      params.jdId = selectedJdId.value || undefined
    } else {
      // 待分配:无有效岗位者(含已入库附件)
      params.unassigned = true
    }
    const res = await candidateApi.page(params)
    rows.value = res.data.records
    total.value = Number(res.data.total)
  } finally {
    loading.value = false
  }
}

function snapshotSummary(snapshot) {
  try {
    const s = JSON.parse(snapshot)
    return [s.title, s.salary, s.city, s.experience, s.company].filter(Boolean).join(' | ')
  } catch {
    return snapshot
  }
}

function greetTagType(status) {
  return { SENT: 'primary', AGREED: 'success', REQUESTED: 'warning', PENDING_CONFIRM: 'info', SEND_FAILED: 'danger' }[status] || 'info'
}
function greetText(status) {
  return { SENT: '已打招呼', AGREED: '候选人已同意', REQUESTED: '已索要简历', PENDING_CONFIRM: '待确认', SEND_FAILED: '发送失败' }[status] || status
}

async function runScore() {
  const res = await recruitApi.score({ jdId: selectedJdId.value, limit: 10 })
  ElMessage.success(`评分完成: ${res.data.scored} 人,通过 ${res.data.passed} 人`)
  load()
}

async function runGreet() {
  const res = await recruitApi.greet({ jdId: selectedJdId.value, limit: 10 })
  ElMessage.success(`打招呼完成: ${res.data.greeted} 人`)
  load()
}

async function runCollect() {
  const res = await recruitApi.collect({ jdId: selectedJdId.value, limit: 50 })
  ElMessage.success(`检测处理: ${res.data.processed} 人`)
  load()
}

async function redo(candidateId) {
  await recruitApi.redo(candidateId)
  ElMessage.success('已重新打分')
  load()
}

const recommendLoading = ref(false)

async function runRecommend() {
  recommendLoading.value = true
  try {
    await searchTaskApi.createRecommend({ jdId: selectedJdId.value, accountId: 1 })
    await searchTaskApi.tick()
    ElMessage.success('平台推荐任务已入队,稍后刷新查看候选人')
  } finally {
    recommendLoading.value = false
  }
}

onMounted(async () => {
  await loadJds()
  await load()
})
</script>

<style scoped>
.toolbar {
  margin-bottom: 12px;
  display: flex;
  gap: 8px;
}
.pagination {
  margin-top: 12px;
  justify-content: flex-end;
}
.score-reason {
  margin-left: 6px;
  color: var(--hr-text-3);
  font-size: 12px;
}
.tab-hint {
  color: var(--hr-text-3);
  font-size: 12px;
  align-self: center;
}
</style>
