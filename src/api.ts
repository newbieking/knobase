export async function api<T>(path: string, options?: RequestInit): Promise<T> {
  const response = await fetch(`/api${path}`, {
    ...options,
    headers: options?.body instanceof FormData ? options.headers : { 'Content-Type': 'application/json', ...options?.headers },
  });
  if (!response.ok) {
    const data = await response.json().catch(() => ({}));
    throw new Error(data.message || data.detail || `请求失败（${response.status}），请稍后重试`);
  }
  if (response.status === 204) return undefined as T;
  const text = await response.text();
  return text ? JSON.parse(text) as T : undefined as T;
}
export function number(value: number) { return new Intl.NumberFormat('en-US').format(value); }
export function relativeTime(date: string) {
  const delta = Math.max(0, Date.now() - new Date(date).getTime());
  if (!Number.isFinite(delta)) return '最近更新';
  if (delta < 60000) return '刚刚';
  if (delta < 3600000) return `${Math.floor(delta / 60000)} 分钟前`;
  if (delta < 86400000) return `${Math.floor(delta / 3600000)} 小时前`;
  if (delta < 604800000) return `${Math.floor(delta / 86400000)} 天前`;
  return new Date(date).toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' });
}
export function fileSize(bytes: number) { return bytes >= 1048576 ? `${(bytes / 1048576).toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`; }
