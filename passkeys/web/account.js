"use strict";
const status = document.getElementById("status");
const username = document.getElementById("username");
const logout = document.getElementById("logout");
let csrf = null;
async function loadSession() {
  try {
    const response = await fetch("/auth/session",{
      method:"GET",credentials:"same-origin",cache:"no-store"
    });
    if (!response.ok) throw new Error("session");
    const data = await response.json();
    if (typeof data.username !== "string" || typeof data.csrf !== "string") {
      throw new Error("invalid session");
    }
    username.textContent = "Angemeldet als: " + data.username;
    status.textContent = "Deine Passkey-Anmeldung wurde bestätigt.";
    csrf = data.csrf;
    logout.disabled = false;
  } catch {
    status.textContent = "Keine gültige Anmeldung. Bitte melde dich mit deinem Passkey an.";
    username.textContent = "";
    logout.disabled = true;
  }
}
logout.addEventListener("click", async () => {
  if (!csrf) return;
  logout.disabled = true;
  try {
    const response = await fetch("/auth/logout",{
      method:"POST",
      credentials:"same-origin",cache:"no-store",
      headers:{"X-Cal45-CSRF":csrf}
    });
    if (!response.ok) throw new Error("logout");
    csrf = null;
    location.replace("/auth/login");
  } catch {
    status.textContent = "Abmelden fehlgeschlagen. Versuche es erneut.";
    logout.disabled = false;
  }
});
loadSession();
