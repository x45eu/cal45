package main

import (
 "encoding/json"
 "fmt"
 "os"

 "github.com/x45eu/cal45/passkeys"
)

// Operator utility; never run on a public unauthenticated web request.
// For use in a protected container with access to the Cal45 auth volume.
func main(){
 if len(os.Args)<3 {usage()}
 auth:=os.Getenv("CAL45_TOKEN_FILE")
 if auth=="" {auth="/var/lib/cal45/tokens.json"}
 grants:=os.Getenv("CAL45_BOOTSTRAP_FILE")
 if grants=="" {grants="/var/lib/cal45/bootstrap.json"}
 s:=passkeys.BootstrapStore{
  Path:grants,
  AccountIsActive:func(username string)bool{
   raw,err:=os.ReadFile(auth);if err!=nil{return false}
   var data struct{Accounts []struct{
    Username string `json:"username"`
    Active bool `json:"active"`
   } `json:"accounts"`}
   if json.Unmarshal(raw,&data)!=nil{return false}
   for _,account:=range data.Accounts {
    if account.Username==username{return account.Active}
   }
   return false
  },
 }
 username:=os.Args[2]
 switch os.Args[1] {
 case "issue":
  token,err:=s.Issue(username)
  if err!=nil{fmt.Fprintln(os.Stderr,"Cal45 bootstrap issuance failed:",err);os.Exit(1)}
  fmt.Println("User:",username)
  fmt.Println("Expires in: 10 minutes")
  fmt.Println("One-time setup token:",token)
 case "revoke":
  if err:=s.RevokeAll(username);err!=nil{fmt.Fprintln(os.Stderr,err);os.Exit(1)}
  fmt.Println("Setup grants revoked:",username)
 default:usage()
 }
}
func usage(){
 fmt.Fprintln(os.Stderr,"usage: cal45-passkey-bootstrap issue USER | revoke USER")
 os.Exit(2)
}
