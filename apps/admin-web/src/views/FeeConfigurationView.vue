<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Select } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import { http } from '../api/http'
import StatusTag from '../components/shared/StatusTag.vue'
import { useAuthStore } from '../stores/auth'

type Row = Record<string, any>

const route = useRoute()
const auth = useAuthStore()
const communityId = computed(() => auth.currentProjectId)
const mode = computed(() => route.path.endsWith('/allocations') ? 'allocations' : route.path.endsWith('/standards') ? 'standards' : 'definitions')
const canWrite = computed(() => auth.hasPermission('fee:write'))
const loading = ref(false)
const definitions = ref<Row[]>([])
const standards = ref<Row[]>([])
const allocations = ref<Row[]>([])
const targets = ref<Row[]>([])
const selectedTargets = ref<Row[]>([])
const selectedAllocations = ref<Row[]>([])
const allocationPreview = ref<any>(null)
const definitionVisible = ref(false)
const standardVisible = ref(false)
const versionVisible = ref(false)
const cancelVisible = ref(false)
const editingDefinition = ref<Row | null>(null)
const versionStandard = ref<Row | null>(null)
const submitting = ref(false)

const definitionForm = reactive({
  code: '', name: '', feeType: 'PROPERTY', feeClass: 'PERIODIC', unitCode: 'M2_MONTH', decimalScale: 2,
  lateFeeEnabled: false, temporaryAllowed: false, accountingSubjectCode: '', prepaymentSubjectCode: '',
  taxCategoryCode: '', taxRate: '0', roundingMode: 'HALF_UP', currencyCode: 'CNY', enabled: true,
})
const standardForm = reactive({
  feeDefinitionId: '', code: '', name: '', assetType: 'ROOM', billingCycle: 'MONTHLY',
  calculationBasis: 'BUILDING_AREA', prorationRule: 'FULL_PERIOD', unitPrice: '1.00',
  minimumAmount: '', maximumAmount: '', formulaCode: 'AREA_PRICE', formulaExpression: 'quantity * unitPrice * coefficient',
  effectiveFrom: dayjs().startOf('month').format('YYYY-MM-DD'), effectiveTo: '',
})
const versionForm = reactive({ unitPrice: '1.00', minimumAmount: '', maximumAmount: '', formulaCode: 'AREA_PRICE',
  formulaExpression: 'quantity * unitPrice * coefficient', effectiveFrom: '', effectiveTo: '' })
const allocationForm = reactive({ feeStandardId: '', coefficient: '1', effectiveFrom: dayjs().startOf('month').format('YYYY-MM-DD'), effectiveTo: '', sourceType: 'MANUAL' })
const cancelForm = reactive({ effectiveTo: dayjs().format('YYYY-MM-DD'), reason: '' })

const selectedStandard = computed(() => standards.value.find((item) => item.id === allocationForm.feeStandardId) || null)
const targetType = computed(() => selectedStandard.value?.assetType === 'METER' ? 'METER' : 'ASSET')
const pageCopy = computed(() => mode.value === 'allocations'
  ? { no: '09', kicker: 'ALLOCATION GOVERNANCE', title: '费用分配与生效范围', note: '批量分配先预览命中对象；同一标准与对象的有效期禁止重叠。' }
  : mode.value === 'standards'
    ? { no: '08A', kicker: 'STANDARD VERSIONING', title: '费用标准与版本', note: '版本只追加不覆盖，历史账单始终引用生成时快照。' }
    : { no: '08', kicker: 'FEE DEFINITION', title: '费用定义与财税口径', note: '统一管理科目、税目、精度、舍入规则与临时应收边界。' })

async function load() {
  if (!communityId.value) return
  loading.value = true
  try {
    const [definitionResponse, standardResponse] = await Promise.all([
      http.get('/fees/definitions', { params: { communityId: communityId.value } }),
      http.get('/fees/standards', { params: { communityId: communityId.value } }),
    ])
    definitions.value = definitionResponse.data
    standards.value = standardResponse.data
    if (mode.value === 'allocations') {
      if (!allocationForm.feeStandardId || !standards.value.some((item) => item.id === allocationForm.feeStandardId)) {
        allocationForm.feeStandardId = standards.value.find((item) => item.status === 'ACTIVE')?.id || ''
      }
      await Promise.all([loadTargets(), loadAllocations()])
    }
  } catch (error: any) {
    ElMessage.error(error.response?.data?.message || '费用配置加载失败')
  } finally { loading.value = false }
}

