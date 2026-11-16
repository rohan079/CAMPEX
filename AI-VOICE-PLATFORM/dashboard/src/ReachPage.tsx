import { useMemo, useState } from "react";
import { importReach } from "./api";
import { ChartCard, COLORS, OTHER_COLOR, RankBars, SERIES_COLORS, StackedLocationChart } from "./charts";
import type { StackSeries } from "./charts";
import { num, pct } from "./format";
import { parseReachCsv } from "./reachCsv";
import { SectionHead, Stat } from "./ui";
import type { AdLocationReach, ClientAnalytics } from "./types";

const MAX_LOCATIONS = 8;
const MAX_ADS = 5;

function stackData(rows: AdLocationReach[]) {
  const byLocation = new Map<string, number>();
  const byAd = new Map<string, { name: string; total: number }>();
  for (const row of rows) {
    byLocation.set(row.location, (byLocation.get(row.location) ?? 0) + row.reach);
    const current = byAd.get(row.ad_id) ?? { name: row.ad_name, total: 0 };
    current.total += row.reach;
    byAd.set(row.ad_id, current);
  }
  const locations = [...byLocation.entries()].sort((a, b) => b[1] - a[1]).slice(0, MAX_LOCATIONS).map(([name]) => name);
  const topAds = [...byAd.entries()].sort((a, b) => b[1].total - a[1].total).slice(0, MAX_ADS);
  const keyOf = new Map(topAds.map(([id], index) => [id, `ad${index}`]));
  const hasOther = byAd.size > topAds.length;

  const data = locations.map((location) => {
    const point: Record<string, string | number> = { location };
    for (const row of rows) {
      if (row.location !== location) continue;
      const key = keyOf.get(row.ad_id) ?? "other";
      point[key] = ((point[key] as number | undefined) ?? 0) + row.reach;
    }
    return point;
  });
  const series: StackSeries[] = topAds.map(([, ad], index) => ({
    key: `ad${index}`,
    name: ad.name,
    color: SERIES_COLORS[index % SERIES_COLORS.length],
  }));
  if (hasOther) series.push({ key: "other", name: "Other ads", color: OTHER_COLOR });
  return { data, series };
}

