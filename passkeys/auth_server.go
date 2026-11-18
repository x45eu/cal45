package passkeys

import (
 "embed"
 "fmt"
 "net/http"
 "strings"
)

//go:embed web/login.html web/login.css web/login.js web/enroll.html web/enroll.js web/account.html web/account.js
var browserAssets embed.FS

// NewAuthHandler composes passkey login, initial registration and authenticated
// sessions into an isolated single-host auth service. No route issues device
// passwords. The operator MUST provide TLS and trusted proxy controls.
func NewAuthHandler(origin string,repository *JSONRepository,grants BootstrapStore)(http.Handler,error){
 if _,err:=ValidOrigin(origin);err!=nil{return nil,err}
 if repository==nil||grants.Path==""||grants.AccountIsActive==nil{return nil,ErrUnavailable}
 service,err:=New(origin,repository)
 if err!=nil{return nil,err}
 sessions:=&SessionEndpoints{
  Store:NewBrowserSessions(),
  Origin:origin,
  Active:repository.accountActive,
 }
 mux:=http.NewServeMux()
 if err:=sessions.Register(mux);err!=nil{return nil,err}
 login:=&Endpoint{
  Service:service,Origin:origin,
  IssueSession:sessions.issuer,
 }
 if err:=login.Register(mux);err!=nil{return nil,err}
 enroll:=&InitialEnrollment{
  Service:service,Repository:repository,Grants:grants,Origin:origin,
 }
 if err:=enroll.Register(mux);err!=nil{return nil,err}
 routes:=map[string]string{
  "/auth/login":"web/login.html",
  "/auth/login.css":"web/login.css",
  "/auth/login.js":"web/login.js",
  "/auth/enroll":"web/enroll.html",
  "/auth/enroll.js":"web/enroll.js",
  "/auth/account":"web/account.html",
  "/auth/account.js":"web/account.js",
 }
 for route,file:=range routes {
  path,asset:=route,file
  mux.HandleFunc("GET "+path,func(w http.ResponseWriter,r *http.Request){
   contents,err:=browserAssets.ReadFile(asset)
   if err!=nil{http.NotFound(w,r);return}
   switch {
   case strings.HasSuffix(asset,".html"):
    w.Header().Set("Content-Type","text/html; charset=utf-8")
   case strings.HasSuffix(asset,".css"):
    w.Header().Set("Content-Type","text/css; charset=utf-8")
   case strings.HasSuffix(asset,".js"):
    w.Header().Set("Content-Type","text/javascript; charset=utf-8")
   default:
    http.Error(w,"Unsupported resource",http.StatusInternalServerError);return
   }
   w.Header().Set("Content-Security-Policy",
    "default-src 'none'; script-src 'self'; style-src 'self'; "+
    "connect-src 'self'; img-src 'self'; form-action 'self'; "+
    "base-uri 'none'; frame-ancestors 'none'")
   w.Header().Set("Cache-Control","no-store")
   w.Header().Set("X-Content-Type-Options","nosniff")
   w.Header().Set("Referrer-Policy","no-referrer")
   w.Header().Set("X-Frame-Options","DENY")
   _,_=w.Write(contents)
  })
 }
 return mux,nil
}
func authServerWarning(bind,origin string)string{
 return fmt.Sprintf("Cal45 auth service binding to %s for trusted HTTPS origin %s; "+
  "configure TLS reverse proxy, request limits and a private auth volume",bind,origin)
}
