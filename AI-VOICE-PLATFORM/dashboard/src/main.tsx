import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./App";
import { completeSocialRedirect } from "./firebaseAuth";
import "./styles.css";

async function boot() {
  // Complete Google/Facebook redirect before React mounts so the session is restored.
  try {
    await completeSocialRedirect();
  } catch {
    // Error is stored in sessionStorage for the login card.
  }

  createRoot(document.getElementById("root")!).render(
    <StrictMode>
      <App />
    </StrictMode>,
  );
}

void boot();
