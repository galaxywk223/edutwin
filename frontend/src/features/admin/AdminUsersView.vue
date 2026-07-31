<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { Building2, Download, FileSpreadsheet, KeyRound, Plus, RefreshCw, SlidersHorizontal, Upload, UserCog } from '@lucide/vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import { api } from '@/api/client/edutwin'
import { showcaseUiMode } from '@/showcase/runtime'
import PageFeedback from '@/shared/components/PageFeedback.vue'
import PageHeader from '@/shared/components/PageHeader.vue'
import PanelCard from '@/shared/components/PanelCard.vue'
import type { AdminUser, CounselorScope, OrganizationUnit, StudentGroup, UserImportPreview, UserRole } from '@/shared/types/api'
import { importErrorLabel, importFieldLabel, importSheetLabel, userErrorMessage } from '@/shared/utils/displayText'
import { dateTime } from '@/shared/utils/format'

const users = ref<AdminUser[]>([])
const loading = ref(true)
const error = ref('')
const query = ref('')
const page = ref(1)
const pageSize = ref(20)
const createOpen = ref(false)
const saving = ref(false)
const scopeOpen = ref(false)
const scopeSaving = ref(false)
const scopeUser = ref<AdminUser | null>(null)
const groups = ref<StudentGroup[]>([])
const selectedScopeKeys = ref<string[]>([])
const importOpen = ref(false)
const importFiles = ref<File[]>([])
const importPreview = ref<UserImportPreview | null>(null)
const importBusy = ref(false)
const roleOpen = ref(false)
const roleSaving = ref(false)
const roleUser = ref<AdminUser | null>(null)
const selectedRoles = ref<UserRole[]>([])
const organizationOpen = ref(false)
const organizationSaving = ref(false)
const organizationUser = ref<AdminUser | null>(null)
const organizations = ref<OrganizationUnit[]>([])
const selectedOrganizationId = ref('')
const form = reactive({ username: '', displayName: '', roles: ['STUDENT'] as UserRole[], password: '' })
const roleLabels: Record<UserRole, string> = { ADMIN: '管理员', TEACHER: '教师', STUDENT: '学生', COUNSELOR: '辅导员' }
const filtered = computed(() => users.value.filter((user) =>
  `${user.username} ${user.displayName} ${user.role}`.toLowerCase().includes(query.value.toLowerCase()),
))
const pagedUsers = computed(() => {
  const start = (page.value - 1) * pageSize.value
  return filtered.value.slice(start, start + pageSize.value)
})

watch(query, () => { page.value = 1 })
watch(pageSize, () => { page.value = 1 })

async function load() {
  loading.value = true; error.value = ''
  try {
    users.value = (await api.adminUsers()).items
    page.value = Math.min(page.value, Math.max(1, Math.ceil(filtered.value.length / pageSize.value)))
  }
  catch (cause) { error.value = userErrorMessage(cause, '用户列表读取失败。') }
  finally { loading.value = false }
}
async function createUser() {
  if (!form.roles.length) return ElMessage.warning('至少选择一个角色')
  saving.value = true
  try {
    await api.createAdminUser({ ...form, role: form.roles[0] })
    createOpen.value = false
    Object.assign(form, { username: '', displayName: '', roles: ['STUDENT'], password: '' })
    ElMessage.success('账号已创建')
    await load()
  } catch (cause) { ElMessage.error(userErrorMessage(cause, '账号创建失败')) }
  finally { saving.value = false }
}
async function toggle(user: AdminUser) {
  try { await api.updateAdminUser(user.userId, { enabled: !user.enabled }); await load() }
  catch (cause) { ElMessage.error(userErrorMessage(cause, '状态更新失败')) }
}
async function reset(user: AdminUser) {
  await ElMessageBox.confirm(`重置 ${user.displayName} 的登录密码？`, '密码重置', { type: 'warning' })
  const value = await api.resetAdminPassword(user.userId)
  await ElMessageBox.alert(value.temporaryPassword, '一次性临时密码', { confirmButtonText: '已记录' })
  await load()
}

