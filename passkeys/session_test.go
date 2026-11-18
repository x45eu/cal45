package passkeys

import (
 "net/http"
 "net/http/httptest"
 "testing"
 "time"
)
func TestBrowserSessionLifetimeAndRevocation(t *testing.T){
 s:=NewBrowserSessions()
 token,csrf,err:=s.issue("alice")
 if err!=nil{t.Fatal(err)}
 if !s.csrfValid(token,csrf){t.Fatal("valid CSRF denied")}
 if s.csrfValid(token,"wrong"){t.Fatal("invalid CSRF accepted")}
 cookie:=newSessionCookie(token)
 if !cookie.Secure||!cookie.HttpOnly||cookie.Path!="/"||
  cookie.SameSite!=http.SameSiteStrictMode{t.Fatal("unsafe session cookie")}
 s.revoke(token)
 if _,ok:=s.get(token);ok{t.Fatal("revoked session accepted")}
 token,_,err=s.issue("alice");if err!=nil{t.Fatal(err)}
 s.now=func()time.Time{return time.Now().Add(9*time.Hour)}
 if _,ok:=s.get(token);ok{t.Fatal("expired session accepted")}
}
func TestSessionEndpointsAuthenticationAndLogout(t *testing.T){
 store:=NewBrowserSessions()
 users:=map[string]bool{"alice":true}
 endpoints:=&SessionEndpoints{Store:store,Origin:"https://cal45.example.org",
  Active:func(user string)bool{return users[user]}}
 mux:=http.NewServeMux()
 if err:=endpoints.Register(mux);err!=nil{t.Fatal(err)}
 request:=httptest.NewRequest("GET","/auth/session",nil)
 rec:=httptest.NewRecorder();mux.ServeHTTP(rec,request)
 if rec.Code!=401{t.Fatal("anonymous session accepted")}
 token,csrf,err:=store.issue("alice");if err!=nil{t.Fatal(err)}
 request=httptest.NewRequest("GET","/auth/session",nil)
 request.AddCookie(newSessionCookie(token))
 rec=httptest.NewRecorder();mux.ServeHTTP(rec,request)
 if rec.Code!=200{t.Fatalf("authenticated session not returned: %d",rec.Code)}
 logout:=httptest.NewRequest("POST","/auth/logout",nil)
 logout.AddCookie(newSessionCookie(token))
 logout.Header.Set("Origin","https://evil.example")
 logout.Header.Set("X-Cal45-CSRF",csrf)
 rec=httptest.NewRecorder();mux.ServeHTTP(rec,logout)
 if rec.Code!=403{t.Fatal("cross-origin logout accepted")}
 logout=httptest.NewRequest("POST","/auth/logout",nil)
 logout.AddCookie(newSessionCookie(token))
 logout.Header.Set("Origin",endpoints.Origin)
 logout.Header.Set("X-Cal45-CSRF",csrf)
 rec=httptest.NewRecorder();mux.ServeHTTP(rec,logout)
 if rec.Code!=204{t.Fatalf("logout failed: %d",rec.Code)}
 if _,ok:=store.get(token);ok{t.Fatal("session survived logout")}
 newToken,_,err:=store.issue("alice");if err!=nil{t.Fatal(err)}
 users["alice"]=false
 request=httptest.NewRequest("GET","/auth/session",nil)
 request.AddCookie(newSessionCookie(newToken))
 rec=httptest.NewRecorder();mux.ServeHTTP(rec,request)
 if rec.Code!=401{t.Fatal("disabled account's session accepted")}
}
func TestSessionIssuerRequiresActiveAccount(t *testing.T){
 s:=&SessionEndpoints{Store:NewBrowserSessions(),Origin:"https://cal45.example.org",
  Active:func(name string)bool{return false}}
 r:=httptest.NewRequest("POST","/auth/passkey/login/finish",nil)
 w:=httptest.NewRecorder()
 if err:=s.issuer(w,r,"alice");err==nil{t.Fatal("disabled user obtained session")}
}
