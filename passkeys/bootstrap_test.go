package passkeys

import (
 "path/filepath"
 "sync"
 "testing"
 "time"
)
func TestBootstrapOneTimeAndAccountBinding(t *testing.T){
 active:=map[string]bool{"alice":true,"bob":true}
 now:=time.Date(2026,11,18,17,0,0,0,time.UTC)
 store:=BootstrapStore{
  Path:filepath.Join(t.TempDir(),"bootstrap.json"),
  AccountIsActive:func(user string)bool{return active[user]},
  Now:func()time.Time{return now},
 }
 if _,err:=store.Issue("unknown");err==nil{t.Fatal("unknown account issued setup token")}
 token,err:=store.Issue("alice");if err!=nil{t.Fatal(err)}
 if len(token)!=55{t.Fatalf("unexpected token length %d",len(token))}
 if store.Consume("bob",token){t.Fatal("cross-account bootstrap accepted")}
 if !store.Consume("alice",token){t.Fatal("valid bootstrap rejected")}
 if store.Consume("alice",token){t.Fatal("bootstrap replay accepted")}
 newer,err:=store.Issue("alice");if err!=nil{t.Fatal(err)}
 active["alice"]=false
 if store.Consume("alice",newer){t.Fatal("disabled user accepted")}
 active["alice"]=true
 now=now.Add(11*time.Minute)
 if store.Consume("alice",newer){t.Fatal("expired setup token accepted")}
}
func TestBootstrapSupersededAndRevoked(t *testing.T){
 now:=time.Now()
 store:=BootstrapStore{Path:filepath.Join(t.TempDir(),"bootstrap.json"),
  AccountIsActive:func(user string)bool{return user=="alice"},
  Now:func()time.Time{return now},
 }
 first,err:=store.Issue("alice");if err!=nil{t.Fatal(err)}
 second,err:=store.Issue("alice");if err!=nil{t.Fatal(err)}
 if store.Consume("alice",first){t.Fatal("superseded grant still valid")}
 if err:=store.RevokeAll("alice");err!=nil{t.Fatal(err)}
 if store.Consume("alice",second){t.Fatal("revoked grant still valid")}
}
func TestConcurrentBootstrapConsumeOnlyOnce(t *testing.T){
 store:=BootstrapStore{Path:filepath.Join(t.TempDir(),"bootstrap.json"),
  AccountIsActive:func(user string)bool{return user=="alice"},
 }
 token,err:=store.Issue("alice");if err!=nil{t.Fatal(err)}
 var group sync.WaitGroup
 results:=make(chan bool,12)
 for i:=0;i<12;i++{
  group.Add(1)
  go func(){defer group.Done();results<-store.Consume("alice",token)}()
 }
 group.Wait()
 close(results)
 count:=0
 for ok:=range results{if ok{count++}}
 if count!=1{t.Fatalf("grant accepted %d times",count)}
}
