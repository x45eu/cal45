package passkeys

import (
 "encoding/json"
 "os"
 "path/filepath"
 "testing"

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
