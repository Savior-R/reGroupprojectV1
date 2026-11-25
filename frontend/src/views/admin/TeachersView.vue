<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  listAdminMajors,
  listTeachers,
  updateTeacherAdminRole,
  updateTeacherMajors,
} from '../../api/auth'
import type { MajorView, TeacherRow } from '../../api/auth'

/**
 * 教师管理（FR-M1-08 / FR-M1-12 / 03 §3.9 /admin/teachers）：
 * M1-F-03 按原型——教师搜索、专业范围弹窗（搜索栏 + 可滚动列表 + 已选计数 / 清空）、
 * 权限管理抽屉（角色开关 + 生效说明）。管理员只叠加 admin，主身份仍是教师。
 */

const teachers = ref<TeacherRow[]>([])
const majors = ref<MajorView[]>([])
const teacherSearch = ref('')
const filteredTeachers = computed(() => {
  const kw = teacherSearch.value.trim().toLowerCase()
  if (!kw) return teachers.value
  return teachers.value.filter(
    (t) => t.username.toLowerCase().includes(kw) || t.majors.some((m) => m.name.toLowerCase().includes(kw)),
  )
})

// 专业范围弹窗
const editing = ref<TeacherRow | null>(null)
const selected = ref<string[]>([])
const majorSearch = ref('')
const filteredMajors = computed(() => {
  const kw = majorSearch.value.trim().toLowerCase()
  return majors.value.filter((m) => m.active && (!kw || m.name.toLowerCase().includes(kw)))
})

// 权限抽屉
const permissionDrawerVisible = ref(false)
const permissionTarget = ref<TeacherRow | null>(null)
const permissionForm = reactive({ admin: false })

async function reload(): Promise<void> {
  teachers.value = await listTeachers().catch(() => [])
  majors.value = await listAdminMajors().catch(() => [])
}

function startEdit(row: TeacherRow): void {
  editing.value = row
  selected.value = row.majors.map((item) => item.id)
  majorSearch.value = ''
}

/** 授予 / 撤销管理员角色；权限随 JWT 下发，需重新登录生效。 */
async function toggleAdmin(row: TeacherRow, enabled: boolean): Promise<void> {
  try {
    await updateTeacherAdminRole(row.account_id, enabled)
    ElMessage.success(
      enabled
        ? '已授予管理员权限，该教师重新登录后可进入管理端'
        : '已撤销管理员权限，该教师重新登录后失去管理端访问',
    )
    permissionTarget.value = null
    await reload()
  } catch (error) {
    ElMessage.error((error as { message?: string }).message ?? '操作失败')
  }
}

function openPermission(row: TeacherRow): void {
  permissionTarget.value = row
  permissionForm.admin = row.roles.includes('admin')
  permissionDrawerVisible.value = true
}

function savePermission(): void {
  if (!permissionTarget.value) return
  void toggleAdmin(permissionTarget.value, permissionForm.admin)
}

function clearSelected(): void {
  selected.value = []
}

async function save(): Promise<void> {
  if (!editing.value) return
  if (selected.value.length === 0) {
    ElMessage.warning('每位教师至少保留一个有效专业')
    return
  }
  try {
    await updateTeacherMajors(editing.value.account_id, selected.value)
    ElMessage.success('专业范围已更新，旧关系保留为历史')
    editing.value = null
    await reload()
  } catch (error) {
    ElMessage.error((error as { message?: string }).message ?? '更新失败')
  }
}

onMounted(reload)
</script>

