import { useCallback, useEffect, useState } from "react";
import { ChartCard, COLORS, RankBars } from "./charts";
import { formatWhen, num, pct } from "./format";
import {
  createPixel, deletePixel, getAdAccounts, getEvent, getEventSummary, labelize, listEvents, listPixels, rangeForDays,
  sendTestEvent, setAdAccounts, updatePixel,
} from "./measurementApi";
import { ClientRequired, ErrorBanner, KpiCard, errorText } from "./measurementUi";
import type { ConversionEvent, EventPage, EventSummary, Pixel, PixelInput } from "./measurementTypes";

type Tab = "events" | "pixels" | "install" | "settings";

const BROWSER_EVENTS = ["PageView", "ViewContent", "Lead", "Contact", "CompleteRegistration", "Schedule"];
const SERVER_EVENTS = ["Purchase", "QualifiedLead", "SiteVisitCompleted"];
const DELIVERY_STATUSES = ["pending", "retry", "sending", "sent", "failed", "skipped_no_consent", "disabled", "not_applicable"];
/** Public host that serves campex.js (the Spring service). Optional; the snippet shows a placeholder otherwise. */
const TRACKING_HOST = (import.meta.env.VITE_CAMPEX_TRACKING_URL as string | undefined)?.replace(/\/+$/, "") || "https://YOUR-CAMPEX-API-HOST";

export function TrackingPage({ clientId, days }: { clientId?: string; days: number }) {
  const [tab, setTab] = useState<Tab>("events");
  return (
    <ClientRequired clientId={clientId}>
      <div className="page-stack">
        <section className="page-intro">
          <div><p className="eyebrow">Tracking &amp; events</p><h2>Every signal, with its delivery status.</h2>
            <p>Browser events from your sites and CRM outcomes, and what Meta's Conversions API received.</p></div>
          <div className="segmented-tabs" role="tablist" aria-label="Tracking sections">
            {(["events", "pixels", "install", "settings"] as Tab[]).map((value) => (
              <button key={value} type="button" role="tab" aria-selected={tab === value} onClick={() => setTab(value)}>
                {{ events: "Events", pixels: "Pixels", install: "Install", settings: "Ad accounts" }[value]}
              </button>
            ))}
          </div>
        </section>
        {tab === "events" && clientId && <EventMonitor clientId={clientId} days={days} />}
        {tab === "pixels" && clientId && <PixelManager clientId={clientId} />}
        {tab === "install" && clientId && <InstallGuide clientId={clientId} />}
        {tab === "settings" && clientId && <AdAccountSettings clientId={clientId} />}
      </div>
    </ClientRequired>
  );
}

// ------------------------------------------------------------------ events

