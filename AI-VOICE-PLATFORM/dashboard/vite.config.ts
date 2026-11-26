import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";

const stripApiPrefix = (path: string) => path.replace(/^\/api/, "");

// Public Firebase web-app config, exposed to the browser by exact name only. Any other FIREBASE_* variable in the
// environment (e.g. a server credential) is never bundled.
const FIREBASE_KEYS = [
  "FIREBASE_API_KEY",
  "FIREBASE_AUTH_DOMAIN",
  "FIREBASE_PROJECT_ID",
  "FIREBASE_STORAGE_BUCKET",
  "FIREBASE_MESSAGING_SENDER_ID",
  "FIREBASE_APP_ID",
  "FIREBASE_MEASUREMENT_ID",
  // Public URL of the Spring lead service for large uploads (videos), which cannot pass through the Vercel proxy.
  "CAMPEX_UPLOAD_URL",
] as const;

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  // The dashboard proxy targets Spring, never the FastAPI voice service.
  const target = process.env.API_PROXY_TARGET || env.CLIENT_SERVICE_URL || env.LEAD_SERVICE_URL || "http://127.0.0.1:8080";
  const define = Object.fromEntries(
    FIREBASE_KEYS.map((key) => [`import.meta.env.${key}`, JSON.stringify(process.env[key] ?? env[key] ?? "")]),
  );
  return {
    plugins: [react()],
    define,
    server: {
      host: true,
      port: 5173,
      proxy: {
        "/__/auth": {
          target: "https://campex-f3ada.firebaseapp.com",
          changeOrigin: true,
          secure: true,
        },
        "/api/auth": { target, changeOrigin: true, rewrite: stripApiPrefix },
        "/api/oauth": { target, changeOrigin: true, rewrite: stripApiPrefix },
        "/api/health": { target, changeOrigin: true, rewrite: stripApiPrefix },
        "/api": { target, changeOrigin: true },
        "/health": { target, changeOrigin: true },
      },
    },
  };
});
