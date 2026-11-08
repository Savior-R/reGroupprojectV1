<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { listAdminMajors, listTeachers, updateTeacherMajors } from '../../api/auth'
import type { MajorView, TeacherRow } from '../../api/auth'

/** 教师管理（FR-M1-08 / 03 §3.9 /admin/teachers）：管理员修改本校教师专业范围。 */

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
  <div class="teachers-page">
    <h2>教师管理</h2>
    <p class="tip">
      仅可修改本校教师的专业范围；教师本人不能修改自己的专业。变更后旧关系失效但保留历史。
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
