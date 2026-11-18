package main

import (
 "path/filepath"
 "strings"
 "testing"
)

func TestAccountRequiredForDeviceTokens(t *testing.T) {
 path:=filepath.Join(t.TempDir(),"tokens.json")
 t.Setenv("CAL45_TOKEN_FILE",path)
 if err:=tokenCLI([]string{"issue","alice","Pixel"});err==nil {t.Fatal("token issued for unknown user")}
 if err:=accountCLI([]string{"add","alice"});err!=nil {t.Fatal(err)}
 if err:=accountCLI([]string{"add","alice"});err==nil {t.Fatal("duplicate account accepted")}
 if err:=accountCLI([]string{"disable","alice"});err!=nil {t.Fatal(err)}
 if err:=tokenCLI([]string{"issue","alice","Tablet"});err==nil {t.Fatal("disabled user issued a token")}
 if verifyToken(path,"alice","cal45_"+strings.Repeat("A",43)) {t.Fatal("disabled user authenticated")}
 if err:=accountCLI([]string{"enable","alice"});err!=nil {t.Fatal(err)}
}
func TestAccountLifecycleDisablesExistingToken(t *testing.T) {
 path:=filepath.Join(t.TempDir(),"tokens.json")
 password:="cal45_"+strings.Repeat("A",43)
 if err:=updateStore(path,func(db *tokenStore)error {
  db.Accounts=append(db.Accounts,account{Username:"alice",Active:true})
  db.Tokens=append(db.Tokens,deviceToken{ID:"1",Username:"alice",SHA256:tokenHash(password)})
  return nil
 });err!=nil {t.Fatal(err)}
 if !verifyToken(path,"alice",password) {t.Fatal("active user refused")}
 t.Setenv("CAL45_TOKEN_FILE",path)
 if err:=accountCLI([]string{"disable","alice"});err!=nil {t.Fatal(err)}
 if verifyToken(path,"alice",password) {t.Fatal("disabled user authenticated")}
 if err:=accountCLI([]string{"enable","alice"});err!=nil {t.Fatal(err)}
 if !verifyToken(path,"alice",password) {t.Fatal("reenabled user cannot authenticate")}
}
