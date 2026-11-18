import {
  GoogleAuthProvider,
  createUserWithEmailAndPassword,
  getRedirectResult,
  signInWithEmailAndPassword,
  signInWithPopup,
  signOut,
  type UserCredential,
} from "firebase/auth";
import { auth } from "./firebase";

const AUTH_ERROR_KEY = "campex.authError";
const REDIRECT_PENDING_KEY = "campex.authRedirectPending";

const googleProvider = new GoogleAuthProvider();
googleProvider.setCustomParameters({ prompt: "select_account" });

/**
 * Prefer popup sign-in. Redirect is avoided for production reliability on third-party hosts.
 */
export async function signInWithGoogle(): Promise<UserCredential> {
  sessionStorage.removeItem(REDIRECT_PENDING_KEY);
  try {
    return await signInWithPopup(auth, googleProvider);
  } catch (err) {
    const code = typeof err === "object" && err && "code" in err ? String((err as { code: unknown }).code) : "";
    const message = String(err);
    if (
      code === "auth/internal-error"
      || /cross-origin-opener-policy|window\.closed/i.test(message)
    ) {
      throw new Error(
        "The sign-in window could not finish. Allow pop-ups for this site and try again.",
      );
    }
    throw err;
  }
}

/** Completes any leftover redirect session from older builds. */
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
        "Sign-in could not finish in this browser. Allow pop-ups, or sign in with email.",
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
      return "Pop-up was blocked. Allow pop-ups for this site and try again.";
    case "auth/account-exists-with-different-credential":
      return "An account already exists with this email using a different sign-in method.";
    case "auth/operation-not-allowed":
      return "This sign-in method is not available. Contact support.";
    case "auth/unauthorized-domain":
      return "This site is not authorized for sign-in. Contact support.";
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
      return "Could not complete sign-in. Try again.";
  }
}
