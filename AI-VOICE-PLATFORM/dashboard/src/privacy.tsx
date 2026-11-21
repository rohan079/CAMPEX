import { useEffect, useState } from "react";

/**
 * Display-time privacy helpers. Personal contact details (phone numbers, emails) are masked everywhere by
 * default; a user who needs to act on a lead can reveal a value deliberately, and it re-masks itself.
 */

const PHONE_LIKE = /^\+?[\d\s().-]{6,}$/;

export function looksLikePhone(value: string | null | undefined): boolean {
  return Boolean(value && PHONE_LIKE.test(value.trim()) && value.replace(/\D/g, "").length >= 6);
}

/** "+919876543210" → "+91 ••••• 3210"; "9876543210" → "•••••• 3210". */
export function maskPhone(value: string | null | undefined): string {
  if (!value) return "—";
  const digits = value.replace(/\D/g, "");
  if (digits.length < 6) return "••••";
  const last = digits.slice(-4);
  const hasCountry = value.trim().startsWith("+") && digits.length > 10;
  const country = hasCountry ? `+${digits.slice(0, digits.length - 10)} ` : "";
  return `${country}•••••• ${last}`;
}

/** "ravi.kumar@example.com" → "r•••@example.com". */
export function maskEmail(value: string | null | undefined): string {
  if (!value) return "—";
  const at = value.indexOf("@");
  if (at < 1) return "•••";
  return `${value[0]}•••${value.slice(at)}`;
}

/** A lead's display name, never falling back to a raw phone number. */
export function safeName(name: string | null | undefined, phone?: string | null): string {
  const trimmed = name?.trim();
  if (trimmed && !looksLikePhone(trimmed)) return trimmed;
  const source = trimmed || phone;
  return source ? `Lead ${maskPhone(source).replace(/^.*\s/, "· ")}` : "Unnamed lead";
}

/** Team member shown by first name only: "asha.manager@example.com" → "Asha". */
export function actorName(email: string | null | undefined): string {
  if (!email) return "";
  const local = email.split("@")[0] ?? "";
  const first = local.split(/[._+-]/)[0] ?? "";
  if (!first) return "Team member";
  return first.charAt(0).toUpperCase() + first.slice(1).toLowerCase();
}

type SensitiveProps = {
  value: string | null | undefined;
  kind: "phone" | "email";
  /** Seconds before a revealed value is masked again. */
  revealFor?: number;
};

/** Masked contact detail with an explicit, time-limited reveal. */
export function Sensitive({ value, kind, revealFor = 30 }: SensitiveProps) {
  const [shown, setShown] = useState(false);

  useEffect(() => {
    if (!shown) return;
    const timer = window.setTimeout(() => setShown(false), revealFor * 1000);
    return () => window.clearTimeout(timer);
  }, [shown, revealFor]);

  if (!value) return <span className="sensitive muted">—</span>;
  const masked = kind === "phone" ? maskPhone(value) : maskEmail(value);
  const label = kind === "phone" ? "phone number" : "email";
  return (
    <span className="sensitive">
      <span className={shown ? "sensitive-value revealed" : "sensitive-value"}>{shown ? value : masked}</span>
      <button type="button" className="reveal-button" onClick={() => setShown((v) => !v)}
        aria-pressed={shown} aria-label={shown ? `Hide ${label}` : `Show ${label}`}>
        {shown ? "Hide" : "Show"}
      </button>
    </span>
  );
}