export function EventMonitor({ clientId, days }: { clientId: string; days: number }) {
  const [summary, setSummary] = useState<EventSummary | null>(null);
  const [page, setPage] = useState<EventPage | null>(null);
  const [filters, setFilters] = useState({ event_name: "", source: "", delivery_status: "", campaign_id: "" });
  const [selected, setSelected] = useState<ConversionEvent | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    const range = rangeForDays(days);
    try {
      const [nextSummary, nextPage] = await Promise.all([
        getEventSummary(clientId, range),
        listEvents(clientId, { ...range, ...filters }),
      ]);
      setSummary(nextSummary);
      setPage(nextPage);
      setError(null);
    } catch (err) {
      setError(errorText(err, "Could not load events"));
    }
  }, [clientId, days, filters]);

  useEffect(() => { void load(); }, [load]);

  async function open(id: string) {
    try {
      setSelected(await getEvent(clientId, id));
    } catch (err) {
      setError(errorText(err, "Could not load the event"));
    }
  }

  return (
    <>
      <ErrorBanner error={error} onDismiss={() => setError(null)} />
      {summary && (
        <>
          {!summary.capi_dispatch_enabled && (
            <p className="notice warn-notice">Conversions API dispatch is switched off on the server (META_CAPI_ENABLED). Events are stored and wait as “pending”.</p>
          )}
          <section className="metric-grid kpi-grid" aria-label="Event delivery">
            <KpiCard label="Events received" value={num(summary.received)} source="campex_tracked" />
            <KpiCard label="Sent to Meta" value={num(summary.sent_to_meta)} source="campex_tracked" note={`${num(summary.pending)} pending`} />
            <KpiCard label="Failed" value={num(summary.failed)} source="campex_tracked" note={`${num(summary.skipped_no_consent)} skipped (no consent)`} />
            <KpiCard label="Deduplicated" value={num(summary.deduplicated)} source="campex_tracked" note="Repeat event ids ignored" />
            <KpiCard label="Delivery success rate" value={summary.delivery_success_rate === null ? "—" : pct(summary.delivery_success_rate)} source="derived" note="Sent ÷ (sent + failed)" />
          </section>
          <section className="insight-grid">
            <ChartCard title="Events by name" subtitle="Last period" hasData={summary.by_event_name.length > 0}>
              <RankBars data={summary.by_event_name.map((row) => ({ label: row.name, value: row.count }))} color={COLORS.leads} name="Events" />
            </ChartCard>
            <ChartCard title="Delivery status" subtitle="Meta Conversions API" hasData={summary.by_delivery_status.length > 0}>
              <RankBars data={summary.by_delivery_status.map((row) => ({ label: labelize(row.name), value: row.count }))} color={COLORS.agent} name="Events" />
            </ChartCard>
          </section>
        </>
      )}

      <section className="surface-card">
        <div className="section-title-row"><div><p className="step-label">EVENT MONITOR</p><h3>Recent events</h3></div>
          <button type="button" className="secondary-button" onClick={() => void load()}>Refresh</button></div>
        <div className="filter-row" role="group" aria-label="Event filters">
          <label className="form-field"><span>Event</span>
            <select aria-label="Event name" value={filters.event_name} onChange={(e) => setFilters({ ...filters, event_name: e.target.value })}>
              <option value="">All events</option>{[...BROWSER_EVENTS, ...SERVER_EVENTS].map((name) => <option key={name}>{name}</option>)}
            </select></label>
          <label className="form-field"><span>Source</span>
            <select aria-label="Source" value={filters.source} onChange={(e) => setFilters({ ...filters, source: e.target.value })}>
              <option value="">All sources</option>
              {["website", "landing_page", "whatsapp", "manual_crm", "backend_api", "meta_webhook"].map((s) => <option key={s} value={s}>{labelize(s)}</option>)}
            </select></label>
          <label className="form-field"><span>Delivery</span>
            <select aria-label="Delivery status" value={filters.delivery_status} onChange={(e) => setFilters({ ...filters, delivery_status: e.target.value })}>
              <option value="">Any status</option>{DELIVERY_STATUSES.map((s) => <option key={s} value={s}>{labelize(s)}</option>)}
            </select></label>
          <label className="form-field"><span>Campaign id</span>
            <input aria-label="Campaign id" value={filters.campaign_id} onChange={(e) => setFilters({ ...filters, campaign_id: e.target.value.trim() })} placeholder="1202…" /></label>
        </div>
        {!page || page.events.length === 0 ? <p className="empty-state">No events match these filters.</p> : (
          <div className="table-wrap"><table>
            <thead><tr><th>Event</th><th>Source</th><th>When</th><th>Campaign</th><th>Consent</th><th>Meta delivery</th></tr></thead>
            <tbody>{page.events.map((event) => (
              <tr key={event.id} className="click-row" onClick={() => void open(event.id)}>
                <td><strong>{event.event_name}</strong><small>{event.event_category === "campex_internal" ? "Campex CRM event" : "Meta standard"}{event.test ? " · test" : ""}</small></td>
                <td>{labelize(event.event_source)}</td>
                <td>{formatWhen(event.event_time)}</td>
                <td>{event.campaign_id ?? "—"}</td>
                <td>{labelize(event.consent_status)}</td>
                <td><span className={`pill delivery-${event.meta_delivery_status}`}>{labelize(event.meta_delivery_status)}</span>
                  {event.duplicate_count > 0 && <small>{event.duplicate_count} duplicate(s)</small>}</td>
              </tr>
            ))}</tbody>
          </table></div>
        )}
        {page && <p className="helper-text">{num(page.total)} events in the last {days} days.</p>}
      </section>

      {selected && (
        <section className="surface-card" aria-label="Event detail">
          <div className="section-title-row"><div><p className="step-label">EVENT</p><h3>{selected.event_name} · {formatWhen(selected.event_time)}</h3></div>
            <button type="button" className="secondary-button" onClick={() => setSelected(null)}>Close</button></div>
          <dl className="facts">
            <div><dt>Sent to Meta as</dt><dd>{selected.meta_event_name ?? "Not sent (internal event)"}</dd></div>
            <div><dt>Identifiers</dt><dd>{selected.identifiers_present.join(", ") || "none"}{selected.fbc_present ? " · fbc" : ""}{selected.fbp_present ? " · fbp" : ""}</dd></div>
            <div><dt>Page</dt><dd className="clip">{selected.event_source_url ?? "—"}</dd></div>
            <div><dt>Last error</dt><dd>{selected.last_error ?? "—"}</dd></div>
          </dl>
          <h4>Delivery attempts</h4>
          {!selected.deliveries?.length ? <p className="empty-state">No delivery attempts yet.</p> : (
            <div className="table-wrap"><table>
              <thead><tr><th>#</th><th>When</th><th>Outcome</th><th>HTTP</th><th>Message</th></tr></thead>
              <tbody>{selected.deliveries.map((d) => (
                <tr key={d.attempt_number}><td>{d.attempt_number}</td><td>{formatWhen(d.attempted_at)}</td><td>{labelize(d.outcome)}</td>
                  <td>{d.http_status ?? "—"}</td><td>{d.error_message ?? (d.events_received !== null ? `${d.events_received} received` : "—")}</td></tr>
              ))}</tbody>
            </table></div>
          )}
        </section>
      )}
    </>
  );
}

