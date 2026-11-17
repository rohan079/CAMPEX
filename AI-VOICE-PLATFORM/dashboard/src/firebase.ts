import { initializeApp, type FirebaseApp } from "firebase/app";
import { getAnalytics, isSupported, type Analytics } from "firebase/analytics";
import { getAuth, type Auth } from "firebase/auth";

const firebaseConfig = {
  apiKey: import.meta.env.VITE_FIREBASE_API_KEY || "AIzaSyC3n37TnL-AQYFoj59eId6k7d9rb_pQp9A",
  authDomain: import.meta.env.VITE_FIREBASE_AUTH_DOMAIN || "campex-f3ada.firebaseapp.com",
  projectId: import.meta.env.VITE_FIREBASE_PROJECT_ID || "campex-f3ada",
  storageBucket: import.meta.env.VITE_FIREBASE_STORAGE_BUCKET || "campex-f3ada.firebasestorage.app",
  messagingSenderId: import.meta.env.VITE_FIREBASE_MESSAGING_SENDER_ID || "476689170017",
  appId: import.meta.env.VITE_FIREBASE_APP_ID || "1:476689170017:web:7fc085a22f591bae6ef420",
  measurementId: import.meta.env.VITE_FIREBASE_MEASUREMENT_ID || "G-NL6TM20PKB",
};

const app: FirebaseApp = initializeApp(firebaseConfig);
export const auth: Auth = getAuth(app);

let analytics: Analytics | null = null;
void isSupported()
  .then((supported) => {
    if (supported) analytics = getAnalytics(app);
  })
  .catch(() => {
    analytics = null;
  });

export function getFirebaseAnalytics(): Analytics | null {
  return analytics;
}

export function isFirebaseConfigured(): boolean {
  return Boolean(firebaseConfig.apiKey && firebaseConfig.projectId && firebaseConfig.appId);
}
