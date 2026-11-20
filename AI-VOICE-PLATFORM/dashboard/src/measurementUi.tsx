import type { ReactNode } from "react";
import { SOURCE_LABELS } from "./measurementApi";
import type { MetricSource } from "./measurementTypes";

/** Shows where a number comes from, so Meta-reported, tracked, CRM and typed-in figures are never confused. */
export function SourceBadge({ source }: { source: MetricSource | string }) {
  return <span className={`source-badge source-${source}`} title={SOURCE_LABELS[source] ?? source}>{SOURCE_LABELS[source] ?? source}</span>;
}

export function ClientRequired({ clientId, children }: { clientId: string | undefined; children: ReactNode }) {
  if (!clientId) {
    return <div className="loading-panel">Choose a client in the top bar. Tracking, attribution and CRM data are kept separately for each client.</div>;
  }
  return <>{children}</>;
}

export function KpiCard({ label, value, source, note }: { label: string; value: string; source: MetricSource | string; note?: string | null }) {
  return (
    <article className="metric-card kpi-card">
      <SourceBadge source={source} />
      <p>{label}</p>
      <strong>{value}</strong>
      {note && <small>{note}</small>}
    </article>
  );
}

export function ErrorBanner({ error, onDismiss }: { error: string | null; onDismiss?: () => void }) {
  if (!error) return null;
  return <div className="global-error" role="alert"><span>{error}</span>{onDismiss && <button type="button" onClick={onDismiss}>Dismiss</button>}</div>;
}

export function errorText(err: unknown, fallback: string): string {
  return err instanceof Error ? err.message : fallback;
}
