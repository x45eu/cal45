package passkeys

import (
 "crypto/rand"
 "crypto/sha256"
 "encoding/base64"
 "errors"
 "net/http"
 "sync"
 "time"

 "github.com/go-webauthn/webauthn/protocol"
 "github.com/go-webauthn/webauthn/webauthn"
)

var ErrUnavailable=errors.New("passkey operation unavailable")
const MaxCredentials=10

// User is a persistent Cal45 account's WebAuthn identity. ID must be a
// cryptographically random, stable handle (not a username or an email).
type User struct {
 Handle []byte `json:"handle"`
 Username string `json:"username"`
 Credentials []webauthn.Credential `json:"credentials"`
}
func(u User)WebAuthnID()[]byte{return u.Handle}
func(u User)WebAuthnName()string{return u.Username}
func(u User)WebAuthnDisplayName()string{return u.Username}
func(u User)WebAuthnCredentials()[]webauthn.Credential{return u.Credentials}

// Repository must implement atomic durable credential updates.
// The public server has to check account state on every operation.
type Repository interface {
 GetActive(username string)(User,error)
 SaveNewCredential(username string,credential *webauthn.Credential)error
 SaveVerifiedCredential(username string,credential *webauthn.Credential)error
}

type pending struct {
 username string
 bound [32]byte
 ceremony string
 data webauthn.SessionData
 expires time.Time
}
type Service struct {
 verifier *webauthn.WebAuthn
 repo Repository
 mu sync.Mutex
 ceremonies map[[32]byte]pending
 now func()time.Time
}
func New(origin string,repository Repository)(*Service,error){
 rp,err:=ValidOrigin(origin);if err!=nil{return nil,err}
 if repository==nil{return nil,ErrUnavailable}
 w,err:=webauthn.New(&webauthn.Config{
  RPID:rp,RPDisplayName:"Cal45",RPOrigins:[]string{origin},
  AuthenticatorSelection:protocol.AuthenticatorSelection{
   UserVerification:protocol.VerificationRequired,
  },
 })
 if err!=nil{return nil,err}
 return &Service{verifier:w,repo:repository,ceremonies:make(map[[32]byte]pending),now:time.Now},nil
}
func(s *Service)remember(user,bind,ceremony string,data webauthn.SessionData)(string,error){
 if len(bind)<32{return "",ErrUnavailable}
 raw:=make([]byte,32)
 if _,err:=rand.Read(raw);err!=nil{return "",err}
 id:=sha256.Sum256(raw)
 s.mu.Lock()
 defer s.mu.Unlock()
 now:=s.now()
 for k,v:=range s.ceremonies{if !now.Before(v.expires){delete(s.ceremonies,k)}}
 if len(s.ceremonies)>=1024{return "",ErrUnavailable}
 s.ceremonies[id]=pending{
  username:user,bound:sha256.Sum256([]byte(bind)),ceremony:ceremony,
  data:data,expires:now.Add(120*time.Second),
 }
 return base64.RawURLEncoding.EncodeToString(raw),nil
}
func(s *Service)consume(handle,user,bind,ceremony string)(webauthn.SessionData,error){
 raw,err:=base64.RawURLEncoding.DecodeString(handle)
 if err!=nil||len(raw)!=32{return webauthn.SessionData{},ErrUnavailable}
 id:=sha256.Sum256(raw)
 s.mu.Lock()
 p,ok:=s.ceremonies[id]
 delete(s.ceremonies,id)
 now:=s.now()
 s.mu.Unlock()
 if !ok||!now.Before(p.expires)||p.username!=user||p.ceremony!=ceremony||
  len(bind)<32||p.bound!=sha256.Sum256([]byte(bind)){
  return webauthn.SessionData{},ErrUnavailable
 }
 return p.data,nil
}

// BeginRegistration must be called only after strong account verification.
// An operator bootstrap grant may be used for the very first passkey.
func(s *Service)BeginRegistration(username,verifiedSession string)(*protocol.CredentialCreation,string,error){
 user,err:=s.repo.GetActive(username)
 if err!=nil||len(user.Handle)<16||len(user.Handle)>64||len(user.Credentials)>=MaxCredentials{return nil,"",ErrUnavailable}
 options,data,err:=s.verifier.BeginRegistration(user)
 if err!=nil{return nil,"",err}
 handle,err:=s.remember(username,verifiedSession,"register",*data)
 return options,handle,err
}
func(s *Service)FinishRegistration(username,verifiedSession,handle string,r *http.Request)error{
 data,err:=s.consume(handle,username,verifiedSession,"register")
 if err!=nil{return err}
 user,err:=s.repo.GetActive(username);if err!=nil{return ErrUnavailable}
 credential,err:=s.verifier.FinishRegistration(user,data,r)
 if err!=nil{return err}
 return s.repo.SaveNewCredential(username,credential)
}
func(s *Service)BeginLogin(username,loginBinding string)(*protocol.CredentialAssertion,string,error){
 user,err:=s.repo.GetActive(username)
 if err!=nil||len(user.Credentials)==0{return nil,"",ErrUnavailable}
 assertion,data,err:=s.verifier.BeginLogin(user)
 if err!=nil{return nil,"",err}
 handle,err:=s.remember(username,loginBinding,"login",*data)
 return assertion,handle,err
}
// FinishLogin returns the authenticated username only after the external
// WebAuthn library verifies the authenticator signature and user verification.
// Caller must apply MFA policy before minting a full administrative session.
func(s *Service)FinishLogin(username,loginBinding,handle string,r *http.Request)(string,error){
 data,err:=s.consume(handle,username,loginBinding,"login")
 if err!=nil{return "",err}
 user,err:=s.repo.GetActive(username);if err!=nil{return "",ErrUnavailable}
 credential,err:=s.verifier.FinishLogin(user,data,r)
 if err!=nil{return "",err}
 if err:=s.repo.SaveVerifiedCredential(username,credential);err!=nil{return "",err}
 return username,nil
}

// FinishInitialRegistration is reserved for the operator bootstrap flow.
// The backing repository must atomically reject a second initial credential.
func(s *Service)FinishInitialRegistration(username,binding,handle string,r *http.Request)error{
 data,err:=s.consume(handle,username,binding,"register")
 if err!=nil{return err}
 repo,ok:=s.repo.(interface{
  SaveFirstCredential(string,*webauthn.Credential)error
 })
 if !ok{return ErrUnavailable}
 user,err:=s.repo.GetActive(username)
 if err!=nil||len(user.Credentials)!=0{return ErrUnavailable}
 credential,err:=s.verifier.FinishRegistration(user,data,r)
 if err!=nil{return err}
 return repo.SaveFirstCredential(username,credential)
}
