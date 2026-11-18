import { useCallback, useEffect, useMemo, useState } from "react";
import { ApiError, disconnectMeta, getMetaConnection } from "./api";
import type { MetaConnectionStatus } from "./api";
import { auth } from "./firebase";

const META_ERROR_MESSAGES: Record<string, string> = {
  login_required: "Sign in with Google, Facebook, or email again, then reconnect Meta.",
  configuration: "Meta OAuth is not fully configured on the server. Check META_APP_ID, META_APP_SECRET, META_OAUTH_REDIRECT_URI, META_TOKEN_ENCRYPTION_KEY, and CAMPEX_FRONTEND_URL.",
  invalid_state: "The Facebook login session expired or was invalid. Click Connect Facebook again.",
  access_denied: "Facebook authorization was cancelled.",
  provider_error: "Facebook rejected the token exchange. Confirm the Meta app redirect URI matches META_OAUTH_REDIRECT_URI exactly.",
  missing_code: "Facebook did not return an authorization code.",
  permissions: "Meta did not grant all requested permissions. Review the requested permissions and reconnect.",
  expired_token: "The Facebook token expired. Reconnect.",
  invalid_response: "Facebook returned an invalid response during connection.",
};

async function startMetaOAuth() {
  const token = await auth.currentUser?.getIdToken();
  if (!token) throw new Error("Sign in before connecting Meta");
  const response = await fetch("/api/oauth/meta/login", {
    headers: {
      Accept: "application/json",
      Authorization: `Bearer ${token}`,
    },
  });
  let detail = response.statusText;
  try {
    const body = await response.json() as { authorization_url?: string; detail?: string };
    if (response.ok && body.authorization_url) {
      window.location.assign(body.authorization_url);
      return;
    }
    if (typeof body.detail === "string") detail = body.detail;
  } catch {
    // keep statusText
  }
  throw new Error(detail || `Could not start Facebook login (${response.status})`);
}

type ChannelDef = {
  id: string;
  mark: string;
  name: string;
  need: string;
  tone: string;
};

