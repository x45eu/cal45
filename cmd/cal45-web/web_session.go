package main

import (
 "crypto/rand"
 "crypto/sha256"
 "crypto/subtle"
 "encoding/base64"
 "net/http"
 "sync"
 "time"
)

// SessionStore provides opaque server-side sessions and CSRF tokens.
// Initial process-local implementation; cannot be shared between replicas.
// Not yet reachable from public routes: passkey and MFA login must be integrated first.
type SessionStore struct{
 mu sync.Mutex
 entries map[[32]byte]webSession
 now func()time.Time
}
type webSession struct{
 Username string
 CSRF [32]byte
 MFA bool
 Expires time.Time
}
func NewSessionStore()*SessionStore{
 return &SessionStore{entries:make(map[[32]byte]webSession),now:time.Now}
}
func(s *SessionStore)New(username string,mfa bool)(token,csrf string,err error){
 raw:=make([]byte,32)
 if _,err=rand.Read(raw);err!=nil{return "","",err}
 var csrfRaw [32]byte
 if _,err=rand.Read(csrfRaw[:]);err!=nil{return "","",err}
 id:=sha256.Sum256(raw)
 s.mu.Lock()
 s.entries[id]=webSession{Username:username,CSRF:csrfRaw,MFA:mfa,Expires:s.now().Add(8*time.Hour)}
 s.mu.Unlock()
 return base64.RawURLEncoding.EncodeToString(raw),base64.RawURLEncoding.EncodeToString(csrfRaw[:]),nil
}
func(s *SessionStore)Get(token string)(webSession,bool){
 raw,err:=base64.RawURLEncoding.DecodeString(token)
 if err!=nil||len(raw)!=32{return webSession{},false}
 id:=sha256.Sum256(raw)
 s.mu.Lock();defer s.mu.Unlock()
 entry,ok:=s.entries[id]
 if !ok{return webSession{},false}
 if !s.now().Before(entry.Expires){delete(s.entries,id);return webSession{},false}
 return entry,true
}
func(s *SessionStore)VerifyCSRF(token,csrf string)bool{
 entry,ok:=s.Get(token);if !ok{return false}
 raw,err:=base64.RawURLEncoding.DecodeString(csrf)
 return err==nil&&len(raw)==32&&subtle.ConstantTimeCompare(raw,entry.CSRF[:])==1
}
func(s *SessionStore)Revoke(token string){
 raw,err:=base64.RawURLEncoding.DecodeString(token);if err!=nil||len(raw)!=32{return}
 id:=sha256.Sum256(raw)
 s.mu.Lock();delete(s.entries,id);s.mu.Unlock()
}
func SessionCookie(token string)*http.Cookie{
 return &http.Cookie{Name:"__Host-cal45_session",Value:token,Path:"/",Secure:true,HttpOnly:true,SameSite:http.SameSiteStrictMode,MaxAge:8*3600}
}
