<template>
  <div class="page console-page access-page">
    <header class="page-header">
      <div>
        <div class="eyebrow">组织与权限</div>
        <h1 class="page-title">{{ isUsers ? '账户管理' : '角色管理' }}</h1>
        <p class="page-desc">
          {{ isUsers
            ? '创建登录账户，分配角色和租户范围，并执行停用或密码重置。'
            : '维护角色定义、状态和权限集合；系统内置角色受保护。' }}
        </p>
      </div>
      <div class="header-actions">
        <button class="btn" :disabled="loading" @click="load">刷新</button>
        <button class="btn primary" @click="openCreate">
          {{ isUsers ? '新建账户' : '新建角色' }}
        </button>
      </div>
    </header>

    <section class="card data-surface access-card">
      <div class="toolbar">
        <div class="filters">
          <input
            v-model.trim="keyword"
            class="input access-search"
            :placeholder="isUsers ? '搜索账号、姓名或邮箱' : '搜索角色编码、名称或说明'"
            @keyup.enter="applyFilters"
          />
          <a-select
            v-model:value="status"
            allow-clear
            class="filter-select"
            placeholder="全部状态"
            :options="statusOptions"
            @change="applyFilters"
          />
          <button class="btn" @click="applyFilters">查询</button>
          <button class="btn" @click="resetFilters">重置</button>
        </div>
      </div>

      <div v-if="error" class="state-panel error-state">
        <strong>{{ isUsers ? '账户列表加载失败' : '角色列表加载失败' }}</strong>
        <p>{{ error }}</p>
        <button class="btn" @click="load">重试</button>
      </div>
      <div v-else-if="loading" class="state-panel">
        <span class="loading-mark"></span>
        <strong>正在读取权限数据</strong>
      </div>

      <template v-else>
        <div class="table-wrap access-table-wrap">
          <table class="data-table access-table">
            <thead>
              <tr v-if="isUsers">
                <th>账号</th><th>姓名 / 邮箱</th><th>角色</th><th>租户范围</th>
                <th>状态</th><th>最近登录</th><th>操作</th>
              </tr>
              <tr v-else>
                <th>角色</th><th>说明</th><th>权限</th><th>关联账户</th>
                <th>类型</th><th>状态</th><th>操作</th>
              </tr>
            </thead>
            <tbody>
              <template v-if="isUsers">
                <tr v-for="row in filteredRows" :key="row.id">
                  <td>
                    <div class="primary-cell">
                      <strong>{{ row.username }}</strong>
                      <span v-if="row.id === currentUserId" class="mini-badge">当前账号</span>
                    </div>
                  </td>
                  <td>
                    <strong>{{ row.displayName || '—' }}</strong>
                    <small>{{ row.email || '—' }}</small>
                  </td>
                  <td><div class="chip-list"><span v-for="item in row.roleNames || []" :key="item" class="chip">{{ item }}</span><span v-if="!(row.roleNames || []).length">—</span></div></td>
                  <td><span class="line-clamp" :title="join(row.tenantNames)">{{ join(row.tenantNames) }}</span></td>
                  <td><span :class="['status', row.status === 'ACTIVE' ? 'ok' : 'danger']">{{ row.status === 'ACTIVE' ? '启用' : '停用' }}</span></td>
                  <td>{{ date(row.lastLoginAt) }}</td>
                  <td>
                    <div class="row-actions">
                      <button class="btn small" @click="openEditUser(row)">编辑</button>
                      <button class="btn small" @click="openPassword(row)">重置密码</button>
                      <button
                        class="btn small"
                        :disabled="row.id === currentUserId"
                        @click="toggleUser(row)"
                      >{{ row.status === 'ACTIVE' ? '停用' : '启用' }}</button>
                    </div>
                  </td>
                </tr>
              </template>
              <template v-else>
                <tr v-for="row in filteredRows" :key="row.id">
                  <td>
                    <div class="primary-cell"><strong>{{ row.name }}</strong><code>{{ row.code }}</code></div>
                  </td>
                  <td><span class="line-clamp two-lines" :title="row.description || ''">{{ row.description || '—' }}</span></td>
                  <td><span class="line-clamp two-lines" :title="join(row.permissionNames)">{{ join(row.permissionNames) }}</span></td>
                  <td>{{ row.userCount || 0 }}</td>
                  <td><span :class="['mini-badge', row.systemBuiltin ? 'builtin' : 'custom']">{{ row.systemBuiltin ? '系统内置' : '自定义' }}</span></td>
                  <td><span :class="['status', row.status === 'ACTIVE' ? 'ok' : 'warn']">{{ row.status === 'ACTIVE' ? '启用' : '停用' }}</span></td>
                  <td>
                    <div class="row-actions">
                      <button class="btn small" @click="openEditRole(row)">编辑</button>
                      <button
                        class="btn small"
                        :disabled="row.code === 'ADMIN'"
                        @click="toggleRole(row)"
                      >{{ row.status === 'ACTIVE' ? '停用' : '启用' }}</button>
                      <button
                        class="btn small danger-button"
                        :disabled="row.systemBuiltin || Number(row.userCount || 0) > 0"
                        @click="removeRole(row)"
                      >删除</button>
                    </div>
                  </td>
                </tr>
              </template>
            </tbody>
          </table>
        </div>
        <footer class="pagination" aria-label="分页">
          <span>共 {{ total }} 条</span>
          <template v-if="total > pageSize">
            <button class="btn" :disabled="page === 1" @click="changePage(page - 1)">上一页</button>
            <span>第 {{ page }} / {{ pageCount }} 页</span>
            <button class="btn" :disabled="page >= pageCount" @click="changePage(page + 1)">下一页</button>
            <select v-model.number="pageSize" class="select compact" aria-label="每页条数" @change="changePage(1)">
              <option :value="20">20 条</option>
              <option :value="50">50 条</option>
              <option :value="100">100 条</option>
            </select>
          </template>
        </footer>
        <div v-if="!rows.length" class="state-panel empty-state">
          <strong>{{ isUsers ? '没有匹配的账户' : '没有匹配的角色' }}</strong>
          <p>{{ isUsers ? '可新建账户并分配角色及租户范围。' : '可新建自定义角色并分配权限。' }}</p>
        </div>
      </template>
    </section>

    <a-modal
      v-model:open="userModal"
      :title="editingUser ? '编辑账户' : '新建账户'"
      :confirm-loading="saving"
      ok-text="保存"
      cancel-text="取消"
      width="640px"
      @ok="saveUser"
    >
      <div v-if="formError" class="inline-alert danger">{{ formError }}</div>
      <a-form layout="vertical">
        <div class="form-grid">
          <a-form-item label="账号" required>
            <a-input v-model:value="userForm.username" :disabled="!!editingUser" placeholder="例如 user@example.com" />
          </a-form-item>
          <a-form-item v-if="!editingUser" label="初始密码" required>
            <a-input-password v-model:value="userForm.initialPassword" autocomplete="new-password" placeholder="至少 8 位，包含字母和数字" />
          </a-form-item>
          <a-form-item label="显示名称" required>
            <a-input v-model:value="userForm.displayName" />
          </a-form-item>
          <a-form-item label="邮箱">
            <a-input v-model:value="userForm.email" />
          </a-form-item>
          <a-form-item label="状态" required>
            <a-select v-model:value="userForm.status" :options="userStatusOptions" />
          </a-form-item>
          <a-form-item label="角色" required class="span-two">
            <a-select
              v-model:value="userForm.roleIds"
              mode="multiple"
              :options="roleOptions"
              placeholder="至少选择一个启用角色"
            />
          </a-form-item>
          <a-form-item label="租户范围" class="span-two">
            <a-select
              v-model:value="userForm.tenantIds"
              mode="multiple"
              allow-clear
              :options="tenantOptions"
              placeholder="平台管理员可留空；租户用户请选择授权租户"
            />
          </a-form-item>
        </div>
      </a-form>
      <p class="modal-note">账户、角色或租户范围变更会立即影响后端鉴权；前端菜单声明在重新登录后刷新。</p>
    </a-modal>

    <a-modal
      v-model:open="roleModal"
      :title="editingRole ? '编辑角色' : '新建角色'"
      :confirm-loading="saving"
      ok-text="保存"
      cancel-text="取消"
      width="680px"
      @ok="saveRole"
    >
      <div v-if="formError" class="inline-alert danger">{{ formError }}</div>
      <a-form layout="vertical">
        <div class="form-grid">
          <a-form-item label="角色编码" required>
            <a-input v-model:value="roleForm.code" :disabled="!!editingRole" placeholder="例如 OPS_ADMIN" />
          </a-form-item>
          <a-form-item label="角色名称" required>
            <a-input v-model:value="roleForm.name" />
          </a-form-item>
          <a-form-item label="状态" required>
            <a-select v-model:value="roleForm.status" :disabled="editingRole?.code === 'ADMIN'" :options="roleStatusOptions" />
          </a-form-item>
          <a-form-item label="角色说明" class="span-two">
            <a-textarea v-model:value="roleForm.description" :rows="3" />
          </a-form-item>
          <a-form-item label="权限集合" class="span-two">
            <a-select
              v-model:value="roleForm.permissionIds"
              mode="multiple"
              allow-clear
              :disabled="editingRole?.code === 'ADMIN'"
              :options="permissionOptions"
              placeholder="选择该角色拥有的权限"
            />
          </a-form-item>
        </div>
      </a-form>
      <p class="modal-note">当前平台管理接口仍以 ADMIN 角色作为最高权限边界；权限集合用于角色治理和后续细粒度授权。</p>
    </a-modal>

    <a-modal
      v-model:open="passwordModal"
      title="重置账户密码"
      :confirm-loading="saving"
      ok-text="确认重置"
      cancel-text="取消"
      @ok="resetPassword"
    >
      <div v-if="formError" class="inline-alert danger">{{ formError }}</div>
      <p>账户：<strong>{{ passwordUser?.username }}</strong></p>
      <a-form layout="vertical">
        <a-form-item label="新密码" required>
          <a-input-password v-model:value="newPassword" autocomplete="new-password" placeholder="至少 8 位，包含字母和数字" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { Modal, message } from 'ant-design-vue'
