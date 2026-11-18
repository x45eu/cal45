package passkeys

import (
 "crypto/rand"
 "crypto/sha256"
 "encoding/base64"
 "encoding/hex"
 "encoding/json"
 "errors"
 "net/http"
 "os"
 "path/filepath"
 "strings"
 "syscall"
 "time"
)

// DevicePasswords uses the same JSON store and POSIX lock as the Cal45 DAV
// gateway. Issuance requires an active user, CSRF token and a recently
// verified passkey. Ordinary account passwords are never accepted here.
type DevicePasswords struct {
 TokenFile string
 Sessions *BrowserSessions
 Origin string
 Active func(string)bool
 Limiter *AttemptLimiter
}
type deviceEntry struct {
 ID string `json:"id"`
 Username string `json:"username"`
 Label string `json:"label"`
 SHA256 string `json:"sha256"`
 Revoked bool `json:"revoked"`
 Created string `json:"created"`
}
func(d *DevicePasswords)Register(mux *http.ServeMux)error {
 if d==nil||mux==nil||d.TokenFile==""||d.Sessions==nil||d.Active==nil {return ErrUnavailable}
 if _,err:=ValidOrigin(d.Origin);err!=nil{return err}
 if d.Limiter==nil{d.Limiter=NewAttemptLimiter()}
 mux.HandleFunc("GET /auth/devices",d.list)
 mux.HandleFunc("POST /auth/devices/create",d.create)
 mux.HandleFunc("POST /auth/devices/revoke",d.revoke)
 return nil
}
func(d *DevicePasswords)session(r *http.Request,stepUp bool)(browserSession,bool){
 cookie,err:=r.Cookie(webSessionCookie)
 if err!=nil{return browserSession{},false}
 item,ok:=d.Sessions.get(cookie.Value)
 if !ok||!d.Active(item.username){return browserSession{},false}
 if stepUp && (d.Sessions.now().Sub(item.authenticatedAt)>5*time.Minute||
  d.Sessions.now().Before(item.authenticatedAt)){return browserSession{},false}
 return item,true
}
func(d *DevicePasswords)mutating(w http.ResponseWriter,r *http.Request)(browserSession,bool){
 if r.Header.Get("Origin")!=d.Origin||
  (r.Header.Get("Sec-Fetch-Site")!=""&&r.Header.Get("Sec-Fetch-Site")!="same-origin")||
  r.Header.Get("Content-Type")!="application/json" {fail(w,http.StatusForbidden);return browserSession{},false}
 if !d.Limiter.Allow(r){rejectBusy(w);return browserSession{},false}
 entry,ok:=d.session(r,true)
 if !ok{fail(w,http.StatusUnauthorized);return browserSession{},false}
 cookie,_:=r.Cookie(webSessionCookie)
 if !d.Sessions.csrfValid(cookie.Value,r.Header.Get("X-Cal45-CSRF")){
  fail(w,http.StatusForbidden);return browserSession{},false
 }
 return entry,true
}
func(d *DevicePasswords)edit(fn func(map[string]json.RawMessage,*[]deviceEntry)error)error {
 if err:=os.MkdirAll(filepath.Dir(d.TokenFile),0700);err!=nil{return err}
 lock,err:=os.OpenFile(d.TokenFile+".lock",os.O_CREATE|os.O_RDWR,0600)
 if err!=nil{return err}
 defer lock.Close()
 if err:=syscall.Flock(int(lock.Fd()),syscall.LOCK_EX);err!=nil{return err}
 defer syscall.Flock(int(lock.Fd()),syscall.LOCK_UN)
 raw,err:=os.ReadFile(d.TokenFile)
 if err!=nil{return err} // no account registry: fail closed
 var root map[string]json.RawMessage
 if err:=json.Unmarshal(raw,&root);err!=nil{return err}
 if root==nil||root["accounts"]==nil{return ErrUnavailable}
 var devices []deviceEntry
 if len(root["tokens"])!=0{
  if err:=json.Unmarshal(root["tokens"],&devices);err!=nil{return err}
 }
 if err:=fn(root,&devices);err!=nil{return err}
 updated,err:=json.Marshal(devices)
 if err!=nil{return err}
 root["tokens"]=updated
 serialized,err:=json.MarshalIndent(root,"","  ")
 if err!=nil{return err}
 temp,err:=os.CreateTemp(filepath.Dir(d.TokenFile),".cal45-*")
 if err!=nil{return err}
 defer os.Remove(temp.Name())
 if err:=temp.Chmod(0600);err!=nil{temp.Close();return err}
 if _,err:=temp.Write(serialized);err!=nil{temp.Close();return err}
 if err:=temp.Sync();err!=nil{temp.Close();return err}
 if err:=temp.Close();err!=nil{return err}
 if err:=os.Rename(temp.Name(),d.TokenFile);err!=nil{return err}
 dir,err:=os.Open(filepath.Dir(d.TokenFile))
 if err==nil{defer dir.Close();return dir.Sync()}
 return nil
}
func(d *DevicePasswords)list(w http.ResponseWriter,r *http.Request){
 w.Header().Set("Cache-Control","no-store")
 entry,ok:=d.session(r,false)
 if !ok{fail(w,http.StatusUnauthorized);return}
 raw,err:=os.ReadFile(d.TokenFile)
 if err!=nil{fail(w,http.StatusServiceUnavailable);return}
 var db struct{Tokens []deviceEntry `json:"tokens"`}
 if json.Unmarshal(raw,&db)!=nil{fail(w,http.StatusServiceUnavailable);return}
 result:=make([]struct{
  ID string `json:"id"`
  Label string `json:"label"`
  Created string `json:"created"`
  Revoked bool `json:"revoked"`
 },0)
 for _,token:=range db.Tokens {
  if token.Username!=entry.username{continue}
  result=append(result,struct{
   ID string `json:"id"`
   Label string `json:"label"`
   Created string `json:"created"`
   Revoked bool `json:"revoked"`
  }{token.ID,token.Label,token.Created,token.Revoked})
 }
 w.Header().Set("Content-Type","application/json; charset=utf-8")
 _=json.NewEncoder(w).Encode(result)
}
func(d *DevicePasswords)create(w http.ResponseWriter,r *http.Request){
 w.Header().Set("Cache-Control","no-store")
 user,ok:=d.mutating(w,r)
 if !ok{return}
 var body struct{Label string `json:"label"`}
 if readObject(w,r,&body)!=nil{fail(w,http.StatusBadRequest);return}
 body.Label=strings.TrimSpace(body.Label)
 if len(body.Label)==0||len(body.Label)>80||strings.ContainsAny(body.Label,"\r\n"){
  fail(w,http.StatusBadRequest);return
 }
 raw:=make([]byte,32)
 if _,err:=rand.Read(raw);err!=nil{fail(w,http.StatusServiceUnavailable);return}
 password:="cal45_"+base64.RawURLEncoding.EncodeToString(raw)
 bytesID:=make([]byte,12)
 if _,err:=rand.Read(bytesID);err!=nil{fail(w,http.StatusServiceUnavailable);return}
 id:=base64.RawURLEncoding.EncodeToString(bytesID)
 hashed:=sha256.Sum256([]byte(password))
 item:=deviceEntry{
  ID:id,Username:user.username,Label:body.Label,
  SHA256:hex.EncodeToString(hashed[:]),
  Created:time.Now().UTC().Format(time.RFC3339),
 }
 err:=d.edit(func(root map[string]json.RawMessage,tokens *[]deviceEntry)error{
  if !d.Active(user.username){return ErrUnavailable}
  active:=0
  for _,old:=range *tokens {
   if old.Username==user.username&&!old.Revoked{active++}
  }
  if active>=20{return ErrUnavailable}
  *tokens=append(*tokens,item)
  return nil
 })
 if err!=nil{fail(w,http.StatusForbidden);return}
 w.Header().Set("Content-Type","application/json; charset=utf-8")
 w.WriteHeader(http.StatusCreated)
 _=json.NewEncoder(w).Encode(struct{
  ID string `json:"id"`
  Password string `json:"password"`
 }{id,password})
}
func(d *DevicePasswords)revoke(w http.ResponseWriter,r *http.Request){
 w.Header().Set("Cache-Control","no-store")
 user,ok:=d.mutating(w,r)
 if !ok{return}
 var body struct{ID string `json:"id"`}
 if readObject(w,r,&body)!=nil||len(body.ID)<1||len(body.ID)>64{
  fail(w,http.StatusBadRequest);return
 }
 err:=d.edit(func(root map[string]json.RawMessage,tokens *[]deviceEntry)error{
  if !d.Active(user.username){return ErrUnavailable}
  for i:=range *tokens{
   entry:=&(*tokens)[i]
   if entry.ID==body.ID&&entry.Username==user.username&&!entry.Revoked{
    entry.Revoked=true
    return nil
   }
  }
  return errors.New("device not found")
 })
 if err!=nil{fail(w,http.StatusNotFound);return}
 w.WriteHeader(http.StatusNoContent)
}
