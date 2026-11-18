package passkeys

import (
 "crypto/rand"
 "encoding/base64"
 "encoding/json"
 "errors"
 "io"
 "net/http"
 "strings"
)

// LoginSessionIssuer is called ONLY after successful WebAuthn assertion
// verification. It must apply account/MFA policy and create a secure session.
// No route is registered if this callback is absent.
type LoginSessionIssuer func(http.ResponseWriter,*http.Request,string)error

// RegistrationAuthorizer must require a fully authenticated, recently
// reverified web session and return its unforgeable server-issued binding.
// A merely enabled account is NOT sufficient authorization.
type RegistrationAuthorizer func(*http.Request)(username,sessionBinding string,err error)

// Endpoint is the opt-in browser adapter; it is NOT automatically installed
// into the Cal45 preview and is NOT a replacement for secure onboarding.
type Endpoint struct{
 Service *Service
 Origin string
 IssueSession LoginSessionIssuer
 AuthorizeRegistration RegistrationAuthorizer
 Limiter *AttemptLimiter
}

// Register explicitly opt-ins handlers to the given mux. The invoking server
// must supply TLS behind a trusted proxy, rate limiting, and an issuer that
// enforces full authentication policy. Registration is intentionally omitted
// until operator bootstrap and reauthentication are implemented.
func(e *Endpoint)Register(mux *http.ServeMux)error{
 if e==nil||e.Service==nil||e.IssueSession==nil||mux==nil{return ErrUnavailable}
 if _,err:=ValidOrigin(e.Origin);err!=nil{return err}
 if e.Limiter==nil {e.Limiter=NewAttemptLimiter()}
 mux.HandleFunc("POST /auth/passkey/login/begin",e.begin)
 mux.HandleFunc("POST /auth/passkey/login/finish",e.finish)
 // Registration routes can only be installed when a strict step-up
 // authorization provider is supplied by the embedding application.
 if e.AuthorizeRegistration!=nil {
  mux.HandleFunc("POST /auth/passkey/register/begin",e.registerBegin)
  mux.HandleFunc("POST /auth/passkey/register/finish",e.registerFinish)
 }
 return nil
}
const loginCookie="__Host-cal45_passkey_challenge"
func fail(w http.ResponseWriter,code int){
 w.Header().Set("Cache-Control","no-store")
 http.Error(w,http.StatusText(code),code)
}
func(e *Endpoint)checkRequest(r *http.Request)bool{
 // Strict browser Origin validation; a request without Origin fails closed.
 // This also prevents CSRF on the unauthenticated login ceremony.
 if r.Method!="POST"||r.Header.Get("Origin")!=e.Origin {return false}
 if site:=r.Header.Get("Sec-Fetch-Site");site!=""&&site!="same-origin"{return false}
 if content:=r.Header.Get("Content-Type");!strings.HasPrefix(content,"application/json"){return false}
 return true
}
func readObject(w http.ResponseWriter,r *http.Request,out any)error{
 r.Body=http.MaxBytesReader(w,r.Body,64*1024)
 d:=json.NewDecoder(r.Body)
 d.DisallowUnknownFields()
 if err:=d.Decode(out);err!=nil{return err}
 var second json.RawMessage
 if err:=d.Decode(&second);!errors.Is(err,io.EOF){return errors.New("trailing payload")}
 return nil
}
func randomBinding()(string,error){
 b:=make([]byte,32)
 if _,err:=rand.Read(b);err!=nil{return "",err}
 return base64.RawURLEncoding.EncodeToString(b),nil
}
func(e *Endpoint)begin(w http.ResponseWriter,r *http.Request){
 if !e.checkRequest(r){fail(w,http.StatusForbidden);return}
 if !e.Limiter.Allow(r){rejectBusy(w);return}
 var request struct{Username string `json:"username"`}
 if readObject(w,r,&request)!=nil {fail(w,http.StatusBadRequest);return}
 // No cookie can be set until after initiating a new isolated ceremony.
 binding,err:=randomBinding()
 if err!=nil{fail(w,http.StatusServiceUnavailable);return}
 options,handle,err:=e.Service.BeginLogin(request.Username,binding)
 if err!=nil{fail(w,http.StatusUnauthorized);return}
 http.SetCookie(w,&http.Cookie{Name:loginCookie,Value:binding,Path:"/",Secure:true,HttpOnly:true,SameSite:http.SameSiteStrictMode,MaxAge:120})
 w.Header().Set("Cache-Control","no-store")
 w.Header().Set("Content-Type","application/json; charset=utf-8")
 if err:=json.NewEncoder(w).Encode(struct{
  Ceremony string `json:"ceremony"`
  Options any `json:"options"`
 }{Ceremony:handle,Options:options});err!=nil{return}
}
func(e *Endpoint)finish(w http.ResponseWriter,r *http.Request){
 if !e.checkRequest(r){fail(w,http.StatusForbidden);return}
 if !e.Limiter.Allow(r){rejectBusy(w);return}
 // The WebAuthn assertion is the request body, as required by the verifier.
 ceremony:=r.Header.Get("X-Cal45-Ceremony")
 username:=r.Header.Get("X-Cal45-Username")
 if len(ceremony)>96||len(username)>128||ceremony==""||username==""{
  fail(w,http.StatusBadRequest);return
 }
 cookie,err:=r.Cookie(loginCookie)
 if err!=nil||len(cookie.Value)!=43 {fail(w,http.StatusUnauthorized);return}
 // Clear even on failed signatures, so the binding cannot be reused.
 http.SetCookie(w,&http.Cookie{Name:loginCookie,Value:"",Path:"/",Secure:true,HttpOnly:true,SameSite:http.SameSiteStrictMode,MaxAge:-1})
 r.Body=http.MaxBytesReader(w,r.Body,64*1024)
 verified,err:=e.Service.FinishLogin(username,cookie.Value,ceremony,r)
 if err!=nil{fail(w,http.StatusUnauthorized);return}
 // Failure MUST NOT grant or silently create a session.
 if err:=e.IssueSession(w,r,verified);err!=nil{
  fail(w,http.StatusForbidden);return
 }
 w.Header().Set("Cache-Control","no-store")
 w.Header().Set("Content-Type","application/json; charset=utf-8")
 w.WriteHeader(http.StatusOK)
 w.Write([]byte("{\"authenticated\":true}"))
}

