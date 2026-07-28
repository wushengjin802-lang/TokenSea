import axios, { type AxiosResponse } from "axios";
import { gatewayBase } from "./client";

export type GatewayModel = {
  id: string;
  object?: string;
  created?: number;
  owned_by?: string;
  [key: string]: unknown;
};

export type GatewayHealth = {
  healthOk: boolean;
  readinessOk: boolean;
  health?: Record<string, unknown>;
  readiness?: Record<string, unknown>;
};

export const gatewayApi = axios.create({
  baseURL: gatewayBase,
  timeout: 20_000,
});

function detailPayload(error: any) {
  const payload = error?.response?.data;
  return payload?.detail ?? payload?.error ?? payload?.data ?? payload;
}

export function gatewayErrorMessage(error: unknown, fallback = "Gateway 请求失败") {
  const value = error as any;
  const detail = detailPayload(value);
  const status = Number(value?.response?.status || 0);
  const code = detail?.error_code || detail?.code;
  const message =
    (typeof detail === "string" ? detail : detail?.message || detail?.problem) ||
    value?.message;

  if (!value?.response) {
    if (value?.code === "ECONNABORTED") {
      return `Gateway 请求超时（${gatewayBase}），请检查服务、路由和上游供应商连接。`;
    }
    return `浏览器无法访问 Gateway（${gatewayBase}）。请确认 Gateway 已启动、地址可从当前浏览器访问，并检查跨域配置。`;
  }

  const statusMessages: Record<number, string> = {
    400: "请求参数不正确",
    401: "Virtual Key 无效",
    402: "预算额度不足",
    403: "当前 Virtual Key、租户或应用没有该模型权限",
    404: "请求的企业服务模型不存在或未发布",
    409: "当前配置状态不允许执行该请求",
    429: "请求触发 RPM、TPM 或 QPS 限制",
    500: "Gateway 内部异常",
    502: "上游模型服务返回异常",
    503: "模型路由、价格、供应商渠道或依赖服务当前不可用",
    504: "上游模型调用超时",
  };
  const prefix = code ? `${code}：` : "";
  return `${prefix}${message || statusMessages[status] || fallback}`;
}

export async function checkGatewayHealth(): Promise<GatewayHealth> {
  const [healthResponse, readinessResponse] = await Promise.all([
    gatewayApi.get<Record<string, unknown>>("/health", { timeout: 10_000 }),
    gatewayApi.get<Record<string, unknown>>("/health/readiness", { timeout: 10_000 }),
  ]);
  const readinessStatus = String(readinessResponse.data?.status || "").toLowerCase();
  return {
    healthOk: healthResponse.status >= 200 && healthResponse.status < 300,
    readinessOk:
      readinessResponse.status >= 200 &&
      readinessResponse.status < 300 &&
      !["not_ready", "failed", "down"].includes(readinessStatus),
    health: healthResponse.data,
    readiness: readinessResponse.data,
  };
}

export async function listGatewayModels(apiKey: string): Promise<GatewayModel[]> {
  const response = await gatewayApi.get<{ data?: GatewayModel[] }>("/v1/models", {
    timeout: 15_000,
    headers: { Authorization: `Bearer ${apiKey.trim()}` },
  });
  const rows = Array.isArray(response.data?.data) ? response.data.data : [];
  const unique = new Map<string, GatewayModel>();
  for (const row of rows) {
    const id = String(row?.id || "").trim();
    if (id && !unique.has(id)) unique.set(id, { ...row, id });
  }
  return Array.from(unique.values()).sort((left, right) => left.id.localeCompare(right.id));
}

export async function createChatCompletion(
  apiKey: string,
  payload: Record<string, unknown>,
): Promise<AxiosResponse<any>> {
  return gatewayApi.post("/v1/chat/completions", payload, {
    timeout: 120_000,
    headers: {
      Authorization: `Bearer ${apiKey.trim()}`,
      "Content-Type": "application/json",
    },
  });
}
