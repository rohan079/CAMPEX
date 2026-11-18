import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./App";
import { completeSocialRedirect } from "./firebaseAuth";
import "./styles.css";

async function boot() {
  // Complete any leftover social redirect before React mounts.
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
