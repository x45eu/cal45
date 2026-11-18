"use strict";

/**
 * Cal45 native WebAuthn API, no external JavaScript or cloud SDK.
 * Not yet linked from the Cal45 Go web server. Requires HTTPS.
 */
const form = document.getElementById("passkey-form");
const submit = document.getElementById("sign-in");
const status = document.getElementById("status");
const usernameInput = document.getElementById("username");
const urlDecode = value => {
  if (typeof value !== "string" || !/^[A-Za-z0-9_-]+$/.test(value)) {
    throw new Error("Ungültige Passkey-Antwort.");
  }
  const padded = value.replace(/-/g, "+").replace(/_/g, "/");
  const raw = atob(padded.padEnd(Math.ceil(padded.length / 4) * 4, "="));
  return Uint8Array.from(raw, c => c.charCodeAt(0));
};
function parseOptions(raw) {
  if (PublicKeyCredential.parseRequestOptionsFromJSON) {
    return PublicKeyCredential.parseRequestOptionsFromJSON(raw);
  }
  return {
    ...raw,
    challenge: urlDecode(raw.challenge),
    allowCredentials: (raw.allowCredentials || []).map(c => ({...c, id: urlDecode(c.id)}))
  };
}
function encode(buffer) {
  const bytes = new Uint8Array(buffer);
  let text = "";
  for (const value of bytes) text += String.fromCharCode(value);
  return btoa(text).split("+").join("-").split("/").join("_").replace(/=+$/g, "");
}
function assertionJSON(credential) {
  if (typeof credential.toJSON === "function") return credential.toJSON();
  const r = credential.response;
  return {
    id: credential.id,
    rawId: encode(credential.rawId),
    type: "public-key",
    response: {
      clientDataJSON: encode(r.clientDataJSON),
      authenticatorData: encode(r.authenticatorData),
      signature: encode(r.signature),
      userHandle: r.userHandle ? encode(r.userHandle) : null
    },
    clientExtensionResults: credential.getClientExtensionResults(),
    authenticatorAttachment: credential.authenticatorAttachment || undefined
  };
}
form.addEventListener("submit", async event => {
  event.preventDefault();
  if (!window.isSecureContext || !window.PublicKeyCredential) {
    status.textContent = "Passkeys benötigen einen unterstützten Browser und HTTPS.";
    return;
  }
  const username = usernameInput.value.trim();
  if (!username || username.length > 128) return;
  submit.disabled = true;
  status.textContent = "Passkey-Anmeldung wird gestartet …";
  try {
    const first = await fetch("/auth/passkey/login/begin", {
      method: "POST",
      headers: {"Content-Type":"application/json"},
      credentials: "same-origin",
      cache: "no-store",
      body: JSON.stringify({username})
    });
    if (!first.ok) throw new Error("Die Anmeldung konnte nicht gestartet werden.");
    const {ceremony,options} = await first.json();
    const publicKey = parseOptions(options.publicKey);
    status.textContent = "Bitte deinen Passkey bestätigen …";
    const credential = await navigator.credentials.get({publicKey});
    if (!credential) throw new Error("Kein Passkey ausgewählt.");
    const second = await fetch("/auth/passkey/login/finish", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Cal45-Ceremony": ceremony,
        "X-Cal45-Username": username
      },
      credentials:"same-origin",
      cache:"no-store",
      body:JSON.stringify(assertionJSON(credential))
    });
    if (!second.ok) throw new Error("Die Passkey-Anmeldung wurde nicht bestätigt.");
    status.textContent = "Passkey erfolgreich bestätigt.";
    location.assign("/settings");
  } catch(error) {
    // Never expose arbitrary server response bodies or sensitive credentials.
    status.textContent = error && error.name === "NotAllowedError"
      ? "Passkey-Abfrage abgebrochen oder nicht bestätigt."
      : "Anmeldung fehlgeschlagen. Prüfe Passkey, Konto und Server.";
  } finally {
    submit.disabled = false;
  }
});
