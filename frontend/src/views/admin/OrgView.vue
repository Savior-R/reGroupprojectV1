<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
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
  updateClass,
  updateMajor,
} from '../../api/auth'
import type { ClassView, ImportErrorRow, MajorView, SchoolView } from '../../api/auth'

/**
 * 学校与组织（FR-M1-04 / 03 §3.9 /admin/org）：
 * 专业 / 班级维护，停用不删除；M1-F-02 按原型 V1.5——新增与编辑均在独立弹窗完成，
 * 列表提供分页与空态，导入失败按行展示。
 */

const school = ref<SchoolView | null>(null)
const majors = ref<MajorView[]>([])
const classes = ref<ClassView[]>([])
const selectedMajor = ref<string>('')

// 分页（前端分页，数据量小；接口全量返回）
const majorPage = ref(1)
const classPage = ref(1)
const pageSize = 8
const pagedMajors = computed(() =>
  majors.value.slice((majorPage.value - 1) * pageSize, majorPage.value * pageSize),
)
const pagedClasses = computed(() =>
  classes.value.slice((classPage.value - 1) * pageSize, classPage.value * pageSize),
)

// 弹窗状态：新增 / 编辑共用
const majorDialogVisible = ref(false)
const majorDialogTitle = ref('新增专业')
const editingMajorId = ref<string | null>(null)
const majorForm = reactive({ name: '', active: true })

const classDialogVisible = ref(false)
const classDialogTitle = ref('新增班级')
const editingClassId = ref<string | null>(null)
const classForm = reactive({ majorId: '', grade: '', name: '', active: true })

// 导入失败明细
const importErrors = ref<ImportErrorRow[]>([])
const importErrorVisible = ref(false)
const importErrorTitle = ref('')

async function reload(): Promise<void> {
  school.value = await currentSchool().catch(() => null)
  majors.value = await listAdminMajors().catch(() => [])
  classes.value = await listAdminClasses(selectedMajor.value || undefined).catch(() => [])
}

function openMajorDialog(major?: MajorView): void {
  editingMajorId.value = major?.id ?? null
  majorDialogTitle.value = major ? '编辑专业' : '新增专业'
  majorForm.name = major?.name ?? ''
  majorForm.active = major?.active ?? true
  majorDialogVisible.value = true
}

async function saveMajor(): Promise<void> {
  if (!majorForm.name.trim()) {
    ElMessage.warning('请输入专业名称')
    return
  }
  try {
    if (editingMajorId.value) {
      await updateMajor(editingMajorId.value, {
        name: majorForm.name.trim(),
        active: majorForm.active,
      })
      ElMessage.success('专业已更新')
    } else {
      await createMajor(majorForm.name.trim())
      ElMessage.success('专业已创建')
    }
    majorDialogVisible.value = false
    await reload()
  } catch (error) {
    ElMessage.error(apiMessage(error, '保存失败'))
  }
}

async function toggleMajor(major: MajorView): Promise<void> {
  try {
    await updateMajor(major.id, { active: !major.active })
    ElMessage.success(major.active ? '专业已停用' : '专业已启用')
    await reload()
  } catch (error) {
    ElMessage.error(apiMessage(error, '操作失败'))
  }
}

function openClassDialog(classItem?: ClassView): void {
  editingClassId.value = classItem?.id ?? null
  classDialogTitle.value = classItem ? '编辑班级' : '新增班级'
  classForm.majorId = classItem?.major_id ?? selectedMajor.value ?? ''
  classForm.grade = classItem?.grade ?? ''
  classForm.name = classItem?.name ?? ''
  classForm.active = classItem?.active ?? true
  classDialogVisible.value = true
}

async function saveClass(): Promise<void> {
  if (!classForm.majorId || !classForm.grade.trim() || !classForm.name.trim()) {
    ElMessage.warning('请填写专业、年级与班名')
    return
  }
  try {
    if (editingClassId.value) {
      await updateClass(editingClassId.value, {
        majorId: classForm.majorId,
        grade: classForm.grade.trim(),
        name: classForm.name.trim(),
        active: classForm.active,
      })
      ElMessage.success('班级已更新')
    } else {
      await createClass(classForm.majorId, classForm.grade.trim(), classForm.name.trim())
      ElMessage.success('班级已创建')
    }
    classDialogVisible.value = false
    await reload()
  } catch (error) {
    ElMessage.error(apiMessage(error, '保存失败'))
  }
}

