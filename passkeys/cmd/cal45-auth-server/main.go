package main

import (
 "encoding/json"
 "log"
 "net/http"
 "os"
 "strings"
 "time"

 "github.com/x45eu/cal45/passkeys"
)

func main(){
 origin:=os.Getenv("CAL45_AUTH_ORIGIN")
 if origin=="" {log.Fatal("CAL45_AUTH_ORIGIN HTTPS domain must be explicitly configured")}
 bind:=os.Getenv("CAL45_AUTH_BIND")
 if strings.TrimSpace(bind)=="" {bind="127.0.0.1:8082"}
 accountFile:=os.Getenv("CAL45_TOKEN_FILE")
 if accountFile==""{accountFile="/var/lib/cal45/tokens.json"}
 passkeyFile:=os.Getenv("CAL45_PASSKEY_FILE")
 if passkeyFile==""{passkeyFile="/var/lib/cal45/passkeys.json"}
 bootstrapFile:=os.Getenv("CAL45_BOOTSTRAP_FILE")
 if bootstrapFile==""{bootstrapFile="/var/lib/cal45/bootstrap.json"}
 repo,err:=passkeys.NewJSONRepository(accountFile,passkeyFile)
 if err!=nil{log.Fatal(err)}
 active:=func(username string)bool{
  raw,err:=os.ReadFile(accountFile);if err!=nil{return false}
  var accounts struct{Accounts []struct{
   Username string `json:"username"`
   Active bool `json:"active"`
  } `json:"accounts"`}
  if json.Unmarshal(raw,&accounts)!=nil{return false}
  for _,a:=range accounts.Accounts {
   if a.Username==username{return a.Active}
  }
  return false
 }
 grants:=passkeys.BootstrapStore{Path:bootstrapFile,AccountIsActive:active}
 handler,err:=passkeys.NewAuthHandler(origin,repo,grants)
 if err!=nil{log.Fatal(err)}
 log.Printf("Cal45 auth service listening at %s for origin %s",bind,origin)
 log.Fatal((&http.Server{
  Addr:bind,Handler:handler,
  ReadHeaderTimeout:5*time.Second,
  ReadTimeout:30*time.Second,
  IdleTimeout:60*time.Second,
 }).ListenAndServe())
}
