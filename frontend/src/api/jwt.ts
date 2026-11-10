/**
 * JWT 载荷解码（仅用于前端路由判断）。
 *
 * 不校验签名——签名校验在后端；前端只读取 account_type / school_id 决定
 * 该显示哪个页面。token 被篡改时后端会返回 401，前端随之跳回登录页。
 */

export interface JwtClaims {
  sub: string
  account_type: 'student' | 'teacher' | 'admin'
  school_id?: string | null
  exp?: number
  iat?: number
  jti?: string
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

/** 登录后应落地的页面：管理员区分是否已建校；学生 / 教师进 M2 课程列表。 */
export function homeFor(claims: JwtClaims): string {
  if (claims.account_type === 'admin') {
    return claims.school_id ? '/admin/org' : '/admin/setup'
  }
  return '/courses'
}
