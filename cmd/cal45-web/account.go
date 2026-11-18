package main

import (
 "fmt"
 "time"
)

// Operator-only account provisioning. WebAuthn/TOTP will later operate on
// this same identity registry; no public endpoint exposes these commands.
func accountCLI(args []string) error {
 if len(args)==0 {return fmt.Errorf("use account add USER | disable USER | enable USER | list")}
 switch args[0] {
 case "add":
  if len(args)!=2 || !allowedUsername(args[1]) {return fmt.Errorf("use account add USER")}
  return updateStore(tokenPath(),func(db *tokenStore)error {
   for _,a:=range db.Accounts {if a.Username==args[1] {return fmt.Errorf("account already exists")}}
   db.Accounts=append(db.Accounts,account{Username:args[1],Active:true,Created:time.Now().UTC().Format(time.RFC3339)})
   fmt.Println("Account created:",args[1])
   return nil
  })
 case "disable","enable":
  if len(args)!=2 || !allowedUsername(args[1]) {return fmt.Errorf("use account disable USER | enable USER")}
  return updateStore(tokenPath(),func(db *tokenStore)error {
   for i:=range db.Accounts {
    if db.Accounts[i].Username==args[1] {
     db.Accounts[i].Active=args[0]=="enable"
     fmt.Println("Account updated:",args[1])
     return nil
    }
   }
   return fmt.Errorf("account not found")
  })
 case "list":
  if len(args)!=1 {return fmt.Errorf("use account list")}
  db,err:=readStore(tokenPath());if err!=nil{return err}
  for _,a:=range db.Accounts {fmt.Printf("%s  active=%t  created=%s\n",a.Username,a.Active,a.Created)}
  return nil
 default: return fmt.Errorf("unknown account command")
 }
}