import { api, create, errorMessage, identity, patchAction, postAction, queryPage, update } from '../api/client'
import { formatDateTime } from '../format'

const props = defineProps<{ mode: 'users' | 'roles' }>()
const isUsers = computed(() => props.mode === 'users')
const currentUserId = identity().userId
const rows = ref<any[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const loading = ref(false)
const error = ref('')
const keyword = ref('')
const status = ref<string>()
const saving = ref(false)
const formError = ref('')
const userModal = ref(false)
const roleModal = ref(false)
const passwordModal = ref(false)
const editingUser = ref<any>()
const editingRole = ref<any>()
const passwordUser = ref<any>()
const newPassword = ref('')
const roleOptions = ref<{ label: string; value: string }[]>([])
const tenantOptions = ref<{ label: string; value: string }[]>([])
const permissionOptions = ref<{ label: string; value: string }[]>([])

const userStatusOptions = [
  { label: '启用', value: 'ACTIVE' },
  { label: '停用', value: 'DISABLED' },
]
const roleStatusOptions = [
  { label: '启用', value: 'ACTIVE' },
  { label: '停用', value: 'INACTIVE' },
]
const statusOptions = computed(() => isUsers.value ? userStatusOptions : roleStatusOptions)
const filteredRows = computed(() => rows.value)
const pageCount = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))

