<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { CalendarDays, Network, Pencil, Plus, RefreshCw } from '@lucide/vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import { api } from '@/api/client/edutwin'
import { showcaseUiMode } from '@/showcase/runtime'
import PageFeedback from '@/shared/components/PageFeedback.vue'
import PageHeader from '@/shared/components/PageHeader.vue'
import PanelCard from '@/shared/components/PanelCard.vue'
import type { AcademicTerm, OrganizationUnit, OrganizationWrite } from '@/shared/types/api'
import { userErrorMessage } from '@/shared/utils/displayText'

const organizations = ref<OrganizationUnit[]>([])
const terms = ref<AcademicTerm[]>([])
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const activeTab = ref('organizations')
const organizationOpen = ref(false)
const termOpen = ref(false)
const editingOrganizationId = ref<string | null>(null)
const editingTermId = ref<string | null>(null)
const organizationForm = reactive<OrganizationWrite>({
  code: '', displayName: '', unitType: 'COLLEGE', parentId: null, enabled: true,
})
const termForm = reactive({ code: '', displayName: '', startsOn: '', endsOn: '', enabled: true })

const unitLabels: Record<string, string> = {
  UNIVERSITY: '学校', COLLEGE: '学院', DEPARTMENT: '系', MAJOR: '专业', CLASS: '班级',
}
const flatOrganizations = computed(() => {
  const result: OrganizationUnit[] = []
  const visit = (items: OrganizationUnit[]) => items.forEach((item) => {
    result.push(item)
    visit(item.children ?? [])
  })
  visit(organizations.value)
  return result
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [organizationList, termList] = await Promise.all([
      api.adminOrganizations(), api.adminAcademicTerms(),
    ])
    organizations.value = organizationList.items
    terms.value = termList.items
  } catch (cause) {
    error.value = userErrorMessage(cause, '组织与学期数据读取失败。')
  } finally {
    loading.value = false
  }
}

function openOrganization(item?: OrganizationUnit) {
  editingOrganizationId.value = item?.organizationId ?? null
  Object.assign(organizationForm, item ? {
    code: item.code,
    displayName: item.displayName,
    unitType: item.unitType,
    parentId: item.parentId,
    enabled: item.enabled,
  } : { code: '', displayName: '', unitType: 'COLLEGE', parentId: null, enabled: true })
  organizationOpen.value = true
}

async function saveOrganization() {
  if (!organizationForm.code.trim() || !organizationForm.displayName.trim()) {
    ElMessage.warning('组织编码和名称不能为空')
    return
  }
  saving.value = true
  try {
    const current = flatOrganizations.value.find((item) => item.organizationId === editingOrganizationId.value)
    if (current?.enabled && !organizationForm.enabled) {
      const impact = await api.adminOrganizationReferences(current.organizationId)
      await ElMessageBox.confirm(
        `该组织关联 ${impact.users} 个用户、${impact.courses} 门课程。停用后编码仍保留，确认继续？`,
        '确认停用组织',
        { type: 'warning', confirmButtonText: '确认停用' },
      )
    }
    const payload = { ...organizationForm, code: organizationForm.code.trim(), displayName: organizationForm.displayName.trim() }
    if (editingOrganizationId.value) await api.updateAdminOrganization(editingOrganizationId.value, payload)
    else await api.createAdminOrganization(payload)
    organizationOpen.value = false
    ElMessage.success(editingOrganizationId.value ? '组织已更新' : '组织已创建')
    await load()
  } catch (cause) {
    if (cause === 'cancel' || cause === 'close') return
    ElMessage.error(userErrorMessage(cause, '组织保存失败'))
  } finally {
    saving.value = false
  }
}

function openTerm(item?: AcademicTerm) {
  editingTermId.value = item?.termId ?? null
  Object.assign(termForm, item ? {
    code: item.code, displayName: item.displayName, startsOn: item.startsOn,
    endsOn: item.endsOn, enabled: item.enabled,
  } : { code: '', displayName: '', startsOn: '', endsOn: '', enabled: true })
  termOpen.value = true
}

