const STATUS_LABELS: Record<string, string> = {
  awaiting_consent: "Awaiting consent",
  awaiting_reply: "Waiting for reply",
  replied: "Replied",
  follow_up_complete: "Follow-up finished",
  message_failed: "Message failed",
  call_queued: "Call queued",
  called_or_calling: "Called",
  do_not_call: "Do not call",
  cancelled: "Cancelled",
  queued: "Queued",
  initiating: "Initiating",
  initiated: "Initiated",
  ringing: "Ringing",
  in_progress: "In progress",
  completed: "Completed",
  failed: "Failed",
  blocked_no_consent: "Blocked",
  initiation_unknown: "Needs review",
};

const LIVE_STATUSES = ["initiating", "initiated", "ringing", "in_progress"];

export function labelStatus(status: string): string {
  return STATUS_LABELS[status] ?? status.replaceAll("_", " ");
}

export function statusTone(status: string): "live" | "good" | "bad" | "warn" | "neutral" {
  if (LIVE_STATUSES.includes(status)) return "live";
  if (status === "completed" || status === "call_queued" || status === "queued") return "good";
  if (status === "failed" || status === "do_not_call" || status === "blocked_no_consent" || status === "message_failed") return "bad";
  if (status === "initiation_unknown" || status === "awaiting_reply") return "warn";
  return "neutral";
}

export function labelLanguage(code: string): string {
  if (code === "te") return "తెలుగు";
  if (code === "en") return "English";
  return code;
}

export function formatDay(value: string | null): string {
  if (!value) return "—";
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(value);
  if (!match) return formatWhen(value);
  const date = new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]));
  return new Intl.DateTimeFormat("en-IN", { day: "2-digit", month: "short" }).format(date);
}

export function formatWhen(value: string | null): string {
  if (!value) return "—";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat("en-IN", {
    day: "2-digit",
    month: "short",
    hour: "2-digit",
    minute: "2-digit",
  }).format(date);
}

export function displayName(name: string | null, phone: string | null): string {
  return name?.trim() || phone || "Unknown lead";
}

export function liveCallCount(byStatus: Record<string, number>): number {
  return LIVE_STATUSES.reduce((sum, key) => sum + (byStatus[key] ?? 0), 0);
}

export function money(value: number, currency: string, compact = false): string {
  return new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency,
    maximumFractionDigits: 0,
    notation: compact ? "compact" : "standard",
  }).format(value);
}

export function num(value: number): string {
  return new Intl.NumberFormat("en-IN").format(value);
}

export function compactNum(value: number): string {
  return new Intl.NumberFormat("en-IN", { notation: "compact", maximumFractionDigits: 1 }).format(value);
}

/** 0.1234 -> "12%". Small rates keep decimals so 0.014% does not read as 0%. */
export function pct(value: number): string {
  if (!Number.isFinite(value) || value <= 0) return "0%";
  const percent = value * 100;
  if (percent < 0.01) return "<0.01%";
  if (percent < 1) return `${percent.toFixed(2)}%`;
  if (percent < 10) return `${percent.toFixed(1)}%`;
  return `${Math.round(percent)}%`;
}
