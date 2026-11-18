package main

import (
 "testing"
 "time"
)

func TestRFC6238AndReplay(t *testing.T){
 // RFC 6238 SHA-1 vector at second 59 gives the last six digits 287082.
 secret:="GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
 code,err:=totpAt(secret,1)
 if err!=nil||code!="287082"{t.Fatalf("code %q err %v",code,err)}
 counter,ok:=VerifyTOTP(secret,code,time.Unix(59,0),-1)
 if !ok||counter!=1{t.Fatal("valid TOTP rejected")}
 if _,ok:=VerifyTOTP(secret,code,time.Unix(59,0),counter);ok{t.Fatal("TOTP replay accepted")}
 if _,ok:=VerifyTOTP(secret,"foobar",time.Unix(59,0),-1);ok{t.Fatal("malformed TOTP accepted")}
}
func TestRecoverySingleUse(t *testing.T){
 codes,digests,err:=NewRecoveryCodes(10);if err!=nil{t.Fatal(err)}
 if len(codes)!=10||len(digests)!=10{t.Fatal("wrong recovery count")}
 after,ok:=ConsumeRecoveryCode(digests,codes[0])
 if !ok||len(after)!=9{t.Fatal("first recovery use rejected")}
 if _,ok:=ConsumeRecoveryCode(after,codes[0]);ok{t.Fatal("recovery code reused")}
}
func TestSecretGeneration(t *testing.T){
 secret,err:=GenerateTOTPSecret();if err!=nil{t.Fatal(err)}
 if len(secret)!=32{t.Fatal("incorrect TOTP secret length")}
}
