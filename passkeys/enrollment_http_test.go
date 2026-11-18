package passkeys

import (
 "bytes"
 "encoding/json"
 "net/http"
 "net/http/httptest"
 "os"
 "path/filepath"
 "strings"
 "testing"
)
func enrollmentFixture(t *testing.T)(*InitialEnrollment,string){
 t.Helper()
 dir:=t.TempDir()
 accounts:=filepath.Join(dir,"tokens.json")
 data:=[]byte(`{"accounts":[{"username":"alice","active":true}]}`)
 if err:=os.WriteFile(accounts,data,0600);err!=nil{t.Fatal(err)}
 repo,err:=NewJSONRepository(accounts,filepath.Join(dir,"passkeys.json"))
 if err!=nil{t.Fatal(err)}
 svc,err:=New("https://cal45.example.org",repo)
 if err!=nil{t.Fatal(err)}
 grant:=BootstrapStore{Path:filepath.Join(dir,"bootstrap.json"),AccountIsActive:func(u string)bool{
  return repo.accountActive(u)
 }}
 tok,err:=grant.Issue("alice");if err!=nil{t.Fatal(err)}
 e:=&InitialEnrollment{Service:svc,Repository:repo,Grants:grant,Origin:"https://cal45.example.org"}
 return e,tok
}
func enrollRequest(body string)*http.Request{
 req:=httptest.NewRequest("POST","https://cal45.example.org/auth/passkey/enroll/begin",strings.NewReader(body))
 req.Header.Set("Origin","https://cal45.example.org")
 req.Header.Set("Content-Type","application/json")
 return req
}
func TestEnrollmentRequiresExplicitRegistration(t *testing.T){
 mux:=http.NewServeMux()
 if err:=(&InitialEnrollment{}).Register(mux);err==nil{t.Fatal("incomplete enrollment accepted")}
 rec:=httptest.NewRecorder()
 mux.ServeHTTP(rec,enrollRequest("{}"))
 if rec.Code!=http.StatusNotFound{t.Fatal("enrollment route unexpectedly enabled")}
}
func TestEnrollmentTokenUsedOnceAndBoundToBrowser(t *testing.T){
 e,token:=enrollmentFixture(t)
 mux:=http.NewServeMux()
 if err:=e.Register(mux);err!=nil{t.Fatal(err)}
 body,_:=json.Marshal(map[string]string{"username":"alice","setupToken":token})
 req:=enrollRequest(string(body))
 rec:=httptest.NewRecorder();mux.ServeHTTP(rec,req)
 if rec.Code!=200{t.Fatalf("begin rejected: %d %s",rec.Code,rec.Body.String())}
 cookies:=rec.Result().Cookies()
 if len(cookies)==0||!cookies[0].Secure||!cookies[0].HttpOnly||
  cookies[0].SameSite!=http.SameSiteStrictMode {t.Fatal("missing secure enrollment cookie")}
 again:=httptest.NewRecorder();mux.ServeHTTP(again,enrollRequest(string(body)))
 if again.Code!=401{t.Fatalf("token reuse accepted: %d",again.Code)}
 var first struct{Ceremony string `json:"ceremony"`}
 if err:=json.NewDecoder(rec.Body).Decode(&first);err!=nil{t.Fatal(err)}
 finish:=httptest.NewRequest("POST","https://cal45.example.org/auth/passkey/enroll/finish",bytes.NewReader([]byte("{}")))
 finish.Header.Set("Origin",e.Origin)
 finish.Header.Set("Content-Type","application/json")
 finish.Header.Set("X-Cal45-Ceremony",first.Ceremony)
 missing:=httptest.NewRecorder();mux.ServeHTTP(missing,finish)
 if missing.Code!=401{t.Fatalf("missing browser cookie accepted: %d",missing.Code)}
 finish=httptest.NewRequest("POST","https://cal45.example.org/auth/passkey/enroll/finish",bytes.NewReader([]byte("{}")))
 finish.Header.Set("Origin",e.Origin)
 finish.Header.Set("Content-Type","application/json")
 finish.Header.Set("X-Cal45-Ceremony",first.Ceremony)
 finish.AddCookie(cookies[0])
 invalid:=httptest.NewRecorder();mux.ServeHTTP(invalid,finish)
 if invalid.Code!=403{t.Fatalf("unverified WebAuthn attestation accepted: %d",invalid.Code)}
 finish=httptest.NewRequest("POST","https://cal45.example.org/auth/passkey/enroll/finish",bytes.NewReader([]byte("{}")))
 finish.Header.Set("Origin",e.Origin)
 finish.Header.Set("Content-Type","application/json")
 finish.Header.Set("X-Cal45-Ceremony",first.Ceremony)
 finish.AddCookie(cookies[0])
 replay:=httptest.NewRecorder();mux.ServeHTTP(replay,finish)
 if replay.Code!=401{t.Fatalf("second finish accepted: %d",replay.Code)}
}
func TestEnrollmentCrossOriginRejectedWithoutConsumingToken(t *testing.T){
 e,token:=enrollmentFixture(t)
 mux:=http.NewServeMux()
 if err:=e.Register(mux);err!=nil{t.Fatal(err)}
 body,_:=json.Marshal(map[string]string{"username":"alice","setupToken":token})
 req:=enrollRequest(string(body))
 req.Header.Set("Origin","https://evil.example")
 rec:=httptest.NewRecorder();mux.ServeHTTP(rec,req)
 if rec.Code!=403{t.Fatal("cross origin accepted")}
 rec=httptest.NewRecorder();mux.ServeHTTP(rec,enrollRequest(string(body)))
 if rec.Code!=200{t.Fatalf("valid request rejected after cross origin: %d",rec.Code)}
}
