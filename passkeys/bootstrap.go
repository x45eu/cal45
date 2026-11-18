package passkeys

import (
 "crypto/rand"
 "crypto/sha256"
 "crypto/subtle"
 "encoding/base64"
 "encoding/hex"
 "encoding/json"
 "errors"
 "fmt"
 "os"
 "path/filepath"
 "strings"
 "syscall"
 "time"
)

// BootstrapGrant is a server-operator-created, one-time authorization to
// enroll an initial passkey. It is NOT itself a web session or a credential.
type BootstrapGrant struct {
 Username string `json:"username"`
 Digest string `json:"digest"`
 Expires time.Time `json:"expires"`
 Consumed bool `json:"consumed"`
}
type bootstrapFile struct {
 Grants []BootstrapGrant `json:"grants"`
}
type BootstrapStore struct {
 Path string
 AccountIsActive func(string)bool
 Now func()time.Time
}
func(s BootstrapStore)currentTime()time.Time{
 if s.Now!=nil{return s.Now()}
 return time.Now()
}
func(s BootstrapStore)modify(fn func(*bootstrapFile)error)error{
 if s.Path==""||s.AccountIsActive==nil{return ErrUnavailable}
 if err:=os.MkdirAll(filepath.Dir(s.Path),0700);err!=nil{return err}
 f,err:=os.OpenFile(s.Path+".lock",os.O_CREATE|os.O_RDWR,0600)
 if err!=nil{return err}
 defer f.Close()
 if err:=syscall.Flock(int(f.Fd()),syscall.LOCK_EX);err!=nil{return err}
 defer syscall.Flock(int(f.Fd()),syscall.LOCK_UN)
 var data bootstrapFile
 raw,err:=os.ReadFile(s.Path)
 if err==nil{
  if json.Unmarshal(raw,&data)!=nil{return ErrUnavailable}
 }else if !errors.Is(err,os.ErrNotExist){return err}
 if err:=fn(&data);err!=nil{return err}
 serialized,err:=json.MarshalIndent(data,"","  ")
 if err!=nil{return err}
 temp,err:=os.CreateTemp(filepath.Dir(s.Path),".bootstrap-*")
 if err!=nil{return err}
 defer os.Remove(temp.Name())
 if err:=temp.Chmod(0600);err!=nil{temp.Close();return err}
 if _,err:=temp.Write(serialized);err!=nil{temp.Close();return err}
 if err:=temp.Sync();err!=nil{temp.Close();return err}
 if err:=temp.Close();err!=nil{return err}
 if err:=os.Rename(temp.Name(),s.Path);err!=nil{return err}
 dir,err:=os.Open(filepath.Dir(s.Path))
 if err==nil{defer dir.Close();return dir.Sync()}
 return nil
}
func bootstrapDigest(token string)string{
 sum:=sha256.Sum256([]byte(token))
 return hex.EncodeToString(sum[:])
}
func validGrantUser(username string)bool{
 if len(username)==0||len(username)>128||strings.Contains(username,".."){return false}
 for _,r:=range username{
  if !((r>='a'&&r<='z')||(r>='A'&&r<='Z')||(r>='0'&&r<='9')||r=='_'||r=='-'||r=='.'||r=='@'){return false}
 }
 return true
}
// Issue is intended for a local operator-only CLI, never an open HTTP route.
// The plaintext token is returned once; its hash is persisted for ten minutes.
func(s BootstrapStore)Issue(username string)(string,error){
 if !validGrantUser(username)||s.AccountIsActive==nil||!s.AccountIsActive(username){return "",ErrUnavailable}
 raw:=make([]byte,32)
 if _,err:=rand.Read(raw);err!=nil{return "",err}
 token:="cal45_setup_"+base64.RawURLEncoding.EncodeToString(raw)
 err:=s.modify(func(data *bootstrapFile)error{
  if !s.AccountIsActive(username){return ErrUnavailable}
  now:=s.currentTime()
  // Supersede earlier grants for this account, limiting exposure.
  active:=make([]BootstrapGrant,0,len(data.Grants)+1)
  for _,grant:=range data.Grants{
   if grant.Username!=username&&!grant.Consumed&&now.Before(grant.Expires){active=append(active,grant)}
  }
  data.Grants=append(active,BootstrapGrant{
   Username:username,Digest:bootstrapDigest(token),
   Expires:now.Add(10*time.Minute),
  })
  return nil
 })
 if err!=nil{return "",err}
 return token,nil
}
// Consume confirms account ownership of the one-time grant, atomically.
// A registration handler must also bind the resulting ceremony to an opaque
// Secure/HttpOnly browser cookie and avoid granting access until WebAuthn
// FinishRegistration validates and saves a credential.
func(s BootstrapStore)Consume(username,token string)bool{
 if !validGrantUser(username)||len(token)!=55||!strings.HasPrefix(token,"cal45_setup_")||
  s.AccountIsActive==nil||!s.AccountIsActive(username){return false}
 wanted:=bootstrapDigest(token)
 accepted:=false
 err:=s.modify(func(data *bootstrapFile)error{
  if !s.AccountIsActive(username){return ErrUnavailable}
  for i:=range data.Grants{
   g:=&data.Grants[i]
   if g.Username!=username||g.Consumed||!s.currentTime().Before(g.Expires){continue}
   if subtle.ConstantTimeCompare([]byte(g.Digest),[]byte(wanted))==1{
    g.Consumed=true
    accepted=true
   }
  }
  return nil
 })
 return err==nil&&accepted
}
func(s BootstrapStore)RevokeAll(username string)error{
 if !validGrantUser(username){return fmt.Errorf("invalid username")}
 return s.modify(func(data *bootstrapFile)error{
  for i:=range data.Grants{if data.Grants[i].Username==username{data.Grants[i].Consumed=true}}
  return nil
 })
}
