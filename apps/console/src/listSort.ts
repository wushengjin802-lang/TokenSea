export type ListSortOrder = "asc" | "desc";

const stableIdentityFields = [
  "id",
  "requestId",
  "statementNo",
  "platformModelName",
  "providerModelName",
  "name",
];

function valueOf(row: Record<string, any>, field: string): unknown {
  const snake = field.replace(/[A-Z]/g, (letter) => `_${letter.toLowerCase()}`);
  return row?.[field] ?? row?.[snake];
}

function compareValues(left: unknown, right: unknown): number {
  if (left === right) return 0;
  if (left === null || left === undefined || left === "") return 1;
  if (right === null || right === undefined || right === "") return -1;
  return String(left).localeCompare(String(right), "zh-CN", {
    numeric: true,
    sensitivity: "base",
  });
}

function stableIdentity(row: Record<string, any>): string {
  for (const field of stableIdentityFields) {
    const value = valueOf(row, field);
    if (value !== null && value !== undefined && value !== "") return String(value);
  }
  return "";
}

export function stableSortRows<T extends Record<string, any>>(
  rows: T[],
  field = "id",
  order: ListSortOrder = "asc",
  fallbackFields: string[] = [],
): T[] {
  const direction = order === "asc" ? 1 : -1;
  return rows
    .map((row, index) => ({ row, index }))
    .sort((left, right) => {
      const primary = compareValues(valueOf(left.row, field), valueOf(right.row, field));
      if (primary !== 0) return primary * direction;
      for (const fallbackField of fallbackFields) {
        const fallback = compareValues(
          valueOf(left.row, fallbackField),
          valueOf(right.row, fallbackField),
        );
        if (fallback !== 0) return fallback * direction;
      }
      const identity = compareValues(stableIdentity(left.row), stableIdentity(right.row));
      if (identity !== 0) return identity;
      return left.index - right.index;
    })
    .map(({ row }) => row);
}
