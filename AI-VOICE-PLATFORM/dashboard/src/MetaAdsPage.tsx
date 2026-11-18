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

export function MetaAdsPage({ onOpenChannels }: { onOpenChannels: () => void }) {
  const [data, setData] = useState<MetaAdsDashboard | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [adAccountId, setAdAccountId] = useState("");
  const [datePreset, setDatePreset] = useState("last_14d");
  const [tab, setTab] = useState<"overview" | "tools">("overview");

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
      setError(err instanceof ApiError || err instanceof Error ? err.message : "Could not load Meta Ads MCP data");
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
          <p className="eyebrow">Meta Ads MCP</p>
          <h2>Live data from Meta Business tools.</h2>
          <p>This page reads the same FastMCP Meta tools used by campaign assist and publish — accounts, campaigns, ads, and insights.</p>
        </div>
        <div className="segmented-tabs" role="tablist" aria-label="Meta Ads sections">
          <button type="button" role="tab" aria-selected={tab === "overview"} onClick={() => setTab("overview")}>Overview</button>
          <button type="button" role="tab" aria-selected={tab === "tools"} onClick={() => setTab("tools")}>
            MCP tools{data ? ` (${data.tools.length})` : ""}
          </button>
        </div>
      </section>

      {error && (
        <section className="connection-banner">
          <span className="connection-icon">!</span>
          <div>
            <strong>{error}</strong>
            <p>Connect Meta Business under Channels, then refresh this page.</p>
          </div>
          <button type="button" className="secondary-button" onClick={onOpenChannels}>Open Channels</button>
        </section>
      )}

      <section className="surface-card">
        <div className="section-title-row">
          <div><p className="step-label">FILTERS</p><h3>Ad account and period</h3></div>
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
                    {text(account.name, id)}{account.currency ? ` · ${String(account.currency)}` : ""}
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

      {loading && !data ? <div className="loading-panel">Loading Meta Ads MCP data…</div> : null}

      {data && tab === "overview" && (
        <>
          <section className="metric-grid" aria-label="Account insights">
            <article className="metric-card"><span className="metric-icon violet">₹</span><p>Spend</p><strong>{num(Math.round(spend))}</strong><small>{datePreset.replaceAll("_", " ")}</small></article>
            <article className="metric-card"><span className="metric-icon blue">◉</span><p>Impressions</p><strong>{num(impressions)}</strong><small>Account insights</small></article>
            <article className="metric-card"><span className="metric-icon green">↗</span><p>Reach</p><strong>{num(reach)}</strong><small>Unique people</small></article>
            <article className="metric-card"><span className="metric-icon amber">⌁</span><p>Clicks</p><strong>{num(clicks)}</strong><small>All clicks</small></article>
          </section>

          <section className="meta-assets-grid">
            <div className="surface-card">
              <h4>Businesses ({data.businesses.length})</h4>
              {data.businesses.length ? (
                <ul>{data.businesses.map((row) => <li key={text(row.id)}><strong>{text(row.name)}</strong><small>{text(row.id)}</small></li>)}</ul>
              ) : <p className="hint">No businesses returned.</p>}
            </div>
            <div className="surface-card">
              <h4>Pages ({data.pages.length})</h4>
              {data.pages.length ? (
                <ul>{data.pages.map((row) => <li key={text(row.id)}><strong>{text(row.name)}</strong><small>{text(row.category, text(row.id))}</small></li>)}</ul>
              ) : <p className="hint">No Pages returned.</p>}
            </div>
            <div className="surface-card">
              <h4>Instagram ({data.instagram_accounts.length})</h4>
              {data.instagram_accounts.length ? (
                <ul>{data.instagram_accounts.map((row) => <li key={text(row.id)}><strong>{text(row.name || row.username)}</strong><small>{row.username ? `@${String(row.username)}` : text(row.id)}</small></li>)}</ul>
              ) : <p className="hint">No Instagram accounts linked.</p>}
            </div>
          </section>

          <section className="surface-card">
            <div className="section-title-row">
              <div><p className="step-label">CAMPAIGNS</p><h3>get_campaigns</h3></div>
              <span className="muted-label">{data.campaigns.length} rows</span>
            </div>
            {data.campaigns.length === 0 ? <p className="empty-state">No campaigns for this ad account.</p> : (
              <div className="table-wrap">
                <table>
                  <thead><tr><th>Name</th><th>Objective</th><th>Status</th><th>Budget</th><th>Updated</th></tr></thead>
                  <tbody>
                    {data.campaigns.map((row) => (
                      <tr key={text(row.id)}>
                        <td><strong>{text(row.name)}</strong><div><small>{text(row.id)}</small></div></td>
                        <td>{text(row.objective)}</td>
                        <td>{text(row.effective_status || row.status)}</td>
                        <td>{text(row.daily_budget || row.lifetime_budget)}</td>
                        <td>{text(row.updated_time)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          <section className="surface-card">
            <div className="section-title-row">
              <div><p className="step-label">AD SETS</p><h3>get_ad_sets</h3></div>
              <span className="muted-label">{data.ad_sets.length} rows</span>
            </div>
            {data.ad_sets.length === 0 ? <p className="empty-state">No ad sets for this ad account.</p> : (
              <div className="table-wrap">
                <table>
                  <thead><tr><th>Name</th><th>Campaign</th><th>Status</th><th>Optimization</th></tr></thead>
                  <tbody>
                    {data.ad_sets.map((row) => (
                      <tr key={text(row.id)}>
                        <td><strong>{text(row.name)}</strong></td>
                        <td>{text(row.campaign_id)}</td>
                        <td>{text(row.effective_status || row.status)}</td>
                        <td>{text(row.optimization_goal)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          <section className="surface-card">
            <div className="section-title-row">
              <div><p className="step-label">ADS</p><h3>get_ads</h3></div>
              <span className="muted-label">{data.ads.length} rows</span>
            </div>
            {data.ads.length === 0 ? <p className="empty-state">No ads for this ad account.</p> : (
              <div className="table-wrap">
                <table>
                  <thead><tr><th>Name</th><th>Ad set</th><th>Status</th><th>Creative</th></tr></thead>
                  <tbody>
                    {data.ads.map((row) => (
                      <tr key={text(row.id)}>
                        <td><strong>{text(row.name)}</strong></td>
                        <td>{text(row.adset_id)}</td>
                        <td>{text(row.effective_status || row.status)}</td>
                        <td>{text((row.creative as Record<string, unknown> | undefined)?.id)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>
        </>
      )}

      {data && tab === "tools" && (
        <section className="surface-card">
          <div className="section-title-row">
            <div><p className="step-label">FASTMCP CATALOG</p><h3>Tools exposed by Meta Ads MCP</h3></div>
            <span className="muted-label">{data.tools.length} tools</span>
          </div>
          <div className="table-wrap">
            <table>
              <thead><tr><th>Tool</th><th>Type</th><th>Description</th></tr></thead>
              <tbody>
                {data.tools.map((tool) => (
                  <tr key={tool.name}>
                    <td><strong>{tool.name}</strong></td>
                    <td>{tool.read_only ? "Read" : "Write"}</td>
                    <td>{tool.description}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
    </div>
  );
}