async function loadTargets() {
  if (!communityId.value || !selectedStandard.value) { targets.value = []; return }
  const resource = targetType.value === 'METER' ? 'meters' : 'assets'
  const params: Row = { communityId: communityId.value, page: 1, size: 100 }
  if (targetType.value === 'ASSET') params.category = selectedStandard.value.assetType
  const { data } = await http.get(`/data/${resource}`, { params })
  targets.value = data.items
  selectedTargets.value = []
  allocationPreview.value = null
}

async function loadAllocations() {
  if (!communityId.value) return
  const { data } = await http.get('/fees/allocations', { params: {
    communityId: communityId.value, feeStandardId: allocationForm.feeStandardId || undefined,
  } })
  allocations.value = data
  selectedAllocations.value = []
}

function openDefinition(row?: Row) {
  editingDefinition.value = row || null
  Object.assign(definitionForm, row ? {
    code: row.code, name: row.name, feeType: row.feeType, feeClass: row.feeClass, unitCode: row.unitCode,
    decimalScale: row.decimalScale, lateFeeEnabled: Boolean(row.lateFeeEnabled), temporaryAllowed: Boolean(row.temporaryAllowed),
    accountingSubjectCode: row.accountingSubjectCode || '', prepaymentSubjectCode: row.prepaymentSubjectCode || '',
    taxCategoryCode: row.taxCategoryCode || '', taxRate: String(row.taxRate || '0'), roundingMode: row.roundingMode,
    currencyCode: row.currencyCode, enabled: Boolean(row.enabled),
  } : {
    code: '', name: '', feeType: 'PROPERTY', feeClass: 'PERIODIC', unitCode: 'M2_MONTH', decimalScale: 2,
    lateFeeEnabled: false, temporaryAllowed: false, accountingSubjectCode: '', prepaymentSubjectCode: '',
    taxCategoryCode: '', taxRate: '0', roundingMode: 'HALF_UP', currencyCode: 'CNY', enabled: true,
  })
  definitionVisible.value = true
}

async function saveDefinition() {
  if (!definitionForm.code || !definitionForm.name) return ElMessage.warning('请填写费用编码和名称')
  submitting.value = true
  const payload: Row = { communityId: communityId.value, ...definitionForm }
  for (const key of ['accountingSubjectCode', 'prepaymentSubjectCode', 'taxCategoryCode']) if (!payload[key]) payload[key] = null
  try {
    if (editingDefinition.value) {
      delete payload.communityId
      delete payload.code
      payload.expectedVersion = editingDefinition.value.version
      await http.put(`/fees/definitions/${editingDefinition.value.id}`, payload, { params: { communityId: communityId.value } })
      ElMessage.success('费用定义已按版本更新')
    } else {
      await http.post('/fees/definitions', payload)
      ElMessage.success('费用定义已创建')
    }
    definitionVisible.value = false
    await load()
  } catch (error: any) { ElMessage.error(error.response?.data?.message || '费用定义保存失败') }
  finally { submitting.value = false }
}

function openStandard() {
  Object.assign(standardForm, { feeDefinitionId: definitions.value.find((item) => item.enabled)?.id || '', code: '', name: '',
    assetType: 'ROOM', billingCycle: 'MONTHLY', calculationBasis: 'BUILDING_AREA', prorationRule: 'FULL_PERIOD',
    unitPrice: '1.00', minimumAmount: '', maximumAmount: '', formulaCode: 'AREA_PRICE',
    formulaExpression: 'quantity * unitPrice * coefficient', effectiveFrom: dayjs().startOf('month').format('YYYY-MM-DD'), effectiveTo: '' })
  standardVisible.value = true
}

async function saveStandard() {
  submitting.value = true
  try {
    await http.post('/fees/standards', nullableAmounts({ communityId: communityId.value, ...standardForm }))
    ElMessage.success('费用标准及首个版本已发布')
    standardVisible.value = false
    await load()
  } catch (error: any) { ElMessage.error(error.response?.data?.message || '费用标准创建失败') }
  finally { submitting.value = false }
}

function openVersion(row: Row) {
  versionStandard.value = row
  Object.assign(versionForm, { unitPrice: row.unitPrice || '1.00', minimumAmount: row.minimumAmount || '', maximumAmount: row.maximumAmount || '',
    formulaCode: row.formulaCode || 'AREA_PRICE', formulaExpression: row.formulaExpression || 'quantity * unitPrice * coefficient',
    effectiveFrom: row.effectiveTo ? dayjs(row.effectiveTo).add(1, 'day').format('YYYY-MM-DD') : '', effectiveTo: '' })
  versionVisible.value = true
}

