import { useCallback, useEffect, useMemo, useState } from "react";
import { ApiError, getMetaAdsDashboard } from "./api";
import type { MetaAdsDashboard } from "./api";
import { num } from "./format";

const DATE_PRESETS = [
  { id: "last_7d", label: "7d" },
  { id: "last_14d", label: "14d" },
  { id: "last_30d", label: "30d" },
  { id: "this_month", label: "This month" },
];

function text(value: unknown, fallback = "—"): string {
  if (value === null || value === undefined || value === "") return fallback;
  return String(value);
}

function insightTotal(rows: Array<Record<string, unknown>>, key: string): number {
  return rows.reduce((sum, row) => {
    const raw = row[key];
    const value = typeof raw === "number" ? raw : Number(raw);
    return sum + (Number.isFinite(value) ? value : 0);
  }, 0);
}

function formatStatus(value: unknown): string {
  const raw = text(value, "");
  if (!raw) return "—";
  return raw.replaceAll("_", " ").toLowerCase().replace(/^\w/, (c) => c.toUpperCase());
}

export function MetaAdsPage({ onOpenChannels }: { onOpenChannels: () => void }) {
  const [data, setData] = useState<MetaAdsDashboard | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [adAccountId, setAdAccountId] = useState("");
  const [datePreset, setDatePreset] = useState("last_14d");

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const next = await getMetaAdsDashboard({
        adAccountId: adAccountId || undefined,
        datePreset,
      });
      setData(next);
      if (!adAccountId && next.selected_ad_account_id) {
        setAdAccountId(next.selected_ad_account_id);
      }
    } catch (err) {
      setData(null);
      const message = err instanceof ApiError || err instanceof Error ? err.message : "";
      setError(
        /connect meta|unauthorized|401/i.test(message)
          ? "Connect your Meta Business account under Channels, then refresh."
          : "Could not load Meta Ads data. Try again shortly.",
      );
    } finally {
      setLoading(false);
    }
  }, [adAccountId, datePreset]);

  useEffect(() => {
    void load();
  }, [load]);

  const insights = data?.account_insights ?? [];
  const spend = useMemo(() => insightTotal(insights, "spend"), [insights]);
  const impressions = useMemo(() => insightTotal(insights, "impressions"), [insights]);
  const reach = useMemo(() => insightTotal(insights, "reach"), [insights]);
  const clicks = useMemo(() => insightTotal(insights, "clicks"), [insights]);

  return (
    <div className="page-stack">
      <section className="page-intro">
        <div>
          <p className="eyebrow">Advertising</p>
          <h2>Your Meta Ads workspace.</h2>
          <p>Review connected businesses, pages, campaigns, and recent performance.</p>
        </div>
      </section>

      {error && (
        <section className="connection-banner">
          <span className="connection-icon">!</span>
          <div>
            <strong>{error}</strong>
            <p>Once Meta Business is connected, your ad accounts and campaigns appear here.</p>
          </div>
          <button type="button" className="secondary-button" onClick={onOpenChannels}>Open Channels</button>
        </section>
      )}

      <section className="surface-card">
        <div className="section-title-row">
          <div><p className="step-label">VIEW</p><h3>Ad account and period</h3></div>
          <button type="button" className="secondary-button" disabled={loading} onClick={() => void load()}>
            {loading ? "Refreshing…" : "Refresh"}
          </button>
        </div>
        <div className="form-two-col targeting-grid">
          <label className="form-field">
            <span>Ad account</span>
            <select
              value={adAccountId}
              onChange={(event) => setAdAccountId(event.target.value)}
              disabled={!data?.ad_accounts?.length}
            >
              {(data?.ad_accounts ?? []).length === 0 && <option value="">No ad accounts</option>}
              {(data?.ad_accounts ?? []).map((account) => {
                const id = text(account.id, "");
                return (
                  <option key={id} value={id}>
                    {text(account.name, "Ad account")}{account.currency ? ` · ${String(account.currency)}` : ""}
                  </option>
                );
              })}
            </select>
          </label>
          <div className="range-select" aria-label="Insights period">
            {DATE_PRESETS.map((preset) => (
              <button
                key={preset.id}
                type="button"
                aria-pressed={datePreset === preset.id}
                onClick={() => setDatePreset(preset.id)}
              >
                {preset.label}
              </button>
            ))}
          </div>
        </div>
      </section>

      {loading && !data ? <div className="loading-panel">Loading Meta Ads…</div> : null}

      {data && (
        <>
          <section className="metric-grid" aria-label="Account insights">
            <article className="metric-card"><span className="metric-icon violet">₹</span><p>Spend</p><strong>{num(Math.round(spend))}</strong><small>Selected period</small></article>
            <article className="metric-card"><span className="metric-icon blue">◉</span><p>Impressions</p><strong>{num(impressions)}</strong><small>Account performance</small></article>
            <article className="metric-card"><span className="metric-icon green">↗</span><p>Reach</p><strong>{num(reach)}</strong><small>Unique people</small></article>
            <article className="metric-card"><span className="metric-icon amber">⌁</span><p>Clicks</p><strong>{num(clicks)}</strong><small>All clicks</small></article>
          </section>

          <section className="meta-assets-grid">
            <div className="surface-card">
              <h4>Businesses ({data.businesses.length})</h4>
              {data.businesses.length ? (
                <ul>{data.businesses.map((row) => <li key={text(row.id)}><strong>{text(row.name)}</strong></li>)}</ul>
              ) : <p className="hint">No businesses found.</p>}
            </div>
            <div className="surface-card">
              <h4>Pages ({data.pages.length})</h4>
              {data.pages.length ? (
                <ul>{data.pages.map((row) => <li key={text(row.id)}><strong>{text(row.name)}</strong><small>{text(row.category, "Page")}</small></li>)}</ul>
              ) : <p className="hint">No Pages found.</p>}
            </div>
            <div className="surface-card">
              <h4>Instagram ({data.instagram_accounts.length})</h4>
              {data.instagram_accounts.length ? (
                <ul>{data.instagram_accounts.map((row) => <li key={text(row.id)}><strong>{text(row.name || row.username)}</strong><small>{row.username ? `@${String(row.username)}` : "Instagram account"}</small></li>)}</ul>
              ) : <p className="hint">No Instagram accounts linked.</p>}
            </div>
          </section>

          <section className="surface-card">
            <div className="section-title-row">
              <div><p className="step-label">CAMPAIGNS</p><h3>Active and paused campaigns</h3></div>
              <span className="muted-label">{data.campaigns.length}</span>
            </div>
            {data.campaigns.length === 0 ? <p className="empty-state">No campaigns for this ad account.</p> : (
              <div className="table-wrap">
                <table>
                  <thead><tr><th>Name</th><th>Objective</th><th>Status</th><th>Updated</th></tr></thead>
                  <tbody>
                    {data.campaigns.map((row) => (
                      <tr key={text(row.id)}>
                        <td><strong>{text(row.name)}</strong></td>
                        <td>{formatStatus(row.objective)}</td>
                        <td>{formatStatus(row.effective_status || row.status)}</td>
                        <td>{row.updated_time ? new Date(String(row.updated_time)).toLocaleDateString() : "—"}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          <section className="surface-card">
            <div className="section-title-row">
              <div><p className="step-label">AD SETS</p><h3>Audience groups</h3></div>
              <span className="muted-label">{data.ad_sets.length}</span>
            </div>
            {data.ad_sets.length === 0 ? <p className="empty-state">No ad sets for this ad account.</p> : (
              <div className="table-wrap">
                <table>
                  <thead><tr><th>Name</th><th>Status</th><th>Optimization</th></tr></thead>
                  <tbody>
                    {data.ad_sets.map((row) => (
                      <tr key={text(row.id)}>
                        <td><strong>{text(row.name)}</strong></td>
                        <td>{formatStatus(row.effective_status || row.status)}</td>
                        <td>{formatStatus(row.optimization_goal)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          <section className="surface-card">
            <div className="section-title-row">
              <div><p className="step-label">ADS</p><h3>Creatives in delivery</h3></div>
              <span className="muted-label">{data.ads.length}</span>
            </div>
            {data.ads.length === 0 ? <p className="empty-state">No ads for this ad account.</p> : (
              <div className="table-wrap">
                <table>
                  <thead><tr><th>Name</th><th>Status</th></tr></thead>
                  <tbody>
                    {data.ads.map((row) => (
                      <tr key={text(row.id)}>
                        <td><strong>{text(row.name)}</strong></td>
                        <td>{formatStatus(row.effective_status || row.status)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>
        </>
      )}
    </div>
  );
}
