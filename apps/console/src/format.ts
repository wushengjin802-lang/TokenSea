const ISO_DATE_TIME_PATTERN = /^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:\d{2})?$/;

function pad(value: number, length = 2): string {
  return String(value).padStart(length, "0");
}

function formatLocalDateTime(value: Date): string {
  return `${value.getFullYear()}-${pad(value.getMonth() + 1)}-${pad(value.getDate())} `
    + `${pad(value.getHours())}:${pad(value.getMinutes())}:${pad(value.getSeconds())}.${pad(value.getMilliseconds(), 3)}`;
}

export function formatDateTime(value: unknown): string | undefined {
  if (typeof value !== "string") return undefined;
  const match = ISO_DATE_TIME_PATTERN.exec(value.trim());
  if (!match) return undefined;

  const milliseconds = (match[3] || "").padEnd(3, "0").slice(0, 3);
  const timezone = match[4];
  if (!timezone) {
    return `${match[1]} ${match[2]}.${milliseconds}`;
  }

  const parsed = new Date(`${match[1]}T${match[2]}.${milliseconds}${timezone}`);
  return Number.isNaN(parsed.getTime()) ? undefined : formatLocalDateTime(parsed);
}
