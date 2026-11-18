package passkeys

import (
 "errors"
 "strings"
 "testing"
 "time"

 "github.com/go-webauthn/webauthn/webauthn"
)
type testRepo struct{ user User }
func(r *testRepo)GetActive(name string)(User,error){
 if name!=r.user.Username{return User{},errors.New("no account")}
 return r.user,nil
}
func(r *testRepo)SaveNewCredential(string,*webauthn.Credential)error{return nil}
func(r *testRepo)SaveVerifiedCredential(string,*webauthn.Credential)error{return nil}
func TestOriginRequirements(t *testing.T){
 for _,valid:=range []string{"https://cal45.example.org","https://dav.example.de"} {
  if _,err:=ValidOrigin(valid);err!=nil{t.Fatal(err)}
 }
 for _,bad:=range []string{"http://cal45.example.org","https://127.0.0.1","https://[::1]","https://localhost","https://foo","https://cal45.example.org/","https://cal45.example.org:8443","https://cal45.example.org/path","https://user@cal45.example.org"}{
  if _,err:=ValidOrigin(bad);err==nil{t.Fatalf("accepted %s",bad)}
 }
}
func TestCeremonySingleUseAndExpiry(t *testing.T){
 repo:=&testRepo{user:User{Username:"alice",Handle:[]byte(strings.Repeat("a",32))}}
 svc,err:=New("https://cal45.example.org",repo);if err!=nil{t.Fatal(err)}
 binding:=strings.Repeat("s",44)
 _,handle,err:=svc.BeginRegistration("alice",binding)
 if err!=nil{t.Fatal(err)}
 if _,err:=svc.consume(handle,"bob",binding,"register");err==nil{t.Fatal("cross-user accepted")}
 if _,err:=svc.consume(handle,"alice",binding,"register");err==nil{t.Fatal("challenge reused")}
 _,handle,err=svc.BeginRegistration("alice",binding);if err!=nil{t.Fatal(err)}
 if _,err:=svc.consume(handle,"alice",strings.Repeat("x",44),"register");err==nil{t.Fatal("wrong session accepted")}
 _,handle,err=svc.BeginRegistration("alice",binding);if err!=nil{t.Fatal(err)}
 svc.now=func()time.Time{return time.Now().Add(3*time.Minute)}
 if _,err:=svc.consume(handle,"alice",binding,"register");err==nil{t.Fatal("expired challenge accepted")}
}
func TestCannotLoginWithoutCredentials(t *testing.T){
 repo:=&testRepo{user:User{Username:"alice",Handle:[]byte(strings.Repeat("a",32))}}
 svc,err:=New("https://cal45.example.org",repo);if err!=nil{t.Fatal(err)}
 if _,_,err:=svc.BeginLogin("alice",strings.Repeat("x",44));err==nil{t.Fatal("empty credential list accepted")}
}
