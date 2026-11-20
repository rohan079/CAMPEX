/** Pure helpers for the measurement screens (no network, safe to import in tests). */

export interface DateRange { from: string; to: string }

/** ISO dates (YYYY-MM-DD) for the last `days` days, inclusive of today. */
export function rangeForDays(days: number, today = new Date()): DateRange {
  const to = new Date(today.getFullYear(), today.getMonth(), today.getDate());
  const from = new Date(to);
  from.setDate(from.getDate() - (days - 1));
  const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
  return { from: iso(from), to: iso(to) };
}

export const SOURCE_LABELS: Record<string, string> = {
  meta_reported: "Meta-reported",
  campex_tracked: "Campex-tracked",
  crm_verified: "CRM-verified",
  manual_financial: "Manually entered",
  derived: "Derived",
};

export function labelize(value: string | null | undefined): string {
  if (!value) return "—";
  return value.toLowerCase().replaceAll("_", " ").replace(/^./, (ch) => ch.toUpperCase());
}
