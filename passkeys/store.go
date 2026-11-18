package passkeys

import (
 "bytes"
 "crypto/rand"
 "encoding/json"
 "errors"
 "fmt"
 "os"
 "path/filepath"
 "sync"
 "syscall"

 "github.com/go-webauthn/webauthn/webauthn"
)

type diskData struct{ Users map[string]User `json:"users"` }
type accountAllowlist struct {
 Accounts []struct {
  Username string `json:"username"`
  Active bool `json:"active"`
 } `json:"accounts"`
}

// JSONRepository is a persistent single-host implementation. It checks the
// existing Cal45 account allowlist before every credential operation and
// atomically persists counter updates. Both files must be on trusted storage.
// Not for use with multiple hosts or a network filesystem.
type JSONRepository struct {
 mu sync.Mutex
 accounts string
 passkeys string
}
func NewJSONRepository(accountStore,passkeyStore string)(*JSONRepository,error){
 if accountStore==""||passkeyStore==""||accountStore==passkeyStore{return nil,ErrUnavailable}
 return &JSONRepository{accounts:accountStore,passkeys:passkeyStore},nil
}
func(r *JSONRepository)accountActive(username string)bool{
 data,err:=os.ReadFile(r.accounts)
 if err!=nil{return false}
 var db accountAllowlist
 if json.Unmarshal(data,&db)!=nil{return false}
 for _,account:=range db.Accounts {if account.Username==username{return account.Active}}
 return false
}
func(r *JSONRepository)read()(diskData,error){
 var db diskData
 data,err:=os.ReadFile(r.passkeys)
 if errors.Is(err,os.ErrNotExist){db.Users=make(map[string]User);return db,nil}
 if err!=nil{return db,err}
 if err:=json.Unmarshal(data,&db);err!=nil{return db,err}
 if db.Users==nil{db.Users=make(map[string]User)}
 return db,nil
}
func(r *JSONRepository)write(db diskData)error{
 if err:=os.MkdirAll(filepath.Dir(r.passkeys),0700);err!=nil{return err}
 data,err:=json.MarshalIndent(db,"","  ")
 if err!=nil{return err}
 temp,err:=os.CreateTemp(filepath.Dir(r.passkeys),".passkeys-*")
 if err!=nil{return err}
 defer os.Remove(temp.Name())
 if err:=temp.Chmod(0600);err!=nil{temp.Close();return err}
 if _,err:=temp.Write(data);err!=nil{temp.Close();return err}
 if err:=temp.Sync();err!=nil{temp.Close();return err}
 if err:=temp.Close();err!=nil{return err}
 if err:=os.Rename(temp.Name(),r.passkeys);err!=nil{return err}
 dir,err:=os.Open(filepath.Dir(r.passkeys))
 if err==nil{defer dir.Close();return dir.Sync()}
 return nil
}
func(r *JSONRepository)change(fn func(*diskData)error)error{
 r.mu.Lock()
 defer r.mu.Unlock()
 if err:=os.MkdirAll(filepath.Dir(r.passkeys),0700);err!=nil{return err}
 lock,err:=os.OpenFile(r.passkeys+".lock",os.O_CREATE|os.O_RDWR,0600)
 if err!=nil{return err}
 defer lock.Close()
 if err:=syscall.Flock(int(lock.Fd()),syscall.LOCK_EX);err!=nil{return err}
 defer syscall.Flock(int(lock.Fd()),syscall.LOCK_UN)
 db,err:=r.read();if err!=nil{return err}
 if err:=fn(&db);err!=nil{return err}
 return r.write(db)
}
// InitializeHandle must be called only after the operator has provisioned
// the Cal45 account. This does not enroll a credential or authenticate anyone.
func(r *JSONRepository)InitializeHandle(username string)error{
 if !r.accountActive(username){return ErrUnavailable}
 return r.change(func(db *diskData)error{
  if !r.accountActive(username){return ErrUnavailable}
  if _,ok:=db.Users[username];ok{return nil}
  handle:=make([]byte,32)
  if _,err:=rand.Read(handle);err!=nil{return err}
  db.Users[username]=User{Username:username,Handle:handle}
  return nil
 })
}
func(r *JSONRepository)GetActive(username string)(User,error){
 if !r.accountActive(username){return User{},ErrUnavailable}
 r.mu.Lock();defer r.mu.Unlock()
 db,err:=r.read();if err!=nil{return User{},err}
 u,ok:=db.Users[username]
 if !ok||len(u.Handle)!=32||!r.accountActive(username){return User{},ErrUnavailable}
 return u,nil
}
func(r *JSONRepository)SaveNewCredential(username string,credential *webauthn.Credential)error{
 if credential==nil||len(credential.ID)==0{return ErrUnavailable}
 return r.change(func(db *diskData)error{
  if !r.accountActive(username){return ErrUnavailable}
  u,ok:=db.Users[username]
  if !ok||len(u.Handle)!=32||len(u.Credentials)>=MaxCredentials{return ErrUnavailable}
  for _,other:=range db.Users {
   for _,c:=range other.Credentials{
    if bytes.Equal(c.ID,credential.ID){return fmt.Errorf("credential already registered")}
   }
  }
  u.Credentials=append(u.Credentials,*credential)
  db.Users[username]=u
  return nil
 })
}
func(r *JSONRepository)SaveVerifiedCredential(username string,credential *webauthn.Credential)error{
 if credential==nil||len(credential.ID)==0{return ErrUnavailable}
 return r.change(func(db *diskData)error{
  if !r.accountActive(username){return ErrUnavailable}
  u,ok:=db.Users[username];if !ok{return ErrUnavailable}
  found:=false
  for i:=range u.Credentials{
   if bytes.Equal(u.Credentials[i].ID,credential.ID){
    // Never allow a previously advancing signature counter to reset to
    // zero or repeat. Passkeys with no counter (0 -> 0) remain supported.
    previous:=u.Credentials[i].Authenticator.SignCount
    incoming:=credential.Authenticator.SignCount
    if previous>0&&incoming<=previous {return ErrUnavailable}
    u.Credentials[i]=*credential
    found=true
    break
   }
  }
  if !found{return ErrUnavailable}
  db.Users[username]=u
  return nil
 })
}

// SaveFirstCredential guarantees first-passkey enrollment is atomic.
// Even two concurrent bootstrap ceremonies cannot register two initial keys.
func(r *JSONRepository)SaveFirstCredential(username string,credential *webauthn.Credential)error{
 if credential==nil||len(credential.ID)==0{return ErrUnavailable}
 return r.change(func(db *diskData)error{
  if !r.accountActive(username){return ErrUnavailable}
  u,ok:=db.Users[username]
  if !ok||len(u.Handle)!=32||len(u.Credentials)!=0{return ErrUnavailable}
  for _,other:=range db.Users {
   for _,c:=range other.Credentials {
    if bytes.Equal(c.ID,credential.ID){return ErrUnavailable}
   }
  }
  u.Credentials=[]webauthn.Credential{*credential}
  db.Users[username]=u
  return nil
 })
}
