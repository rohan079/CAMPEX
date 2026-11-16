import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// The dashboard talks to the Spring Boot lead service, never to the voice service.
const target = process.env.API_PROXY_TARGET || "http://127.0.0.1:8080";

export default defineConfig({
  plugins: [react()],
  envPrefix: ["VITE_", "LEAD_SERVICE_URL"],
  server: {
    host: true,
    port: 5173,
    proxy: {
      "/api": target,
      "/health": target,
    },
  },
});