async function saveVersion() {
  if (!versionStandard.value) return
  submitting.value = true
  try {
    await http.post(`/fees/standards/${versionStandard.value.id}/versions`, nullableAmounts({ communityId: communityId.value, ...versionForm }))
    ElMessage.success('新版本已发布，旧版本保持不变')
    versionVisible.value = false
    await load()
  } catch (error: any) { ElMessage.error(error.response?.data?.message || '标准版本发布失败') }
  finally { submitting.value = false }
}

async function disableStandard(row: Row) {
  await ElMessageBox.confirm('停用后新账期不再命中该标准，历史账单不会变化。', '停用费用标准', { type: 'warning' })
  try {
    await http.post(`/fees/standards/${row.id}:disable`, null, { params: { communityId: communityId.value } })
    ElMessage.success('费用标准已停用')
    await load()
  } catch (error: any) { ElMessage.error(error.response?.data?.message || '费用标准停用失败') }
}

function allocationPayload() {
  return { communityId: communityId.value, feeStandardId: allocationForm.feeStandardId, targetType: targetType.value,
    targetIds: selectedTargets.value.map((item) => item.id), coefficient: allocationForm.coefficient,
    effectiveFrom: allocationForm.effectiveFrom, effectiveTo: allocationForm.effectiveTo || null, sourceType: allocationForm.sourceType }
}

async function previewAllocation() {
  if (!allocationForm.feeStandardId || !selectedTargets.value.length) return ElMessage.warning('请选择费用标准和分配对象')
  try {
    const { data } = await http.post('/fees/allocations:preview', allocationPayload())
    allocationPreview.value = data
    ElMessage.success(`预览完成，${data.changed} 项可分配，${data.skipped} 项将重放跳过`)
  } catch (error: any) { ElMessage.error(error.response?.data?.message || '分配预览失败') }
}

async function assignAllocation() {
  if (!allocationPreview.value) return ElMessage.warning('请先预览命中范围')
  submitting.value = true
  try {
    const { data } = await http.post('/fees/allocations:assign', allocationPayload())
    ElMessage.success(`分配完成：新增 ${data.changed}，跳过 ${data.skipped}`)
    allocationPreview.value = null
    await loadAllocations()
  } catch (error: any) { ElMessage.error(error.response?.data?.message || '费用分配失败') }
  finally { submitting.value = false }
}

async function cancelAllocations() {
  if (!selectedAllocations.value.length || !cancelForm.reason) return ElMessage.warning('请选择分配记录并填写失效原因')
  submitting.value = true
  try {
    const { data } = await http.post('/fees/allocations:cancel', { communityId: communityId.value,
      allocationIds: selectedAllocations.value.map((item) => item.id), effectiveTo: cancelForm.effectiveTo, reason: cancelForm.reason })
    ElMessage.success(`已截止 ${data.changed} 条分配`)
    cancelVisible.value = false
    await loadAllocations()
  } catch (error: any) { ElMessage.error(error.response?.data?.message || '批量失效失败') }
  finally { submitting.value = false }
}

function nullableAmounts(payload: Row): Row {
  const result: Row = { ...payload }
  for (const key of ['minimumAmount', 'maximumAmount', 'effectiveTo']) if (result[key] === '') result[key] = null
  return result
}

watch([communityId, mode], () => void load())
watch(() => allocationForm.feeStandardId, () => mode.value === 'allocations' && void Promise.all([loadTargets(), loadAllocations()]))
onMounted(() => void load())
</script>

