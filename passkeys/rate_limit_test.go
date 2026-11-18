package passkeys

import (
 "net/http/httptest"
 "testing"
 "time"
)
func TestLimiterRemoteSourceNotSpoofed(t *testing.T) {
 limit:=NewAttemptLimiter()
 limit.perSource=2
 first:=httptest.NewRequest("POST","https://cal45.example.org/auth",nil)
 first.RemoteAddr="192.0.2.10:52342"
 for i:=0;i<2;i++{
  first.Header.Set("X-Forwarded-For","198.51.100."+string(rune('1'+i)))
  if !limit.Allow(first){t.Fatal("attempt unexpectedly blocked")}
 }
 first.Header.Set("X-Forwarded-For","203.0.113.100")
 if limit.Allow(first){t.Fatal("spoofable XFF bypassed limit")}
 other:=httptest.NewRequest("POST","https://cal45.example.org/auth",nil)
 other.RemoteAddr="192.0.2.11:49999"
 if !limit.Allow(other){t.Fatal("different peer blocked")}
}
func TestLimiterWindowAndGlobalCap(t *testing.T){
 limit:=NewAttemptLimiter()
 now:=time.Date(2026,11,18,0,0,0,0,time.UTC)
 limit.now=func()time.Time{return now}
 limit.perSource=1
 limit.globalLimit=2
 first:=httptest.NewRequest("POST","https://cal45.example.org/auth",nil)
 first.RemoteAddr="192.0.2.1:5000"
 second:=httptest.NewRequest("POST","https://cal45.example.org/auth",nil)
 second.RemoteAddr="192.0.2.2:5000"
 third:=httptest.NewRequest("POST","https://cal45.example.org/auth",nil)
 third.RemoteAddr="192.0.2.3:5000"
 if !limit.Allow(first)||!limit.Allow(second){t.Fatal("initial attempts rejected")}
 if limit.Allow(third){t.Fatal("global cap bypassed")}
 now=now.Add(time.Minute)
 if limit.Allow(first){t.Fatal("per-source window unexpectedly expired")}
 now=now.Add(15*time.Minute)
 if !limit.Allow(first){t.Fatal("window did not reset")}
}
func TestLimiterBoundedMemory(t *testing.T){
 limit:=NewAttemptLimiter()
 limit.maxEntries=1
 a:=httptest.NewRequest("POST","https://cal45.example.org/auth",nil)
 a.RemoteAddr="192.0.2.1:1000"
 b:=httptest.NewRequest("POST","https://cal45.example.org/auth",nil)
 b.RemoteAddr="192.0.2.2:1000"
 if !limit.Allow(a){t.Fatal("first peer blocked")}
 if limit.Allow(b){t.Fatal("capacity exceeded")}
}
