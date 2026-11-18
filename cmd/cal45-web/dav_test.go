package main

import (
 "net/http"
 "net/http/httptest"
 "path/filepath"
 "strings"
 "testing"
)
func TestDeviceTokens(t *testing.T) {
 path:=filepath.Join(t.TempDir(),"tokens.json")
 pass:="cal45_"+strings.Repeat("A",43)
 if err:=updateStore(path,func(db *tokenStore)error{
  db.Accounts=append(db.Accounts,account{Username:"alice",Active:true})
  db.Tokens=append(db.Tokens,deviceToken{ID:"test",Username:"alice",SHA256:tokenHash(pass)})
  return nil
 });err!=nil{t.Fatal(err)}
 if !verifyToken(path,"alice",pass){t.Fatal("valid token rejected")}
 if verifyToken(path,"alice","account-password"){t.Fatal("account password accepted")}
 if verifyToken(path,"bob",pass){t.Fatal("cross-user token accepted")}
 if err:=updateStore(path,func(db *tokenStore)error{db.Tokens[0].Revoked=true;return nil});err!=nil{t.Fatal(err)}
 if verifyToken(path,"alice",pass){t.Fatal("revoked token accepted")}
}
func TestGatewayHeaders(t *testing.T) {
 var identity,auth,forwarded string
 backend:=httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){
  identity=r.Header.Get("X-Remote-User")
  auth=r.Header.Get("Authorization")
  forwarded=r.Header.Get("Forwarded")
  w.WriteHeader(http.StatusMultiStatus)
 }))
 defer backend.Close()
 path:=filepath.Join(t.TempDir(),"tokens.json")
 pass:="cal45_"+strings.Repeat("B",43)
 if err:=updateStore(path,func(db *tokenStore)error{
  db.Accounts=append(db.Accounts,account{Username:"alice",Active:true})
  db.Tokens=append(db.Tokens,deviceToken{ID:"t",Username:"alice",SHA256:tokenHash(pass)});return nil
 });err!=nil{t.Fatal(err)}
 handler,err:=newDAVHandler(backend.URL,path);if err!=nil{t.Fatal(err)}
 req:=httptest.NewRequest("PROPFIND","http://gateway.example/",nil)
 req.SetBasicAuth("alice",pass)
 req.Header.Set("X-Remote-User","admin")
 req.Header.Set("Forwarded","for=spoofed")
 rec:=httptest.NewRecorder()
 handler.ServeHTTP(rec,req)
 if rec.Code!=207||identity!="alice"||auth!=""||forwarded!="" {t.Fatalf("proxy error %d identity=%q auth=%q forwarded=%q",rec.Code,identity,auth,forwarded)}
 req2:=httptest.NewRequest("GET","http://gateway.example/",nil)
 req2.SetBasicAuth("alice","account-password")
 denied:=httptest.NewRecorder()
 handler.ServeHTTP(denied,req2)
 if denied.Code!=401 {t.Fatalf("account password accepted: %d",denied.Code)}
}
func TestInvalidUsernames(t *testing.T){
 for _,u:=range []string{"","../admin","user/path","bad:name"} {if allowedUsername(u){t.Fatalf("accepted %q",u)}}
}
