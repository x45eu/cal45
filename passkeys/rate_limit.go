package passkeys

import (
 "net"
 "net/http"
 "strings"
 "sync"
 "time"
)

// AttemptLimiter is a bounded, single-process, IP-based abuse guard.
// It deliberately does not trust client-supplied X-Forwarded-For headers.
// A trusted reverse proxy MUST also enforce limits in multi-instance deployments.
type AttemptLimiter struct {
 mu sync.Mutex
 entries map[string]attemptWindow
 now func()time.Time
 maxEntries int
 perSource int
 window time.Duration
 globalStart time.Time
 globalCount int
 globalLimit int
}
type attemptWindow struct {
 start time.Time
 count int
}
func NewAttemptLimiter()*AttemptLimiter {
 return &AttemptLimiter{
  entries:make(map[string]attemptWindow),
  now:time.Now,maxEntries:4096,perSource:12,
  window:15*time.Minute,globalLimit:600,
 }
}
func sourceFromRequest(r *http.Request)string {
 address:=r.RemoteAddr
 host,_,err:=net.SplitHostPort(address)
 if err==nil{return host}
 // Malformed or missing RemoteAddr shares one conservative bucket.
 if strings.TrimSpace(address)=="" {return "unknown"}
 return address
}
func(l *AttemptLimiter)Allow(r *http.Request)bool{
 if l==nil{return false}
 source:=sourceFromRequest(r)
 l.mu.Lock()
 defer l.mu.Unlock()
 now:=l.now()
 if l.globalStart.IsZero()||now.Sub(l.globalStart)>=time.Minute{
  l.globalStart=now;l.globalCount=0
 }
 if l.globalCount>=l.globalLimit{return false}
 entry,exists:=l.entries[source]
 if !exists||now.Sub(entry.start)>=l.window{
  entry=attemptWindow{start:now}
 }
 if entry.count>=l.perSource{return false}
 if !exists&&len(l.entries)>=l.maxEntries{
  for key,value:=range l.entries{
   if now.Sub(value.start)>=l.window{delete(l.entries,key)}
  }
  if len(l.entries)>=l.maxEntries{return false}
 }
 entry.count++
 l.entries[source]=entry
 l.globalCount++
 return true
}
func rejectBusy(w http.ResponseWriter){
 w.Header().Set("Cache-Control","no-store")
 w.Header().Set("Retry-After","60")
 http.Error(w,"Too many authentication attempts",http.StatusTooManyRequests)
}
