import type { ReactNode } from "react";

export function Stat({
  label,
  value,
  detail,
  accent,
}: {
  label: string;
  value: ReactNode;
  detail?: ReactNode;
  accent?: "ads" | "agent" | "leads";
}) {
  return (
    <article className={`stat${accent ? ` accent-${accent}` : ""}`}>
      <p>{label}</p>
      <strong>{value}</strong>
      {detail && <small>{detail}</small>}
    </article>
  );
}

export function SectionHead({ eyebrow, text }: { eyebrow: string; text: string }) {
  return (
    <div className="section-head">
      <p className="eyebrow">{eyebrow}</p>
      <p className="muted">{text}</p>
    </div>
  );
}
