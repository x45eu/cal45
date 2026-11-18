package main

import (
 "crypto/hmac"
 "crypto/rand"
 "crypto/sha1"
 "crypto/sha256"
 "crypto/subtle"
 "encoding/base32"
 "encoding/hex"
 "fmt"
 "strconv"
 "strings"
 "time"
)

// GenerateTOTPSecret generates a random 160-bit RFC 6238 shared secret.
func GenerateTOTPSecret() (string,error) {
 b:=make([]byte,20)
 if _,err:=rand.Read(b);err!=nil{return "",err}
 return base32.StdEncoding.WithPadding(base32.NoPadding).EncodeToString(b),nil
}
func totpAt(secret string,counter int64)(string,error){
 raw,err:=base32.StdEncoding.WithPadding(base32.NoPadding).DecodeString(strings.ToUpper(secret))
 if err!=nil||len(raw)<20||counter<0{return "",fmt.Errorf("invalid TOTP parameters")}
 var msg [8]byte
 for i:=7;i>=0;i--{msg[i]=byte(counter);counter>>=8}
 mac:=hmac.New(sha1.New,raw)
 mac.Write(msg[:])
 sum:=mac.Sum(nil)
 offset:=int(sum[len(sum)-1]&15)
 number:=uint32(sum[offset]&0x7f)<<24|uint32(sum[offset+1])<<16|uint32(sum[offset+2])<<8|uint32(sum[offset+3])
 return fmt.Sprintf("%06d",number%1000000),nil
}
// VerifyTOTP accepts +/- one 30s step. Persist the returned counter atomically;
// it must never be possible to authenticate with a consumed counter again.
func VerifyTOTP(secret,code string,now time.Time,lastCounter int64)(int64,bool){
 if len(code)!=6{return lastCounter,false}
 for _,r:=range code {if r<'0'||r>'9'{return lastCounter,false}}
 if _,err:=strconv.Atoi(code);err!=nil{return lastCounter,false}
 current:=now.Unix()/30
 for _,c:=range []int64{current-1,current,current+1}{
  if c<=lastCounter||c<0{continue}
  expected,err:=totpAt(secret,c)
  if err!=nil{return lastCounter,false}
  if subtle.ConstantTimeCompare([]byte(code),[]byte(expected))==1{return c,true}
 }
 return lastCounter,false
}
func NewRecoveryCodes(count int)([]string,[]string,error){
 if count<1||count>20{return nil,nil,fmt.Errorf("invalid code count")}
 codes:=make([]string,count);digests:=make([]string,count)
 for i:=range codes{
  raw:=make([]byte,16)
  if _,err:=rand.Read(raw);err!=nil{return nil,nil,err}
  codes[i]=strings.ToUpper(hex.EncodeToString(raw))
  digests[i]=RecoveryDigest(codes[i])
 }
 return codes,digests,nil
}
func RecoveryDigest(code string)string{
 normalized:=strings.ToUpper(strings.TrimSpace(strings.ReplaceAll(code,"-","")))
 hash:=sha256.Sum256([]byte(normalized))
 return hex.EncodeToString(hash[:])
}
// Caller must persist the reduced recovery set transactionally.
func ConsumeRecoveryCode(stored []string,code string)([]string,bool){
 code=strings.ToUpper(strings.TrimSpace(strings.ReplaceAll(code,"-","")))
 if len(code)!=32{return stored,false}
 for _,r:=range code{if !strings.ContainsRune("0123456789ABCDEF",r){return stored,false}}
 wanted:=RecoveryDigest(code)
 for i,digest:=range stored{
  if subtle.ConstantTimeCompare([]byte(wanted),[]byte(digest))==1 {
   next:=append([]string{},stored[:i]...)
   next=append(next,stored[i+1:]...)
   return next,true
  }
 }
 return stored,false
}
