"use strict";
// Initial passkey enrollment for Cal45. Not publicly served until integration.
const form = document.getElementById("enroll-form");
const button = document.getElementById("enroll");
const status = document.getElementById("status");
const userInput = document.getElementById("username");
const secretInput = document.getElementById("setup-token");

function decode64(value) {
  if (typeof value !== "string" || !/^[A-Za-z0-9_-]+$/.test(value)) {
    throw new Error("Invalid encoding");
  }
  const text = atob(value.replace(/-/g, "+").replace(/_/g, "/").padEnd(Math.ceil(value.length / 4) * 4, "="));
  return Uint8Array.from(text, c => c.charCodeAt(0));
}
function encode64(buffer) {
  let text = "";
  for (const b of new Uint8Array(buffer)) text += String.fromCharCode(b);
  return btoa(text).split("+").join("-").split("/").join("_").replace(/=+$/g, "");
}
function parseCreation(raw) {
  if (typeof PublicKeyCredential.parseCreationOptionsFromJSON === "function") {
    return PublicKeyCredential.parseCreationOptionsFromJSON(raw);
  }
  return {
    ...raw,
    challenge: decode64(raw.challenge),
    user: {...raw.user, id: decode64(raw.user.id)},
    excludeCredentials: (raw.excludeCredentials || []).map(item => ({
      ...item, id: decode64(item.id)
    }))
  };
}
function toJSON(credential) {
  if (typeof credential.toJSON === "function") return credential.toJSON();
  const response = credential.response;
  return {
    id: credential.id, rawId: encode64(credential.rawId), type: "public-key",
    response: {
      clientDataJSON: encode64(response.clientDataJSON),
      attestationObject: encode64(response.attestationObject),
      transports: typeof response.getTransports === "function" ? response.getTransports() : []
    },
    clientExtensionResults: credential.getClientExtensionResults(),
    authenticatorAttachment: credential.authenticatorAttachment || undefined
  };
}
form.addEventListener("submit", async event => {
  event.preventDefault();
  if (!window.isSecureContext || !window.PublicKeyCredential) {
    status.textContent = "Du benötigst HTTPS und einen Browser mit Passkey-Unterstützung.";
    return;
  }
  const username = userInput.value.trim();
  const setupToken = secretInput.value.trim();
  if (!username || !setupToken) return;
  button.disabled = true;
  status.textContent = "Einrichtungsschlüssel wird geprüft …";
  try {
    const begin = await fetch("/auth/passkey/enroll/begin", {
      method: "POST", headers: {"Content-Type": "application/json"},
      cache: "no-store", credentials: "same-origin",
      body: JSON.stringify({username, setupToken})
    });
    // Once sent, discard the sensitive setup key from the form.
    secretInput.value = "";
    if (!begin.ok) throw new Error("setup rejected");
    const {ceremony, options} = await begin.json();
    status.textContent = "Bitte deinen neuen Passkey bestätigen …";
    const credential = await navigator.credentials.create({
      publicKey: parseCreation(options.publicKey)
    });
    if (!credential) throw new Error("no credential");
    const finish = await fetch("/auth/passkey/enroll/finish", {
      method: "POST",
      headers: {"Content-Type":"application/json","X-Cal45-Ceremony":ceremony},
      cache:"no-store",credentials:"same-origin",
      body:JSON.stringify(toJSON(credential))
    });
    if (!finish.ok) throw new Error("registration not confirmed");
    status.textContent = "Passkey eingerichtet! Melde dich damit bei Cal45 an.";
  } catch(error) {
    secretInput.value = "";
    status.textContent = error && error.name === "NotAllowedError"
      ? "Passkey-Erstellung abgebrochen. Ein neuer Einrichtungsschlüssel kann nötig sein."
      : "Einrichtung fehlgeschlagen. Bitte prüfe Konto und Schlüssel oder fordere einen neuen an.";
  } finally {
    button.disabled = false;
  }
});