// ------------------------------------------------------------------ pixels

const EMPTY_PIXEL: PixelInput = {
  meta_pixel_id: "", name: "", allowed_domains: [], enabled: true,
  enabled_events: [...BROWSER_EVENTS, "Purchase"], forward_custom_events: false, consent_mode: "required",
};

export function PixelManager({ clientId }: { clientId: string }) {
  const [pixels, setPixels] = useState<Pixel[]>([]);
  const [editing, setEditing] = useState<Pixel | null>(null);
  const [form, setForm] = useState<PixelInput>(EMPTY_PIXEL);
  const [domains, setDomains] = useState("");
  const [token, setToken] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    try {
      setPixels(await listPixels(clientId));
    } catch (err) {
      setError(errorText(err, "Could not load pixels"));
    }
  }, [clientId]);

  useEffect(() => { void load(); }, [load]);

  function edit(pixel: Pixel | null) {
    setEditing(pixel);
    setToken("");
    setNote(null);
    if (!pixel) {
      setForm(EMPTY_PIXEL);
      setDomains("");
      return;
    }
    setForm({
      meta_pixel_id: pixel.meta_pixel_id, name: pixel.name, allowed_domains: pixel.allowed_domains, enabled: pixel.enabled,
      enabled_events: pixel.enabled_events, forward_custom_events: pixel.forward_custom_events,
      consent_mode: pixel.consent_mode, test_event_code: pixel.test_event_code ?? "",
    });
    setDomains(pixel.allowed_domains.join(", "));
  }

  function toggleEvent(name: string) {
    setForm((current) => ({
      ...current,
      enabled_events: current.enabled_events.includes(name)
        ? current.enabled_events.filter((value) => value !== name)
        : [...current.enabled_events, name],
    }));
  }

  async function save(event: React.FormEvent) {
    event.preventDefault();
    setSaving(true);
    setError(null);
    const body: PixelInput = {
      ...form,
      allowed_domains: domains.split(/[,\s]+/).map((value) => value.trim()).filter(Boolean),
      test_event_code: form.test_event_code?.trim() || undefined,
      capi_access_token: token.trim() || undefined,
    };
    try {
      if (editing) await updatePixel(clientId, editing.id, body);
      else await createPixel(clientId, body);
      const message = editing ? "Pixel updated." : "Pixel created.";
      edit(null);
      setNote(message);
      await load();
    } catch (err) {
      setError(errorText(err, "Could not save the pixel"));
    } finally {
      setSaving(false);
    }
  }

  async function remove(pixel: Pixel) {
    if (!window.confirm(`Remove pixel ${pixel.name}? Its stored Conversions API token is deleted.`)) return;
    try {
      await deletePixel(clientId, pixel.id);
      await load();
    } catch (err) {
      setError(errorText(err, "Could not remove the pixel"));
    }
  }

  async function test(pixel: Pixel) {
    try {
      const result = await sendTestEvent(clientId, pixel.id);
      setNote(`Test event queued (${labelize(result.delivery_status)}). Check Events Manager → Test events.`);
    } catch (err) {
      setError(errorText(err, "Could not send a test event"));
    }
  }

  return (
    <>
      <ErrorBanner error={error} onDismiss={() => setError(null)} />
      {note && <p className="notice success-notice" role="status">{note}</p>}
      <section className="surface-card">
        <div className="section-title-row"><div><p className="step-label">PIXELS</p><h3>Configured Meta Pixels</h3></div></div>
        {pixels.length === 0 ? <p className="empty-state">No pixel configured yet. Add the Pixel ID from Meta Events Manager below.</p> : (
          <div className="table-wrap"><table>
            <thead><tr><th>Pixel</th><th>Domains</th><th>Consent</th><th>Conversions API</th><th /></tr></thead>
            <tbody>{pixels.map((pixel) => (
              <tr key={pixel.id}>
                <td><strong>{pixel.name}</strong><small>{pixel.meta_pixel_id}{pixel.enabled ? "" : " · disabled"}</small></td>
                <td>{pixel.allowed_domains.join(", ") || "—"}</td>
                <td>{pixel.consent_mode === "required" ? "Required" : "Not required"}</td>
                <td>{pixel.capi_token_configured ? "Token stored" : "No token"}{pixel.test_event_code ? " · test code set" : ""}</td>
                <td className="row-actions">
                  <button type="button" className="text-button" onClick={() => edit(pixel)}>Edit</button>
                  <button type="button" className="text-button" disabled={!pixel.test_event_code || !pixel.capi_token_configured} onClick={() => void test(pixel)}>Send test event</button>
                  <button type="button" className="link-danger" onClick={() => void remove(pixel)}>Remove</button>
                </td>
              </tr>
            ))}</tbody>
          </table></div>
        )}
      </section>

      <form className="surface-card" onSubmit={(e) => void save(e)} aria-label="Pixel configuration">
        <div className="section-title-row"><div><p className="step-label">{editing ? "EDIT PIXEL" : "ADD PIXEL"}</p><h3>{editing ? editing.name : "New Meta Pixel"}</h3></div>
          {editing && <button type="button" className="secondary-button" onClick={() => edit(null)}>Cancel</button>}</div>
        <div className="form-grid-2">
          <label className="form-field"><span>Meta Pixel ID</span>
            <input required inputMode="numeric" pattern="[0-9]{5,32}" value={form.meta_pixel_id} onChange={(e) => setForm({ ...form, meta_pixel_id: e.target.value })} /></label>
          <label className="form-field"><span>Name</span>
            <input required maxLength={160} value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></label>
          <label className="form-field"><span>Website domains <small>comma separated</small></span>
            <input value={domains} onChange={(e) => setDomains(e.target.value)} placeholder="example.com, offers.example.com" /></label>
          <label className="form-field"><span>Test event code <small>Events Manager → Test events</small></span>
            <input value={form.test_event_code ?? ""} onChange={(e) => setForm({ ...form, test_event_code: e.target.value })} /></label>
          <label className="form-field"><span>Conversions API access token <small>write-only</small></span>
            <input type="password" autoComplete="off" value={token} onChange={(e) => setToken(e.target.value)}
              placeholder={editing?.capi_token_configured ? "Stored — leave blank to keep" : "Paste the Events Manager token"} /></label>
          <label className="form-field"><span>Consent</span>
            <select value={form.consent_mode} onChange={(e) => setForm({ ...form, consent_mode: e.target.value as PixelInput["consent_mode"] })}>
              <option value="required">Required before tracking (recommended)</option>
              <option value="not_required">Not required (you hold another lawful basis)</option>
            </select></label>
        </div>
        <fieldset className="event-checks"><legend>Events to accept and forward</legend>
          {[...BROWSER_EVENTS, ...SERVER_EVENTS].map((name) => (
            <label key={name}><input type="checkbox" checked={form.enabled_events.includes(name)} onChange={() => toggleEvent(name)} /> {name}
              {SERVER_EVENTS.includes(name) && <small> (server)</small>}</label>
          ))}
        </fieldset>
        <label className="check-line"><input type="checkbox" checked={form.forward_custom_events} onChange={(e) => setForm({ ...form, forward_custom_events: e.target.checked })} />
          Send Campex CRM events (QualifiedLead, SiteVisitCompleted) to Meta as custom events</label>
        <label className="check-line"><input type="checkbox" checked={form.enabled} onChange={(e) => setForm({ ...form, enabled: e.target.checked })} /> Enabled</label>
        {editing?.capi_token_configured && (
          <label className="check-line"><input type="checkbox" checked={Boolean(form.clear_capi_token)} onChange={(e) => setForm({ ...form, clear_capi_token: e.target.checked })} /> Remove the stored token</label>
        )}
        <div className="campaign-actions"><button type="submit" className="primary-button" disabled={saving}>{saving ? "Saving…" : editing ? "Save pixel" : "Add pixel"}</button></div>
        <p className="helper-text">The token is encrypted on the server and never shown again. Browser events are only accepted from the domains listed here.</p>
      </form>
    </>
  );
}

