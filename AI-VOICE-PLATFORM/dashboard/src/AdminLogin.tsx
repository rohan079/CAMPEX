import { useState } from "react";
import type { FormEvent } from "react";
import { loginAdmin, loginAdminWithFirebase } from "./api";
import { mapFirebaseAuthError, signInWithSocial, type SocialProvider } from "./firebaseAuth";

export function AdminLogin({ onAuthenticated, initialError }: { onAuthenticated: () => void; initialError?: string | null }) {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState<"password" | SocialProvider | null>(null);
  const [error, setError] = useState<string | null>(initialError ?? null);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy("password");
    setError(null);
    try {
      await loginAdmin(email.trim(), password);
      setPassword("");
      onAuthenticated();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not sign in");
    } finally {
      setBusy(null);
    }
  }

  async function socialSignIn(provider: SocialProvider) {
    setBusy(provider);
    setError(null);
    try {
      const { idToken } = await signInWithSocial(provider);
      await loginAdminWithFirebase(idToken);
      onAuthenticated();
    } catch (err) {
      setError(mapFirebaseAuthError(err));
    } finally {
      setBusy(null);
    }
  }

  return (
    <main className="admin-login-shell">
      <section className="admin-login-card">
        <img className="brand-logo" src="/campex-logo.png" alt="Campex logo" />
        <p className="eyebrow">Secure workspace</p>
        <h1>Sign in to Campex</h1>
        <p className="hint">Use Google, Facebook, or the configured administrator account.</p>

        <div className="social-login-stack">
          <button
            type="button"
            className="social-button google"
            disabled={busy !== null}
            onClick={() => void socialSignIn("google")}
          >
            <span className="social-mark" aria-hidden="true">G</span>
            {busy === "google" ? "Connecting Google…" : "Continue with Google"}
          </button>
          <button
            type="button"
            className="social-button facebook"
            disabled={busy !== null}
            onClick={() => void socialSignIn("facebook")}
          >
            <span className="social-mark" aria-hidden="true">f</span>
            {busy === "facebook" ? "Connecting Facebook…" : "Continue with Facebook"}
          </button>
        </div>

        <div className="login-divider" role="separator"><span>or</span></div>

        <form onSubmit={(event) => void submit(event)}>
          <label className="form-field">
            <span>Email</span>
            <input type="email" autoComplete="username" value={email} onChange={(event) => setEmail(event.target.value)} required />
          </label>
          <label className="form-field">
            <span>Password</span>
            <input type="password" autoComplete="current-password" value={password} onChange={(event) => setPassword(event.target.value)} required />
          </label>
          {error && <p className="global-error" role="alert">{error}</p>}
          <button className="primary-button" type="submit" disabled={busy !== null}>
            {busy === "password" ? "Signing in…" : "Sign in"}
          </button>
        </form>
      </section>
    </main>
  );
}
