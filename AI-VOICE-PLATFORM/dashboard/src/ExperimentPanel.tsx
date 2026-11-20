import { useCallback, useEffect, useState } from "react";
import { completeExperiment, createExperiment, getExperiment, listExperiments, startExperiment } from "./intelligenceApi";
import type { Experiment } from "./intelligenceTypes";
import { labelize } from "./measurementFormat";
import { ErrorBanner, errorText } from "./measurementUi";
import { DataNotes } from "./PerformanceIntelligencePage";

const METRICS = ["ctr", "cpc", "cost_per_lead", "cost_per_qualified_lead", "conversion_rate", "cost_per_site_visit", "cost_per_booking"];
const VARIABLES = ["image", "headline", "primary_text", "cta", "landing_page", "audience"];

export function ExperimentPanel({ clientId }: { clientId: string }) {
  const [items, setItems] = useState<Experiment[]>([]);
  const [selected, setSelected] = useState<Experiment | null>(null);
  const [error, setError] = useState<string | null>(null);
  const empty = { name: "", hypothesis: "", variable: "headline", primary_metric: "cost_per_qualified_lead", min_sample_size: "30",
    control_name: "Control", control_ad: "", test_name: "Test", test_ad: "", end_date: "" };
  const [form, setForm] = useState(empty);

  const load = useCallback(async () => {
    try {
      setItems(await listExperiments(clientId));
    } catch (err) {
      setError(errorText(err, "Could not load experiments"));
    }
  }, [clientId]);
  useEffect(() => { void load(); }, [load]);

  async function run(promise: Promise<Experiment>) {
    try {
      setSelected(await promise);
      await load();
    } catch (err) {
      setError(errorText(err, "That action failed"));
    }
  }

  async function create(event: React.FormEvent) {
    event.preventDefault();
    await run(createExperiment(clientId, {
      name: form.name, hypothesis: form.hypothesis, variable: form.variable, primary_metric: form.primary_metric,
      min_sample_size: Number(form.min_sample_size), end_date: form.end_date || undefined,
      control: { name: form.control_name, ad_id: form.control_ad.trim() }, test: { name: form.test_name, ad_id: form.test_ad.trim() },
    }));
    setForm(empty);
  }

  const set = (k: keyof typeof form, v: string) => setForm({ ...form, [k]: v });
  return (
    <>
      <ErrorBanner error={error} onDismiss={() => setError(null)} />
      <section className="surface-card"><p className="step-label">EXPERIMENTS</p><h3>Active and past experiments</h3>
        {items.length === 0 ? <p className="empty-state">No experiments yet.</p> : (
          <div className="table-wrap"><table>
            <thead><tr><th>Name</th><th>Variable</th><th>Primary metric</th><th>Status</th><th>Outcome</th><th /></tr></thead>
            <tbody>{items.map((e) => (
              <tr key={e.id}><td><strong>{e.name}</strong><small>{e.hypothesis}</small></td><td>{labelize(e.variable)}</td><td>{labelize(e.primary_metric)}</td>
                <td>{labelize(e.status)}</td><td>{e.outcome ? labelize(e.outcome) : "—"}</td>
                <td className="row-actions">
                  <button type="button" className="text-button" onClick={() => void run(getExperiment(e.id))}>Results</button>
                  {e.status === "draft" && <button type="button" className="text-button" onClick={() => void run(startExperiment(e.id))}>Start</button>}
                  {e.status === "running" && <button type="button" className="text-button" onClick={() => void run(completeExperiment(e.id))}>Complete</button>}
                </td></tr>
            ))}</tbody>
          </table></div>
        )}
      </section>
      {selected && <ExperimentResult experiment={selected} />}
      <form className="surface-card" aria-label="New experiment" onSubmit={(e) => void create(e)}>
        <p className="step-label">NEW EXPERIMENT</p><h3>Compare two existing ads</h3>
        <p className="helper-text">Publish both ads through Campaign Studio or Ads Manager first; Campex measures them and never edits live ads.</p>
        <div className="form-grid-2">
          <label className="form-field"><span>Name</span><input required value={form.name} onChange={(e) => set("name", e.target.value)} /></label>
          <label className="form-field"><span>Hypothesis</span><input required value={form.hypothesis} onChange={(e) => set("hypothesis", e.target.value)} /></label>
          <label className="form-field"><span>What changes</span><select value={form.variable} onChange={(e) => set("variable", e.target.value)}>
            {VARIABLES.map((v) => <option key={v} value={v}>{labelize(v)}</option>)}</select></label>
          <label className="form-field"><span>Primary metric</span><select value={form.primary_metric} onChange={(e) => set("primary_metric", e.target.value)}>
            {METRICS.map((m) => <option key={m} value={m}>{labelize(m)}</option>)}</select></label>
          <label className="form-field"><span>Minimum sample per variant <small>conversions, or impressions/clicks for rates</small></span>
            <input type="number" min="1" required value={form.min_sample_size} onChange={(e) => set("min_sample_size", e.target.value)} /></label>
          <label className="form-field"><span>End date</span><input type="date" value={form.end_date} onChange={(e) => set("end_date", e.target.value)} /></label>
          <label className="form-field"><span>Control ad id</span><input required inputMode="numeric" value={form.control_ad} onChange={(e) => set("control_ad", e.target.value)} /></label>
          <label className="form-field"><span>Test ad id</span><input required inputMode="numeric" value={form.test_ad} onChange={(e) => set("test_ad", e.target.value)} /></label>
        </div>
        <div className="campaign-actions"><button type="submit" className="primary-button">Create experiment</button></div>
      </form>
    </>
  );
}

