package passkeys

import (
 "encoding/json"
 "crypto/sha256"
 "encoding/hex"
 "net/http"
 "net/http/httptest"
 "os"
 "path/filepath"
 "strings"
 "testing"
 "time"
)
func deviceFixture(t *testing.T)(*DevicePasswords,string,string){
 t.Helper()
 file:=filepath.Join(t.TempDir(),"tokens.json")
 if err:=os.WriteFile(file,[]byte(`{"accounts":[{"username":"alice","active":true},{"username":"bob","active":true}],"tokens":[]}`),0600);err!=nil{t.Fatal(err)}
 sessions:=NewBrowserSessions()
 token,csrf,err:=sessions.issue("alice")
 if err!=nil{t.Fatal(err)}
 d:=&DevicePasswords{TokenFile:file,Sessions:sessions,Origin:"https://cal45.example.org",
  Active:func(name string)bool{return name=="alice"||name=="bob"}}
 return d,token,csrf
}
func sendDeviceRequest(mux *http.ServeMux,path,body,token,csrf string)*httptest.ResponseRecorder{
 req:=httptest.NewRequest("POST",path,strings.NewReader(body))
 req.Header.Set("Content-Type","application/json")
 req.Header.Set("Origin","https://cal45.example.org")
 req.Header.Set("X-Cal45-CSRF",csrf)
 req.AddCookie(newSessionCookie(token))
 recorder:=httptest.NewRecorder()
 mux.ServeHTTP(recorder,req)
 return recorder
}
func TestDevicePasswordCreateListAndRevoke(t *testing.T){
 device,session,csrf:=deviceFixture(t)
 mux:=http.NewServeMux()
 if err:=device.Register(mux);err!=nil{t.Fatal(err)}
 bad:=sendDeviceRequest(mux,"/auth/devices/create",`{"label":"Pixel 9a"}`,session,"bad")
 if bad.Code!=403{t.Fatal("missing CSRF accepted")}
 good:=sendDeviceRequest(mux,"/auth/devices/create",`{"label":"Pixel 9a"}`,session,csrf)
 if good.Code!=201{t.Fatalf("create failed %d: %s",good.Code,good.Body.String())}
 var issued struct{ID,Password string}
 if err:=json.Unmarshal(good.Body.Bytes(),&issued);err!=nil{t.Fatal(err)}
 if !strings.HasPrefix(issued.Password,"cal45_")||len(issued.Password)!=49{
  t.Fatalf("invalid token format: %d",len(issued.Password))
 }
 raw,err:=os.ReadFile(device.TokenFile);if err!=nil{t.Fatal(err)}
 if strings.Contains(string(raw),issued.Password){t.Fatal("plaintext token persisted")}
 var persisted struct{Tokens []deviceEntry `json:"tokens"`}
 if err:=json.Unmarshal(raw,&persisted);err!=nil{t.Fatal(err)}
 if len(persisted.Tokens)!=1{t.Fatal("expected one issued token")}
 sum:=sha256.Sum256([]byte(issued.Password))
 if persisted.Tokens[0].SHA256!=hex.EncodeToString(sum[:]){
  t.Fatal("stored digest does not match issued app password")
 }
 request:=httptest.NewRequest("GET","/auth/devices",nil)
 request.AddCookie(newSessionCookie(session))
 list:=httptest.NewRecorder();mux.ServeHTTP(list,request)
 if list.Code!=200||!strings.Contains(list.Body.String(),issued.ID)||
  strings.Contains(list.Body.String(),issued.Password){
  t.Fatalf("device list leaked secret: %d %s",list.Code,list.Body.String())
 }
 revoked:=sendDeviceRequest(mux,"/auth/devices/revoke",
  `{"id":"`+issued.ID+`"}`,session,csrf)
 if revoked.Code!=204{t.Fatalf("revoke failed: %d",revoked.Code)}
 raw,err=os.ReadFile(device.TokenFile);if err!=nil{t.Fatal(err)}
 if err:=json.Unmarshal(raw,&persisted);err!=nil{t.Fatal(err)}
 if !persisted.Tokens[0].Revoked{t.Fatal("revocation not saved")}
 revoked=sendDeviceRequest(mux,"/auth/devices/revoke",
  `{"id":"`+issued.ID+`"}`,session,csrf)
 if revoked.Code!=404{t.Fatal("token revoked twice")}
}
func TestDeviceCreationRequiresRecentPasskey(t *testing.T){
 d,session,csrf:=deviceFixture(t)
 mux:=http.NewServeMux()
 if err:=d.Register(mux);err!=nil{t.Fatal(err)}
 d.Sessions.mu.Lock()
 for key,item:=range d.Sessions.sessions{
  item.authenticatedAt=item.authenticatedAt.Add(-6*time.Minute)
  d.Sessions.sessions[key]=item
 }
 d.Sessions.mu.Unlock()
 resp:=sendDeviceRequest(mux,"/auth/devices/create",`{"label":"Tablet"}`,session,csrf)
 if resp.Code!=401{t.Fatalf("old session issued token: %d",resp.Code)}
}
func TestDeviceMutationCannotCrossUsers(t *testing.T){
 d,session,csrf:=deviceFixture(t)
 mux:=http.NewServeMux()
 if err:=d.Register(mux);err!=nil{t.Fatal(err)}
 seed:=[]byte(`{"accounts":[{"username":"alice","active":true},{"username":"bob","active":true}],"tokens":[{"id":"bob-device","username":"bob","sha256":"abc","label":"Bob"}]}`)
 if err:=os.WriteFile(d.TokenFile,seed,0600);err!=nil{t.Fatal(err)}
 res:=sendDeviceRequest(mux,"/auth/devices/revoke",`{"id":"bob-device"}`,session,csrf)
 if res.Code!=404{t.Fatal("cross-user device revoked")}
}
