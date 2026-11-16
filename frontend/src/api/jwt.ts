/**
 * JWT 载荷解码（仅用于前端路由判断）。
 *
 * 不校验签名——签名校验在后端；前端只读取 account_type / roles / school_id
 * 决定该显示哪个页面。token 被篡改时后端会返回 401，前端随之跳回登录页。
 *
 * 多角色（V14）：`account_type` 是主身份（注册时确认、不可变），`roles` 是
 * 本次 token 的授权角色集合（教师可被授予 admin）。判断“能不能进管理端”
 * 必须用 roles，不能用 account_type。
 */

export type Role = 'student' | 'teacher' | 'admin'

export interface JwtClaims {
  sub: string
  account_type: Role
  /** 授权角色集合；旧 token 可能没有该字段，此时回退到 account_type */
  roles?: Role[]
  school_id?: string | null
  exp?: number
  iat?: number
  jti?: string
}

/** 当前 token 拥有的角色；旧 token（无 roles）按主身份处理。 */
export function rolesOf(claims: JwtClaims | null): Role[] {
  if (!claims) {
    return []
  }
  if (claims.roles && claims.roles.length > 0) {
    return claims.roles
  }
  return claims.account_type ? [claims.account_type] : []
}

export function hasRole(claims: JwtClaims | null, role: Role): boolean {
  return rolesOf(claims).includes(role)
}

export function isAdmin(claims: JwtClaims | null): boolean {
  return hasRole(claims, 'admin')
}

function base64UrlDecode(input: string): string {
  const padded = input.replace(/-/g, '+').replace(/_/g, '/')
  const pad = padded.length % 4 === 0 ? '' : '='.repeat(4 - (padded.length % 4))
  const binary = window.atob(padded + pad)
  // 中文用户名等非 ASCII 内容需要按 UTF-8 还原
  const bytes = Uint8Array.from(binary, (char) => char.charCodeAt(0))
  return new TextDecoder().decode(bytes)
}

/** 解析失败或已过期时返回 null。 */
export function decodeJwt(token: string | null): JwtClaims | null {
  if (!token) {
    return null
  }
  try {
    const parts = token.split('.')
    if (parts.length < 2) {
      return null
    }
    const claims = JSON.parse(base64UrlDecode(parts[1])) as JwtClaims
    if (claims.exp !== undefined && claims.exp * 1000 <= Date.now()) {
      return null
    }
    return claims
  } catch {
    return null
  }
}

/** 登录后应落地的页面：管理员按是否已建校；学生进任务中心；教师暂无专属首页。 */
export function homeFor(claims: JwtClaims): string {
  if (isAdmin(claims)) {
    return claims.school_id ? '/admin/org' : '/admin/setup'
  }
  if (hasRole(claims, 'student')) {
    return '/student/tasks'
  }
  return '/settings/security'
}
