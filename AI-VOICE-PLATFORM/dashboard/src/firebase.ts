import { initializeApp, type FirebaseApp } from "firebase/app";
import { getAnalytics, isSupported, type Analytics } from "firebase/analytics";
import {
  browserLocalPersistence,
  browserPopupRedirectResolver,
  getAuth,
  initializeAuth,
  type Auth,
} from "firebase/auth";

import { firebaseConfig, isFirebaseConfigured } from "./firebaseConfig";

if (!isFirebaseConfigured()) {
  throw new Error("Firebase is not configured. Set the VITE_FIREBASE_* environment variables.");
}

const app: FirebaseApp = initializeApp(firebaseConfig);

function createAuth(): Auth {
  try {
    return initializeAuth(app, {
      persistence: browserLocalPersistence,
      popupRedirectResolver: browserPopupRedirectResolver,
    });
  } catch {
    return getAuth(app);
  }
}

export const auth: Auth = createAuth();

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

export { isFirebaseConfigured };
