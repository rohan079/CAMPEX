import {
  FacebookAuthProvider,
  GoogleAuthProvider,
  getRedirectResult,
  signInWithPopup,
  signInWithRedirect,
  signOut,
  type UserCredential,
} from "firebase/auth";
import { auth } from "./firebase";

export type SocialProvider = "google" | "facebook";

const AUTH_ERROR_KEY = "campex.authError";

const googleProvider = new GoogleAuthProvider();
googleProvider.setCustomParameters({ prompt: "select_account" });

const facebookProvider = new FacebookAuthProvider();
facebookProvider.addScope("email");
facebookProvider.addScope("public_profile");

function providerFor(kind: SocialProvider) {
  return kind === "google" ? googleProvider : facebookProvider;
}

/**
 * Prefer popup (works with COOP same-origin-allow-popups). Fall back to redirect
 * only when the popup path is blocked.
 */
export async function signInWithSocial(kind: SocialProvider): Promise<UserCredential | void> {
  const provider = providerFor(kind);
  try {
    return await signInWithPopup(auth, provider);
  } catch (err) {
    const code = typeof err === "object" && err && "code" in err ? String((err as { code: unknown }).code) : "";
    if (
      code === "auth/popup-blocked"
      || code === "auth/popup-closed-by-user"
      || code === "auth/cancelled-popup-request"
    ) {
      // Closed-by-user should not force redirect; only blocked popups should.
      if (code === "auth/popup-blocked") {
        await signInWithRedirect(auth, provider);
        return;
      }
    }
    // COOP / missing window.closed support often surfaces as an internal error —
    // fall back to redirect so login can still complete.
    if (code === "auth/internal-error" || /cross-origin-opener-policy|window\.closed/i.test(String(err))) {
      await signInWithRedirect(auth, provider);
      return;
    }
    throw err;
  }
}

/** Must run once on startup before React mounts so redirect state is not lost. */
export async function completeSocialRedirect(): Promise<UserCredential | null> {
  try {
    const result = await getRedirectResult(auth);
    if (result?.user) sessionStorage.removeItem(AUTH_ERROR_KEY);
    return result;
  } catch (err) {
    sessionStorage.setItem(AUTH_ERROR_KEY, mapFirebaseAuthError(err));
    throw err;
  }
}

export function consumeStoredAuthError(): string | null {
  const value = sessionStorage.getItem(AUTH_ERROR_KEY);
  if (value) sessionStorage.removeItem(AUTH_ERROR_KEY);
  return value;
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
      return "An account already exists with the same email using a different sign-in method.";
    case "auth/operation-not-allowed":
      return "This sign-in provider is not enabled in Firebase Authentication.";
    case "auth/unauthorized-domain":
      return "This domain is not authorized in Firebase Authentication settings.";
    default:
      return err instanceof Error ? err.message : "Could not complete social sign-in";
  }
}
