import { ResponseShapeError } from "./parse";

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly retryable: boolean;
  readonly traceId: string;
  readonly details: Record<string, unknown>;

  constructor(
    status: number,
    code: string,
    message: string,
    retryable: boolean,
    traceId: string,
    details: Record<string, unknown>,
  ) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.retryable = retryable;
    this.traceId = traceId;
    this.details = details;
  }
}

export function isApiError(error: unknown): error is ApiError {
  return error instanceof ApiError;
}

export function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === "AbortError";
}

export function isTransportLoss(error: unknown): boolean {
  return error instanceof TypeError;
}

export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return `${error.code}: ${error.message}`;
  if (error instanceof ResponseShapeError) return error.message;
  if (isTransportLoss(error)) return "网络中断";
  if (error instanceof Error && error.message) return error.message;
  return "请求失败";
}

export async function requestJson(path: string, options: {
  method: "GET" | "POST" | "PUT";
  body?: unknown;
  signal?: AbortSignal;
}): Promise<unknown> {
  const mutation = options.method !== "GET";
  const headers = new Headers({ Accept: "application/json" });
  if (mutation) {
    headers.set("Content-Type", "application/json");
    headers.set("X-Frayground-Client", "laboratory");
  }
  let response: Response;
  try {
    response = await fetch(`/api/v1${path}`, {
      method: options.method,
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: options.signal,
      cache: "no-store",
    });
  } catch (error) {
    if (isAbortError(error)) throw error;
    throw error;
  }
  const text = await response.text();
  let payload: unknown = null;
  if (text) {
    try {
      payload = JSON.parse(text) as unknown;
    } catch {
      throw new ResponseShapeError("json");
    }
  }
  if (!response.ok) throw parseErrorBody(response.status, payload);
  return payload;
}

function parseErrorBody(status: number, value: unknown): ApiError {
  if (isRecord(value)
    && typeof value.code === "string"
    && value.code.length > 0
    && typeof value.message === "string"
    && typeof value.retryable === "boolean"
    && typeof value.traceId === "string"
    && isRecord(value.details)) {
    return new ApiError(status, value.code, value.message, value.retryable, value.traceId, value.details);
  }
  return new ApiError(status, "UNRECOGNIZED_ERROR", `HTTP ${status}`, false, "", {});
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