function openRoles(user: AdminUser) {
  roleUser.value = user
  selectedRoles.value = [...(user.roles?.length ? user.roles : [user.role])]
  roleOpen.value = true
}

async function saveRoles() {
  if (!roleUser.value || !selectedRoles.value.length) return ElMessage.warning('至少保留一个角色')
  roleSaving.value = true
  try {
    await api.updateAdminUser(roleUser.value.userId, { roles: selectedRoles.value })
    roleOpen.value = false
    ElMessage.success('角色授权已更新')
    await load()
  } catch (cause) { ElMessage.error(userErrorMessage(cause, '角色授权更新失败')) }
  finally { roleSaving.value = false }
}

function flattenOrganizations(items: OrganizationUnit[]): OrganizationUnit[] {
  return items.flatMap((item) => [item, ...flattenOrganizations(item.children ?? [])])
}

async function openOrganization(user: AdminUser) {
  organizationUser.value = user
  organizationOpen.value = true
  organizationSaving.value = true
  selectedOrganizationId.value = ''
  try { organizations.value = flattenOrganizations((await api.adminOrganizations()).items).filter((item) => item.enabled) }
  catch (cause) { organizationOpen.value = false; ElMessage.error(userErrorMessage(cause, '组织列表读取失败')) }
  finally { organizationSaving.value = false }
}

async function saveOrganization() {
  if (!organizationUser.value || !selectedOrganizationId.value) return ElMessage.warning('请选择组织归属')
  organizationSaving.value = true
  try {
    await api.replaceUserOrganization(organizationUser.value.userId, selectedOrganizationId.value)
    organizationOpen.value = false
    ElMessage.success('组织归属已更新')
  } catch (cause) { ElMessage.error(userErrorMessage(cause, '组织归属更新失败')) }
  finally { organizationSaving.value = false }
}

function downloadBlob(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url; anchor.download = name; anchor.click()
  URL.revokeObjectURL(url)
}

async function downloadTemplate(template: 'xlsx' | 'users.csv' | 'course_memberships.csv') {
  try {
    const blob = await api.userImportTemplate(template)
    const names = { xlsx: 'edutwin-user-provisioning.xlsx', 'users.csv': 'users.csv', 'course_memberships.csv': 'course_memberships.csv' }
    downloadBlob(blob, names[template])
  } catch (cause) { ElMessage.error(userErrorMessage(cause, '模板下载失败')) }
}

function chooseImportFiles(event: Event) {
  importFiles.value = Array.from((event.target as HTMLInputElement).files ?? [])
  importPreview.value = null
}

async function previewImport() {
  if (!importFiles.value.length) return ElMessage.warning('请选择导入文件')
  importBusy.value = true
  try { importPreview.value = await api.previewUserImport(importFiles.value) }
  catch (cause) { ElMessage.error(userErrorMessage(cause, '导入预检失败')) }
  finally { importBusy.value = false }
}

function credentialCsv(rows: Array<{ username: string; displayName: string; role: string; temporaryPassword: string }>) {
  const escape = (value: string) => `"${value.replaceAll('"', '""')}"`
  return `\ufeffusername,display_name,role,temporary_password\r\n${rows.map((row) =>
    [row.username, row.displayName, row.role, row.temporaryPassword].map(escape).join(','),
  ).join('\r\n')}\r\n`
}

async function commitImport() {
  if (!importPreview.value?.valid) return
  importBusy.value = true
  try {
    const result = await api.commitUserImport(importFiles.value, importPreview.value.fileSha256)
    downloadBlob(new Blob([credentialCsv(result.credentials)], { type: 'text/csv;charset=utf-8' }), 'edutwin-temporary-credentials.csv')
    ElMessage.success(`已创建 ${result.createdCount} 个账号，更新 ${result.updatedCount} 个账号`)
    importOpen.value = false; importFiles.value = []; importPreview.value = null
    await load()
  } catch (cause) { ElMessage.error(userErrorMessage(cause, '批量导入失败')) }
  finally { importBusy.value = false }
}

