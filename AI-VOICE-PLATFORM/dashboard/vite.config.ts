import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";

const stripApiPrefix = (path: string) => path.replace(/^\/api/, "");

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  // The dashboard proxy targets Spring, never the FastAPI voice service.
  const target = process.env.API_PROXY_TARGET || env.CLIENT_SERVICE_URL || "http://127.0.0.1:8080";
  return {
    plugins: [react()],
    server: {
      host: true,
      port: 5173,
      proxy: {
        "/api/auth": { target, changeOrigin: true, rewrite: stripApiPrefix },
        "/api/oauth": { target, changeOrigin: true, rewrite: stripApiPrefix },
        "/api/health": { target, changeOrigin: true, rewrite: stripApiPrefix },
        "/api": { target, changeOrigin: true },
        "/health": { target, changeOrigin: true },
      },
    },
  };
});