export function ReachPage({
  data,
  clientId,
  onChanged,
}: {
  data: ClientAnalytics;
  clientId: string;
  onChanged: () => void;
}) {
  const reach = data.reach;
  const stacked = useMemo(() => stackData(reach.by_ad_location), [reach.by_ad_location]);
  const adsWithReach = reach.by_ad.filter((a) => a.reach > 0);
  const topLocation = reach.by_location[0];

  return (
    <div className="stack">
      <SectionHead
        eyebrow="Section 3"
        text={`How many people each of ${data.client_name}'s ads reached, and where.`}
      />
      <section className="stats" aria-label="Reach summary">
        <Stat label="People reached" value={num(reach.total_reach)} detail="Summed over the days shown" accent="leads" />
        <Stat label="Locations" value={num(reach.by_location.length)} detail="With at least one person reached" />
        <Stat label="Ads with reach" value={num(adsWithReach.length)} detail={`${num(data.summary.leads)} leads in total`} />
        <Stat label="Top location" value={topLocation ? topLocation.location : "—"} detail={topLocation ? `${num(topLocation.reach)} reached` : "No reach data"} />
      </section>

      <section className="split">
        <ChartCard
          title="Reach by location"
          subtitle="All ads together"
          hasData={reach.by_location.length > 0}
          emptyText="No reach data yet. Import it below, from Meta Ads Manager (breakdown by region)."
        >
          <RankBars
            data={reach.by_location.slice(0, 10).map((l) => ({ label: l.location, value: l.reach }))}
            color={COLORS.leads}
            name="People reached"
          />
        </ChartCard>
        <ChartCard
          title="Each ad in each location"
          subtitle={`Top ${MAX_LOCATIONS} locations, one colour per ad`}
          hasData={stacked.data.length > 0}
          emptyText="Import reach by ad and location to see this."
        >
          <StackedLocationChart data={stacked.data} series={stacked.series} />
        </ChartCard>
      </section>

      <section className="card table-card">
        <div className="card-head">
          <h3>Reach per ad</h3>
          <p>Lead rate is leads divided by people reached.</p>
        </div>
        {reach.by_ad.length === 0 ? (
          <p className="empty">No ad has reach data in this period.</p>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Ad</th>
                  <th className="num">Reached</th>
                  <th className="num">Leads</th>
                  <th className="num">Lead rate</th>
                  <th>Where it reached most</th>
                </tr>
              </thead>
              <tbody>
                {reach.by_ad.map((ad) => (
                  <tr key={ad.ad_id}>
                    <td>
                      <strong>{ad.ad_name}</strong>
                      {ad.ad_name !== ad.ad_id && <small>{ad.ad_id}</small>}
                    </td>
                    <td className="num">{num(ad.reach)}</td>
                    <td className="num">{num(ad.leads)}</td>
                    <td className="num">{ad.reach > 0 ? pct(ad.lead_rate) : "—"}</td>
                    <td>
                      <span className="chips">
                        {ad.top_locations.map((l) => (
                          <span key={l.location} className="chip">{l.location} · {num(l.reach)}</span>
                        ))}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <p className="hint">
          Reach counts people once per ad, location and day. Adding days together counts a person again each day they
          were reached, so a total over several days is "daily reach", not unique people.
        </p>
      </section>

      <ReachImport clientId={clientId} clientName={data.client_name} onImported={onChanged} />
    </div>
  );
}

function ReachImport({
  clientId,
  clientName,
  onImported,
}: {
  clientId: string;
  clientName: string;
  onImported: () => void;
}) {
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const parsed = useMemo(() => parseReachCsv(text), [text]);

  async function submit() {
    if (!clientId || parsed.rows.length === 0 || parsed.errors.length > 0) return;
    setBusy(true);
    setError(null);
    setMessage(null);
    try {
      const result = await importReach(clientId, parsed.rows);
      setMessage(`Imported ${result.imported} rows for ${clientName}.`);
      setText("");
      onImported();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not import the reach rows");
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card">
      <div className="card-head">
        <h3>Import reach</h3>
        <p>
          Meta numbers are not pulled automatically. Export the ad report with a region breakdown, then paste the rows
          here. A row for the same ad, location and day replaces the old one.
        </p>
      </div>
      {!clientId ? (
        <p className="empty">Choose one client in the switcher to import reach for them.</p>
      ) : (
        <div className="stack">
          <label className="field">
            <span>Rows for {clientName}</span>
            <textarea
              rows={7}
              value={text}
              onChange={(event) => setText(event.target.value)}
              spellCheck={false}
              placeholder={"ad_id,ad_name,location,reach,impressions,date\n120210000001,Diwali offer,Hyderabad,4200,6100,2026-10-30\n120210000001,Diwali offer,Vijayawada,1800,2500,2026-10-30"}
            />
          </label>
          <p className="hint">Columns: ad id, ad name, location, reach, impressions (may be empty), date as YYYY-MM-DD. Use plain numbers without thousands commas.</p>
          {parsed.errors.length > 0 && (
            <ul className="banner error-list" role="alert">
              {parsed.errors.slice(0, 5).map((e) => <li key={e}>{e}</li>)}
              {parsed.errors.length > 5 && <li>…and {parsed.errors.length - 5} more</li>}
            </ul>
          )}
          {error && <p className="banner" role="alert">{error}</p>}
          {message && <p className="hint saved" role="status">{message}</p>}
          <div>
            <button
              type="button"
              className="primary"
              disabled={busy || parsed.rows.length === 0 || parsed.errors.length > 0}
              onClick={() => void submit()}
            >
              {busy ? "Importing…" : parsed.rows.length > 0 ? `Import ${parsed.rows.length} rows` : "Import"}
            </button>
          </div>
        </div>
      )}
    </section>
  );
}
