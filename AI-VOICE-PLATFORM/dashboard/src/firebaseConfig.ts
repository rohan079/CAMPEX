/**
 * Firebase web configuration comes only from build-time environment variables (FIREBASE_*, injected by name in vite.config.ts), so project
 * identifiers are not committed to source. Locally they live in the git-ignored .env.local; on Vercel they are
 * project environment variables.
 */
export const firebaseConfig = {
  apiKey: import.meta.env.FIREBASE_API_KEY ?? "",
  authDomain: import.meta.env.FIREBASE_AUTH_DOMAIN ?? "",
  projectId: import.meta.env.FIREBASE_PROJECT_ID ?? "",
  storageBucket: import.meta.env.FIREBASE_STORAGE_BUCKET ?? "",
  messagingSenderId: import.meta.env.FIREBASE_MESSAGING_SENDER_ID ?? "",
  appId: import.meta.env.FIREBASE_APP_ID ?? "",
  measurementId: import.meta.env.FIREBASE_MEASUREMENT_ID || undefined,
};

export function isFirebaseConfigured(): boolean {
  return Boolean(firebaseConfig.apiKey && firebaseConfig.authDomain && firebaseConfig.projectId && firebaseConfig.appId);
}
