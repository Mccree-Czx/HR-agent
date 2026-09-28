<template>
  <el-drawer
    :model-value="modelValue"
    size="620px"
    :with-header="false"
    @update:model-value="(v) => $emit('update:modelValue', v)"
    @open="loadDetail"
  >
    <template v-if="detail">
      <div class="cd-header">
        <div>
          <div class="cd-name">{{ detail.candidate.name || '匿名候选人' }}</div>
          <div class="cd-meta">
            <el-tag size="small" type="info">{{ detail.jdTitle || '无岗位' }}</el-tag>
            <el-tag size="small" :type="tagOf(RECRUIT_STATUS, cur)">{{ textOf(RECRUIT_STATUS, cur) }}</el-tag>
            <el-tag v-if="detail.candidate.passStatus === 'PASS'" size="small" type="success">评分通过</el-tag>
            <el-tag v-else-if="detail.candidate.passStatus === 'FAIL'" size="small" type="danger">评分未通过</el-tag>
            <el-tag v-else size="small" type="info">待评分</el-tag>
          </div>
        </div>
        <el-button text @click="$emit('update:modelValue', false)">关闭</el-button>
      </div>

      <el-card shadow="never" class="cd-card">
        <template #header>招聘状态</template>
        <div class="cd-status-row">
          <el-button :type="cur === 'QUALIFIED' ? 'primary' : 'default'" @click="setStatus('QUALIFIED')">合格</el-button>
          <el-button :type="cur === 'INTERVIEW_SCHEDULED' ? 'warning' : 'default'" @click="setStatus('INTERVIEW_SCHEDULED')">约面</el-button>
          <el-button :type="cur === 'NOT_SUITABLE' ? 'danger' : 'default'" @click="setStatus('NOT_SUITABLE')">不合适</el-button>
          <el-button text :disabled="cur === 'PENDING_REVIEW'" @click="setStatus('PENDING_REVIEW')">恢复待筛选</el-button>
        </div>
      </el-card>

      <el-card shadow="never" class="cd-card">
        <template #header>基本信息</template>
        <el-descriptions :column="2" size="small" border>
          <el-descriptions-item label="来源岗位">{{ detail.jdTitle || '-' }}</el-descriptions-item>
          <el-descriptions-item label="入库时间">{{ fmt(detail.candidate.createdAt) }}</el-descriptions-item>
          <el-descriptions-item v-for="f in snapshotFields" :key="f.label" :label="f.label">{{ f.value }}</el-descriptions-item>
        </el-descriptions>
      </el-card>

      <el-card shadow="never" class="cd-card">
        <template #header>AI 评分</template>
        <template v-if="detail.latestScore">
          <div class="cd-score-row">
            <span class="cd-score-num">{{ detail.latestScore.score }}</span>
            <span class="cd-stars">{{ starText(detail.latestScore.score) }}</span>
            <span class="cd-score-meta">{{ fmt(detail.latestScore.createdAt) }} · {{ detail.latestScore.model || '-' }}</span>
          </div>
          <div class="cd-reason">{{ detail.latestScore.reason || '无评分理由' }}</div>
        </template>
        <el-empty v-else description="尚未评分" :image-size="56" />
      </el-card>

      <el-card shadow="never" class="cd-card">
        <template #header>简历资料</template>
        <template v-if="detail.resumeFile">
          <div class="cd-file-row">
            <span class="cd-file-name">{{ detail.resumeFileName }}</span>
            <span class="cd-file-meta">{{ formatBytes(detail.resumeFile.size) }} · 入库 {{ fmt(detail.resumeFile.createdAt) }}</span>
          </div>
          <div class="cd-file-hint">
            最后查看：{{ lastViewedText }}
          </div>
          <div class="cd-file-actions">
            <el-button type="primary" :loading="previewLoading" @click="openPreview">预览简历</el-button>
            <el-button text type="primary" @click="openInNewTab">在新标签打开</el-button>
          </div>
          <iframe v-if="previewUrl" :src="previewUrl" class="cd-preview" title="简历预览" />
        </template>
        <el-empty v-else description="简历未入库" :image-size="56" />
      </el-card>
    </template>
    <el-skeleton v-else :rows="7" animated />
  </el-drawer>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { candidateApi } from '../api/modules'
