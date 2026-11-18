package passkeys

import (
 "encoding/json"
 "os"
 "path/filepath"
 "testing"
 "sync"

 "github.com/go-webauthn/webauthn/webauthn"
)
func TestPersistentPasskeyAccountBoundary(t *testing.T){
 dir:=t.TempDir()
 auth:=filepath.Join(dir,"tokens.json")
 file:=filepath.Join(dir,"passkeys.json")
 repo,err:=NewJSONRepository(auth,file)
 if err!=nil{t.Fatal(err)}
 if err:=repo.InitializeHandle("alice");err==nil{t.Fatal("unknown account initialized")}
 accounts:=map[string]any{"accounts":[]map[string]any{{"username":"alice","active":true}}}
 encoded,_:=json.Marshal(accounts)
 if err:=os.WriteFile(auth,encoded,0600);err!=nil{t.Fatal(err)}
 if err:=repo.InitializeHandle("alice");err!=nil{t.Fatal(err)}
 u,err:=repo.GetActive("alice");if err!=nil||len(u.Handle)!=32{t.Fatal("handle not initialized")}
 reload,err:=NewJSONRepository(auth,file);if err!=nil{t.Fatal(err)}
 again,err:=reload.GetActive("alice");if err!=nil||string(again.Handle)!=string(u.Handle){t.Fatal("unstable handle")}
 creds:=&webauthn.Credential{ID:[]byte("key-1")}
 if err:=repo.SaveNewCredential("alice",creds);err!=nil{t.Fatal(err)}
 if err:=repo.SaveNewCredential("alice",creds);err==nil{t.Fatal("credential duplicate accepted")}
 if err:=repo.SaveVerifiedCredential("alice",creds);err!=nil{t.Fatal(err)}
 accounts=map[string]any{"accounts":[]map[string]any{{"username":"alice","active":false}}}
 encoded,_=json.Marshal(accounts)
 if err:=os.WriteFile(auth,encoded,0600);err!=nil{t.Fatal(err)}
 if _,err:=repo.GetActive("alice");err==nil{t.Fatal("disabled account returned")}
 if err:=repo.SaveNewCredential("alice",&webauthn.Credential{ID:[]byte("key-2")});err==nil{t.Fatal("disabled account modified")}
}

func TestFirstCredentialConcurrentEnrollment(t *testing.T){
 dir:=t.TempDir()
 accounts:=filepath.Join(dir,"tokens.json")
 if err:=os.WriteFile(accounts,[]byte(`{"accounts":[{"username":"alice","active":true}]}`),0600);err!=nil{t.Fatal(err)}
 store,err:=NewJSONRepository(accounts,filepath.Join(dir,"passkeys.json"))
 if err!=nil{t.Fatal(err)}
 if err:=store.InitializeHandle("alice");err!=nil{t.Fatal(err)}
 var wg sync.WaitGroup
 successes:=make(chan bool,20)
 for n:=0;n<20;n++{
  wg.Add(1)
  go func(i int){
   defer wg.Done()
   cred:=&webauthn.Credential{ID:[]byte{byte(i+1)}}
   successes<-store.SaveFirstCredential("alice",cred)==nil
  }(n)
 }
 wg.Wait()
 close(successes)
 count:=0
 for ok:=range successes{if ok{count++}}
 if count!=1{t.Fatalf("concurrent initial registrations succeeded %d times",count)}
 user,err:=store.GetActive("alice")
 if err!=nil||len(user.Credentials)!=1{t.Fatal("first registration not durable")}
 if err:=store.SaveFirstCredential("alice",&webauthn.Credential{ID:[]byte("other")});err==nil{
  t.Fatal("second bootstrap credential accepted")
 }
}

func TestPasskeyCounterRollbackToZeroDenied(t *testing.T){
 dir:=t.TempDir()
 auth:=filepath.Join(dir,"tokens.json")
 if err:=os.WriteFile(auth,[]byte(`{"accounts":[{"username":"alice","active":true}]}`),0600);err!=nil{t.Fatal(err)}
 store,err:=NewJSONRepository(auth,filepath.Join(dir,"passkeys.json"))
 if err!=nil{t.Fatal(err)}
 if err:=store.InitializeHandle("alice");err!=nil{t.Fatal(err)}
 initial:=&webauthn.Credential{ID:[]byte("test-credential")}
 initial.Authenticator.SignCount=7
 if err:=store.SaveFirstCredential("alice",initial);err!=nil{t.Fatal(err)}
 reset:=&webauthn.Credential{ID:[]byte("test-credential")}
 reset.Authenticator.SignCount=0
 if err:=store.SaveVerifiedCredential("alice",reset);err==nil{t.Fatal("counter rollback to zero accepted")}
 repeated:=&webauthn.Credential{ID:[]byte("test-credential")}
 repeated.Authenticator.SignCount=7
 if err:=store.SaveVerifiedCredential("alice",repeated);err==nil{t.Fatal("counter reuse accepted")}
 valid:=&webauthn.Credential{ID:[]byte("test-credential")}
 valid.Authenticator.SignCount=8
 if err:=store.SaveVerifiedCredential("alice",valid);err!=nil{t.Fatal(err)}
}
