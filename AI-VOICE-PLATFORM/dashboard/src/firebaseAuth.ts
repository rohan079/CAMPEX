import {
  FacebookAuthProvider,
  GoogleAuthProvider,
  getRedirectResult,
  signInWithRedirect,
  signOut,
  type UserCredential,
} from "firebase/auth";
import { auth } from "./firebase";

export type SocialProvider = "google" | "facebook";

const googleProvider = new GoogleAuthProvider();
googleProvider.setCustomParameters({ prompt: "select_account" });

const facebookProvider = new FacebookAuthProvider();
facebookProvider.addScope("email");
facebookProvider.addScope("public_profile");

function providerFor(kind: SocialProvider) {
  return kind === "google" ? googleProvider : facebookProvider;
}

/** Uses full-page redirect so Vercel COOP headers cannot break popup window.closed checks. */
export async function signInWithSocial(kind: SocialProvider): Promise<void> {
  await signInWithRedirect(auth, providerFor(kind));
}

export async function completeSocialRedirect(): Promise<UserCredential | null> {
  return getRedirectResult(auth);
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
