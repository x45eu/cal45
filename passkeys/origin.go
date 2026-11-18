package passkeys

import (
 "errors"
 "net/url"
 "strings"
)

// ValidOrigin requires a standalone HTTPS domain, not a raw IP, localhost,
// URL path, query, fragment, credentials or a custom port.
func ValidOrigin(origin string)(string,error){
 u,err:=url.Parse(origin)
 if err!=nil||u.Scheme!="https"||u.User!=nil||u.RawQuery!=""||
  u.Fragment!=""||u.Path!=""||u.RawPath!=""||u.Port()!=""||
  strings.HasSuffix(origin,"/")||u.Hostname()!=u.Host||
  !validDNS(u.Hostname()){
  return "",errors.New("Cal45 passkeys require a HTTPS DNS origin")
 }
 return u.Hostname(),nil
}
func validDNS(host string)bool{
 if len(host)>253||!strings.Contains(host,".")||strings.ContainsAny(host,":[]")||strings.HasSuffix(host,".") {return false}
 labels:=strings.Split(host,".")
 for _,l:=range labels{
  if len(l)<1||len(l)>63||l[0]=='-'||l[len(l)-1]=='-' {return false}
  for _,r:=range l {if !(r>='a'&&r<='z'||r>='A'&&r<='Z'||r>='0'&&r<='9'||r=='-'){return false}}
 }
 last:=labels[len(labels)-1]
 if len(last)<2{return false}
 for _,r:=range last{if !(r>='a'&&r<='z'||r>='A'&&r<='Z'){return false}}
 return true
}
