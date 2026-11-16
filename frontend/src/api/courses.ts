import { apiClient } from './client'
import type { PageResult } from '../types/api'

/**
 * M2 课程 API 客户端（docs/design/02 §4.2）。
 *
 * 后台 JSON 统一 snake_case，本文件的类型与字段名与契约保持一致；
 * 页面层负责把 snake_case 转成展示用的 ViewModel。
 */

export type CourseStatus = 'active' | 'archived'

export interface MajorBrief {
  id: string
  name: string
}

export interface TeacherBrief {
  account_id: string
  username: string
}

export interface CourseCardView {
  id: string
  name: string
  status: CourseStatus
  ended: boolean
  end_at: string | null
  majors: MajorBrief[]
  teachers: TeacherBrief[]
  student_count: number
  joined: boolean
  can_manage: boolean
}

export interface CourseDetailView extends CourseCardView {
  school_id: string
  created_by: string
  archived_at: string | null
  created_at: string
  can_join: boolean
  can_leave: boolean
  can_archive: boolean
  can_restore: boolean
}

export interface CourseTeacherView {
  account_id: string
  username: string
  status: 'active' | 'removed'
  joined_at: string
  left_at: string | null
}

export interface CourseStudentView {
  account_id: string
  username: string
  class_id: string | null
  class_name: string | null
  join_method: 'self' | 'class' | 'account' | 'admin'
  status: 'active' | 'left' | 'removed'
  joined_at: string
  left_at: string | null
}

export interface BatchAddStudentsResult {
  total: number
  success: number
  skipped: number
  errors: Array<{ account_id: string; reason: string }>
}

export interface CourseListQuery {
  keyword?: string
  scope?: 'mine' | 'all'
  status?: CourseStatus | 'ended'
  page?: number
  size?: number
}

export interface CreateCoursePayload {
  name: string
  major_ids: string[]
  end_at?: string | null
}

export interface UpdateCoursePayload {
  name?: string
  end_at?: string | null
}

export async function listCourses(query: CourseListQuery = {}): Promise<PageResult<CourseCardView>> {
  const { data } = await apiClient.get<PageResult<CourseCardView>>('/courses', {
    params: {
      keyword: query.keyword || undefined,
      scope: query.scope,
      status: query.status,
      page: query.page ?? 1,
      size: query.size ?? 20,
    },
  })
  return data
}

export async function getCourse(courseId: string): Promise<CourseDetailView> {
  const { data } = await apiClient.get<CourseDetailView>(`/courses/${courseId}`)
  return data
}

export async function createCourse(payload: CreateCoursePayload): Promise<CourseDetailView> {
  const { data } = await apiClient.post<CourseDetailView>('/courses', payload)
  return data
}

export async function updateCourse(
  courseId: string,
  payload: UpdateCoursePayload,
): Promise<CourseDetailView> {
  const { data } = await apiClient.patch<CourseDetailView>(`/courses/${courseId}`, payload)
  return data
}

export async function archiveCourse(courseId: string): Promise<void> {
  await apiClient.post(`/courses/${courseId}/archive`)
}

export async function restoreCourse(courseId: string): Promise<void> {
  await apiClient.post(`/courses/${courseId}/restore`)
}

export async function listTeachers(
  courseId: string,
  includeHistory = false,
): Promise<CourseTeacherView[]> {
  const { data } = await apiClient.get<CourseTeacherView[]>(`/courses/${courseId}/teachers`, {
    params: { include_history: includeHistory },
  })
  return data
}

export async function addTeacher(courseId: string, accountId: string): Promise<void> {
  await apiClient.post(`/courses/${courseId}/teachers`, { account_id: accountId })
}

export async function removeTeacher(courseId: string, accountId: string): Promise<void> {
  await apiClient.delete(`/courses/${courseId}/teachers/${accountId}`)
}

export async function listStudents(
  courseId: string,
  params: { includeHistory?: boolean; page?: number; size?: number } = {},
): Promise<PageResult<CourseStudentView>> {
  const { data } = await apiClient.get<PageResult<CourseStudentView>>(
    `/courses/${courseId}/students`,
    {
      params: {
        include_history: params.includeHistory ?? false,
        page: params.page ?? 1,
        size: params.size ?? 20,
      },
    },
  )
  return data
}

export async function addStudentsByClass(
  courseId: string,
  classId: string,
): Promise<BatchAddStudentsResult> {
  const { data } = await apiClient.post<BatchAddStudentsResult>(
    `/courses/${courseId}/students/class`,
    { class_id: classId },
  )
  return data
}

export async function addStudentByAccount(courseId: string, accountId: string): Promise<void> {
  await apiClient.post(`/courses/${courseId}/students/account`, { student_account_id: accountId })
}

export async function removeStudent(courseId: string, accountId: string): Promise<void> {
  await apiClient.delete(`/courses/${courseId}/students/${accountId}`)
}

export async function joinCourse(courseId: string): Promise<void> {
  await apiClient.post(`/courses/${courseId}/join`)
}

export async function leaveCourse(courseId: string): Promise<void> {
  await apiClient.post(`/courses/${courseId}/leave`)
}