function scopeKey(scope: CounselorScope) {
  return [scope.scopeType, scope.college, scope.major, scope.cohortYear, scope.className ?? ''].join('|')
}

async function openScopes(user: AdminUser) {
  scopeUser.value = user
  scopeOpen.value = true
  scopeSaving.value = true
  try {
    const [groupList, scopeList] = await Promise.all([api.studentGroups(), api.counselorScopes(user.userId)])
    groups.value = groupList.items
    selectedScopeKeys.value = scopeList.items.map(scopeKey)
  } catch (cause) {
    scopeOpen.value = false
    ElMessage.error(userErrorMessage(cause, '管理范围读取失败'))
  } finally {
    scopeSaving.value = false
  }
}

async function saveScopes() {
  if (!scopeUser.value) return
  scopeSaving.value = true
  try {
    const selected = new Set(selectedScopeKeys.value)
    await api.replaceCounselorScopes(
      scopeUser.value.userId,
      groups.value.filter((group) => selected.has(scopeKey(group))).map(({ studentCount: _studentCount, ...scope }) => scope),
    )
    scopeOpen.value = false
    ElMessage.success('辅导员管理范围已更新')
  } catch (cause) {
    ElMessage.error(userErrorMessage(cause, '管理范围保存失败'))
  } finally {
    scopeSaving.value = false
  }
}
onMounted(load)
</script>

