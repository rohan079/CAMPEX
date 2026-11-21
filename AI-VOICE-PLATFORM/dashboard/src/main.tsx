import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { isFirebaseConfigured } from "./firebaseConfig";
import "./styles.css";

function SetupRequired() {
  return (
    <main className="setup-required">
      <section className="surface-card">
        <p className="eyebrow">Configuration needed</p>
        <h1>Campex can't start yet</h1>
        <p>Sign-in is not configured for this deployment. An administrator needs to set the
          <code> FIREBASE_* </code> environment variables and redeploy.</p>
      </section>
    </main>
  );
}

async function boot() {
  const root = createRoot(document.getElementById("root")!);
  if (!isFirebaseConfigured()) {
    root.render(<SetupRequired />);
    return;
  }

  const [{ App }, { completeSocialRedirect }] = await Promise.all([import("./App"), import("./firebaseAuth")]);
  // Complete any leftover social redirect before React mounts.
  try {
    await completeSocialRedirect();
  } catch {
    // Error is stored in sessionStorage for the login card.
  }

  root.render(
    <StrictMode>
      <App />
    </StrictMode>,
  );
}

void boot();
