import { onAuthStateChanged } from "firebase/auth";
import { useCallback, useEffect, useState } from "react";
import { AdminLogin } from "./AdminLogin";
import { getAnalytics, getClients, getHealth, getLeadReviewStats } from "./api";
import { auth } from "./firebase";
import { consumeStoredAuthError, signOutFirebase } from "./firebaseAuth";
import { AiCampaignAssistant } from "./AiCampaignAssistant";
import { AttributionPage, MeasurementPage } from "./AttributionPage";
import { CrmPipelinePage } from "./CrmPipelinePage";
import { SalesAttributionPage } from "./SalesAttributionPage";
import { SiteVisitsPage } from "./SiteVisitsPage";
import { TrackingPage } from "./TrackingPage";
import { CampaignStudio } from "./CampaignStudio";
import { ChannelsPage } from "./ChannelsPage";
import { ClientsPage } from "./ClientsPage";
import { LeadReviewPage } from "./LeadReviewPage";
import { MetaAdsPage } from "./MetaAdsPage";
import { PerformancePage } from "./PerformancePage";
import { num } from "./format";
import type { Client, ClientAnalytics, Health, LeadReviewStats } from "./types";

type View = "campaigns" | "assistant" | "performance" | "meta-ads" | "lead-review" | "channels" | "clients"
  | "measurement" | "tracking" | "attribution" | "crm" | "site-visits" | "sales";
/** Views whose data depends on the selected period. */
const RANGED_VIEWS: View[] = ["performance", "measurement", "tracking", "attribution", "crm"];
const RANGE_OPTIONS = [7, 30, 90];
const CLIENT_KEY = "campaign-workspace.client";
const DAYS_KEY = "campaign-workspace.days";

const PAGE_META: Record<View, { title: string; eyebrow: string; description: string }> = {
  campaigns: { title: "Campaign studio", eyebrow: "Create and distribute", description: "Plan one creative for every channel." },
  assistant: { title: "AI Campaign Assistant", eyebrow: "Skills and Meta insights", description: "Run marketing skills, analyze live Meta data, and design ads from uploaded images." },
  performance: { title: "Performance", eyebrow: "Measure what matters", description: "Reach, lead quality and outcomes." },
  "meta-ads": { title: "Meta Ads", eyebrow: "Advertising", description: "Accounts, campaigns, ads, and performance from your Meta Business connection." },
  "lead-review": { title: "Lead review", eyebrow: "Keep the pipeline clean", description: "Accept qualified leads or reject them from future follow-up." },
  channels: { title: "Channels", eyebrow: "Workspace setup", description: "Manage platform publishing connections." },
  clients: { title: "Clients", eyebrow: "Workspace setup", description: "Manage the businesses in this workspace." },
  measurement: { title: "Measurement", eyebrow: "Spend to revenue", description: "KPIs with the source of every number." },
  tracking: { title: "Tracking & events", eyebrow: "Pixel and Conversions API", description: "Pixels, website events and Meta delivery." },
  attribution: { title: "Attribution", eyebrow: "Campaign to lead to sale", description: "First-touch and last-touch campaign performance." },
  crm: { title: "CRM pipeline", eyebrow: "Sales operations", description: "Assign, qualify and move leads to a sale." },
  "site-visits": { title: "Site visits", eyebrow: "Sales operations", description: "Schedule and track property visits." },
  sales: { title: "Sales", eyebrow: "Lead to sale", description: "Bookings, payments and confirmed revenue." },
};

function initialDays(): number {
  const value = Number(localStorage.getItem(DAYS_KEY));
  return RANGE_OPTIONS.includes(value) ? value : 30;
}

