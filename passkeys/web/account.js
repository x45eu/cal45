"use strict";
const status = document.getElementById("status");
const username = document.getElementById("username");
const logout = document.getElementById("logout");
const create = document.getElementById("create-device");
const label = document.getElementById("device-label");
const deviceMessage = document.getElementById("device-message");
const deviceList = document.getElementById("device-list");
const issued = document.getElementById("issued-device");
const issuedPassword = document.getElementById("issued-password");
const copyPassword = document.getElementById("copy-password");
let csrf = null;

async function sessionRequest(path,body) {
  if (!csrf) throw new Error("missing-session");
  const response = await fetch(path,{
    method:"POST",credentials:"same-origin",cache:"no-store",
    headers:{"Content-Type":"application/json","X-Cal45-CSRF":csrf},
    body:JSON.stringify(body)
  });
  if (!response.ok) throw new Error(String(response.status));
  return response;
}
async function loadDevices(){
  const result = await fetch("/auth/devices",{credentials:"same-origin",cache:"no-store"});
  if (!result.ok) throw new Error("device-list");
  const items = await result.json();
  if (!Array.isArray(items)) throw new Error("invalid-devices");
  deviceList.replaceChildren();
  for (const item of items) {
    const row = document.createElement("li");
    const title = document.createElement("span");
    title.textContent = item.label + (item.revoked ? " (widerrufen)" : " (aktiv)");
    row.append(title);
    if (!item.revoked){
      const revoke = document.createElement("button");
      revoke.type = "button";
      revoke.textContent = "Widerrufen";
      revoke.addEventListener("click",async ()=>{
        if (!confirm("Zugriff dieses Geräts wirklich widerrufen?")) return;
        revoke.disabled = true;
        try{
          await sessionRequest("/auth/devices/revoke",{id:item.id});
          deviceMessage.textContent="App-Passwort widerrufen.";
          await loadDevices();
        }catch(err){
          revoke.disabled=false;
          deviceMessage.textContent=err.message==="401"
            ? "Bitte erneut mit Passkey anmelden und dann widerrufen."
            : "Widerruf fehlgeschlagen.";
        }
      });
      row.append(revoke);
    }
    deviceList.append(row);
  }
}
async function loadSession(){
  try{
    const response = await fetch("/auth/session",{credentials:"same-origin",cache:"no-store"});
    if (!response.ok) throw new Error("session");
    const data = await response.json();
    if (typeof data.username!=="string"||typeof data.csrf!=="string")throw new Error("session");
    username.textContent = "Angemeldet als: " + data.username;
    status.textContent="Deine Passkey-Anmeldung wurde bestätigt.";
    csrf=data.csrf;
    logout.disabled=false;
    create.disabled=false;
    await loadDevices();
  }catch{
    status.textContent="Keine gültige Anmeldung. Bitte melde dich mit deinem Passkey an.";
    username.textContent="";
    logout.disabled=true;
    create.disabled=true;
  }
}
create.addEventListener("click",async ()=>{
  if (!csrf||!label.value.trim())return;
  create.disabled=true;
  issued.hidden=true;
  issuedPassword.value="";
  deviceMessage.textContent="Gerätepasswort wird erstellt …";
  try{
    const response=await sessionRequest("/auth/devices/create",{label:label.value.trim()});
    const data=await response.json();
    if (typeof data.password!=="string"||!data.password.startsWith("cal45_"))throw new Error("response");
    issuedPassword.value=data.password;
    issued.hidden=false;
    deviceMessage.textContent="Passwort nur einmal sichtbar. Übernimm es jetzt in die Cal45-Sync-App.";
    await loadDevices();
  }catch(err){
    deviceMessage.textContent=err.message==="401"
      ? "Die Anmeldung ist älter als fünf Minuten. Bitte erneut mit Passkey anmelden."
      : "Gerätepasswort konnte nicht erstellt werden.";
  }finally{
    create.disabled=false;
  }
});
copyPassword.addEventListener("click",async ()=>{
  try{
    await navigator.clipboard.writeText(issuedPassword.value);
    deviceMessage.textContent="App-Passwort kopiert. Zwischenablage nach Verwendung leeren.";
  }catch{deviceMessage.textContent="Kopieren nicht möglich. Passwort manuell übernehmen."}
});
logout.addEventListener("click",async ()=>{
  if (!csrf)return;
  logout.disabled=true;
  try{
    const response=await sessionRequest("/auth/logout",{});
    if (response.status!==204)throw new Error("logout");
    csrf=null;
    issuedPassword.value="";
    location.replace("/auth/login");
  }catch{
    status.textContent="Abmelden fehlgeschlagen. Versuche es erneut.";
    logout.disabled=false;
  }
});
loadSession();
