package main

import (
 "context"
 "crypto/rand"
 "crypto/sha256"
 "crypto/subtle"
 "encoding/base64"
 "encoding/hex"
 "encoding/json"
 "fmt"
 "log"
 "net/http"
 "net/http/httputil"
 "net/url"
 "os"
 "path/filepath"
 "strings"
 "syscall"
 "time"
)

type deviceToken struct {
 ID string `json:"id"`
 Username string `json:"username"`
 Label string `json:"label"`
 SHA256 string `json:"sha256"`
 Revoked bool `json:"revoked"`
 Created string `json:"created"`
}
type tokenStore struct { Tokens []deviceToken `json:"tokens"` }
func tokenPath() string {
 if s:=os.Getenv("CAL45_TOKEN_FILE");s!="" {return s}
 return "/var/lib/cal45/tokens.json"
}
func tokenHash(s string) string {sum:=sha256.Sum256([]byte(s));return hex.EncodeToString(sum[:])}
func randomToken(n int) (string,error) {
 b:=make([]byte,n)
 if _,err:=rand.Read(b);err!=nil{return "",err}
 return base64.RawURLEncoding.EncodeToString(b),nil
}
func allowedUsername(s string) bool {
 if len(s)==0||len(s)>128||strings.Contains(s,"..") {return false}
 for _,r:=range s {
  if !(r>='a'&&r<='z'||r>='A'&&r<='Z'||r>='0'&&r<='9'||r=='_'||r=='-'||r=='.'||r=='@') {return false}
 }
 return true
}
func readStore(path string) (tokenStore,error) {
 var db tokenStore
 b,err:=os.ReadFile(path)
 if os.IsNotExist(err) {return db,nil}
 if err!=nil{return db,err}
 err=json.Unmarshal(b,&db)
 return db,err
}
func updateStore(path string,cb func(*tokenStore)error) error {
 if err:=os.MkdirAll(filepath.Dir(path),0700);err!=nil{return err}
 lock,err:=os.OpenFile(path+".lock",os.O_CREATE|os.O_RDWR,0600)
 if err!=nil{return err}
 defer lock.Close()
 if err:=syscall.Flock(int(lock.Fd()),syscall.LOCK_EX);err!=nil{return err}
 defer syscall.Flock(int(lock.Fd()),syscall.LOCK_UN)
 db,err:=readStore(path)
 if err!=nil{return err}
 if err:=cb(&db);err!=nil{return err}
 b,err:=json.MarshalIndent(db,"","  ")
 if err!=nil{return err}
 tmp,err:=os.CreateTemp(filepath.Dir(path),".cal45-*")
 if err!=nil{return err}
 defer os.Remove(tmp.Name())
 if err:=tmp.Chmod(0600);err!=nil {tmp.Close();return err}
 if _,err:=tmp.Write(b);err!=nil {tmp.Close();return err}
 if err:=tmp.Sync();err!=nil {tmp.Close();return err}
 if err:=tmp.Close();err!=nil{return err}
 return os.Rename(tmp.Name(),path)
}
func tokenCLI(args []string) error {
 if len(args)==0{return fmt.Errorf("use token issue USER LABEL | list | revoke ID")}
 switch args[0] {
 case "issue":
  if len(args)!=3||!allowedUsername(args[1])||len(args[2])<1||len(args[2])>80 {return fmt.Errorf("use token issue USER LABEL")}
  random,err:=randomToken(32);if err!=nil{return err}
  id,err:=randomToken(9);if err!=nil{return err}
  token:="cal45_"+random
  entry:=deviceToken{ID:id,Username:args[1],Label:args[2],SHA256:tokenHash(token),Created:time.Now().UTC().Format(time.RFC3339)}
  if err:=updateStore(tokenPath(),func(db *tokenStore)error{db.Tokens=append(db.Tokens,entry);return nil});err!=nil{return err}
  fmt.Printf("Token-ID: %s\nBenutzer: %s\nApp-Passwort (einmalig): %s\n",id,args[1],token)
 case "list":
  if len(args)!=1{return fmt.Errorf("use token list")}
  db,err:=readStore(tokenPath());if err!=nil{return err}
  for _,t:=range db.Tokens {fmt.Printf("%s  %s  %s  revoked=%t\n",t.ID,t.Username,t.Label,t.Revoked)}
 case "revoke":
  if len(args)!=2{return fmt.Errorf("use token revoke ID")}
  return updateStore(tokenPath(),func(db *tokenStore)error{
   for i:=range db.Tokens {if db.Tokens[i].ID==args[1]&&!db.Tokens[i].Revoked {db.Tokens[i].Revoked=true;return nil}}
   return fmt.Errorf("token not found")
  })
 default: return fmt.Errorf("unknown command")
 }
 return nil
}
func verifyToken(path,user,token string) bool {
 if !allowedUsername(user)||!strings.HasPrefix(token,"cal45_")||len(token)>256{return false}
 db,err:=readStore(path)
 if err!=nil {log.Printf("token store unavailable: %v",err);return false}
 sum:=sha256.Sum256([]byte(token))
 for _,t:=range db.Tokens {
  expected,err:=hex.DecodeString(t.SHA256)
  if err==nil&&len(expected)==32&&subtle.ConstantTimeCompare(sum[:],expected)==1&&t.Username==user&&!t.Revoked{return true}
 }
 return false
}
type verifiedKey struct{}
func newDAVHandler(target,store string)(http.Handler,error) {
 u,err:=url.Parse(target);if err!=nil||u.Scheme!="http"||u.Host=="" {return nil,fmt.Errorf("invalid upstream")}
 proxy:=httputil.NewSingleHostReverseProxy(u)
 original:=proxy.Director
 proxy.Director=func(r *http.Request){
  for key:=range r.Header {
   k:=strings.ToLower(key)
   if k=="authorization"||k=="remote-user"||k=="x-remote-user"||k=="forwarded"||k=="x-script-name"||strings.HasPrefix(k,"x-forwarded-"){r.Header.Del(key)}
  }
  user:=r.Context().Value(verifiedKey{}).(string)
  original(r)
  r.Header.Set("X-Remote-User",user)
  r.Header.Set("X-Forwarded-Proto","https")
 }
 return http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){
  w.Header().Set("Cache-Control","no-store")
  user,password,ok:=r.BasicAuth()
  if !ok||!verifyToken(store,user,password){
   w.Header().Set("WWW-Authenticate",`Basic realm="Cal45 DAV"`)
   http.Error(w,"Cal45 App-Passwort erforderlich",http.StatusUnauthorized)
   return
  }
  proxy.ServeHTTP(w,r.WithContext(context.WithValue(r.Context(),verifiedKey{},user)))
 }),nil
}
func startDAV()error{
 upstream:=os.Getenv("CAL45_DAV_UPSTREAM");if upstream=="" {upstream="http://cal45:5232"}
 handler,err:=newDAVHandler(upstream,tokenPath());if err!=nil{return err}
 bind:=os.Getenv("CAL45_GATEWAY_BIND");if bind=="" {bind="127.0.0.1:8081"}
 log.Printf("Cal45 DAV gateway listening: %s",bind)
 return (&http.Server{Addr:bind,Handler:handler,ReadHeaderTimeout:5*time.Second}).ListenAndServe()
}