export function ExperimentResult({ experiment }: { experiment: Experiment }) {
  const r = experiment.result as Record<string, unknown> | null;
  const p = typeof r?.p_value === "number" ? r.p_value : null;
  return (
    <section className="surface-card" aria-label="Experiment result">
      <p className="step-label">{r?.interim ? "INTERIM RESULT" : "RESULT"}</p><h3>{experiment.name}</h3>
      <DataNotes notes={experiment.data_notes} />
      {r ? (
        <>
          <p><strong>{labelize(String(r.status))}</strong>{r.winner ? ` — ${String(r.winner)} variant` : ""}. {String(r.explanation ?? "")}</p>
          <dl className="facts">
            <div><dt>Control {labelize(experiment.primary_metric)}</dt><dd>{r.control_value === null ? "—" : Number(r.control_value).toFixed(4)}</dd></div>
            <div><dt>Test {labelize(experiment.primary_metric)}</dt><dd>{r.test_value === null ? "—" : Number(r.test_value).toFixed(4)}</dd></div>
            <div><dt>p-value</dt><dd>{p === null ? "—" : p.toFixed(4)}</dd></div>
            <div><dt>Confidence level</dt><dd>{Math.round(experiment.confidence_level * 100)}%</dd></div>
          </dl>
          <div className="table-wrap"><table>
            <thead><tr><th>Variant</th><th className="num">Spend</th><th className="num">Impressions</th><th className="num">Link clicks</th><th className="num">Leads</th><th className="num">Qualified</th></tr></thead>
            <tbody>{experiment.variants.map((v) => (
              <tr key={v.id}><td><strong>{v.name}</strong><small>{v.role} · ad {v.ad_id ?? v.adset_id}</small></td>
                <td className="num">{v.metrics?.spend ?? "—"}</td><td className="num">{v.metrics?.impressions ?? "—"}</td>
                <td className="num">{v.metrics?.link_clicks ?? "—"}</td><td className="num">{v.metrics?.leads ?? "—"}</td>
                <td className="num">{v.metrics?.qualified_leads ?? "—"}</td></tr>
            ))}</tbody>
          </table></div>
        </>
      ) : <p className="empty-state">No results yet{experiment.status === "draft" ? " — start the experiment first" : ""}.</p>}
      {experiment.result_summary && <p className="helper-text">{experiment.result_summary}</p>}
    </section>
  );
}
