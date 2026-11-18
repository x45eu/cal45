package passkeys

import (
 "crypto/sha256"
 "encoding/json"
 "net/http"
 "sync"
 "time"
)

// InitialEnrollment is an optional one-time, operator-authorized enrollment
// adapter. It MUST NOT be registered as a public route until deployment has
// TLS, rate limiting and monitoring. It never issues a login session.
type InitialEnrollment struct {
 Service *Service
 Repository *JSONRepository
 Grants BootstrapStore
 Origin string
 Limiter *AttemptLimiter

 mu sync.Mutex
 pending map[[32]byte]enrollmentBinding
 now func()time.Time
}
type enrollmentBinding struct {
 Username string
 Expires time.Time
}
const enrollmentCookie="__Host-cal45_enroll"

// Register opts this flow in explicitly; the current Cal45 preview never calls it.
func(e *InitialEnrollment)Register(mux *http.ServeMux)error{
 if e==nil||e.Service==nil||e.Repository==nil||e.Grants.Path==""||
  e.Grants.AccountIsActive==nil||mux==nil{return ErrUnavailable}
 if _,err:=ValidOrigin(e.Origin);err!=nil{return err}
 if e.Limiter==nil{e.Limiter=NewAttemptLimiter()}
 if e.pending==nil{e.pending=make(map[[32]byte]enrollmentBinding)}
 if e.now==nil{e.now=time.Now}
 mux.HandleFunc("POST /auth/passkey/enroll/begin",e.beginEnrollment)
 mux.HandleFunc("POST /auth/passkey/enroll/finish",e.finishEnrollment)
 return nil
}
func(e *InitialEnrollment)check(r *http.Request)bool {
 if r.Method!="POST"||r.Header.Get("Origin")!=e.Origin{return false}
 if r.Header.Get("Content-Type")!="application/json"{return false}
 if site:=r.Header.Get("Sec-Fetch-Site");site!=""&&site!="same-origin"{return false}
 return true
}
func(e *InitialEnrollment)beginEnrollment(w http.ResponseWriter,r *http.Request){
 if !e.check(r){fail(w,http.StatusForbidden);return}
 if !e.Limiter.Allow(r){rejectBusy(w);return}
 var payload struct{
  Username string `json:"username"`
  SetupToken string `json:"setupToken"`
 }
 if readObject(w,r,&payload)!=nil{fail(w,http.StatusBadRequest);return}
 // No initial bootstrap grant may enroll an additional credential on an
 // already enrolled account. Additional devices need MFA step-up instead.
 existing,err:=e.Repository.GetActive(payload.Username)
 if err==nil&&len(existing.Credentials)>0 {fail(w,http.StatusForbidden);return}
 if !e.Grants.Consume(payload.Username,payload.SetupToken){
  fail(w,http.StatusUnauthorized);return
 }
 if err:=e.Repository.InitializeHandle(payload.Username);err!=nil{
  fail(w,http.StatusServiceUnavailable);return
 }
 binding,err:=randomBinding()
 if err!=nil{fail(w,http.StatusServiceUnavailable);return}
 options,ceremony,err:=e.Service.BeginRegistration(payload.Username,binding)
 if err!=nil{fail(w,http.StatusServiceUnavailable);return}
 key:=sha256.Sum256([]byte(binding))
 e.mu.Lock()
 now:=e.now()
 for k,v:=range e.pending{if !now.Before(v.Expires){delete(e.pending,k)}}
 if len(e.pending)>=1024{
  e.mu.Unlock();fail(w,http.StatusServiceUnavailable);return
 }
 e.pending[key]=enrollmentBinding{Username:payload.Username,Expires:now.Add(120*time.Second)}
 e.mu.Unlock()
 http.SetCookie(w,&http.Cookie{
  Name:enrollmentCookie,Value:binding,Path:"/",Secure:true,HttpOnly:true,
  SameSite:http.SameSiteStrictMode,MaxAge:120,
 })
 w.Header().Set("Cache-Control","no-store")
 w.Header().Set("Content-Type","application/json; charset=utf-8")
 _=json.NewEncoder(w).Encode(struct{
  Ceremony string `json:"ceremony"`
  Options any `json:"options"`
 }{ceremony,options})
}
func(e *InitialEnrollment)finishEnrollment(w http.ResponseWriter,r *http.Request){
 if !e.check(r){fail(w,http.StatusForbidden);return}
 if !e.Limiter.Allow(r){rejectBusy(w);return}
 ceremony:=r.Header.Get("X-Cal45-Ceremony")
 if len(ceremony)==0||len(ceremony)>96{fail(w,http.StatusBadRequest);return}
 cookie,err:=r.Cookie(enrollmentCookie)
 if err!=nil||len(cookie.Value)!=43{fail(w,http.StatusUnauthorized);return}
 // Clear cookie for both success and failure; server-side state is single-use.
 http.SetCookie(w,&http.Cookie{Name:enrollmentCookie,Value:"",Path:"/",
  Secure:true,HttpOnly:true,SameSite:http.SameSiteStrictMode,MaxAge:-1})
 key:=sha256.Sum256([]byte(cookie.Value))
 e.mu.Lock()
 grant,ok:=e.pending[key]
 delete(e.pending,key)
 now:=e.now()
 e.mu.Unlock()
 if !ok||!now.Before(grant.Expires){fail(w,http.StatusUnauthorized);return}
 // The real WebAuthn verification occurs here, including challenge, origin,
 // RP ID, attestation policy and user verification.
 r.Body=http.MaxBytesReader(w,r.Body,64*1024)
 if err:=e.Service.FinishInitialRegistration(grant.Username,cookie.Value,ceremony,r);err!=nil{
  fail(w,http.StatusForbidden);return
 }
 w.Header().Set("Cache-Control","no-store")
 w.Header().Set("Content-Type","application/json; charset=utf-8")
 _,_=w.Write([]byte("{\"registered\":true}"))
 // Never issue a web session on bootstrap. User logs in with new passkey.
}