<template>
  <section v-loading="loading" class="fee-config-page">
    <el-alert type="info" :closable="false" show-icon :title="`页面 ${pageCopy.no} · ${pageCopy.note}`" />

    <el-card v-if="mode === 'definitions'" class="workflow-card" shadow="never">
      <template #header><div class="workflow-card__header"><div><span class="section-kicker">{{ pageCopy.kicker }}</span><h3>{{ pageCopy.title }}</h3></div>
        <div><el-button :icon="Refresh" @click="load">刷新</el-button><el-button v-if="canWrite" type="primary" :icon="Plus" @click="openDefinition()">新增费用定义</el-button></div></div></template>
      <el-table :data="definitions" stripe height="calc(100vh - 330px)" empty-text="暂无费用定义">
        <el-table-column prop="code" label="费用编码" width="135" /><el-table-column prop="name" label="费用名称" min-width="170" />
        <el-table-column prop="feeType" label="类型" width="105" /><el-table-column prop="feeClass" label="类别" width="105" />
        <el-table-column prop="unitCode" label="单位" width="110" /><el-table-column label="舍入" width="125"><template #default="scope">{{ scope.row.decimalScale }} 位 / {{ scope.row.roundingMode }}</template></el-table-column>
        <el-table-column prop="taxCategoryCode" label="税目" width="125" /><el-table-column prop="accountingSubjectCode" label="会计科目" width="125" />
        <el-table-column label="临时应收" width="95"><template #default="scope"><StatusTag :value="scope.row.temporaryAllowed" /></template></el-table-column>
        <el-table-column label="状态" width="90"><template #default="scope"><StatusTag :value="scope.row.enabled" /></template></el-table-column>
        <el-table-column label="操作" width="90" fixed="right"><template #default="scope"><el-button link type="primary" @click="openDefinition(scope.row)">编辑</el-button></template></el-table-column>
      </el-table>
    </el-card>

    <el-card v-else-if="mode === 'standards'" class="workflow-card" shadow="never">
      <template #header><div class="workflow-card__header"><div><span class="section-kicker">{{ pageCopy.kicker }}</span><h3>{{ pageCopy.title }}</h3></div>
        <div><el-button :icon="Refresh" @click="load">刷新</el-button><el-button v-if="canWrite" type="primary" :icon="Plus" @click="openStandard">新增费用标准</el-button></div></div></template>
      <el-table :data="standards" stripe height="calc(100vh - 330px)" empty-text="暂无费用标准">
        <el-table-column prop="code" label="标准编码" width="135" /><el-table-column prop="name" label="标准名称" min-width="170" />
        <el-table-column prop="feeDefinitionName" label="费用定义" min-width="160" /><el-table-column prop="assetType" label="对象" width="90" />
        <el-table-column prop="calculationBasis" label="计费基数" width="135" /><el-table-column prop="unitPrice" label="当前单价" width="105" />
        <el-table-column label="当前版本" width="105"><template #default="scope">V{{ scope.row.currentVersionNo || '—' }}</template></el-table-column>
        <el-table-column prop="effectiveFrom" label="生效日" width="115" /><el-table-column prop="effectiveTo" label="失效日" width="115" />
        <el-table-column label="状态" width="95"><template #default="scope"><StatusTag :value="scope.row.status" /></template></el-table-column>
        <el-table-column label="操作" width="150" fixed="right"><template #default="scope"><el-button link type="primary" @click="openVersion(scope.row)">发布版本</el-button><el-button v-if="scope.row.status === 'ACTIVE'" link type="danger" @click="disableStandard(scope.row)">停用</el-button></template></el-table-column>
      </el-table>
    </el-card>

    <template v-else>
      <el-card class="workflow-card" shadow="never">
        <template #header><div class="workflow-card__header"><div><span class="section-kicker">{{ pageCopy.kicker }}</span><h3>{{ pageCopy.title }}</h3></div><el-tag effect="plain">已选 {{ selectedTargets.length }} 个对象</el-tag></div></template>
        <el-form inline>
          <el-form-item label="费用标准"><el-select v-model="allocationForm.feeStandardId" filterable style="width: 260px"><el-option v-for="item in standards.filter((row) => row.status === 'ACTIVE')" :key="item.id" :label="`${item.code} · ${item.name}`" :value="item.id" /></el-select></el-form-item>
          <el-form-item label="系数"><el-input v-model="allocationForm.coefficient" style="width: 100px" /></el-form-item>
          <el-form-item label="生效日期"><el-date-picker v-model="allocationForm.effectiveFrom" type="date" value-format="YYYY-MM-DD" style="width: 150px" /></el-form-item>
          <el-form-item label="失效日期"><el-date-picker v-model="allocationForm.effectiveTo" type="date" value-format="YYYY-MM-DD" clearable style="width: 150px" /></el-form-item>
        </el-form>
        <el-table :data="targets" height="245" @selection-change="selectedTargets = $event">
          <el-table-column type="selection" width="48" /><el-table-column :prop="targetType === 'METER' ? 'meter_no' : 'code'" label="对象编码" width="165" />
          <el-table-column :prop="targetType === 'METER' ? 'meter_no' : 'display_name'" label="对象名称" min-width="220" />
          <el-table-column v-if="targetType === 'ASSET'" prop="building_area" label="建筑面积" width="120" />
          <el-table-column v-else prop="meter_type" label="仪表类型" width="120" />
        </el-table>
        <div class="workflow-actions"><el-button :icon="Select" :disabled="!selectedTargets.length" @click="previewAllocation">预览命中范围</el-button><el-button type="primary" :loading="submitting" :disabled="!allocationPreview" @click="assignAllocation">确认批量分配</el-button></div>
        <el-alert v-if="allocationPreview" type="success" :closable="false" :title="`预览结果：${allocationPreview.changed} 项可新增，${allocationPreview.skipped} 项将幂等跳过`" />
      </el-card>
      <el-card class="workflow-card" shadow="never">
        <template #header><div class="workflow-card__header"><div><span class="section-kicker">EFFECTIVE TIMELINE</span><h3>已分配费用与有效期</h3></div><div><el-button :icon="Refresh" @click="loadAllocations">刷新</el-button><el-button type="danger" plain :disabled="!selectedAllocations.length" @click="cancelVisible = true">批量截止</el-button></div></div></template>
        <el-table :data="allocations" stripe max-height="310" @selection-change="selectedAllocations = $event">
          <el-table-column type="selection" width="48" /><el-table-column prop="standardCode" label="标准" width="130" /><el-table-column prop="targetName" label="对象" min-width="170" />
          <el-table-column prop="targetType" label="类型" width="90" /><el-table-column prop="coefficient" label="系数" width="90" />
          <el-table-column prop="effectiveFrom" label="生效日期" width="115" /><el-table-column prop="effectiveTo" label="失效日期" width="115" />
          <el-table-column prop="sourceType" label="来源" width="100" /><el-table-column prop="cancellationReason" label="失效原因" min-width="150" />
          <el-table-column label="状态" width="95"><template #default="scope"><StatusTag :value="scope.row.status" /></template></el-table-column>
        </el-table>
      </el-card>
    </template>

    <el-dialog v-model="definitionVisible" :title="editingDefinition ? '编辑费用定义' : '新增费用定义'" width="760px">
      <el-form label-width="105px" class="config-form-grid">
        <el-form-item label="费用编码" required><el-input v-model="definitionForm.code" :disabled="Boolean(editingDefinition)" /></el-form-item><el-form-item label="费用名称" required><el-input v-model="definitionForm.name" /></el-form-item>
        <el-form-item label="费用类型"><el-select v-model="definitionForm.feeType"><el-option v-for="item in ['PROPERTY','PARKING','METER','TEMPORARY']" :key="item" :label="item" :value="item" /></el-select></el-form-item>
        <el-form-item label="计费类别"><el-select v-model="definitionForm.feeClass"><el-option v-for="item in ['PERIODIC','USAGE','TEMPORARY']" :key="item" :label="item" :value="item" /></el-select></el-form-item>
        <el-form-item label="计量单位"><el-input v-model="definitionForm.unitCode" /></el-form-item><el-form-item label="金额精度"><el-input-number v-model="definitionForm.decimalScale" :min="0" :max="2" /></el-form-item>
        <el-form-item label="舍入方式"><el-select v-model="definitionForm.roundingMode"><el-option v-for="item in ['HALF_UP','HALF_EVEN','DOWN','UP']" :key="item" :label="item" :value="item" /></el-select></el-form-item>
        <el-form-item label="税率"><el-input v-model="definitionForm.taxRate" /></el-form-item><el-form-item label="税目编码"><el-input v-model="definitionForm.taxCategoryCode" /></el-form-item>
        <el-form-item label="会计科目"><el-input v-model="definitionForm.accountingSubjectCode" /></el-form-item><el-form-item label="预收科目"><el-input v-model="definitionForm.prepaymentSubjectCode" /></el-form-item>
        <el-form-item label="临时应收"><el-switch v-model="definitionForm.temporaryAllowed" /></el-form-item><el-form-item label="启用"><el-switch v-model="definitionForm.enabled" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="definitionVisible = false">取消</el-button><el-button type="primary" :loading="submitting" @click="saveDefinition">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="standardVisible" title="新增费用标准与首个版本" width="780px"><el-form label-width="110px" class="config-form-grid">
      <el-form-item label="费用定义" required><el-select v-model="standardForm.feeDefinitionId" filterable><el-option v-for="item in definitions.filter((row) => row.enabled)" :key="item.id" :label="`${item.code} · ${item.name}`" :value="item.id" /></el-select></el-form-item>
      <el-form-item label="标准编码" required><el-input v-model="standardForm.code" /></el-form-item><el-form-item label="标准名称" required><el-input v-model="standardForm.name" /></el-form-item>
      <el-form-item label="对象类型"><el-select v-model="standardForm.assetType"><el-option v-for="item in ['ROOM','PARKING','METER']" :key="item" :label="item" :value="item" /></el-select></el-form-item>
      <el-form-item label="计费基数"><el-select v-model="standardForm.calculationBasis"><el-option v-for="item in ['BUILDING_AREA','USABLE_AREA','FIXED','METER_USAGE']" :key="item" :label="item" :value="item" /></el-select></el-form-item>
      <el-form-item label="单价"><el-input v-model="standardForm.unitPrice" /></el-form-item><el-form-item label="最低金额"><el-input v-model="standardForm.minimumAmount" /></el-form-item><el-form-item label="最高金额"><el-input v-model="standardForm.maximumAmount" /></el-form-item>
      <el-form-item label="生效日期"><el-date-picker v-model="standardForm.effectiveFrom" type="date" value-format="YYYY-MM-DD" /></el-form-item><el-form-item label="失效日期"><el-date-picker v-model="standardForm.effectiveTo" type="date" value-format="YYYY-MM-DD" clearable /></el-form-item>
      <el-form-item label="公式表达式" class="span-two"><el-input v-model="standardForm.formulaExpression" /></el-form-item>
    </el-form><template #footer><el-button @click="standardVisible = false">取消</el-button><el-button type="primary" :loading="submitting" @click="saveStandard">发布</el-button></template></el-dialog>

    <el-dialog v-model="versionVisible" :title="`发布新版本 · ${versionStandard?.name || ''}`" width="680px"><el-alert type="warning" :closable="false" title="新版本有效期不得与已有版本重叠；已有版本与历史账单不会被覆盖。" /><el-form label-width="105px" class="config-form-grid dialog-form">
      <el-form-item label="单价"><el-input v-model="versionForm.unitPrice" /></el-form-item><el-form-item label="最低金额"><el-input v-model="versionForm.minimumAmount" /></el-form-item><el-form-item label="最高金额"><el-input v-model="versionForm.maximumAmount" /></el-form-item>
      <el-form-item label="生效日期"><el-date-picker v-model="versionForm.effectiveFrom" type="date" value-format="YYYY-MM-DD" /></el-form-item><el-form-item label="失效日期"><el-date-picker v-model="versionForm.effectiveTo" type="date" value-format="YYYY-MM-DD" clearable /></el-form-item>
      <el-form-item label="公式表达式" class="span-two"><el-input v-model="versionForm.formulaExpression" /></el-form-item>
    </el-form><template #footer><el-button @click="versionVisible = false">取消</el-button><el-button type="primary" :loading="submitting" @click="saveVersion">发布版本</el-button></template></el-dialog>

    <el-dialog v-model="cancelVisible" title="批量截止费用分配" width="520px"><el-alert type="warning" :closable="false" :title="`将截止 ${selectedAllocations.length} 条记录；截止日前的历史计费仍可追溯。`" /><el-form label-width="90px" class="dialog-form"><el-form-item label="失效日期"><el-date-picker v-model="cancelForm.effectiveTo" type="date" value-format="YYYY-MM-DD" /></el-form-item><el-form-item label="失效原因"><el-input v-model="cancelForm.reason" type="textarea" :rows="3" /></el-form-item></el-form><template #footer><el-button @click="cancelVisible = false">取消</el-button><el-button type="danger" :loading="submitting" @click="cancelAllocations">确认截止</el-button></template></el-dialog>
  </section>
</template>

<style scoped>
.fee-config-page { display: flex; flex-direction: column; gap: 14px; min-width: 0; }
.config-form-grid { display: grid; grid-template-columns: 1fr 1fr; column-gap: 22px; }
.config-form-grid .el-select, .config-form-grid .el-date-editor, .config-form-grid .el-input-number { width: 100%; }
.config-form-grid .span-two { grid-column: 1 / -1; }
.dialog-form { margin-top: 18px; }
@media (max-width: 1180px) { .config-form-grid { grid-template-columns: 1fr; }.config-form-grid .span-two { grid-column: auto; } }
</style>