<template>
  <div>
    <PageHeader
      title="用户与角色"
      description="统一管理账号、多角色授权、组织归属与登录状态。"
    >
      <template #actions>
        <el-button :icon="RefreshCw" :loading="loading" @click="load">刷新</el-button>
        <el-button :icon="Upload" :disabled="showcaseUiMode" :title="showcaseUiMode ? '展示模式为只读' : undefined" @click="importOpen = true">批量导入</el-button>
        <el-button type="primary" :icon="Plus" :disabled="showcaseUiMode" :title="showcaseUiMode ? '展示模式为只读' : undefined" @click="createOpen = true">新建账号</el-button>
      </template>
    </PageHeader>
    <PageFeedback :loading="loading" :error="error" @retry="load" />
    <PanelCard v-if="!loading" padding="flush">
      <div class="table-toolbar"><el-input v-model="query" clearable placeholder="搜索账号、姓名或角色" /></div>
      <el-table :data="pagedUsers" row-key="userId">
        <el-table-column prop="displayName" label="姓名" min-width="140" />
        <el-table-column prop="username" label="账号" min-width="150" />
        <el-table-column label="角色" min-width="180"><template #default="{ row }"><span class="user-role-tags"><el-tag v-for="role in (row.roles?.length ? row.roles : [row.role])" :key="role" effect="plain">{{ roleLabels[role as UserRole] }}</el-tag></span></template></el-table-column>
        <el-table-column label="状态" width="110"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '有效' : '停用' }}</el-tag></template></el-table-column>
        <el-table-column label="凭据" width="120"><template #default="{ row }">{{ row.mustChangePassword ? '待修改密码' : '正常' }}</template></el-table-column>
        <el-table-column label="账号来源" width="130"><template #default="{ row }">{{ { DEMO_SYNTHETIC: '演示合成', MANUAL: '手工创建', BULK_IMPORT: '批量导入' }[row.originType as AdminUser['originType']] }}</template></el-table-column>
        <el-table-column label="创建时间" min-width="175"><template #default="{ row }">{{ dateTime(row.createdAt) }}</template></el-table-column>
        <el-table-column label="操作" width="410" fixed="right"><template #default="{ row }"><el-button link :icon="UserCog" :disabled="showcaseUiMode" @click="openRoles(row)">角色</el-button><el-button link :icon="Building2" :disabled="showcaseUiMode" @click="openOrganization(row)">组织</el-button><el-button v-if="(row.roles ?? [row.role]).includes('COUNSELOR')" link :icon="SlidersHorizontal" :disabled="showcaseUiMode" @click="openScopes(row)">范围</el-button><el-button link :icon="KeyRound" :disabled="showcaseUiMode" @click="reset(row)">密码</el-button><el-button link :disabled="showcaseUiMode" @click="toggle(row)">{{ row.enabled ? '停用' : '启用' }}</el-button></template></el-table-column>
      </el-table>
      <div class="table-pagination">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="pageSize"
          :page-sizes="[20, 50, 100]"
          :total="filtered.length"
          layout="total, sizes, prev, pager, next"
        />
      </div>
    </PanelCard>
    <el-dialog v-model="createOpen" title="新建账号" width="min(480px, 92vw)">
      <el-form label-position="top">
        <el-form-item label="登录账号"><el-input v-model="form.username" /></el-form-item>
        <el-form-item label="显示姓名"><el-input v-model="form.displayName" /></el-form-item>
        <el-form-item label="角色"><el-select v-model="form.roles" multiple><el-option v-for="(label, role) in roleLabels" :key="role" :label="label" :value="role" /></el-select></el-form-item>
        <el-form-item label="初始密码"><el-input v-model="form.password" type="password" show-password /></el-form-item>
      </el-form>
      <template #footer><el-button @click="createOpen = false">取消</el-button><el-button type="primary" :loading="saving" @click="createUser">创建</el-button></template>
    </el-dialog>
    <el-dialog v-model="roleOpen" :title="`${roleUser?.displayName ?? ''} · 角色授权`" width="min(500px, 92vw)">
      <el-checkbox-group v-model="selectedRoles" class="role-checkboxes">
        <el-checkbox v-for="(label, role) in roleLabels" :key="role" :value="role" border>{{ label }}</el-checkbox>
      </el-checkbox-group>
      <template #footer><el-button @click="roleOpen = false">取消</el-button><el-button type="primary" :loading="roleSaving" :disabled="!selectedRoles.length" @click="saveRoles">保存角色</el-button></template>
    </el-dialog>
    <el-dialog v-model="organizationOpen" :title="`${organizationUser?.displayName ?? ''} · 组织归属`" width="min(520px, 92vw)">
      <el-select v-model="selectedOrganizationId" class="organization-select" filterable placeholder="选择学院、系、专业或班级" :loading="organizationSaving">
        <el-option v-for="organization in organizations" :key="organization.organizationId" :label="`${organization.displayName} · ${organization.code}`" :value="organization.organizationId" />
      </el-select>
      <template #footer><el-button @click="organizationOpen = false">取消</el-button><el-button type="primary" :loading="organizationSaving" :disabled="!selectedOrganizationId" @click="saveOrganization">保存归属</el-button></template>
    </el-dialog>
    <el-dialog v-model="importOpen" title="批量导入用户" width="min(860px, 96vw)" @closed="importPreview = null">
      <div class="import-template-actions">
        <el-button :icon="FileSpreadsheet" @click="downloadTemplate('xlsx')">Excel 模板</el-button>
        <el-button :icon="Download" @click="downloadTemplate('users.csv')">用户 CSV</el-button>
        <el-button :icon="Download" @click="downloadTemplate('course_memberships.csv')">课程关系 CSV</el-button>
      </div>
      <label class="import-file-picker">
        <Upload :size="24" />
        <span>{{ importFiles.length ? importFiles.map((file) => file.name).join('、') : '选择一个 .xlsx，或 users.csv 与可选的 course_memberships.csv' }}</span>
        <input type="file" multiple accept=".xlsx,.csv" @change="chooseImportFiles" />
      </label>
      <div class="import-preview-actions"><el-button type="primary" plain :loading="importBusy" :disabled="!importFiles.length" @click="previewImport">执行预检</el-button></div>
      <template v-if="importPreview">
        <el-alert v-if="importPreview.valid" type="success" :closable="false" show-icon :title="`预检通过：新建 ${importPreview.createCount}，更新 ${importPreview.updateCount}，课程关系 ${importPreview.membershipRows}`" />
        <el-alert v-else type="error" :closable="false" show-icon :title="`发现 ${importPreview.errors.length} 个错误，提交已禁用`" />
        <el-table v-if="importPreview.errors.length" :data="importPreview.errors" max-height="300" class="import-error-table">
          <el-table-column prop="file" label="文件" min-width="140" />
          <el-table-column label="工作表" width="130"><template #default="{ row }">{{ importSheetLabel(row.sheet) }}</template></el-table-column>
          <el-table-column prop="row" label="行" width="60" />
          <el-table-column label="字段" width="150"><template #default="{ row }">{{ importFieldLabel(row.field) }}</template></el-table-column>
          <el-table-column label="说明" min-width="260"><template #default="{ row }">{{ importErrorLabel(row.code) }}</template></el-table-column>
        </el-table>
      </template>
      <template #footer><el-button @click="importOpen = false">取消</el-button><el-button type="primary" :loading="importBusy" :disabled="!importPreview?.valid" @click="commitImport">提交并下载一次性凭据</el-button></template>
    </el-dialog>
    <el-dialog v-model="scopeOpen" :title="`${scopeUser?.displayName ?? ''} · 管理范围`" width="min(760px, 94vw)">
      <p class="scope-note">所选年级或班级内的学生将对该辅导员可见，课程与答题内容保持只读。</p>
      <el-skeleton v-if="scopeSaving && !groups.length" :rows="5" animated />
      <el-checkbox-group v-else v-model="selectedScopeKeys" class="scope-list">
        <el-checkbox v-for="group in groups" :key="scopeKey(group)" :value="scopeKey(group)" border>
          <span class="scope-label"><strong>{{ group.scopeType === 'CLASS' ? group.className : `${group.cohortYear} 级` }}</strong><small>{{ group.college }} · {{ group.major }} · {{ group.studentCount }} 人</small></span>
        </el-checkbox>
      </el-checkbox-group>
      <template #footer><el-button @click="scopeOpen = false">取消</el-button><el-button type="primary" :loading="scopeSaving" @click="saveScopes">保存范围</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.table-toolbar { padding: 14px 16px; border-bottom: 1px solid var(--border); }
