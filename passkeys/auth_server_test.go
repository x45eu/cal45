package passkeys

import (
 "net/http"
 "net/http/httptest"
 "os"
 "path/filepath"
 "strings"
 "testing"
)
func TestAuthCompositionAndRoutes(t *testing.T){
 dir:=t.TempDir()
 accounts:=filepath.Join(dir,"tokens.json")
 if err:=os.WriteFile(accounts,[]byte(`{"accounts":[{"username":"alice","active":true}]}`),0600);err!=nil{t.Fatal(err)}
 repo,err:=NewJSONRepository(accounts,filepath.Join(dir,"passkeys.json"))
 if err!=nil{t.Fatal(err)}
 grant:=BootstrapStore{Path:filepath.Join(dir,"bootstrap.json"),
  AccountIsActive:func(name string)bool{return name=="alice"}}
 handler,err:=NewAuthHandler("https://cal45.example.org",repo,grant)
 if err!=nil{t.Fatal(err)}
 for _,path:=range []string{"/auth/login","/auth/login.js","/auth/enroll","/auth/enroll.js","/auth/account","/auth/account.js"}{
  request:=httptest.NewRequest("GET",path,nil)
  result:=httptest.NewRecorder()
  handler.ServeHTTP(result,request)
  if result.Code!=200{t.Fatalf("missing route %s: %d",path,result.Code)}
  if result.Header().Get("Content-Security-Policy")==""{t.Fatal("missing CSP")}
 }
 req:=httptest.NewRequest("GET","/auth/session",nil)
 result:=httptest.NewRecorder();handler.ServeHTTP(result,req)
 if result.Code!=http.StatusUnauthorized{t.Fatal("session exposed anonymously")}
 req=httptest.NewRequest("POST","/auth/passkey/login/begin",strings.NewReader(`{"username":"alice"}`))
 req.Header.Set("Content-Type","application/json")
 req.Header.Set("Origin","https://evil.example")
 result=httptest.NewRecorder();handler.ServeHTTP(result,req)
 if result.Code!=403{t.Fatal("cross-origin login accepted")}
}
func TestAuthRejectsMissingConfig(t *testing.T){
 if _,err:=NewAuthHandler("http://127.0.0.1",nil,BootstrapStore{});err==nil{
  t.Fatal("insecure origin accepted")
 }
}
