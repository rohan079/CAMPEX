import { useState } from "react";
import type { FormEvent } from "react";
import {
  mapFirebaseAuthError,
  registerWithEmailPassword,
  signInWithEmailPassword,
  signInWithSocial,
  type SocialProvider,
} from "./firebaseAuth";

export function AdminLogin({
  onAuthenticated,
  initialError,
}: {
  onAuthenticated: () => void;
  initialError?: string | null;
}) {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [mode, setMode] = useState<"signin" | "register">("signin");
  const [busy, setBusy] = useState<"password" | SocialProvider | null>(null);
  const [error, setError] = useState<string | null>(initialError ?? null);

  async function socialSignIn(provider: SocialProvider) {
    setBusy(provider);
    setError(null);
    try {
      const result = await signInWithSocial(provider);
      if (result.user) onAuthenticated();
    } catch (err) {
      setError(mapFirebaseAuthError(err));
    } finally {
      setBusy(null);
    }
  }

  async function submitPassword(event: FormEvent) {
    event.preventDefault();
    setBusy("password");
    setError(null);
    try {
      if (mode === "register") {
        await registerWithEmailPassword(email, password);
      } else {
        await signInWithEmailPassword(email, password);
      }
      setPassword("");
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
        <p className="eyebrow">Campex authentication</p>
        <h1>Sign in to Campex</h1>
        <p className="hint">Firebase user login: Google, Facebook, or email and password. Meta Ads accounts are connected later under Channels.</p>

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

        <form onSubmit={(event) => void submitPassword(event)}>
          <label className="form-field">
            <span>Email</span>
            <input
              type="email"
              autoComplete="username"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              required
            />
          </label>
          <label className="form-field">
            <span>Password</span>
            <input
              type="password"
              autoComplete={mode === "register" ? "new-password" : "current-password"}
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              minLength={6}
              required
            />
          </label>
          {error && <p className="global-error" role="alert">{error}</p>}
          <button className="primary-button" type="submit" disabled={busy !== null}>
            {busy === "password"
              ? (mode === "register" ? "Creating account…" : "Signing in…")
              : (mode === "register" ? "Create account" : "Sign in with email")}
          </button>
        </form>

        <button
          type="button"
          className="text-button login-mode-switch"
          disabled={busy !== null}
          onClick={() => {
            setMode((current) => current === "signin" ? "register" : "signin");
            setError(null);
          }}
        >
          {mode === "signin" ? "Need an account? Create one" : "Already have an account? Sign in"}
        </button>
      </section>
    </main>
  );
}
