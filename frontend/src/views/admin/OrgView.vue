<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { UploadRequestOptions } from 'element-plus'
import {
  apiMessage,
  createClass,
  createMajor,
  currentSchool,
  downloadImportTemplate,
  importClasses,
  importErrorRows,
  importMajors,
  listAdminClasses,
  listAdminMajors,
  updateMajor,
} from '../../api/auth'
import type { ClassView, ImportErrorRow, MajorView, SchoolView } from '../../api/auth'

/** 学校与组织（FR-M1-04 / 03 §3.9 /admin/org）：专业 / 班级维护，停用不删除。 */

const school = ref<SchoolView | null>(null)
const majors = ref<MajorView[]>([])
const classes = ref<ClassView[]>([])
const selectedMajor = ref<string>('')

const majorForm = reactive({ name: '' })
const classForm = reactive({ majorId: '', grade: '', name: '' })

/** 导入失败明细（整批回滚后展示行号 / 列 / 原因）。 */
const importErrors = ref<ImportErrorRow[]>([])
const importErrorVisible = ref(false)
const importErrorTitle = ref('')

async function reload(): Promise<void> {
  school.value = await currentSchool().catch(() => null)
  majors.value = await listAdminMajors().catch(() => [])
  classes.value = await listAdminClasses(selectedMajor.value || undefined).catch(() => [])
}

async function addMajor(): Promise<void> {
  if (!majorForm.name.trim()) {
    ElMessage.warning('请输入专业名称')
    return
  }
  try {
    await createMajor(majorForm.name.trim())
    majorForm.name = ''
    ElMessage.success('专业已创建')
    await reload()
  } catch (error) {
    ElMessage.error((error as { message?: string }).message ?? '创建失败')
  }
}

async function toggleMajor(major: MajorView): Promise<void> {
  try {
    await updateMajor(major.id, { active: !major.active })
    ElMessage.success(major.active ? '专业已停用' : '专业已启用')
    await reload()
  } catch (error) {
    ElMessage.error((error as { message?: string }).message ?? '操作失败')
  }
}

async function addClass(): Promise<void> {
  if (!classForm.majorId || !classForm.grade || !classForm.name) {
    ElMessage.warning('请填写专业、年级与班名')
    return
  }
  try {
    await createClass(classForm.majorId, classForm.grade.trim(), classForm.name.trim())
    classForm.name = ''
    ElMessage.success('班级已创建')
    await reload()
  } catch (error) {
    ElMessage.error((error as { message?: string }).message ?? '创建失败')
  }
}

async function filterByMajor(): Promise<void> {
  classes.value = await listAdminClasses(selectedMajor.value || undefined)
}

/**
 * 导入 CSV / Excel（FR-M1-04）：整批事务，任一行失败则一行不写。
 * 失败时把 `details.errors` 摊进弹窗，便于按行修正后重传。
 */
async function handleImport(
  type: 'major' | 'class',
  options: UploadRequestOptions,
): Promise<void> {
  try {
    const result =
      type === 'major'
        ? await importMajors(options.file as File)
        : await importClasses(options.file as File)
    ElMessage.success(
      `导入完成：共 ${result.total} 行，新增 ${result.created}，更新 ${result.updated}`,
    )
    importErrors.value = []
    await reload()
  } catch (error) {
    const rows = importErrorRows(error)
    if (rows.length > 0) {
      importErrors.value = rows
      importErrorTitle.value = type === 'major' ? '专业导入失败' : '班级导入失败'
      importErrorVisible.value = true
    }
    ElMessage.error(apiMessage(error, '导入失败，整批未写入'))
  }
}

const uploadMajor = (options: UploadRequestOptions): Promise<void> => handleImport('major', options)
const uploadClass = (options: UploadRequestOptions): Promise<void> => handleImport('class', options)

async function downloadTemplate(type: 'major' | 'class'): Promise<void> {
  try {
    await downloadImportTemplate(type)
  } catch {
    ElMessage.error('模板下载失败')
  }
}

onMounted(reload)
</script>

<template>
  <div class="org-page">
    <h2>学校与组织</h2>
    <p class="tip">
      当前学校：{{ school?.name ?? '未创建' }}；专业与班级停用后不再出现在注册选项，已有学生关系保留。
    </p>

    <el-row :gutter="16">
      <el-col :span="11">
        <el-card>
          <template #header>
            <div class="card-header">
              <span>专业</span>
              <div class="header-actions">
                <el-button link type="primary" size="small" @click="downloadTemplate('major')">
                  下载模板
                </el-button>
                <el-upload
                  :show-file-list="false"
                  accept=".csv,.xlsx,.xls"
                  :http-request="uploadMajor"
                >
                  <el-button size="small" plain>导入 CSV / Excel</el-button>
                </el-upload>
              </div>
            </div>
          </template>
          <div class="row">
            <el-input v-model="majorForm.name" placeholder="新专业名称" maxlength="100" />
            <el-button type="primary" @click="addMajor">新增</el-button>
          </div>
          <el-table :data="majors" size="small" class="table">
            <el-table-column prop="name" label="专业" />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="row.active ? 'success' : 'info'" size="small">
                  {{ row.active ? '启用' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="90">
              <template #default="{ row }">
                <el-button link type="primary" @click="toggleMajor(row)">
                  {{ row.active ? '停用' : '启用' }}
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>

      <el-col :span="13">
        <el-card>
          <template #header>
            <div class="card-header">
              <span>班级</span>
              <div class="header-actions">
                <el-button link type="primary" size="small" @click="downloadTemplate('class')">
                  下载模板
                </el-button>
                <el-upload
                  :show-file-list="false"
                  accept=".csv,.xlsx,.xls"
                  :http-request="uploadClass"
                >
                  <el-button size="small" plain>导入 CSV / Excel</el-button>
                </el-upload>
              </div>
            </div>
          </template>
          <div class="row">
            <el-select v-model="classForm.majorId" placeholder="专业" class="select">
              <el-option
                v-for="major in majors.filter((item) => item.active)"
                :key="major.id"
                :label="major.name"
                :value="major.id"
              />
            </el-select>
            <el-input v-model="classForm.grade" placeholder="年级（如 2024）" class="grade" />
            <el-input v-model="classForm.name" placeholder="班名" class="name" />
            <el-button type="primary" @click="addClass">新增</el-button>
          </div>

          <div class="row filter">
            <el-select
              v-model="selectedMajor"
              placeholder="按专业筛选"
              clearable
              class="select"
              @change="filterByMajor"
            >
              <el-option
                v-for="major in majors"
                :key="major.id"
                :label="major.name"
                :value="major.id"
              />
            </el-select>
          </div>

          <el-table :data="classes" size="small" class="table">
            <el-table-column prop="major_name" label="专业" />
            <el-table-column prop="grade" label="年级" width="90" />
            <el-table-column prop="name" label="班名" />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="row.active ? 'success' : 'info'" size="small">
                  {{ row.active ? '启用' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <el-dialog v-model="importErrorVisible" :title="importErrorTitle" width="560px">
      <p class="tip">整批未写入。修正以下行后重新上传即可。</p>
      <el-table :data="importErrors" size="small" max-height="320">
        <el-table-column prop="row" label="行号" width="70" />
        <el-table-column prop="column" label="列" width="110" />
        <el-table-column prop="message" label="问题" />
      </el-table>
    </el-dialog>
  </div>
</template>

<style scoped>
.org-page {
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

.row {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 12px;
}

.table {
  width: 100%;
}

.select {
  width: 160px;
}

.grade {
  width: 110px;
}

.name {
  width: 100px;
}

.filter {
  margin-top: 4px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
