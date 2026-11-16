/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly LEAD_SERVICE_URL?: string;
  readonly VITE_DASHBOARD_API_KEY?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
