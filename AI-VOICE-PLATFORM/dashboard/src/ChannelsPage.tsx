import { useCallback, useEffect, useMemo, useState } from "react";
import { ApiError, disconnectMeta, getMetaConnection } from "./api";
import type { MetaConnectionStatus } from "./api";
import { auth } from "./firebase";

const META_ERROR_MESSAGES: Record<string, string> = {
  login_required: "Sign in again, then reconnect Meta Business.",
  configuration: "Meta connection is not available right now. Contact support if this continues.",
  invalid_state: "The connection session expired. Try connecting again.",
  access_denied: "Authorization was cancelled.",
  provider_error: "Meta could not complete the connection. Try again.",
  missing_code: "Meta did not complete authorization. Try again.",
  permissions: "Required permissions were not granted. Reconnect and approve the requested access.",
  expired_token: "Your Meta connection expired. Reconnect to continue.",
  invalid_response: "Meta returned an unexpected response. Try again.",
};

async function startMetaOAuth() {
  const token = await auth.currentUser?.getIdToken();
  if (!token) throw new Error("Sign in before connecting Meta Business");
  const response = await fetch("/api/oauth/meta/login", {
    headers: {
      Accept: "application/json",
      Authorization: `Bearer ${token}`,
    },
  });
  try {
    const body = await response.json() as { authorization_url?: string };
    if (response.ok && body.authorization_url) {
      window.location.assign(body.authorization_url);
      return;
    }
  } catch {
    // keep default message
  }
  throw new Error("Could not start Meta Business connection. Try again.");
}

type ChannelDef = {
  id: string;
  mark: string;
  name: string;
  need: string;
  tone: string;
};

const CHANNELS: ChannelDef[] = [
  { id: "instagram", mark: "◎", name: "Instagram", need: "Link a professional Instagram account to a Facebook Page in Meta Business Suite.", tone: "instagram" },
  { id: "whatsapp_business", mark: "◔", name: "WhatsApp Business", need: "Messaging channel for customer conversations. Separate from ad placement.", tone: "whatsapp" },
  { id: "google_ads", mark: "G", name: "Google Ads", need: "Connect Google Ads when that connector is enabled for your workspace.", tone: "google" },
  { id: "whatsapp_ads", mark: "↗", name: "Click-to-WhatsApp Ads", need: "Meta ad campaigns that open a WhatsApp conversation.", tone: "whatsapp-ads" },
];

