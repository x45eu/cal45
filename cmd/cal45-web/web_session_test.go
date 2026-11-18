package main

import (
 "net/http"
 "testing"
 "time"
)
func TestSessionSecurity(t *testing.T){
 s:=NewSessionStore()
 token,csrf,err:=s.New("alice",true);if err!=nil{t.Fatal(err)}
 got,ok:=s.Get(token)
 if !ok||got.Username!="alice"||!got.MFA{t.Fatal("session not found")}
 if !s.VerifyCSRF(token,csrf){t.Fatal("CSRF check failed")}
 if s.VerifyCSRF(token,"not-a-csrf"){t.Fatal("invalid CSRF accepted")}
 cookie:=SessionCookie(token)
 if !cookie.Secure||!cookie.HttpOnly||cookie.SameSite!=http.SameSiteStrictMode||cookie.Path!="/"{t.Fatal("insecure session cookie")}
 s.now=func()time.Time{return time.Now().Add(9*time.Hour)}
 if _,ok:=s.Get(token);ok{t.Fatal("expired session still valid")}
}
func TestLogoutRevokesSession(t *testing.T){
 s:=NewSessionStore()
 token,_,err:=s.New("alice",true);if err!=nil{t.Fatal(err)}
 s.Revoke(token)
 if _,ok:=s.Get(token);ok{t.Fatal("revoked session valid")}
}