.table-toolbar .el-input { width: min(360px, 100%); }
.table-pagination { display: flex; justify-content: flex-end; padding: 14px 16px; overflow-x: auto; }
.scope-note { margin: 0 0 14px; color: var(--muted); font-size: 13px; }
.user-role-tags { display: flex; flex-wrap: wrap; gap: 5px; }
.role-checkboxes { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 9px; }
.role-checkboxes .el-checkbox { width: 100%; margin: 0; }
.organization-select { width: 100%; }
.import-template-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 14px; }
.import-file-picker { min-height: 94px; display: flex; align-items: center; justify-content: center; gap: 10px; padding: 18px; border: 1px dashed var(--border-strong); border-radius: var(--radius-sm); color: var(--muted); background: var(--surface-soft); cursor: pointer; text-align: center; }
.import-file-picker input { display: none; }
.import-preview-actions { display: flex; justify-content: flex-end; padding: 12px 0; }
.import-error-table { margin-top: 12px; }
.scope-list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; max-height: 440px; overflow-y: auto; }
.scope-list .el-checkbox { width: 100%; height: auto; min-height: 58px; margin: 0; padding: 10px 12px; }
.scope-label { display: flex; min-width: 0; flex-direction: column; gap: 4px; }
.scope-label strong, .scope-label small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.scope-label small { color: var(--muted); }
@media (max-width: 640px) { .scope-list { grid-template-columns: 1fr; } }
</style>