async function saveTerm() {
  if (!termForm.code.trim() || !termForm.displayName.trim() || !termForm.startsOn || !termForm.endsOn) {
    ElMessage.warning('学期编码、名称与日期不能为空')
    return
  }
  if (termForm.startsOn > termForm.endsOn) {
    ElMessage.warning('学期开始日期不能晚于结束日期')
    return
  }
  saving.value = true
  try {
    const payload = { ...termForm, code: termForm.code.trim(), displayName: termForm.displayName.trim() }
    if (editingTermId.value) await api.updateAdminAcademicTerm(editingTermId.value, payload)
    else await api.createAdminAcademicTerm(payload)
    termOpen.value = false
    ElMessage.success(editingTermId.value ? '学期已更新' : '学期已创建')
    await load()
  } catch (cause) {
    ElMessage.error(userErrorMessage(cause, '学期保存失败'))
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <PageHeader title="组织与学期" description="稳定组织编码用于账号归属、课程范围与批量导入关联。">
      <template #actions>
        <el-button :icon="RefreshCw" :loading="loading" @click="load">刷新</el-button>
        <el-button v-if="activeTab === 'organizations'" type="primary" :icon="Plus" :disabled="showcaseUiMode" :title="showcaseUiMode ? '展示模式为只读' : undefined" @click="openOrganization()">新建组织</el-button>
        <el-button v-else type="primary" :icon="Plus" :disabled="showcaseUiMode" :title="showcaseUiMode ? '展示模式为只读' : undefined" @click="openTerm()">新建学期</el-button>
      </template>
    </PageHeader>
    <PageFeedback :loading="loading" :error="error" @retry="load" />

    <PanelCard v-if="!loading" padding="flush">
      <el-tabs v-model="activeTab" class="organization-tabs">
        <el-tab-pane name="organizations"><template #label><span class="tab-label"><Network :size="16" />组织层级</span></template></el-tab-pane>
        <el-tab-pane name="terms"><template #label><span class="tab-label"><CalendarDays :size="16" />学年学期</span></template></el-tab-pane>
      </el-tabs>
      <el-table
        v-if="activeTab === 'organizations'"
        :data="organizations"
        row-key="organizationId"
        default-expand-all
        :tree-props="{ children: 'children' }"
      >
        <el-table-column prop="displayName" label="组织名称" min-width="220" />
        <el-table-column prop="code" label="稳定编码" min-width="170" />
        <el-table-column label="类型" width="110"><template #default="{ row }">{{ unitLabels[row.unitType] ?? row.unitType }}</template></el-table-column>
        <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="100" fixed="right"><template #default="{ row }"><el-button link :icon="Pencil" :disabled="showcaseUiMode" @click="openOrganization(row)">编辑</el-button></template></el-table-column>
      </el-table>
      <el-table v-else :data="terms" row-key="termId">
        <el-table-column prop="displayName" label="学期名称" min-width="180" />
        <el-table-column prop="code" label="稳定编码" min-width="150" />
        <el-table-column prop="startsOn" label="开始日期" width="130" />
        <el-table-column prop="endsOn" label="结束日期" width="130" />
        <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="100" fixed="right"><template #default="{ row }"><el-button link :icon="Pencil" :disabled="showcaseUiMode" @click="openTerm(row)">编辑</el-button></template></el-table-column>
      </el-table>
    </PanelCard>

    <el-dialog v-model="organizationOpen" :title="editingOrganizationId ? '编辑组织' : '新建组织'" width="min(520px, 94vw)">
      <el-form label-position="top">
        <div class="organization-form-grid">
          <el-form-item label="稳定编码"><el-input v-model="organizationForm.code" :disabled="Boolean(editingOrganizationId)" /></el-form-item>
          <el-form-item label="组织类型"><el-select v-model="organizationForm.unitType"><el-option v-for="(label, value) in unitLabels" :key="value" :label="label" :value="value" /></el-select></el-form-item>
        </div>
        <el-form-item label="组织名称"><el-input v-model="organizationForm.displayName" /></el-form-item>
        <el-form-item label="上级组织"><el-select v-model="organizationForm.parentId" clearable placeholder="无上级组织"><el-option v-for="item in flatOrganizations.filter((row) => row.organizationId !== editingOrganizationId)" :key="item.organizationId" :label="`${item.displayName} · ${item.code}`" :value="item.organizationId" /></el-select></el-form-item>
        <el-form-item label="启用状态"><el-switch v-model="organizationForm.enabled" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="organizationOpen = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveOrganization">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="termOpen" :title="editingTermId ? '编辑学期' : '新建学期'" width="min(520px, 94vw)">
      <el-form label-position="top">
        <div class="organization-form-grid">
          <el-form-item label="稳定编码"><el-input v-model="termForm.code" :disabled="Boolean(editingTermId)" /></el-form-item>
          <el-form-item label="学期名称"><el-input v-model="termForm.displayName" /></el-form-item>
          <el-form-item label="开始日期"><el-date-picker v-model="termForm.startsOn" value-format="YYYY-MM-DD" type="date" /></el-form-item>
          <el-form-item label="结束日期"><el-date-picker v-model="termForm.endsOn" value-format="YYYY-MM-DD" type="date" /></el-form-item>
        </div>
        <el-form-item label="启用状态"><el-switch v-model="termForm.enabled" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="termOpen = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveTerm">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.organization-tabs { padding: 0 16px; }
.organization-tabs :deep(.el-tabs__header) { margin-bottom: 0; }
.organization-tabs :deep(.el-tabs__content) { display: none; }
.tab-label { display: inline-flex; align-items: center; gap: 7px; }
.organization-form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.organization-form-grid :deep(.el-date-editor) { width: 100%; }
@media (max-width: 560px) { .organization-form-grid { grid-template-columns: 1fr; } }
</style>
