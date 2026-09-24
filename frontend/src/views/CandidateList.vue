<template>
  <div>
    <div class="toolbar">
      <el-select v-model="selectedJdId" placeholder="选择岗位" clearable style="width: 220px" @change="load()">
        <el-option v-for="jd in jds" :key="jd.id" :label="jd.title" :value="jd.id" />
      </el-select>
      <el-select v-model="filterStatus" placeholder="评分状态" clearable style="width: 140px" @change="load()">
        <el-option label="待评分" value="PENDING" />
        <el-option label="已通过" value="PASS" />
        <el-option label="未通过" value="FAIL" />
      </el-select>
      <el-button type="primary" :disabled="!selectedJdId" @click="runScore">批量评分(10人)</el-button>
      <el-button type="success" :disabled="!selectedJdId" @click="runGreet">批量打招呼(10人)</el-button>
      <el-button :disabled="!selectedJdId" @click="runCollect">检测回复并索要简历</el-button>
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
            <el-tag :type="greetTagType(row.greeting.status)" size="small">{{ greetText(row.greeting.status) }}</el-tag>
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
      <el-table-column label="操作" width="140" fixed="right">
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
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { jdApi, candidateApi, recruitApi } from '../api/modules'

const rows = ref([])
const jds = ref([])
const selectedJdId = ref(null)
const filterStatus = ref('')
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

async function load(page = 1) {
  pageNo.value = page
  loading.value = true
  try {
    const res = await candidateApi.page({
      pageNo: pageNo.value,
      pageSize,
      jdId: selectedJdId.value || undefined,
      passStatus: filterStatus.value || undefined
    })
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
  return { SENT: 'primary', AGREED: 'success', REQUESTED: 'warning', PENDING_CONFIRM: 'info' }[status] || 'info'
}
function greetText(status) {
  return { SENT: '已打招呼', AGREED: '候选人已同意', REQUESTED: '已索要简历', PENDING_CONFIRM: '待确认' }[status] || status
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
  color: #999;
  font-size: 12px;
}
</style>