const CHANNELS: ChannelDef[] = [
  { id: "instagram", mark: "◎", name: "Instagram", need: "Professional account linked to a Facebook Page plus Instagram Graph permissions (instagram_basic).", tone: "instagram" },
  { id: "whatsapp_business", mark: "◔", name: "WhatsApp Business", need: "WhatsApp Cloud API account, approved templates and messaging opt-in. This is messaging, not ad placement.", tone: "whatsapp" },
  { id: "google_ads", mark: "G", name: "Google Ads", need: "OAuth consent, developer token, customer ID and an approved Google Ads API access level.", tone: "google" },
  { id: "whatsapp_ads", mark: "↗", name: "Click-to-WhatsApp Ads", need: "These are Meta Ads campaigns with a WhatsApp destination; they use Meta Marketing API and an eligible WhatsApp number.", tone: "whatsapp-ads" },
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
      setMetaError(err instanceof ApiError ? err.message : "Could not load Meta connection status");
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
      setMetaNotice("Facebook account connected successfully.");
      setMetaNoticeError(false);
    } else if (result === "error") {
      setMetaNotice(
        (reason && META_ERROR_MESSAGES[reason])
          || `Facebook could not complete the connection${reason ? ` (${reason})` : ""}. Reconnect and try again.`,
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
        ? { connected: true, label: `${instagramAccounts.length} Instagram account${instagramAccounts.length === 1 ? "" : "s"}` }
        : { connected: false, label: "Not connected" };
    }
    if (channelId === "whatsapp_ads") {
      return adsReady
        ? { connected: true, label: "Ready via Meta ad accounts" }
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
      setMetaNotice("Facebook account disconnected.");
      setMetaNoticeError(false);
    } catch (err) {
      setMetaError(err instanceof Error ? err.message : "Could not disconnect Facebook");
    } finally {
      setMetaBusy(false);
    }
  }

  return (
    <div className="page-stack">
      <section className="page-intro">
        <div><p className="eyebrow">Workspace setup</p><h2>Connect Meta Ads.</h2>
          <p>This uses Meta Business Login (separate from Firebase sign-in) to grant Campex access to your Business assets.</p></div>
        <span className="connector-summary"><i /> {connectedCount} connected</span>
      </section>

      <section className="connection-banner">
        <span className="connection-icon">↗</span>
        <div><strong>Two separate logins.</strong><p>Firebase signs you into Campex. Meta Business Login below connects ad accounts, Pages, Instagram, and campaign permissions used by Campaign Studio Publish.</p></div>
      </section>

      <section className="card meta-connection-panel">
        <div className="card-head">
          <h3>Meta Business Login</h3>
          <p>Connect a Facebook Business account to load ad accounts, Facebook Pages, Instagram professional accounts, and campaign permissions.</p>
        </div>
        {metaNotice && <p className={`meta-notice${metaNoticeError ? " error" : ""}`} role={metaNoticeError ? "alert" : "status"}>{metaNotice}</p>}
        {metaError && <p className="banner" role="alert">{metaError}</p>}
        {metaLoading ? <p className="hint">Checking Meta connection…</p> : metaConnection?.connected ? <>
          <div className="meta-account-summary">
            <span className="channel-mark facebook">f</span>
            <div><strong>{metaConnection.meta_user_name || "Facebook account"}</strong>
              <small>{metaConnection.expired ? "Access expired — reconnect required" : `Connected${metaConnection.token_expires_at ? ` · expires ${new Date(metaConnection.token_expires_at).toLocaleDateString()}` : ""}`}</small>
            </div>
            <div className="meta-actions">
              <button type="button" className="secondary-button" onClick={() => void startMetaOAuth().catch((err) => setMetaError(err instanceof Error ? err.message : "Could not start Meta Business Login"))}>Reconnect</button>
              <button type="button" className="secondary-button" disabled={metaBusy} onClick={() => void disconnectFacebook()}>{metaBusy ? "Disconnecting…" : "Disconnect"}</button>
            </div>
          </div>
          <div className="meta-assets-grid">
            <div><h4>Facebook Pages ({metaConnection.pages.length})</h4>
              {metaConnection.pages.length ? <ul>{metaConnection.pages.map((page) => <li key={page.id}><strong>{page.name}</strong><small>{page.category || page.id}</small></li>)}</ul> : <p className="hint">No Pages were returned for the granted permissions.</p>}
            </div>
            <div><h4>Instagram ({instagramAccounts.length})</h4>
              {instagramAccounts.length ? <ul>{instagramAccounts.map((account) => <li key={account.id}><strong>{account.name || account.username || account.id}</strong><small>{account.username ? `@${account.username}` : account.id}{account.page_id ? ` · Page ${account.page_id}` : ""}</small></li>)}</ul> : <p className="hint">No Instagram professional accounts linked to your Pages. Link an IG account in Meta Business Suite, then reconnect.</p>}
            </div>
            <div><h4>Ad accounts ({metaConnection.ad_accounts.length})</h4>
              {metaConnection.ad_accounts.length ? <ul>{metaConnection.ad_accounts.map((account) => <li key={account.id}><strong>{account.name}</strong><small>{account.account_id || account.id}{account.currency ? ` · ${account.currency}` : ""}</small></li>)}</ul> : <p className="hint">No ad accounts were returned for the granted permissions.</p>}
            </div>
          </div>
        </> : <div className="meta-connect-actions">
          <p className="hint">Tokens stay encrypted on the server. This is Meta Business Login for ads assets — not the Firebase Facebook button on the sign-in screen.</p>
          <button type="button" className="primary-button" onClick={() => void startMetaOAuth().catch((err) => setMetaError(err instanceof Error ? err.message : "Could not start Meta Business Login"))}>Connect Meta Business</button>
        </div>}
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
                <button type="button" className="secondary-button" disabled>Connected via Meta</button>
              ) : (
                <button type="button" className="secondary-button" disabled title="Provider OAuth and server-side credentials are not configured">Setup required</button>
              )}
            </article>
          );
        })}
      </div>

      <section className="surface-card setup-checklist">
        <p className="step-label">SAFE PUBLISHING CHECKLIST</p>
        <h3>What must be configured before live publishing</h3>
        <ol>
          <li>OAuth/token storage and account IDs in the backend secret store—not in the React app.</li>
          <li>Instagram professional account linked to a Facebook Page used by your ad account.</li>
          <li>Provider-specific creative, image hosting, ad review, spend limits and status callbacks.</li>
          <li>Consent, audience targeting (location and age), and platform policies reviewed for every campaign destination.</li>
          <li>Connector tests against a test account before enabling “Publish”.</li>
        </ol>
      </section>
    </div>
  );
}
