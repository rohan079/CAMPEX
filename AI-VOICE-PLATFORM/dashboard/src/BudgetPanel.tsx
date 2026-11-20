import { useCallback, useEffect, useState } from "react";
import { ChartCard, COLORS, RankBars } from "./charts";
import { money } from "./format";
import { getBudgetAnalysis, saveBudgetPolicy } from "./intelligenceApi";
import type { BudgetAnalysis } from "./intelligenceTypes";
import { labelize } from "./measurementFormat";
import { ErrorBanner, KpiCard, errorText } from "./measurementUi";
import { DataNotes, moneyOrDash, pctOrDash, ratio } from "./PerformanceIntelligencePage";

export function BudgetPanel({ clientId, from, to }: { clientId: string; from: string; to: string }) {
  const [data, setData] = useState<BudgetAnalysis | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const [policy, setPolicy] = useState({ daily: "", monthly: "", maxChange: "20", minQualified: "5", targetCpql: "", targetRoas: "" });

  const load = useCallback(async () => {
    try {
      const next = await getBudgetAnalysis(clientId, { from, to });
      setData(next);
      const p = next.policy;
      setPolicy({ daily: p.daily_spend_limit?.toString() ?? "", monthly: p.monthly_spend_limit?.toString() ?? "",
        maxChange: String(p.max_change_pct), minQualified: String(p.min_qualified_leads),
        targetCpql: p.target_cost_per_qualified_lead?.toString() ?? "", targetRoas: p.target_roas?.toString() ?? "" });
    } catch (err) {
      setError(errorText(err, "Could not load the budget analysis"));
    }
  }, [clientId, from, to]);
  useEffect(() => { void load(); }, [load]);

  async function save(event: React.FormEvent) {
    event.preventDefault();
    const n = (v: string) => (v.trim() === "" ? null : Number(v));
    try {
      await saveBudgetPolicy(clientId, { daily_spend_limit: n(policy.daily), monthly_spend_limit: n(policy.monthly),
        max_change_pct: Number(policy.maxChange), min_qualified_leads: Number(policy.minQualified),
        target_cost_per_qualified_lead: n(policy.targetCpql), target_roas: n(policy.targetRoas) });
      setNote("Guardrails saved and recorded in the audit history.");
      await load();
    } catch (err) {
      setError(errorText(err, "Could not save the guardrails"));
    }
  }

  const cur = data?.currency ?? "INR";
  return (
    <>
      <ErrorBanner error={error} onDismiss={() => setError(null)} />
      {note && <p className="notice success-notice" role="status">{note}</p>}
      {data && (
        <>
          <DataNotes notes={data.data_notes} />
          <section className="metric-grid kpi-grid" aria-label="Budget totals">
            <KpiCard label="Current daily budgets" value={money(data.total_daily_budget, cur)} source="meta_reported" />
            <KpiCard label="Daily total if recommendations were applied" value={money(data.projected_daily_budget, cur)} source="estimated" note="Within your spending limits" />
          </section>
          <ChartCard title="Recommended daily budget" subtitle="Current vs recommended (estimate)" hasData={data.campaigns.some((c) => c.daily_budget)}>
            <RankBars data={data.campaigns.filter((c) => c.daily_budget).map((c) => ({ label: c.name, value: c.proposed_daily_budget ?? c.daily_budget ?? 0 }))}
              color={COLORS.agent} name="Recommended daily budget" format={(v) => money(v, cur)} />
          </ChartCard>
          <section className="surface-card">
            <p className="step-label">CAMPAIGN BUDGETS</p><h3>Spend, utilization and outcomes</h3>
            <div className="table-wrap"><table>
              <thead><tr><th>Campaign</th><th className="num">Daily budget</th><th className="num">Spend</th><th className="num">Utilization</th>
                <th className="num">Cost / qualified</th><th className="num">Cost / booking</th><th className="num">ROAS</th><th>Decision</th></tr></thead>
              <tbody>{data.campaigns.map((c) => (
                <tr key={c.campaign_id}>
                  <td><strong>{c.name}</strong><small>{c.objective ?? "—"} · {c.budget_level} budget</small></td>
                  <td className="num">{moneyOrDash(c.daily_budget, cur)}{c.proposed_daily_budget !== null && <small>→ {money(c.proposed_daily_budget, cur)}</small>}</td>
                  <td className="num">{money(c.spend, cur)}</td><td className="num">{pctOrDash(c.utilization)}</td>
                  <td className="num">{moneyOrDash(c.cost_per_qualified_lead, cur)}</td><td className="num">{moneyOrDash(c.cost_per_booking, cur)}</td>
                  <td className="num">{ratio(c.roas)}</td>
                  <td><span className={`pill decision-${c.decision}`}>{labelize(c.decision)}</span><small>{c.reason}</small></td>
                </tr>
              ))}</tbody>
            </table></div>
            <ul className="plain-list">{data.notes.map((n, i) => <li key={i}>{n}</li>)}</ul>
          </section>
        </>
      )}
      <form className="surface-card" aria-label="Budget guardrails" onSubmit={(e) => void save(e)}>
        <p className="step-label">GUARDRAILS</p><h3>Spending limits and decision thresholds</h3>
        <div className="form-grid-2">
          <label className="form-field"><span>Daily spend limit</span><input type="number" min="1" value={policy.daily} onChange={(e) => setPolicy({ ...policy, daily: e.target.value })} /></label>
          <label className="form-field"><span>Monthly spend limit</span><input type="number" min="1" value={policy.monthly} onChange={(e) => setPolicy({ ...policy, monthly: e.target.value })} /></label>
          <label className="form-field"><span>Max budget change (%)</span><input type="number" min="1" max="100" value={policy.maxChange} onChange={(e) => setPolicy({ ...policy, maxChange: e.target.value })} /></label>
          <label className="form-field"><span>Min qualified leads to judge</span><input type="number" min="1" value={policy.minQualified} onChange={(e) => setPolicy({ ...policy, minQualified: e.target.value })} /></label>
          <label className="form-field"><span>Target cost per qualified lead</span><input type="number" min="0" value={policy.targetCpql} onChange={(e) => setPolicy({ ...policy, targetCpql: e.target.value })} /></label>
          <label className="form-field"><span>Target ROAS</span><input type="number" min="0" step="0.1" value={policy.targetRoas} onChange={(e) => setPolicy({ ...policy, targetRoas: e.target.value })} /></label>
        </div>
        <div className="campaign-actions"><button type="submit" className="primary-button">Save guardrails</button></div>
      </form>
    </>
  );
}
