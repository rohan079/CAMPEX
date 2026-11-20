import { useCallback, useEffect, useState } from "react";
import { money, num } from "./format";
import { analyzeCreative, getCreativePerformance } from "./intelligenceApi";
import type { CreativeDraft, CreativeReport, GroupRow } from "./intelligenceTypes";
import { labelize } from "./measurementFormat";
import { ErrorBanner, errorText } from "./measurementUi";
import { DataNotes, moneyOrDash, pctOrDash } from "./PerformanceIntelligencePage";

export function CreativePanel({ clientId, from, to }: { clientId: string; from: string; to: string }) {
  const [data, setData] = useState<CreativeReport | null>(null);
  const [draft, setDraft] = useState<CreativeDraft | null>(null);
  const [busyAd, setBusyAd] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setData(await getCreativePerformance(clientId, { from, to }));
    } catch (err) {
      setError(errorText(err, "Could not load creative performance"));
    }
  }, [clientId, from, to]);
  useEffect(() => { void load(); }, [load]);

  async function analyze(adId: string) {
    setBusyAd(adId);
    try {
      setDraft(await analyzeCreative(clientId, adId, { from, to }));
      await load();
    } catch (err) {
      setError(errorText(err, "Could not generate creative suggestions"));
    } finally {
      setBusyAd(null);
    }
  }

  const cur = data?.currency ?? "INR";
  return (
    <>
      <ErrorBanner error={error} onDismiss={() => setError(null)} />
      {data && (
        <>
          <DataNotes notes={data.data_notes} />
          <p className="helper-text">Ranked by {labelize(data.ranking_metric)} (the strongest outcome metric with enough data). Images are shown, not analysed.</p>
          <section className="creative-grid-cards" aria-label="Creative comparison">
            {data.creatives.map((c) => (
              <article key={c.ad_id} className={`surface-card creative-card${c.rank ? ` rank-${c.rank}` : ""}`}>
                {c.image_url && <img src={c.image_url} alt="" loading="lazy" />}
                <div className="rec-head">{c.rank && <span className={`pill rank-pill-${c.rank}`}>{c.rank === "best" ? "Best performer" : "Lowest performer"}</span>}
                  {c.fatigue.status === "fatigued" && <span className="pill risk-high">Fatigue</span>}
                  {c.fatigue.status === "watch" && <span className="pill risk-medium">CTR falling</span>}</div>
                <h3>{c.headline ?? c.ad_name}</h3>
                {c.primary_text && <p className="clamp">{c.primary_text}</p>}
                <small>{c.cta ? labelize(c.cta) : "No CTA"} · {c.format ?? "format unknown"}</small>
                <dl className="facts">
                  <div><dt>Spend</dt><dd>{money(c.metrics.spend, cur)}</dd></div>
                  <div><dt>CTR</dt><dd>{pctOrDash(c.metrics.ctr)}</dd></div>
                  <div><dt>CPC</dt><dd>{moneyOrDash(c.metrics.cpc, cur)}</dd></div>
                  <div><dt>Leads (Meta)</dt><dd>{num(c.metrics.meta_leads)}</dd></div>
                  <div><dt>Qualified</dt><dd>{num(c.metrics.qualified_leads)}</dd></div>
                  <div><dt>Visits / bookings</dt><dd>{num(c.metrics.site_visits)} / {num(c.metrics.bookings)}</dd></div>
                </dl>
                {c.fatigue.status !== "ok" && <small>{c.fatigue.reason}</small>}
                <button type="button" className="secondary-button" disabled={busyAd !== null} onClick={() => void analyze(c.ad_id)}>
                  {busyAd === c.ad_id ? "Generating…" : "Generate draft variants"}</button>
              </article>
            ))}
            {data.creatives.length === 0 && <p className="empty-state">No ads delivered in this period.</p>}
          </section>
          <GroupTable title="Headline comparison" rows={data.by_headline} currency={cur} />
          <GroupTable title="Call-to-action comparison" rows={data.by_cta} currency={cur} />
        </>
      )}
      {draft && <DraftView draft={draft} />}
      {data && data.drafts.length > 0 && (
        <section className="surface-card"><p className="step-label">SAVED DRAFTS</p><h3>AI creative suggestions (not published)</h3>
          <ul className="plain-list">{data.drafts.map((d) => <li key={d.id}>Ad {d.ad_id}: {d.suggestions.headlines?.[0] ?? "draft"} · {labelize(d.status)}</li>)}</ul>
        </section>
      )}
    </>
  );
}

function GroupTable({ title, rows, currency }: { title: string; rows: GroupRow[]; currency: string }) {
  if (rows.length < 2) return null;
  return (
    <section className="surface-card"><p className="step-label">COMPARISON</p><h3>{title}</h3>
      <div className="table-wrap"><table>
        <thead><tr><th>Value</th><th className="num">Ads</th><th className="num">Spend</th><th className="num">CTR</th><th className="num">Qualified</th><th className="num">Cost / qualified</th></tr></thead>
        <tbody>{rows.map((g) => (
          <tr key={g.value}><td>{g.value}</td><td className="num">{g.ads}</td><td className="num">{money(g.metrics.spend, currency)}</td>
            <td className="num">{pctOrDash(g.metrics.ctr)}</td><td className="num">{num(g.metrics.qualified_leads)}</td>
            <td className="num">{moneyOrDash(g.metrics.cost_per_qualified_lead, currency)}</td></tr>
        ))}</tbody>
      </table></div>
    </section>
  );
}

export function DraftView({ draft }: { draft: CreativeDraft }) {
  const s = draft.suggestions;
  return (
    <section className="surface-card" aria-label="Creative draft">
      <p className="step-label">DRAFT · NOT PUBLISHED</p><h3>Suggested variants for ad {draft.ad_id}</h3>
      <p className="helper-text">{draft.observation}</p>
      {s.unverified_numbers && s.unverified_numbers.length > 0 && (
        <p className="notice warn-notice">Check these figures before use: {s.unverified_numbers.join(", ")}.</p>
      )}
      <h4>Headlines</h4><ol>{s.headlines?.map((h) => <li key={h}>{h}</li>)}</ol>
      <h4>Primary texts</h4><ol>{s.primary_texts?.map((t) => <li key={t}>{t}</li>)}</ol>
      <p><strong>CTA:</strong> {s.cta} · <strong>Audience messaging:</strong> {s.audience_messaging}</p>
      {s.concepts && <><h4>Concepts</h4><ul>{s.concepts.map((c) => <li key={c}>{c}</li>)}</ul></>}
      {s.image_prompts && <><h4>Image prompts</h4><ul>{s.image_prompts.map((p) => <li key={p}><code>{p}</code></li>)}</ul></>}
      <p className="helper-text">Use Campaign Studio to build and publish a variant; then compare it in an experiment.</p>
    </section>
  );
}
