import { useCallback, useEffect, useState } from "react";
import type { FormEvent } from "react";
import { createClient, getClientAds, registerAd, updateClientMetaApp, updateClientVoiceSettings } from "./api";
import { formatDay, num } from "./format";
import type { Client, RegisteredAd } from "./types";

export function ClientsPage({
  clients,
  clientId,
  onPick,
  onChanged,
}: {
  clients: Client[];
  clientId: string;
  onPick: (id: string) => void;
  onChanged: () => void;
}) {
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [newVoiceEnabled, setNewVoiceEnabled] = useState(false);
  const [newKnowledge, setNewKnowledge] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const selectedClient = clients.find((client) => client.id === clientId);
  const [voiceEnabled, setVoiceEnabled] = useState(false);
  const [knowledgeBase, setKnowledgeBase] = useState("");
  const [settingsBusy, setSettingsBusy] = useState(false);
  const [settingsError, setSettingsError] = useState<string | null>(null);
  const [settingsSaved, setSettingsSaved] = useState(false);
  const [metaAppId, setMetaAppId] = useState("");
  const [metaAppSecret, setMetaAppSecret] = useState("");
  const [metaLoginConfigId, setMetaLoginConfigId] = useState("");
  const [metaBusy, setMetaBusy] = useState(false);
  const [metaError, setMetaError] = useState<string | null>(null);
  const [metaSaved, setMetaSaved] = useState(false);

  useEffect(() => {
    setVoiceEnabled(selectedClient?.voice_agent_enabled ?? false);
    setKnowledgeBase(selectedClient?.knowledge_base ?? "");
    setSettingsSaved(false);
    setMetaAppId(selectedClient?.meta_app_id ?? "");
    setMetaLoginConfigId(selectedClient?.meta_login_config_id ?? "");
    setMetaAppSecret("");
    setMetaSaved(false);
    setMetaError(null);
  }, [selectedClient?.id]);

  async function add(event: FormEvent) {
    event.preventDefault();
    if (!name.trim()) return;
    setBusy(true);
    setError(null);
    try {
      const created = await createClient({
        name: name.trim(),
        description: description.trim() || undefined,
        voice_agent_enabled: newVoiceEnabled,
        knowledge_base: newKnowledge.trim(),
      });
      setName("");
      setDescription("");
      setNewVoiceEnabled(false);
      setNewKnowledge("");
      onChanged();
      onPick(created.id);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not add the client");
    } finally {
      setBusy(false);
    }
  }

  async function saveVoiceSettings() {
    if (!selectedClient) return;
    setSettingsBusy(true);
    setSettingsSaved(false);
    setSettingsError(null);
    try {
      await updateClientVoiceSettings(selectedClient.id, {
        voice_agent_enabled: voiceEnabled,
        knowledge_base: knowledgeBase.trim(),
      });
      setSettingsSaved(true);
      onChanged();
    } catch (err) {
      setSettingsError(err instanceof Error ? err.message : "Could not save client voice settings");
    } finally {
      setSettingsBusy(false);
    }
  }

  async function saveMetaApp() {
    if (!selectedClient) return;
    setMetaBusy(true);
    setMetaSaved(false);
    setMetaError(null);
    try {
      await updateClientMetaApp(selectedClient.id, {
        meta_app_id: metaAppId.trim(),
        meta_app_secret: metaAppSecret.trim() || undefined,
        meta_login_config_id: metaLoginConfigId.trim() || undefined,
      });
      setMetaAppSecret("");
      setMetaSaved(true);
      onChanged();
    } catch (err) {
      setMetaError(err instanceof Error ? err.message : "Could not save Meta app settings");
    } finally {
      setMetaBusy(false);
    }
  }

  async function clearMetaApp() {
    if (!selectedClient) return;
    if (!window.confirm(`Remove Meta app credentials for ${selectedClient.name}?`)) return;
    setMetaBusy(true);
    setMetaError(null);
    try {
      await updateClientMetaApp(selectedClient.id, { clear: true });
      setMetaAppId("");
      setMetaAppSecret("");
      setMetaLoginConfigId("");
      setMetaSaved(true);
      onChanged();
    } catch (err) {
      setMetaError(err instanceof Error ? err.message : "Could not clear Meta app settings");
    } finally {
      setMetaBusy(false);
    }
  }

  return (
    <div className="stack">
      <section className="card table-card">
        <div className="card-head">
          <h3>Clients</h3>
          <p>Each client has its own leads, ads, sales and reach.</p>
        </div>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Client</th>
                <th>Ad popup name</th>
                <th>AI voice</th>
                <th className="num">Leads</th>
                <th>Added</th>
              </tr>
            </thead>
            <tbody>
              {clients.map((client) => (
                <tr
                  key={client.id}
                  className={`click-row${client.id === clientId ? " selected" : ""}`}
                  onClick={() => onPick(client.id)}
                >
                  <td>
                    <strong>{client.name}</strong>
                    {client.description && <small>{client.description}</small>}
                  </td>
                  <td><code>{client.slug}</code></td>
                  <td><span className={`client-voice-state ${client.voice_agent_enabled ? "enabled" : "disabled"}`}>{client.voice_agent_enabled ? "Enabled" : "Ad-only"}</span></td>
                  <td className="num">{num(client.leads)}</td>
                  <td>{client.created_at ? formatDay(client.created_at) : "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <p className="hint">The popup sends the name shown above as <code>client</code>. An ad registered below also finds its client on its own.</p>
      </section>

      <section className="card">
        <div className="card-head">
          <h3>Add a client</h3>
          <p>The description is what the agent says about the business in WhatsApp and on calls.</p>
        </div>
        <form className="form-grid" onSubmit={(event) => void add(event)}>
          <label className="field">
            <span>Name</span>
            <input value={name} onChange={(event) => setName(event.target.value)} maxLength={160} required />
          </label>
          <label className="field grow">
            <span>Description</span>
            <input value={description} onChange={(event) => setDescription(event.target.value)} maxLength={2000} />
          </label>
          <button type="submit" className="primary" disabled={busy}>{busy ? "Adding…" : "Add client"}</button>
        </form>
        <div className="client-voice-config">
          <label className="client-toggle"><input type="checkbox" checked={newVoiceEnabled} onChange={(event) => setNewVoiceEnabled(event.target.checked)} />
            <span><strong>Enable AI voice agent</strong><small>Off by default. Turn on only if this client wants AI phone follow-up.</small></span>
          </label>
          {newVoiceEnabled && <label className="field client-kb-field"><span>Client knowledge base · max 12,000 characters</span>
            <textarea rows={5} maxLength={12000} value={newKnowledge} onChange={(event) => setNewKnowledge(event.target.value)} placeholder={"Add approved facts, one per line.\nProject: Example Gardens\nLocation: Vijayawada\nPrice: enter only verified current pricing\nAvailability: ask the sales team if not listed"} />
          </label>}
        </div>
        {error && <p className="banner" role="alert">{error}</p>}
      </section>

      {selectedClient && <section className="card">
        <div className="card-head">
          <h3>{selectedClient.name}: Meta app</h3>
          <p>
            Save this client’s Meta App ID, App Secret, and Login for Business config id.
            Then select the client and connect under Channels so Campex authorizes that client’s app.
          </p>
        </div>
        <p className="hint">
          {selectedClient.meta_app_configured
            ? `Meta app configured${selectedClient.meta_app_id ? ` · ${selectedClient.meta_app_id}` : ""}.`
            : "No client Meta app saved yet. Without this, Channels uses the platform Meta app."}
        </p>
        <div className="form-grid">
          <label className="field">
            <span>Meta App ID</span>
            <input value={metaAppId} onChange={(event) => { setMetaAppId(event.target.value); setMetaSaved(false); }} maxLength={64} placeholder="Client Meta app id" />
          </label>
          <label className="field">
            <span>Meta App Secret</span>
            <input
              type="password"
              value={metaAppSecret}
              onChange={(event) => { setMetaAppSecret(event.target.value); setMetaSaved(false); }}
              maxLength={256}
              placeholder={selectedClient.meta_app_configured ? "Leave blank to keep current secret" : "App secret"}
              autoComplete="new-password"
            />
          </label>
          <label className="field grow">
            <span>Login config ID</span>
            <input
              value={metaLoginConfigId}
              onChange={(event) => { setMetaLoginConfigId(event.target.value); setMetaSaved(false); }}
              maxLength={128}
              placeholder="Facebook Login for Business configuration id"
            />
          </label>
        </div>
        <p className="hint">
          In the client Meta app, add the same OAuth redirect used by Campex
          (`…/oauth/meta/callback`) and create a Login for Business configuration with ads/pages permissions.
        </p>
        <div className="client-settings-footer">
          <div>
            {metaError && <p className="banner" role="alert">{metaError}</p>}
            {metaSaved && <p className="hint saved">Meta app settings saved.</p>}
            <button type="button" className="primary" disabled={metaBusy} onClick={() => void saveMetaApp()}>
              {metaBusy ? "Saving…" : "Save Meta app"}
            </button>
            {selectedClient.meta_app_configured && (
              <button type="button" className="secondary-button" disabled={metaBusy} onClick={() => void clearMetaApp()}>
                Clear
              </button>
            )}
          </div>
        </div>
      </section>}

      {selectedClient && <section className="card client-voice-settings">
        <div className="card-head"><h3>{selectedClient.name}: voice agent</h3>
          <p>Marketing and lead intake stay available when AI calling is off. Only verified information here is sent to the voice prompt.</p>
        </div>
        <label className="client-toggle"><input type="checkbox" checked={voiceEnabled} onChange={(event) => { setVoiceEnabled(event.target.checked); setSettingsSaved(false); }} />
          <span><strong>Enable AI voice calls for this client</strong><small>{voiceEnabled ? "Eligible, explicitly consented leads may be called." : "Ad-only mode: queued calls are canceled and future AI calls are blocked. An already active call is not forcibly ended."}</small></span>
        </label>
        {voiceEnabled && <label className="field client-kb-field"><span>Client-specific knowledge base · {knowledgeBase.length}/12,000</span>
          <textarea rows={8} maxLength={12000} value={knowledgeBase} onChange={(event) => { setKnowledgeBase(event.target.value); setSettingsSaved(false); }} placeholder={"Use short, verified facts, one per line.\nProject name: ...\nLocation: ...\nApproved price range: ...\nPlot sizes: ...\nPayment plan: ...\nSite-visit process: ...\nIf a fact is missing, the assistant will say it needs confirmation."} />
        </label>}
        <div className="client-settings-footer"><p className="hint">Do not put customer data, credentials, unapproved guarantees, or outdated availability here. Voice calling still requires separate recorded customer consent.</p>
          <div>{settingsError && <p className="banner" role="alert">{settingsError}</p>}{settingsSaved && <p className="hint saved">Voice settings saved.</p>}
            <button type="button" className="primary" disabled={settingsBusy} onClick={() => void saveVoiceSettings()}>{settingsBusy ? "Saving…" : "Save voice settings"}</button>
          </div>
        </div>
      </section>}

      {clientId ? (
        <ClientAds client={clients.find((c) => c.id === clientId)} />
      ) : (
        <p className="hint">Choose a client above to register its ads.</p>
      )}
    </div>
  );
}

function ClientAds({ client }: { client: Client | undefined }) {
  const [ads, setAds] = useState<RegisteredAd[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [externalId, setExternalId] = useState("");
  const [adName, setAdName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const id = client?.id;

  const load = useCallback(async () => {
    if (!id) return;
    try {
      setAds(await getClientAds(id));
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not load the ads");
    } finally {
      setLoaded(true);
    }
  }, [id]);

  useEffect(() => {
    setAds([]);
    setLoaded(false);
    void load();
  }, [load]);

  async function add(event: FormEvent) {
    event.preventDefault();
    if (!id || !externalId.trim()) return;
    setBusy(true);
    setError(null);
    try {
      await registerAd(id, { external_id: externalId.trim(), name: adName.trim() || undefined });
      setExternalId("");
      setAdName("");
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not register the ad");
    } finally {
      setBusy(false);
    }
  }

  if (!client) return null;

  return (
    <section className="card">
      <div className="card-head">
        <h3>{client.name}: ads</h3>
        <p>Register the Meta ad id so its leads, sales and reach are filed under this client.</p>
      </div>
      {!loaded ? (
        <p className="empty">Loading…</p>
      ) : ads.length === 0 ? (
        <p className="empty">No ads registered.</p>
      ) : (
        <ul className="plain-list">
          {ads.map((ad) => (
            <li key={ad.id} className="ad-row">
              <strong>{ad.name || ad.external_id}</strong>
              {ad.name && <small>{ad.external_id}</small>}
            </li>
          ))}
        </ul>
      )}
      <form className="form-grid" onSubmit={(event) => void add(event)}>
        <label className="field">
          <span>Ad id</span>
          <input value={externalId} onChange={(event) => setExternalId(event.target.value)} maxLength={128} required />
        </label>
        <label className="field grow">
          <span>Ad name</span>
          <input value={adName} onChange={(event) => setAdName(event.target.value)} maxLength={200} />
        </label>
        <button type="submit" className="primary" disabled={busy}>{busy ? "Saving…" : "Register ad"}</button>
      </form>
      {error && <p className="banner" role="alert">{error}</p>}
    </section>
  );
}
