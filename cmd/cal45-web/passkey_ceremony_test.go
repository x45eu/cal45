package main

import (
 "strings"
 "testing"
 "time"
)
func TestWebAuthnRPConfig(t *testing.T){
 for _,tc:=range []struct{origin,rp string}{
  {"https://cal45.example.org","cal45.example.org"},
  {"https://dav.example.de","dav.example.de"},
 }{
  got,err:=WebAuthnRPConfig(tc.origin)
  if err!=nil||got!=tc.rp{t.Fatalf("got %q, %v",got,err)}
 }
 invalid:=[]string{
  "http://cal45.example.org","https://192.168.1.10","https://[2001:db8::1]",
  "https://localhost","https://foo","https://cal45.example.org:8443",
  "https://cal45.example.org/path","https://cal45.example.org/",
  "https://user:pass@cal45.example.org","https://cal45.example.org?x=y",
  "https://cal45.example.org#fragment",
 }
 for _,origin:=range invalid {
  if _,err:=WebAuthnRPConfig(origin);err==nil{t.Fatalf("accepted %q",origin)}
 }
}
func TestPasskeyCeremonySingleUseAndBinding(t *testing.T){
 store:=NewPasskeyCeremonies()
 sess:=strings.Repeat("s",44)
 origin:="https://cal45.example.org"
 challenge,err:=store.Begin("alice",sess,origin,"register")
 if err!=nil{t.Fatal(err)}
 if _,ok:=store.Consume(challenge,"alice",sess,origin,"login");ok{t.Fatal("wrong purpose accepted")}
 if _,ok:=store.Consume(challenge,"alice",sess,origin,"register");ok{t.Fatal("failed attempt was reusable")}
 challenge,err=store.Begin("alice",sess,origin,"login")
 if err!=nil{t.Fatal(err)}
 if _,ok:=store.Consume(challenge,"bob",sess,origin,"login");ok{t.Fatal("cross-account ceremony accepted")}
 challenge,err=store.Begin("alice",sess,origin,"login")
 if err!=nil{t.Fatal(err)}
 if _,ok:=store.Consume(challenge,"alice",strings.Repeat("x",44),origin,"login");ok{t.Fatal("cross-session ceremony accepted")}
 challenge,err=store.Begin("alice",sess,origin,"login")
 if err!=nil{t.Fatal(err)}
 if _,ok:=store.Consume(challenge,"alice",sess,origin,"login");!ok{t.Fatal("valid ceremony rejected")}
 if _,ok:=store.Consume(challenge,"alice",sess,origin,"login");ok{t.Fatal("challenge replay accepted")}
}
func TestPasskeyCeremonyExpirationAndCapacity(t *testing.T){
 store:=NewPasskeyCeremonies()
 origin:="https://cal45.example.org"
 session:=strings.Repeat("s",44)
 challenge,err:=store.Begin("alice",session,origin,"login")
 if err!=nil{t.Fatal(err)}
 store.now=func()time.Time{return time.Now().Add(3*time.Minute)}
 if _,ok:=store.Consume(challenge,"alice",session,origin,"login");ok{t.Fatal("expired ceremony accepted")}
 store=NewPasskeyCeremonies()
 store.limit=1
 if _,err:=store.Begin("alice",session,origin,"login");err!=nil{t.Fatal(err)}
 if _,err:=store.Begin("alice",session,origin,"login");err==nil{t.Fatal("capacity limit bypassed")}
}