<template>
  <div class="teachers-page">
    <h2>教师管理</h2>
    <p class="tip">
      仅可修改本校教师的专业范围与管理权限；教师本人不能修改自己的资料。
      专业变更后旧关系失效但保留历史；管理员权限授予后，该教师需重新登录生效。
    </p>

    <el-card>
      <div class="toolbar">
        <el-input
          v-model="teacherSearch"
          placeholder="搜索教师姓名 / 用户名 / 专业"
          clearable
          class="search"
        />
        <span class="count">共 {{ filteredTeachers.length }} 位教师</span>
      </div>
      <el-table :data="filteredTeachers" size="small">
        <el-table-column prop="username" label="教师" width="200" />
        <el-table-column label="当前有效专业">
          <template #default="{ row }">
            <el-tag v-for="major in row.majors" :key="major.id" size="small" class="tag">
              {{ major.name }}
            </el-tag>
            <span v-if="row.majors.length === 0" class="empty">未设置</span>
          </template>
        </el-table-column>
        <el-table-column label="角色" width="160">
          <template #default="{ row }">
            <el-tag size="small" type="info">教师</el-tag>
            <el-tag v-if="row.roles.includes('admin')" size="small" type="warning" class="tag">
              管理员
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="190">
          <template #default="{ row }">
            <el-button link type="primary" @click="startEdit(row)">修改专业</el-button>
            <el-button link type="primary" @click="openPermission(row)">权限管理</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="filteredTeachers.length === 0" description="未找到教师" />
    </el-card>

    <!-- 专业范围弹窗：搜索栏 + 可滚动列表 + 已选计数 / 清空 -->
    <el-dialog
      :model-value="editing !== null"
      title="修改专业范围"
      width="480px"
      @close="editing = null"
    >
      <p class="dialog-tip">{{ editing?.username }}</p>
      <el-input v-model="majorSearch" placeholder="搜索专业" clearable class="major-search" />
      <div class="major-list">
        <el-checkbox-group v-model="selected">
          <el-checkbox
            v-for="major in filteredMajors"
            :key="major.id"
            :value="major.id"
            class="major-item"
          >
            {{ major.name }}
          </el-checkbox>
        </el-checkbox-group>
        <el-empty v-if="filteredMajors.length === 0" description="无匹配专业" :image-size="50" />
      </div>
      <div class="selected-bar">
        <span>已选 {{ selected.length }} 个专业</span>
        <el-button link type="danger" size="small" @click="clearSelected">清空</el-button>
      </div>
      <template #footer>
        <el-button @click="editing = null">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 权限管理抽屉 -->
    <el-drawer v-model="permissionDrawerVisible" title="权限管理" size="380px">
      <template v-if="permissionTarget">
        <h3 class="drawer-title">{{ permissionTarget.username }}</h3>
        <p class="tip">主身份：教师（不可变更）。以下为可叠加的授权角色。</p>
        <div class="role-row">
          <div>
            <div class="role-name">管理员</div>
            <div class="role-desc">可进入管理端维护学校 / 组织 / 教师，需重新登录生效</div>
          </div>
          <el-switch v-model="permissionForm.admin" />
        </div>
        <div class="drawer-footer">
          <el-button @click="permissionDrawerVisible = false">取消</el-button>
          <el-button type="primary" @click="savePermission">保存角色</el-button>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.teachers-page {
  padding: 24px;
}

h2 {
  margin: 0 0 4px;
  color: var(--el-text-color-primary);
}

.tip {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  margin-bottom: 16px;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.search {
  width: 280px;
}

.count {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.tag {
  margin-left: 6px;
}

.empty {
  color: var(--el-text-color-placeholder);
  font-size: 12px;
}

.dialog-tip {
  margin: 0 0 12px;
  color: var(--el-text-color-regular);
}

.major-search {
  margin-bottom: 10px;
}

.major-list {
  max-height: 260px;
  overflow-y: auto;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  padding: 8px 12px;
}

.major-item {
  display: block;
  margin: 4px 0;
}

.selected-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 10px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.drawer-title {
  margin: 0 0 8px;
  color: var(--el-text-color-primary);
}

.role-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 0;
  border-top: 1px solid var(--el-border-color-lighter);
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.role-name {
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.role-desc {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.drawer-footer {
  margin-top: 24px;
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