export function ChannelsPage() {
  const [metaConnection, setMetaConnection] = useState<MetaConnectionStatus | null>(null);
  const [metaLoading, setMetaLoading] = useState(true);
  const [metaBusy, setMetaBusy] = useState(false);
  const [metaError, setMetaError] = useState<string | null>(null);
  const [metaNotice, setMetaNotice] = useState<string | null>(null);
  const [metaNoticeError, setMetaNoticeError] = useState(false);

  const refreshMetaConnection = useCallback(async () => {
    setMetaLoading(true);
    setMetaError(null);
    try {
      setMetaConnection(await getMetaConnection());
    } catch (err) {
      setMetaError(err instanceof ApiError ? err.message : "Could not load connection status");
      setMetaConnection(null);
    } finally {
      setMetaLoading(false);
    }
  }, []);

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const result = params.get("meta");
    const reason = params.get("reason");
    if (result === "connected") {
      setMetaNotice("Meta Business connected successfully.");
      setMetaNoticeError(false);
    } else if (result === "error") {
      setMetaNotice(
        (reason && META_ERROR_MESSAGES[reason])
          || "Meta could not complete the connection. Try again.",
      );
      setMetaNoticeError(true);
    }
    if (result) {
      params.delete("meta");
      params.delete("reason");
      const query = params.toString();
      window.history.replaceState({}, "", `${window.location.pathname}${query ? `?${query}` : ""}${window.location.hash}`);
    }
    void refreshMetaConnection();
  }, [refreshMetaConnection]);

  const instagramAccounts = metaConnection?.instagram_accounts ?? [];
  const instagramReady = Boolean(metaConnection?.connected && !metaConnection.expired && instagramAccounts.length > 0);
  const adsReady = Boolean(metaConnection?.connected && !metaConnection.expired && (metaConnection.ad_accounts?.length ?? 0) > 0);

  const connectedCount = useMemo(() => {
    let count = 0;
    if (instagramReady) count += 1;
    if (adsReady) count += 1;
    return count;
  }, [adsReady, instagramReady]);

  function channelStatus(channelId: string): { connected: boolean; label: string } {
    if (channelId === "instagram") {
      return instagramReady
        ? { connected: true, label: `${instagramAccounts.length} account${instagramAccounts.length === 1 ? "" : "s"}` }
        : { connected: false, label: "Not connected" };
    }
    if (channelId === "whatsapp_ads") {
      return adsReady
        ? { connected: true, label: "Ready" }
        : { connected: false, label: "Not connected" };
    }
    return { connected: false, label: "Not connected" };
  }

  async function disconnectFacebook() {
    setMetaBusy(true);
    setMetaError(null);
    try {
      await disconnectMeta();
      setMetaConnection({ connected: false, pages: [], ad_accounts: [], instagram_accounts: [] });
      setMetaNotice("Meta Business disconnected.");
      setMetaNoticeError(false);
    } catch (err) {
      setMetaError(err instanceof Error ? err.message : "Could not disconnect");
    } finally {
      setMetaBusy(false);
    }
  }

  return (
    <div className="page-stack">
      <section className="page-intro">
        <div>
          <p className="eyebrow">Workspace setup</p>
          <h2>Connect your ad channels.</h2>
          <p>Link Meta Business so Campex can work with your pages, Instagram accounts, and ad accounts.</p>
        </div>
        <span className="connector-summary"><i /> {connectedCount} connected</span>
      </section>

      <section className="card meta-connection-panel">
        <div className="card-head">
          <h3>Meta Business</h3>
          <p>Connect once to unlock Pages, Instagram, and ad accounts for campaigns.</p>
        </div>
        {metaNotice && <p className={`meta-notice${metaNoticeError ? " error" : ""}`} role={metaNoticeError ? "alert" : "status"}>{metaNotice}</p>}
        {metaError && <p className="banner" role="alert">{metaError}</p>}
        {metaLoading ? <p className="hint">Checking connection…</p> : metaConnection?.connected ? <>
          <div className="meta-account-summary">
            <span className="channel-mark facebook">f</span>
            <div>
              <strong>{metaConnection.meta_user_name || "Meta account"}</strong>
              <small>
                {metaConnection.expired
                  ? "Access expired — reconnect required"
                  : `Connected${metaConnection.token_expires_at ? ` · renews ${new Date(metaConnection.token_expires_at).toLocaleDateString()}` : ""}`}
              </small>
            </div>
            <div className="meta-actions">
              <button type="button" className="secondary-button" onClick={() => void startMetaOAuth().catch((err) => setMetaError(err instanceof Error ? err.message : "Could not reconnect"))}>Reconnect</button>
              <button type="button" className="secondary-button" disabled={metaBusy} onClick={() => void disconnectFacebook()}>{metaBusy ? "Disconnecting…" : "Disconnect"}</button>
            </div>
          </div>
          <div className="meta-assets-grid">
            <div>
              <h4>Pages ({metaConnection.pages.length})</h4>
              {metaConnection.pages.length
                ? <ul>{metaConnection.pages.map((page) => <li key={page.id}><strong>{page.name}</strong><small>{page.category || "Page"}</small></li>)}</ul>
                : <p className="hint">No Pages available for this connection.</p>}
            </div>
            <div>
              <h4>Instagram ({instagramAccounts.length})</h4>
              {instagramAccounts.length
                ? <ul>{instagramAccounts.map((account) => <li key={account.id}><strong>{account.name || account.username || "Instagram"}</strong><small>{account.username ? `@${account.username}` : "Professional account"}</small></li>)}</ul>
                : <p className="hint">No Instagram accounts linked. Connect one in Meta Business Suite, then reconnect.</p>}
            </div>
            <div>
              <h4>Ad accounts ({metaConnection.ad_accounts.length})</h4>
              {metaConnection.ad_accounts.length
                ? <ul>{metaConnection.ad_accounts.map((account) => <li key={account.id}><strong>{account.name}</strong><small>{account.currency || "Ad account"}</small></li>)}</ul>
                : <p className="hint">No ad accounts available for this connection.</p>}
            </div>
          </div>
        </> : (
          <div className="meta-connect-actions">
            <p className="hint">Your Meta credentials stay secure and are never shown in this browser.</p>
            <button type="button" className="primary-button" onClick={() => void startMetaOAuth().catch((err) => setMetaError(err instanceof Error ? err.message : "Could not connect"))}>Connect Meta Business</button>
          </div>
        )}
      </section>

      <div className="connection-grid">
        {CHANNELS.map((channel) => {
          const status = channelStatus(channel.id);
          return (
            <article className="connection-card" key={channel.id}>
              <div className="connection-card-top">
                <span className={`channel-mark ${channel.tone}`}>{channel.mark}</span>
                <span className={status.connected ? "is-connected" : "not-connected"}>
                  <i /> {status.label}
                </span>
              </div>
              <h3>{channel.name}</h3>
              <p>{channel.need}</p>
              {status.connected ? (
                <button type="button" className="secondary-button" disabled>Connected</button>
              ) : (
                <button type="button" className="secondary-button" disabled>Setup required</button>
              )}
            </article>
          );
        })}
      </div>

      <section className="surface-card setup-checklist">
        <p className="step-label">BEFORE YOU PUBLISH</p>
        <h3>Quick checklist</h3>
        <ol>
          <li>Connect Meta Business with access to the right ad account and Page.</li>
          <li>Link Instagram to that Page when you plan Instagram placements.</li>
          <li>Review creative, targeting, and spend limits before activating ads.</li>
          <li>Confirm consent and policy requirements for each destination.</li>
        </ol>
      </section>
    </div>
  );
}