const userForm = reactive({
  username: '', initialPassword: '', displayName: '', email: '',
  roleIds: [] as string[], tenantIds: [] as string[], status: 'ACTIVE',
})
const roleForm = reactive({
  code: '', name: '', description: '', permissionIds: [] as string[], status: 'ACTIVE',
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    const result = await queryPage<any>(isUsers.value ? '/api/users' : '/api/roles', {
      page: page.value,
      size: pageSize.value,
      keyword: keyword.value || undefined,
      status: status.value || undefined,
    })
    rows.value = result.items
    total.value = result.total
    if (page.value > pageCount.value) {
      page.value = pageCount.value
      await load()
    }
  } catch (e) {
    rows.value = []
    total.value = 0
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

function applyFilters() {
  page.value = 1
  load()
}

function resetFilters() {
  keyword.value = ''
  status.value = undefined
  applyFilters()
}

function changePage(value: number) {
  page.value = Math.min(Math.max(1, value), pageCount.value)
  load()
}

async function loadOptions() {
  if (isUsers.value) {
    const [roles, tenants] = await Promise.all([
      queryPage<any>('/api/roles', { size: 500 }),
      queryPage<any>('/api/tenants', { size: 500 }),
    ])
    roleOptions.value = roles.items
      .filter((row) => row.status === 'ACTIVE')
      .map((row) => ({ label: `${row.name}（${row.code}）`, value: row.id }))
    tenantOptions.value = tenants.items.map((row) => ({ label: row.name, value: row.id }))
  } else {
    const permissions = await queryPage<any>('/api/permissions', { size: 500 })
    permissionOptions.value = permissions.items.map((row) => ({
      label: `${row.name}（${row.code}）`, value: row.id,
    }))
  }
}

function openCreate() {
  formError.value = ''
  if (isUsers.value) {
    editingUser.value = undefined
    Object.assign(userForm, {
      username: '', initialPassword: '', displayName: '', email: '',
      roleIds: [], tenantIds: [], status: 'ACTIVE',
    })
    userModal.value = true
  } else {
    editingRole.value = undefined
    Object.assign(roleForm, {
      code: '', name: '', description: '', permissionIds: [], status: 'ACTIVE',
    })
    roleModal.value = true
  }
}

function openEditUser(row: any) {
  formError.value = ''
  editingUser.value = row
  Object.assign(userForm, {
    username: row.username,
    initialPassword: '',
    displayName: row.displayName || '',
    email: row.email || '',
    roleIds: [...(row.roleIds || [])],
    tenantIds: [...(row.tenantIds || [])],
    status: row.status,
  })
  userModal.value = true
}

function openEditRole(row: any) {
  formError.value = ''
  editingRole.value = row
  Object.assign(roleForm, {
    code: row.code,
    name: row.name,
    description: row.description || '',
    permissionIds: [...(row.permissionIds || [])],
    status: row.status,
  })
  roleModal.value = true
}

async function saveUser() {
  if (!userForm.username || !userForm.displayName || !userForm.roleIds.length || (!editingUser.value && !userForm.initialPassword)) {
    formError.value = '请填写账号、显示名称、角色和初始密码'
    return
  }
  saving.value = true
  formError.value = ''
  try {
    const payload = {
      displayName: userForm.displayName,
      email: userForm.email || undefined,
      roleIds: userForm.roleIds,
      tenantIds: userForm.tenantIds,
      status: userForm.status,
    }
    if (editingUser.value) {
      await update('/api/users', editingUser.value.id, payload, 'put')
    } else {
      await create('/api/users', {
        username: userForm.username,
        initialPassword: userForm.initialPassword,
        ...payload,
      })
    }
    userModal.value = false
    message.success(editingUser.value ? '账户更新成功' : '账户创建成功')
    await load()
  } catch (e) {
    formError.value = errorMessage(e)
  } finally {
    saving.value = false
  }
}

async function toggleUser(row: any) {
  const next = row.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  Modal.confirm({
    title: `${next === 'ACTIVE' ? '启用' : '停用'}账户`,
    content: `确认${next === 'ACTIVE' ? '启用' : '停用'}账号 ${row.username}？`,
    okText: '确认', cancelText: '取消',
    async onOk() {
      try {
        await patchAction(`/api/users/${row.id}/status`, { status: next })
        message.success('账户状态已更新')
        await load()
      } catch (e) { message.error(errorMessage(e)) }
    },
  })
}

function openPassword(row: any) {
  passwordUser.value = row
  newPassword.value = ''
  formError.value = ''
  passwordModal.value = true
}

async function resetPassword() {
  if (newPassword.value.length < 8) {
    formError.value = '新密码至少 8 位，并需同时包含字母和数字'
    return
  }
  saving.value = true
  formError.value = ''
  try {
    await postAction(`/api/users/${passwordUser.value.id}/reset-password`, { newPassword: newPassword.value })
    passwordModal.value = false
    message.success('密码重置成功')
    await load()
  } catch (e) {
    formError.value = errorMessage(e)
  } finally {
    saving.value = false
  }
}

async function saveRole() {
  if (!roleForm.code || !roleForm.name) {
    formError.value = '请填写角色编码和角色名称'
    return
  }
  saving.value = true
  formError.value = ''
  try {
    const payload = {
      code: roleForm.code.toUpperCase(),
      name: roleForm.name,
      description: roleForm.description || undefined,
      permissionIds: roleForm.permissionIds,
      status: roleForm.status,
    }
    if (editingRole.value) await update('/api/roles', editingRole.value.id, payload, 'put')
    else await create('/api/roles', payload)
    roleModal.value = false
    message.success(editingRole.value ? '角色更新成功' : '角色创建成功')
    await load()
  } catch (e) {
    formError.value = errorMessage(e)
  } finally {
    saving.value = false
  }
}

async function toggleRole(row: any) {
  const next = row.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'
  try {
    await patchAction(`/api/roles/${row.id}/status`, { status: next })
    message.success('角色状态已更新')
    await load()
  } catch (e) { message.error(errorMessage(e)) }
}

function removeRole(row: any) {
  Modal.confirm({
    title: '删除自定义角色',
    content: `确认删除角色 ${row.name}（${row.code}）？该操作不可恢复。`,
    okText: '删除', okType: 'danger', cancelText: '取消',
    async onOk() {
      try {
        await api.delete(`/api/roles/${row.id}`)
        message.success('角色已删除')
        await load()
      } catch (e) { message.error(errorMessage(e)) }
    },
  })
}

function join(value: any) {
  return Array.isArray(value) && value.length ? value.join('、') : '—'
}
function date(value: any) { return formatDateTime(value) || '—' }

async function initialize() {
  await Promise.all([
    load(),
    loadOptions().catch((e) => message.error(errorMessage(e, '加载权限选项'))),
  ])
}

watch(() => props.mode, () => {
  keyword.value = ''
  status.value = undefined
  page.value = 1
  initialize()
})
onMounted(initialize)
</script>

<style scoped>
.access-page { min-height: 0; }
.header-actions { display: flex; gap: 10px; align-items: center; }
.access-card { overflow: hidden; }
.access-search { min-width: 280px; }
.access-table-wrap { overflow: auto; }
.access-table { min-width: 1080px; }
.access-table th:last-child,
.access-table td:last-child { width: 230px; }
.primary-cell { display: flex; flex-wrap: wrap; gap: 7px; align-items: center; }
.primary-cell code { display: block; width: 100%; color: var(--muted); font-size: 12px; }
td small { display: block; margin-top: 4px; color: var(--muted); }
.chip-list { display: flex; flex-wrap: wrap; gap: 5px; }
.chip,
.mini-badge { border: 1px solid var(--line); border-radius: 999px; padding: 2px 7px; background: #f7f4ed; font-size: 12px; white-space: nowrap; }
.mini-badge.builtin { background: #edf3ff; border-color: #b9cdf8; color: #174f9e; }
.mini-badge.custom { background: #f6f4ef; color: var(--muted); }
.line-clamp { display: -webkit-box; overflow: hidden; -webkit-box-orient: vertical; -webkit-line-clamp: 1; }
.line-clamp.two-lines { -webkit-line-clamp: 2; }
.row-actions { display: flex; flex-wrap: wrap; gap: 6px; }
.danger-button { color: var(--red); }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 16px; }
.span-two { grid-column: 1 / -1; }
.modal-note { margin: 4px 0 0; color: var(--muted); font-size: 12px; line-height: 1.6; }
@media (max-width: 760px) {
  .form-grid { grid-template-columns: 1fr; }
  .span-two { grid-column: auto; }
  .access-search { min-width: 0; width: 100%; }
  .header-actions { width: 100%; }
}
</style>
