package passkeys

import (
 "crypto/rand"
 "crypto/sha256"
 "crypto/subtle"
 "encoding/base64"
 "encoding/json"
 "net/http"
 "sync"
 "time"
)

const webSessionCookie="__Host-cal45_session"

type browserSession struct {
 username string
 csrf [32]byte
 authenticatedAt time.Time
 expiry time.Time
}

// BrowserSessions are purpose-built for a single-process Cal45 auth server.
// They are not persisted or shared across replicas; restart logs users out.
type BrowserSessions struct {
 mu sync.Mutex
 sessions map[[32]byte]browserSession
 now func()time.Time
 maxSessions int
}
func NewBrowserSessions()*BrowserSessions {
 return &BrowserSessions{
  sessions:make(map[[32]byte]browserSession),now:time.Now,maxSessions:4096,
 }
}
func(s *BrowserSessions)issue(username string)(token,csrf string,err error) {
 if s==nil||!validGrantUser(username){return "","",ErrUnavailable}
 raw:=make([]byte,32)
 if _,err=rand.Read(raw);err!=nil{return "","",err}
 var csrfRaw [32]byte
 if _,err=rand.Read(csrfRaw[:]);err!=nil{return "","",err}
 key:=sha256.Sum256(raw)
 s.mu.Lock()
 defer s.mu.Unlock()
 now:=s.now()
 for id,item:=range s.sessions {
  if !now.Before(item.expiry){delete(s.sessions,id)}
 }
 if len(s.sessions)>=s.maxSessions{return "","",ErrUnavailable}
 s.sessions[key]=browserSession{username:username,csrf:csrfRaw,authenticatedAt:now,expiry:now.Add(8*time.Hour)}
 return base64.RawURLEncoding.EncodeToString(raw),
  base64.RawURLEncoding.EncodeToString(csrfRaw[:]),nil
}
func(s *BrowserSessions)get(token string)(browserSession,bool){
 if s==nil{return browserSession{},false}
 raw,err:=base64.RawURLEncoding.DecodeString(token)
 if err!=nil||len(raw)!=32{return browserSession{},false}
 key:=sha256.Sum256(raw)
 s.mu.Lock();defer s.mu.Unlock()
 item,ok:=s.sessions[key]
 if !ok{return browserSession{},false}
 if !s.now().Before(item.expiry){delete(s.sessions,key);return browserSession{},false}
 return item,true
}
func(s *BrowserSessions)revoke(token string){
 if s==nil{return}
 raw,err:=base64.RawURLEncoding.DecodeString(token)
 if err!=nil||len(raw)!=32{return}
 key:=sha256.Sum256(raw)
 s.mu.Lock();delete(s.sessions,key);s.mu.Unlock()
}
func(s *BrowserSessions)csrfValid(token,csrf string)bool{
 item,ok:=s.get(token)
 if !ok{return false}
 raw,err:=base64.RawURLEncoding.DecodeString(csrf)
 return err==nil&&len(raw)==32&&subtle.ConstantTimeCompare(raw,item.csrf[:])==1
}
func newSessionCookie(token string)*http.Cookie {
 return &http.Cookie{Name:webSessionCookie,Value:token,Path:"/",
  Secure:true,HttpOnly:true,SameSite:http.SameSiteStrictMode,MaxAge:8*3600}
}
func expireSessionCookie()*http.Cookie{
 return &http.Cookie{Name:webSessionCookie,Value:"",Path:"/",
  Secure:true,HttpOnly:true,SameSite:http.SameSiteStrictMode,MaxAge:-1}
}

// SessionEndpoints is opt-in and must be wrapped in deployment HTTPS and
// trusted reverse proxy controls. It only authorizes already-active accounts.
type SessionEndpoints struct {
 Store *BrowserSessions
 Origin string
 Active func(string)bool
}
func(s *SessionEndpoints)issuer(w http.ResponseWriter,r *http.Request,username string)error{
 if s==nil||s.Store==nil||s.Active==nil||!s.Active(username) {
  return ErrUnavailable
 }
 token,_,err:=s.Store.issue(username)
 if err!=nil{return err}
 http.SetCookie(w,newSessionCookie(token))
 return nil
}
func(s *SessionEndpoints)Register(mux *http.ServeMux)error {
 if s==nil||s.Store==nil||s.Active==nil||mux==nil{return ErrUnavailable}
 if _,err:=ValidOrigin(s.Origin);err!=nil{return err}
 mux.HandleFunc("GET /auth/session",s.session)
 mux.HandleFunc("POST /auth/logout",s.logout)
 return nil
}
func(s *SessionEndpoints)session(w http.ResponseWriter,r *http.Request) {
 w.Header().Set("Cache-Control","no-store")
 cookie,err:=r.Cookie(webSessionCookie)
 if err!=nil{fail(w,http.StatusUnauthorized);return}
 item,ok:=s.Store.get(cookie.Value)
 if !ok||!s.Active(item.username){fail(w,http.StatusUnauthorized);return}
 csrf:=base64.RawURLEncoding.EncodeToString(item.csrf[:])
 w.Header().Set("Content-Type","application/json; charset=utf-8")
 _=json.NewEncoder(w).Encode(struct{
  Username string `json:"username"`
  CSRF string `json:"csrf"`
 }{item.username,csrf})
}
func(s *SessionEndpoints)logout(w http.ResponseWriter,r *http.Request){
 if r.Header.Get("Origin")!=s.Origin||
  (r.Header.Get("Sec-Fetch-Site")!=""&&r.Header.Get("Sec-Fetch-Site")!="same-origin"){
  fail(w,http.StatusForbidden);return
 }
 cookie,err:=r.Cookie(webSessionCookie)
 if err!=nil||!s.Store.csrfValid(cookie.Value,r.Header.Get("X-Cal45-CSRF")){
  fail(w,http.StatusForbidden);return
 }
 s.Store.revoke(cookie.Value)
 http.SetCookie(w,expireSessionCookie())
 w.Header().Set("Cache-Control","no-store")
 w.WriteHeader(http.StatusNoContent)
}
