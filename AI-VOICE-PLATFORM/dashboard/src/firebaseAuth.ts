import {
  FacebookAuthProvider,
  GoogleAuthProvider,
  createUserWithEmailAndPassword,
  getRedirectResult,
  signInWithEmailAndPassword,
  signInWithPopup,
  signOut,
  type UserCredential,
} from "firebase/auth";
import { auth } from "./firebase";

export type SocialProvider = "google" | "facebook";

const AUTH_ERROR_KEY = "campex.authError";
const REDIRECT_PENDING_KEY = "campex.authRedirectPending";

const googleProvider = new GoogleAuthProvider();
googleProvider.setCustomParameters({ prompt: "select_account" });

// Campex user auth only — consumer Facebook Login via Firebase (not Meta Business Login).
// Enable `email` under Meta Use cases → Authenticate and request data with Facebook Login.
// Ads / Pages / Instagram use Channels → Connect Meta Business (server META_LOGIN_CONFIG_ID).
const facebookProvider = new FacebookAuthProvider();
facebookProvider.addScope("email");
facebookProvider.setCustomParameters({ display: "popup" });

function providerFor(kind: SocialProvider) {
  return kind === "google" ? googleProvider : facebookProvider;
}

/**
 * Use popup only. Full-page redirect fails on Chrome/Safari/Firefox when the app
 * is hosted outside Firebase Hosting (e.g. Vercel) because third-party storage
 * for *.firebaseapp.com is blocked — users return unsigned-in with no error.
 */
export async function signInWithSocial(kind: SocialProvider): Promise<UserCredential> {
  sessionStorage.removeItem(REDIRECT_PENDING_KEY);
  try {
    return await signInWithPopup(auth, providerFor(kind));
  } catch (err) {
    const code = typeof err === "object" && err && "code" in err ? String((err as { code: unknown }).code) : "";
    const message = String(err);
    if (
      code === "auth/internal-error"
      || /cross-origin-opener-policy|window\.closed/i.test(message)
    ) {
      throw new Error(
        "The sign-in pop-up could not finish. Allow pop-ups for this site, disable blockers for Campex, and try again.",
      );
    }
    throw err;
  }
}

/** Completes any leftover redirect session from older builds; prefer popup going forward. */
export async function completeSocialRedirect(): Promise<UserCredential | null> {
  const pending = sessionStorage.getItem(REDIRECT_PENDING_KEY);
  try {
    await auth.authStateReady();
    const result = await getRedirectResult(auth);
    sessionStorage.removeItem(REDIRECT_PENDING_KEY);
    if (result?.user) {
      sessionStorage.removeItem(AUTH_ERROR_KEY);
      return result;
    }
    if (pending) {
      sessionStorage.setItem(
        AUTH_ERROR_KEY,
        "Social sign-in redirect could not finish in this browser. Use the pop-up buttons (allow pop-ups) or sign in with email.",
      );
    }
    return null;
  } catch (err) {
    sessionStorage.removeItem(REDIRECT_PENDING_KEY);
    sessionStorage.setItem(AUTH_ERROR_KEY, mapFirebaseAuthError(err));
    throw err;
  }
}

export function consumeStoredAuthError(): string | null {
  const value = sessionStorage.getItem(AUTH_ERROR_KEY);
  if (value) sessionStorage.removeItem(AUTH_ERROR_KEY);
  return value;
}

export async function signInWithEmailPassword(email: string, password: string): Promise<UserCredential> {
  return signInWithEmailAndPassword(auth, email.trim(), password);
}

export async function registerWithEmailPassword(email: string, password: string): Promise<UserCredential> {
  return createUserWithEmailAndPassword(auth, email.trim(), password);
}

export async function signOutFirebase(): Promise<void> {
  if (!auth.currentUser) return;
  await signOut(auth);
}

export function mapFirebaseAuthError(err: unknown): string {
  const code = typeof err === "object" && err && "code" in err ? String((err as { code: unknown }).code) : "";
  switch (code) {
    case "auth/popup-closed-by-user":
    case "auth/cancelled-popup-request":
      return "Sign-in was cancelled.";
    case "auth/popup-blocked":
      return "Pop-up was blocked. Allow pop-ups for this site and try Facebook or Google again.";
    case "auth/account-exists-with-different-credential":
      return "An account already exists with the same email using a different sign-in method. Sign in with Google or email first.";
    case "auth/operation-not-allowed":
      return "This sign-in provider is not enabled in Firebase Authentication.";
    case "auth/unauthorized-domain":
      return "This domain is not authorized in Firebase Authentication settings.";
    case "auth/invalid-email":
      return "Enter a valid email address.";
    case "auth/user-disabled":
      return "This account has been disabled.";
    case "auth/user-not-found":
    case "auth/wrong-password":
    case "auth/invalid-credential":
      return "Invalid email or password.";
    case "auth/email-already-in-use":
      return "An account already exists with this email. Sign in instead.";
    case "auth/weak-password":
      return "Use a password with at least 6 characters.";
    case "auth/too-many-requests":
      return "Too many attempts. Wait a moment and try again.";
    case "auth/network-request-failed":
      return "Network error during sign-in. Check your connection and try again.";
    default:
      return err instanceof Error ? err.message : "Could not complete sign-in";
  }
}
