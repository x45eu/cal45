package passkeys

import (
 "net/http"
 "net/http/httptest"
 "strings"
 "testing"
)

func TestLoginEndpointDefaultClosed(t *testing.T){
 mux:=http.NewServeMux()
 if err:=(&Endpoint{Origin:"https://cal45.example.org"}).Register(mux);err==nil{
  t.Fatal("login routes registered without verifier and session issuer")
 }
 req:=httptest.NewRequest("POST","/auth/passkey/login/begin",nil)
 rec:=httptest.NewRecorder();mux.ServeHTTP(rec,req)
 if rec.Code!=http.StatusNotFound{t.Fatalf("unexpected open route: %d",rec.Code)}
}
func TestLoginEndpointOriginValidation(t *testing.T){
 e:=&Endpoint{Origin:"https://cal45.example.org"}
 req:=httptest.NewRequest("POST","/auth/passkey/login/begin",strings.NewReader("{}"))
 req.Header.Set("Content-Type","application/json")
 if e.checkRequest(req){t.Fatal("missing Origin allowed")}
 req.Header.Set("Origin","https://attacker.example")
 if e.checkRequest(req){t.Fatal("foreign Origin allowed")}
 req.Header.Set("Origin",e.Origin)
 req.Header.Set("Sec-Fetch-Site","cross-site")
 if e.checkRequest(req){t.Fatal("cross-site request allowed")}
 req.Header.Set("Sec-Fetch-Site","same-origin")
 if !e.checkRequest(req){t.Fatal("valid browser request rejected")}
 req.Header.Set("Content-Type","text/plain")
 if e.checkRequest(req){t.Fatal("unsupported content type allowed")}
}
func TestPasskeyLoginBindingIsRandom(t *testing.T){
 a,err:=randomBinding();if err!=nil{t.Fatal(err)}
 b,err:=randomBinding();if err!=nil{t.Fatal(err)}
 if len(a)!=43||a==b{t.Fatal("binding must be random 256-bit value")}
}

func TestRegistrationRequiresStepUpProvider(t *testing.T){
 repo:=&testRepo{user:User{Username:"alice",Handle:[]byte(strings.Repeat("a",32))}}
 service,err:=New("https://cal45.example.org",repo)
 if err!=nil{t.Fatal(err)}
 endpoint:=&Endpoint{
  Service:service,
  Origin:"https://cal45.example.org",
  IssueSession:func(http.ResponseWriter,*http.Request,string)error{return nil},
 }
 mux:=http.NewServeMux()
 if err:=endpoint.Register(mux);err!=nil{t.Fatal(err)}
 req:=httptest.NewRequest("POST","/auth/passkey/register/begin",strings.NewReader("{}"))
 req.Header.Set("Origin",endpoint.Origin)
 req.Header.Set("Content-Type","application/json")
 rec:=httptest.NewRecorder()
 mux.ServeHTTP(rec,req)
 if rec.Code!=http.StatusNotFound{t.Fatalf("registration exposed without authorizer: %d",rec.Code)}
}
func TestRegistrationRejectsUnauthenticatedRequests(t *testing.T){
 repo:=&testRepo{user:User{Username:"alice",Handle:[]byte(strings.Repeat("a",32))}}
 service,err:=New("https://cal45.example.org",repo)
 if err!=nil{t.Fatal(err)}
 endpoint:=&Endpoint{
  Service:service,
  Origin:"https://cal45.example.org",
  IssueSession:func(http.ResponseWriter,*http.Request,string)error{return nil},
  AuthorizeRegistration:func(*http.Request)(string,string,error){return "","",ErrUnavailable},
 }
 mux:=http.NewServeMux()
 if err:=endpoint.Register(mux);err!=nil{t.Fatal(err)}
 req:=httptest.NewRequest("POST","/auth/passkey/register/begin",strings.NewReader("{}"))
 req.Header.Set("Origin",endpoint.Origin)
 req.Header.Set("Content-Type","application/json")
 rec:=httptest.NewRecorder()
 mux.ServeHTTP(rec,req)
 if rec.Code!=http.StatusUnauthorized{t.Fatalf("unauthenticated enrollment accepted: %d",rec.Code)}
}