func(e *Endpoint)registerBegin(w http.ResponseWriter,r *http.Request){
 if !e.checkRequest(r){fail(w,http.StatusForbidden);return}
 if !e.Limiter.Allow(r){rejectBusy(w);return}
 user,binding,err:=e.AuthorizeRegistration(r)
 if err!=nil||user==""||len(binding)<32{fail(w,http.StatusUnauthorized);return}
 options,handle,err:=e.Service.BeginRegistration(user,binding)
 if err!=nil{fail(w,http.StatusForbidden);return}
 w.Header().Set("Cache-Control","no-store")
 w.Header().Set("Content-Type","application/json; charset=utf-8")
 json.NewEncoder(w).Encode(struct{
  Ceremony string `json:"ceremony"`
  Options any `json:"options"`
 }{Ceremony:handle,Options:options})
}
func(e *Endpoint)registerFinish(w http.ResponseWriter,r *http.Request){
 if !e.checkRequest(r){fail(w,http.StatusForbidden);return}
 if !e.Limiter.Allow(r){rejectBusy(w);return}
 user,binding,err:=e.AuthorizeRegistration(r)
 if err!=nil||user==""||len(binding)<32{fail(w,http.StatusUnauthorized);return}
 ceremony:=r.Header.Get("X-Cal45-Ceremony")
 if ceremony==""||len(ceremony)>96{fail(w,http.StatusBadRequest);return}
 r.Body=http.MaxBytesReader(w,r.Body,64*1024)
 if err:=e.Service.FinishRegistration(user,binding,ceremony,r);err!=nil{
  fail(w,http.StatusForbidden);return
 }
 w.Header().Set("Content-Type","application/json; charset=utf-8")
 w.Header().Set("Cache-Control","no-store")
 w.Write([]byte("{\"registered\":true}"))
}