export function App() {
  const [view, setView] = useState<View>(() => new URLSearchParams(window.location.search).has("meta") ? "channels" : "campaigns");
  const [authState, setAuthState] = useState<"checking" | "authenticated" | "anonymous">("checking");
  const [authError, setAuthError] = useState<string | null>(null);
  const [clients, setClients] = useState<Client[]>([]);
  const [clientId, setClientId] = useState(() => localStorage.getItem(CLIENT_KEY) ?? "");
  const [days, setDays] = useState(initialDays);
  const [analytics, setAnalytics] = useState<ClientAnalytics | null>(null);
  const [reviewStats, setReviewStats] = useState<LeadReviewStats>({ pending: 0, accepted: 0, rejected: 0 });
  const [health, setHealth] = useState<Health | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [refreshKey, setRefreshKey] = useState(0);

  useEffect(() => {
    const stored = consumeStoredAuthError();
    if (stored) setAuthError(stored);
  }, []);

  useEffect(() => {
    let active = true;
    const unsubscribe = onAuthStateChanged(auth, (user) => {
      if (!active) return;
      if (!user) {
        setAuthState("anonymous");
        return;
      }
      // Ensure an ID token exists before the dashboard fires API calls.
      void user.getIdToken()
        .then(() => {
          if (!active) return;
          setAuthError(null);
          setAuthState("authenticated");
        })
        .catch((err) => {
          if (!active) return;
          setAuthError(err instanceof Error ? err.message : "Could not complete sign-in");
          setAuthState("anonymous");
        });
    });
    return () => {
      active = false;
      unsubscribe();
    };
  }, []);

  const currentClient = clients.find((client) => client.id === clientId);
  const scopeName = currentClient?.name ?? "All clients";
  const meta = PAGE_META[view];

  const loadClients = useCallback(async () => {
    try {
      setClients(await getClients());
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not load clients");
    }
  }, []);

  useEffect(() => {
    if (authState === "authenticated") void loadClients();
  }, [authState, loadClients]);

  useEffect(() => {
    if (authState !== "authenticated") return;
    let active = true;
    void getHealth().then((value) => { if (active) setHealth(value); }).catch(() => { if (active) setHealth(null); });
    return () => { active = false; };
  }, [authState, refreshKey]);

  useEffect(() => {
    if (clients.length && clientId && !clients.some((client) => client.id === clientId)) {
      setClientId("");
      localStorage.removeItem(CLIENT_KEY);
    }
  }, [clients, clientId]);

  useEffect(() => {
    if (authState !== "authenticated") return;
    let active = true;
    void getLeadReviewStats(clientId || undefined)
      .then((value) => { if (active) setReviewStats(value); })
      .catch(() => { if (active) setReviewStats({ pending: 0, accepted: 0, rejected: 0 }); });
    return () => { active = false; };
  }, [authState, clientId, refreshKey]);

  useEffect(() => {
    if (authState !== "authenticated" || view !== "performance") return;
    let active = true;
    const pull = async () => {
      setLoading(true);
      try {
        const nextAnalytics = await getAnalytics(clientId || undefined, days);
        if (!active) return;
        setAnalytics(nextAnalytics);
        setError(null);
      } catch (err) {
        if (active) setError(err instanceof Error ? err.message : "Could not load campaign performance");
      } finally {
        if (active) setLoading(false);
      }
    };
    void pull();
    const timer = window.setInterval(() => void pull(), 30000);
    return () => { active = false; window.clearInterval(timer); };
  }, [authState, view, clientId, days, refreshKey]);

  function selectClient(id: string) {
    setClientId(id);
    if (id) localStorage.setItem(CLIENT_KEY, id);
    else localStorage.removeItem(CLIENT_KEY);
  }

  function selectDays(value: number) {
    setDays(value);
    localStorage.setItem(DAYS_KEY, String(value));
  }

  async function signOut() {
    try {
      await signOutFirebase();
      setAuthError(null);
    } catch (err) {
      setAuthError(err instanceof Error ? err.message : "Could not sign out");
    } finally {
      setAuthState("anonymous");
    }
  }

  const refresh = useCallback(() => setRefreshKey((key) => key + 1), []);

  function navItem(target: View, icon: string, label: string, count?: number) {
    return <button type="button" className="side-link" aria-current={view === target ? "page" : undefined} onClick={() => setView(target)}>
      <span className="side-icon" aria-hidden="true">{icon}</span><span>{label}</span>
      {count !== undefined && <span className="side-count">{num(count)}</span>}
    </button>;
  }

  if (authState === "checking") {
    return <main className="admin-login-shell"><p>Checking sign-in…</p></main>;
  }
  if (authState === "anonymous") {
    return <AdminLogin
      initialError={authError}
      onAuthenticated={() => {
        setAuthError(null);
        setAuthState("authenticated");
      }}
    />;
  }

  return (
    <div className="campaign-app">
      <aside className="app-sidebar">
        <a className="workspace-brand" href="#campaigns" onClick={(event) => { event.preventDefault(); setView("campaigns"); }}>
          <img className="brand-logo" src="/campex-logo.png" alt="Campex logo" />
          <span><strong>Campex</strong><small>Growth workspace</small></span>
        </a>
        <div className="workspace-picker"><span className="workspace-avatar">{(currentClient?.name ?? "A").slice(0, 1).toUpperCase()}</span>
          <span><small>WORKSPACE</small><strong>{scopeName}</strong></span><span className="chevron">⌄</span></div>
        <nav className="side-nav" aria-label="Main navigation">
          <p className="side-group-label">WORKSPACE</p>
          {navItem("campaigns", "✦", "Campaign studio")}
          {navItem("assistant", "◎", "AI Assistant")}
          {navItem("performance", "◴", "Performance")}
          {navItem("meta-ads", "◈", "Meta Ads")}
          {navItem("lead-review", "✓", "Lead review", reviewStats.pending || undefined)}
          <p className="side-group-label">MEASUREMENT</p>
          {navItem("measurement", "▤", "KPIs")}
          {navItem("attribution", "⇢", "Attribution")}
          {navItem("tracking", "⌖", "Tracking & events")}
          <p className="side-group-label">SALES</p>
          {navItem("crm", "▥", "CRM pipeline")}
          {navItem("site-visits", "⌂", "Site visits")}
          {navItem("sales", "₹", "Sales")}
          <p className="side-group-label">CONFIGURATION</p>
          {navItem("channels", "⌁", "Channels")}
          {navItem("clients", "▦", "Clients", clients.length || undefined)}
        </nav>
        <div className="sidebar-bottom">
          <div className={`connection-status${health ? " online" : ""}`}><span className="status-orb" />
            <span><strong>{health ? "Workspace online" : "Checking connection"}</strong><small>Campex services</small></span>
          </div>
          <p className="sidebar-footer">Campaigns · reach · lead quality</p>
        </div>
      </aside>

      <main className={`app-main${view === "assistant" ? " assistant-mode" : ""}`}>
        <header className="workspace-topbar">
          <div className="breadcrumbs"><span>Workspace</span><b>/</b><strong>{meta.title}</strong></div>
          <div className="topbar-controls">
            <label className="client-select"><span>Client</span><select value={clientId} onChange={(event) => selectClient(event.target.value)}>
              <option value="">All clients</option>{clients.map((client) => <option key={client.id} value={client.id}>{client.name}</option>)}
            </select></label>
            {RANGED_VIEWS.includes(view) && <div className="range-select" aria-label="Performance range">
              {RANGE_OPTIONS.map((value) => <button key={value} type="button" aria-pressed={days === value} onClick={() => selectDays(value)}>{value}d</button>)}
            </div>}
            <button type="button" className="icon-button" onClick={refresh} aria-label="Refresh data" title="Refresh data">↻</button>
            {view !== "assistant" && <button type="button" className="primary-button top-create" onClick={() => setView("campaigns")}>＋ New campaign</button>}
            <button type="button" className="secondary-button" onClick={() => void signOut()}>Sign out</button>
          </div>
        </header>

        {error && <div className="global-error" role="alert"><span>{error}</span><button type="button" onClick={() => setError(null)}>Dismiss</button></div>}
        {view !== "assistant" && (
          <section className="view-heading"><div><p className="eyebrow">{meta.eyebrow}</p><h1>{meta.title}</h1><p>{meta.description}</p></div>
            {RANGED_VIEWS.includes(view) && <span className="range-caption">{scopeName} · last {days} days</span>}
          </section>
        )}

        {view === "campaigns" && <CampaignStudio client={currentClient} onOpenChannels={() => setView("channels")} />}
        {view === "assistant" && (
          <AiCampaignAssistant
            clientId={clientId || undefined}
            clientName={currentClient?.name}
            onOpenChannels={() => setView("channels")}
          />
        )}
        {view === "performance" && (analytics ? <PerformancePage data={analytics} reviewStats={reviewStats} clientId={clientId} onChanged={refresh} /> : <div className="loading-panel">{loading ? "Loading performance data…" : "Choose a client or refresh to load performance."}</div>)}
        {view === "meta-ads" && <MetaAdsPage onOpenChannels={() => setView("channels")} />}
        {view === "lead-review" && <LeadReviewPage clientId={clientId || undefined} />}
        {view === "measurement" && <MeasurementPage clientId={clientId || undefined} days={days} />}
        {view === "tracking" && <TrackingPage clientId={clientId || undefined} days={days} />}
        {view === "attribution" && <AttributionPage clientId={clientId || undefined} days={days} />}
        {view === "crm" && <CrmPipelinePage clientId={clientId || undefined} days={days} />}
        {view === "site-visits" && <SiteVisitsPage clientId={clientId || undefined} />}
        {view === "sales" && <SalesAttributionPage clientId={clientId || undefined} currency={analytics?.currency ?? "INR"} />}
        {view === "channels" && (
          <ChannelsPage
            clientId={clientId || undefined}
            clientName={currentClient?.name}
            clientMetaConfigured={Boolean(currentClient?.meta_app_configured)}
          />
        )}
        {view === "clients" && <ClientsPage clients={clients} clientId={clientId} onPick={selectClient} onChanged={() => { void loadClients(); refresh(); }} />}
      </main>
    </div>
  );
}
