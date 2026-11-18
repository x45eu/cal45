package main

import (
 "crypto/rand"
 "crypto/sha256"
 "crypto/subtle"
 "encoding/base64"
 "errors"
 "net/url"
 "strings"
 "sync"
 "time"
)

// PasskeyCeremonies holds one-time, short-lived server-side ceremony state.
// Only credential assertions validated by a WebAuthn library may finish authentication.
// This store intentionally does NOT issue sessions or accept WebAuthn signatures.
type PasskeyCeremonies struct {
 mu sync.Mutex
 pending map[[32]byte]PasskeyCeremony
 now func() time.Time
 limit int
}
type PasskeyCeremony struct {
 Account string
 SessionBinding [32]byte
 RPDomain string
 Origin string
 Purpose string
 Expires time.Time
}
var ErrCeremonyUnavailable=errors.New("passkey ceremony unavailable")

func NewPasskeyCeremonies()*PasskeyCeremonies {
 return &PasskeyCeremonies{
  pending: make(map[[32]byte]PasskeyCeremony),
  now:time.Now,
  limit:1024,
 }
}

// WebAuthnRPConfig accepts exactly one configured HTTPS origin and its domain.
// It must be configured by the operator, never derived from Host or forwarded headers.
func WebAuthnRPConfig(origin string)(string,error) {
 u,err:=url.Parse(origin)
 if err!=nil||u.Scheme!="https"||u.Host==""||u.Hostname()==""||
  u.User!=nil||u.RawQuery!=""||u.Fragment!=""||u.Path!=""||u.RawPath!=""||
  u.Port()!=""||strings.HasSuffix(origin,"/") {
  return "",errors.New("WebAuthn origin must be a HTTPS DNS origin without path or port")
 }
 host:=u.Hostname()
 if !validRPDomain(host){return "",errors.New("WebAuthn relying party must be a DNS domain")}
 return host,nil
}
func validRPDomain(host string) bool {
 if len(host)>253||!strings.Contains(host,".")||strings.Contains(host,":")||strings.HasSuffix(host,"."){return false}
 parts:=strings.Split(host,".")
 if len(parts)<2{return false}
 for _,part:=range parts {
  if len(part)==0||len(part)>63||part[0]=='-'||part[len(part)-1]=='-' {return false}
  for _,c:=range part {
   if !(c>='a'&&c<='z'||c>='A'&&c<='Z'||c>='0'&&c<='9'||c=='-') {return false}
  }
 }
 tld:=parts[len(parts)-1]
 if len(tld)<2{return false}
 for _,c:=range tld {if !(c>='a'&&c<='z'||c>='A'&&c<='Z'){return false}}
 return true
}
func(s *PasskeyCeremonies)Begin(account,sessionToken,origin,purpose string)(string,error) {
 if !allowedUsername(account)||len(sessionToken)<32||
  (purpose!="register"&&purpose!="login") {
  return "",ErrCeremonyUnavailable
 }
 rp,err:=WebAuthnRPConfig(origin)
 if err!=nil{return "",err}
 raw:=make([]byte,32)
 if _,err:=rand.Read(raw);err!=nil{return "",err}
 key:=sha256.Sum256(raw)
 binding:=sha256.Sum256([]byte(sessionToken))
 s.mu.Lock()
 defer s.mu.Unlock()
 now:=s.now()
 // Time-bounded map with a hard cap against unbounded memory usage.
 for id,state:=range s.pending {
  if !now.Before(state.Expires){delete(s.pending,id)}
 }
 if len(s.pending)>=s.limit{return "",ErrCeremonyUnavailable}
 s.pending[key]=PasskeyCeremony{
  Account:account, SessionBinding:binding, RPDomain:rp, Origin:origin,
  Purpose:purpose, Expires:now.Add(120*time.Second),
 }
 return base64.RawURLEncoding.EncodeToString(raw),nil
}
// Consume permanently invalidates the challenge even if the supplied binding is wrong.
// The returned state is for use with full WebAuthn library verification, NOT a login grant.
func(s *PasskeyCeremonies)Consume(challenge,account,sessionToken,origin,purpose string)(PasskeyCeremony,bool){
 raw,err:=base64.RawURLEncoding.DecodeString(challenge)
 if err!=nil||len(raw)!=32{return PasskeyCeremony{},false}
 key:=sha256.Sum256(raw)
 s.mu.Lock()
 state,ok:=s.pending[key]
 delete(s.pending,key)
 now:=s.now()
 s.mu.Unlock()
 if !ok||!now.Before(state.Expires){return PasskeyCeremony{},false}
 rp,err:=WebAuthnRPConfig(origin)
 if err!=nil{return PasskeyCeremony{},false}
 binding:=sha256.Sum256([]byte(sessionToken))
 if subtle.ConstantTimeCompare(binding[:],state.SessionBinding[:])!=1||
  state.Account!=account||state.Origin!=origin||state.RPDomain!=rp||
  state.Purpose!=purpose {
  return PasskeyCeremony{},false
 }
 return state,true
}
