<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  listAdminMajors,
  listTeachers,
  updateTeacherAdminRole,
  updateTeacherMajors,
} from '../../api/auth'
import type { MajorView, TeacherRow } from '../../api/auth'
import AdminShell from './AdminShell.vue'

/**
 * 教师管理（FR-M1-08 / FR-M1-12 / 03 §3.9 /admin/teachers）：
 * 管理员修改本校教师的专业范围，并可为其叠加 / 撤销管理员权限。
 */

const teachers = ref<TeacherRow[]>([])
const majors = ref<MajorView[]>([])
const editing = ref<TeacherRow | null>(null)
const selected = ref<string[]>([])

async function reload(): Promise<void> {
  teachers.value = await listTeachers().catch(() => [])
  majors.value = await listAdminMajors().catch(() => [])
}

function startEdit(row: TeacherRow): void {
  editing.value = row
  selected.value = row.majors.map((item) => item.id)
}

/**
 * 授予 / 撤销管理员角色。只叠加 admin，主身份仍是教师；
 * 权限随 JWT 下发，因此被授权教师需重新登录后才会生效。
 */
async function toggleAdmin(row: TeacherRow, enabled: boolean): Promise<void> {
  try {
    await updateTeacherAdminRole(row.account_id, enabled)
    ElMessage.success(
      enabled
        ? '已授予管理员权限，该教师重新登录后可进入管理端'
        : '已撤销管理员权限，该教师重新登录后失去管理端访问',
    )
    await reload()
  } catch (error) {
    ElMessage.error((error as { message?: string }).message ?? '操作失败')
  }
}

async function save(): Promise<void> {
  if (!editing.value) {
    return
  }
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
  <AdminShell>
    <div class="teachers-page">
    <h2>教师管理</h2>
    <p class="tip">
      仅可修改本校教师的专业范围与管理权限；教师本人不能修改自己的资料。
      专业变更后旧关系失效但保留历史；管理员权限授予后，该教师需重新登录生效。
    </p>

    <el-card>
      <el-table :data="teachers" size="small">
        <el-table-column prop="username" label="教师" width="200" />
        <el-table-column label="当前有效专业">
          <template #default="{ row }">
            <el-tag
              v-for="major in row.majors"
              :key="major.id"
              size="small"
              class="tag"
            >
              {{ major.name }}
            </el-tag>
            <span v-if="row.majors.length === 0" class="empty">未设置</span>
          </template>
        </el-table-column>
        <el-table-column label="管理权限" width="150">
          <template #default="{ row }">
            <el-switch
              :model-value="row.roles.includes('admin')"
              inline-prompt
              active-text="管理员"
              inactive-text="教师"
              @change="(value: string | number | boolean) => toggleAdmin(row, Boolean(value))"
            />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110">
          <template #default="{ row }">
            <el-button link type="primary" @click="startEdit(row)">修改专业</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="teachers.length === 0" description="本校暂无教师" />
    </el-card>

    <el-dialog :model-value="editing !== null" title="修改专业范围" width="420px"
               @close="editing = null">
      <p class="dialog-tip">{{ editing?.username }}</p>
      <el-select v-model="selected" multiple class="full" placeholder="选择专业">
        <el-option
          v-for="major in majors.filter((item) => item.active)"
          :key="major.id"
          :label="major.name"
          :value="major.id"
        />
      </el-select>
      <template #footer>
        <el-button @click="editing = null">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
    </div>
  </AdminShell>
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

.tag {
  margin-right: 6px;
}

.empty {
  color: var(--el-text-color-placeholder);
  font-size: 12px;
}

.dialog-tip {
  margin: 0 0 12px;
  color: var(--el-text-color-regular);
}

.full {
  width: 100%;
}
</style>
