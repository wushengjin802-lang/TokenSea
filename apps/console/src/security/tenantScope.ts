import type { SessionIdentity } from "../api/client";

export function assertTenantScope(context: unknown, session: SessionIdentity) {
  if (session.roles.includes("ADMIN")) return;
  const returned = Array.isArray((context as any)?.tenantIds)
    ? (context as any).tenantIds.map(String)
    : [];
  const granted = new Set((session.tenantIds || []).map(String));
  const leaked = returned.filter((tenantId: string) => !granted.has(tenantId));
  if (leaked.length) throw new Error("租户数据范围校验失败，请停止操作并联系管理员");
}
