<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { DocumentChecked, Refresh, Search } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import { http } from '../api/http'
import { useAuthStore } from '../stores/auth'

interface AssetRow {
  id: string
  code: string
  display_name: string
  building_area: string
  occupancy_status: string
}

interface PreviewLine {
  allocationId: string
  assetId: string
  assetName: string
  itemName: string
  quantity: string
  unitPrice: string
  coefficient: string
  amount: string
  assumptionRule: boolean
}

const auth = useAuthStore()
const communityId = computed(() => auth.currentProjectId)
const period = ref(dayjs().format('YYYY-MM'))
const keyword = ref('')
const assets = ref<AssetRow[]>([])
const selected = ref<AssetRow[]>([])
const previewItems = ref<PreviewLine[]>([])
const previewTotal = ref('0.00')
const loadingAssets = ref(false)
const running = ref(false)
const lastJob = ref<{ jobId: string; status: string; generatedCount: number; errorCount: number; replayed: boolean } | null>(null)

async function loadAssets() {
  if (!communityId.value) return
  loadingAssets.value = true
  try {
    const { data } = await http.get('/data/assets', {
      params: { communityId: communityId.value, category: 'ROOM', keyword: keyword.value || undefined, page: 1, size: 100, sort: 'code,asc' },
    })
    assets.value = data.items
  } catch (error: any) {
    ElMessage.error(error.response?.data?.message || '资产加载失败')
  } finally {
    loadingAssets.value = false
  }
}

function requestBody() {
  return { communityId: communityId.value, billingPeriod: period.value, assetIds: selected.value.map((item) => item.id) }
}

async function preview() {
  if (!period.value || selected.value.length === 0) {
    ElMessage.warning('请选择账期和至少一项资产')
    return
  }
  running.value = true
  try {
    const { data } = await http.post('/receivable-jobs:preview', requestBody())
    previewItems.value = data.items
    previewTotal.value = data.totalAmount
    lastJob.value = null
    ElMessage.success(`试算完成，共 ${data.lineCount} 条费用明细`)
  } catch (error: any) {
    ElMessage.error(error.response?.data?.message || '应收试算失败')
  } finally {
    running.value = false
  }
}

async function generate() {
  if (previewItems.value.length === 0) {
    ElMessage.warning('请先完成试算并核对结果')
    return
  }
  running.value = true
  try {
    const { data } = await http.post('/receivable-jobs', requestBody(), {
      headers: { 'Idempotency-Key': crypto.randomUUID() },
    })
    lastJob.value = data
    ElMessage.success(`应收任务已完成，生成 ${data.generatedCount} 笔账单`)
  } catch (error: any) {
    ElMessage.error(error.response?.data?.message || '应收生成失败')
  } finally {
    running.value = false
  }
}

watch(communityId, () => {
  selected.value = []
  previewItems.value = []
  void loadAssets()
})
onMounted(loadAssets)
</script>

<template>
  <div class="workflow-page">
    <el-alert title="应收生成采用“先试算、后落账”流程；金额按字符串传输并由后端 Decimal 计算。" type="info" show-icon :closable="false" />
    <el-card class="workflow-card" shadow="never">
      <template #header>
        <div class="workflow-card__header">
          <div><span class="section-kicker">RECEIVABLE WORKFLOW</span><h3>选择账期与计费资产</h3></div>
          <el-tag effect="plain">已选择 {{ selected.length }} 项</el-tag>
        </div>
      </template>
      <el-form inline>
        <el-form-item label="计费账期">
          <el-date-picker v-model="period" type="month" value-format="YYYY-MM" format="YYYY 年 MM 月" :clearable="false" />
        </el-form-item>
        <el-form-item label="房屋检索">
          <el-input v-model="keyword" placeholder="房号或房屋名称" clearable @keyup.enter="loadAssets">
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
        </el-form-item>
        <el-form-item><el-button :icon="Refresh" :loading="loadingAssets" @click="loadAssets">查询</el-button></el-form-item>
      </el-form>
      <el-table v-loading="loadingAssets" :data="assets" height="330" @selection-change="selected = $event">
        <el-table-column type="selection" width="48" />
        <el-table-column prop="code" label="房屋编码" width="150" />
        <el-table-column prop="display_name" label="房屋名称" min-width="220" />
        <el-table-column prop="building_area" label="建筑面积" width="120" />
        <el-table-column prop="occupancy_status" label="入住状态" width="120" />
      </el-table>
      <div class="workflow-actions">
        <el-button type="primary" plain :loading="running" :disabled="selected.length === 0" @click="preview">计算应收预览</el-button>
        <el-button type="primary" :icon="DocumentChecked" :loading="running" :disabled="previewItems.length === 0" @click="generate">确认生成账单</el-button>
      </div>
    </el-card>

    <el-card v-if="previewItems.length" class="workflow-card" shadow="never">
      <template #header>
        <div class="workflow-card__header">
          <div><span class="section-kicker">CALCULATION SNAPSHOT</span><h3>试算明细</h3></div>
          <div class="amount-summary"><span>试算合计</span><strong>¥ {{ previewTotal }}</strong></div>
        </div>
      </template>
      <el-table :data="previewItems" max-height="420">
        <el-table-column prop="assetName" label="资产" min-width="180" />
        <el-table-column prop="itemName" label="费用项目" min-width="180" />
        <el-table-column prop="quantity" label="数量" width="105" />
        <el-table-column prop="unitPrice" label="单价" width="105" />
        <el-table-column prop="coefficient" label="系数" width="90" />
        <el-table-column prop="amount" label="金额" width="120" />
        <el-table-column label="口径" width="110"><template #default="scope"><el-tag v-if="scope.row.assumptionRule" type="warning" effect="plain">假设规则</el-tag></template></el-table-column>
      </el-table>
      <el-result v-if="lastJob" icon="success" title="应收任务执行成功" :sub-title="`任务 ${lastJob.jobId} · 生成 ${lastJob.generatedCount} 笔 · 错误 ${lastJob.errorCount} 笔`" />
    </el-card>
  </div>
</template>