async function filterByMajor(): Promise<void> {
  classPage.value = 1
  classes.value = await listAdminClasses(selectedMajor.value || undefined)
}

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
      新增与编辑均在弹窗内完成。
    </p>

    <el-row :gutter="16">
      <el-col :span="11">
        <el-card>
          <template #header>
            <div class="card-header">
              <span>专业（{{ majors.length }}）</span>
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
                <el-button type="primary" size="small" @click="openMajorDialog()">新增专业</el-button>
              </div>
            </div>
          </template>
          <el-table :data="pagedMajors" size="small" class="table" empty-text="暂无专业，点击「新增专业」创建">
            <el-table-column prop="name" label="专业" />
            <el-table-column label="状态" width="80">
              <template #default="{ row }">
                <el-tag :type="row.active ? 'success' : 'info'" size="small">
                  {{ row.active ? '启用' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="130">
              <template #default="{ row }">
                <el-button link type="primary" @click="openMajorDialog(row)">编辑</el-button>
                <el-button link type="primary" @click="toggleMajor(row)">
                  {{ row.active ? '停用' : '启用' }}
                </el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination
            v-model:current-page="majorPage"
            :page-size="pageSize"
            :total="majors.length"
            layout="prev, pager, next"
            small
            class="pager"
          />
        </el-card>
      </el-col>

      <el-col :span="13">
        <el-card>
          <template #header>
            <div class="card-header">
              <span>班级（{{ classes.length }}）</span>
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
                <el-button type="primary" size="small" @click="openClassDialog()">新增班级</el-button>
              </div>
            </div>
          </template>

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

          <el-table :data="pagedClasses" size="small" class="table" empty-text="暂无班级，点击「新增班级」创建">
            <el-table-column prop="major_name" label="专业" />
            <el-table-column prop="grade" label="年级" width="80" />
            <el-table-column prop="name" label="班名" />
            <el-table-column label="状态" width="80">
              <template #default="{ row }">
                <el-tag :type="row.active ? 'success' : 'info'" size="small">
                  {{ row.active ? '启用' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="70">
              <template #default="{ row }">
                <el-button link type="primary" @click="openClassDialog(row)">编辑</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination
            v-model:current-page="classPage"
            :page-size="pageSize"
            :total="classes.length"
            layout="prev, pager, next"
            small
            class="pager"
          />
        </el-card>
      </el-col>
    </el-row>

    <!-- 专业新增 / 编辑弹窗 -->
    <el-dialog v-model="majorDialogVisible" :title="majorDialogTitle" width="420px">
      <el-form label-width="72px">
        <el-form-item label="专业名称">
          <el-input v-model="majorForm.name" maxlength="100" placeholder="如：汉语言文学" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="majorForm.active" active-text="启用" inactive-text="停用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="majorDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveMajor">保存</el-button>
      </template>
    </el-dialog>

    <!-- 班级新增 / 编辑弹窗 -->
    <el-dialog v-model="classDialogVisible" :title="classDialogTitle" width="460px">
      <el-form label-width="72px">
        <el-form-item label="专业">
          <el-select v-model="classForm.majorId" placeholder="选择专业" class="dialog-select">
            <el-option
              v-for="major in majors.filter((item) => item.active || item.id === classForm.majorId)"
              :key="major.id"
              :label="major.name"
              :value="major.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="年级">
          <el-input v-model="classForm.grade" placeholder="如：2024" />
        </el-form-item>
        <el-form-item label="班名">
          <el-input v-model="classForm.name" maxlength="100" placeholder="如：语文2401" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="classForm.active" active-text="启用" inactive-text="停用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="classDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveClass">保存</el-button>
      </template>
    </el-dialog>

    <!-- 导入失败明细 -->
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
  width: 180px;
}

.dialog-select {
  width: 100%;
}

.filter {
  margin-bottom: 12px;
}

.pager {
  margin-top: 12px;
  justify-content: flex-end;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
</style>