// ------------------------------------------------------------------ install guide

export function InstallGuide({ clientId }: { clientId: string }) {
  const [pixels, setPixels] = useState<Pixel[]>([]);
  useEffect(() => { void listPixels(clientId).then(setPixels).catch(() => setPixels([])); }, [clientId]);
  const pixelId = pixels[0]?.meta_pixel_id ?? "PIXEL_ID";
  const snippet = `<script src="${TRACKING_HOST}/campex.js"\n        data-client-id="${clientId}"\n        data-pixel-id="${pixelId}" async></script>`;
  return (
    <section className="surface-card install-guide">
      <p className="step-label">INSTALLATION</p><h3>Add Campex tracking to a website</h3>
      <ol>
        <li>Add the website's domain to the pixel (Pixels tab). Other domains are rejected.</li>
        <li>Paste this before <code>&lt;/head&gt;</code> on every page:<pre><code>{snippet}</code></pre></li>
        <li>After your consent banner's choice, call <code>Campex.consent(&#123; analytics_storage, ad_storage, ad_user_data &#125;)</code> (or <code>Campex.grantConsent()</code>). Nothing is stored or sent before that.</li>
        <li>Track conversions, e.g. <code>Campex.track("Lead", &#123; content_name: "Villa" &#125;, &#123; userData: &#123; em, ph &#125; &#125;)</code>. Email and phone are SHA-256 hashed in the browser.</li>
        <li>On lead forms, send <code>Campex.getAttribution()</code> as <code>attribution</code> with the lead so the CRM keeps the campaign and click ids.</li>
        <li>If the Meta Pixel (<code>fbq</code>) is also on the page, Campex passes the same <code>eventID</code> so Meta deduplicates browser and server events.</li>
      </ol>
      <p className="helper-text">Supported browser events: {BROWSER_EVENTS.join(", ")}. Purchases and CRM outcomes are sent server-side only.</p>
    </section>
  );
}

// ------------------------------------------------------------------ ad accounts

export function AdAccountSettings({ clientId }: { clientId: string }) {
  const [value, setValue] = useState("");
  const [note, setNote] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    void getAdAccounts(clientId).then((r) => setValue(r.ad_account_ids.join(", "))).catch((err) => setError(errorText(err, "Could not load ad accounts")));
  }, [clientId]);
  async function save(event: React.FormEvent) {
    event.preventDefault();
    try {
      const ids = value.split(/[,\s]+/).map((v) => v.trim()).filter(Boolean);
      const result = await setAdAccounts(clientId, ids);
      setValue(result.ad_account_ids.join(", "));
      setNote("Saved. Meta-reported spend for these ad accounts now feeds cost and ROAS metrics.");
    } catch (err) {
      setError(errorText(err, "Could not save ad accounts"));
    }
  }
  return (
    <form className="surface-card" onSubmit={(e) => void save(e)}>
      <ErrorBanner error={error} onDismiss={() => setError(null)} />
      <p className="step-label">SPEND ATTRIBUTION</p><h3>Meta ad accounts for this client</h3>
      <label className="form-field"><span>Ad account ids <small>act_123…, comma separated</small></span>
        <input value={value} onChange={(e) => setValue(e.target.value)} placeholder="act_1234567890" /></label>
      <div className="campaign-actions"><button type="submit" className="primary-button">Save ad accounts</button></div>
      {note && <p className="notice success-notice" role="status">{note}</p>}
      <p className="helper-text">Spend and impressions are read live from Meta Insights with your Meta Business connection (Channels). Imported reach is never used as spend.</p>
    </form>
  );
}