import { RECRUIT_STATUS, textOf, tagOf, starText, formatBytes } from '../utils/labels'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  candidateId: { type: [Number, String], default: null }
})
const emit = defineEmits(['update:modelValue', 'updated'])

const detail = ref(null)
const previewUrl = ref('')
const previewLoading = ref(false)

const cur = computed(() => detail.value?.candidate?.recruitStatus || 'PENDING_REVIEW')

const lastViewedText = computed(() => {
  const c = detail.value?.candidate
  if (!c?.resumeLastViewedAt) return '未读'
  const isMe = detail.value?.lastViewedByName && detail.value.lastViewedByName === localStorage.getItem('username')
  const viewer = isMe ? '我' : (detail.value?.lastViewedByName || '他人')
  return `${viewer} · ${fmt(c.resumeLastViewedAt)}`
})

const snapshotFields = computed(() => {
  try {
    const s = JSON.parse(detail.value?.candidate?.snapshot || '{}')
    return [
      { label: '城市', value: s.city },
      { label: '薪资', value: s.salary },
      { label: '经验', value: s.experience },
      { label: '学历', value: s.degree },
      { label: '公司', value: s.company },
      { label: '职位', value: s.title }
    ].filter((f) => f.value)
  } catch {
    return []
  }
})

async function loadDetail() {
  if (!props.candidateId) return
  detail.value = null
  clearPreview()
  try {
    const res = await candidateApi.detail(props.candidateId)
    detail.value = res.data
  } catch {
    // 拦截器已提示(403/404)
  }
}

// 抽屉已打开时切换候选人:重新加载
watch(
  () => props.candidateId,
  (v) => {
    if (props.modelValue && v) loadDetail()
  }
)

async function setStatus(status) {
  if (status === cur.value) return
  try {
    await candidateApi.setRecruitStatus(props.candidateId, status)
    ElMessage.success(`已标记为「${textOf(RECRUIT_STATUS, status)}」`)
    await loadDetail()
    emit('updated')
  } catch {
    // 拦截器已提示
  }
}

async function openPreview() {
  previewLoading.value = true
  try {
    const blob = await candidateApi.resumeBlob(props.candidateId)
    clearPreview()
    previewUrl.value = URL.createObjectURL(new Blob([blob], { type: 'application/pdf' }))
    // 预览即写入读标记:通知父级刷新"最后查看"
    emit('updated')
  } catch {
    // 拦截器已提示
  } finally {
    previewLoading.value = false
  }
}

async function openInNewTab() {
  if (!previewUrl.value) await openPreview()
  if (previewUrl.value) window.open(previewUrl.value, '_blank')
}

function clearPreview() {
  if (previewUrl.value) {
    URL.revokeObjectURL(previewUrl.value)
    previewUrl.value = ''
  }
}

function fmt(t) {
  return t ? String(t).slice(0, 16).replace('T', ' ') : '-'
}

onBeforeUnmount(clearPreview)
</script>

<style scoped>
.cd-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 12px;
}
.cd-name {
  font-size: 18px;
  font-weight: 600;
  color: var(--hr-text-1);
}
.cd-meta {
  display: flex;
  gap: 6px;
  margin-top: 6px;
  flex-wrap: wrap;
}
.cd-card {
  margin-bottom: 12px;
  border-radius: var(--hr-radius-sm);
}
.cd-status-row {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.cd-score-row {
  display: flex;
  align-items: baseline;
  gap: 10px;
}
.cd-score-num {
  font-size: 28px;
  font-weight: 700;
  color: var(--el-color-primary);
}
.cd-stars {
  color: #e6a23c;
  font-size: 16px;
  letter-spacing: 2px;
}
.cd-score-meta {
  font-size: 12px;
  color: var(--hr-text-3);
}
.cd-reason {
  margin-top: 8px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--hr-text-2);
  white-space: pre-wrap;
}
.cd-file-row {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 8px;
}
.cd-file-name {
  font-weight: 600;
  color: var(--hr-text-1);
  word-break: break-all;
}
.cd-file-meta,
.cd-file-hint {
  font-size: 12px;
  color: var(--hr-text-3);
}
.cd-file-hint {
  margin-top: 4px;
}
.cd-file-actions {
  margin-top: 10px;
}
.cd-preview {
  margin-top: 12px;
  width: 100%;
  height: 560px;
  border: 1px solid var(--hr-border);
  border-radius: var(--hr-radius-sm);
}
</style>
