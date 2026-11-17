import { useState } from "react";
import { mapFirebaseAuthError, signInWithSocial, type SocialProvider } from "./firebaseAuth";

export function AdminLogin({ initialError }: { initialError?: string | null }) {
  const [busy, setBusy] = useState<SocialProvider | null>(null);
  const [error, setError] = useState<string | null>(initialError ?? null);

  async function socialSignIn(provider: SocialProvider) {
    setBusy(provider);
    setError(null);
    try {
      // Navigates away to Google/Facebook, then back to this app.
      await signInWithSocial(provider);
    } catch (err) {
      setError(mapFirebaseAuthError(err));
      setBusy(null);
    }
  }

  return (
    <main className="admin-login-shell">
      <section className="admin-login-card">
        <img className="brand-logo" src="/campex-logo.png" alt="Campex logo" />
        <p className="eyebrow">Secure workspace</p>
        <h1>Sign in to Campex</h1>
        <p className="hint">Continue with Google or Facebook to open the workspace.</p>

        <div className="social-login-stack">
          <button
            type="button"
            className="social-button google"
            disabled={busy !== null}
            onClick={() => void socialSignIn("google")}
          >
            <span className="social-mark" aria-hidden="true">G</span>
            {busy === "google" ? "Redirecting to Google…" : "Continue with Google"}
          </button>
          <button
            type="button"
            className="social-button facebook"
            disabled={busy !== null}
            onClick={() => void socialSignIn("facebook")}
          >
            <span className="social-mark" aria-hidden="true">f</span>
            {busy === "facebook" ? "Redirecting to Facebook…" : "Continue with Facebook"}
          </button>
        </div>

        {error && <p className="global-error" role="alert">{error}</p>}
      </section>
    </main>
  );
}
