package main

import (
 "embed"
 "html/template"
 "log"
 "net/http"
 "os"
 "strings"
 "time"
)

//go:embed templates/* static/*
var content embed.FS

type Page struct {
 Title string
 Active string
 Description string
}
func main() {
 if len(os.Args)>1 {
  if os.Args[1]=="dav-gateway" {log.Fatal(startDAV());return}
  if os.Args[1]=="token" {if err:=tokenCLI(os.Args[2:]);err!=nil{log.Fatal(err)};return}
  log.Fatal("unknown command")
 }
 tmpl := template.Must(template.ParseFS(content, "templates/*.html"))
 mux := http.NewServeMux()
 mux.Handle("GET /static/", http.FileServer(http.FS(content)))
 mux.HandleFunc("GET /healthz", func(w http.ResponseWriter,r *http.Request){w.Header().Set("Content-Type","text/plain; charset=utf-8");w.Write([]byte("ok\n"))})
 render := func(section, title, description string) http.HandlerFunc {
  return func(w http.ResponseWriter,r *http.Request) {
   w.Header().Set("Content-Security-Policy", "default-src 'none'; style-src 'self'; img-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'")
   w.Header().Set("X-Content-Type-Options", "nosniff")
   w.Header().Set("Referrer-Policy", "no-referrer")
   w.Header().Set("X-Frame-Options", "DENY")
   w.Header().Set("Cache-Control","no-store")
   w.Header().Set("Content-Type", "text/html; charset=utf-8")
   if err:=tmpl.ExecuteTemplate(w,"index.html",Page{Title:title,Active:section,Description:description});err!=nil {log.Printf("template error: %v",err)}
  }
 }
 mux.HandleFunc("GET /", func(w http.ResponseWriter,r *http.Request){
  if r.URL.Path!="/" { http.NotFound(w,r);return }
  render("calendar","Kalender","Die Kalenderansicht wird hier später mit deinem CalDAV-Konto verbunden.")(w,r)
 })
 mux.HandleFunc("GET /contacts",render("contacts","Kontakte","Adressbücher und Kontakte werden hier später per CardDAV verwaltet."))
 mux.HandleFunc("GET /settings",render("settings","Einstellungen","Passkeys, TOTP und Wiederherstellungscodes sind vorgesehen, aber noch nicht aktiviert."))
 bind:=os.Getenv("CAL45_WEB_BIND")
 if strings.TrimSpace(bind)=="" {bind="127.0.0.1:8080"}
 srv:=&http.Server{Addr:bind,Handler:mux,ReadHeaderTimeout:5*time.Second,IdleTimeout:45*time.Second}
 log.Printf("Cal45 frontend preview listening on %s",bind)
 log.Fatal(srv.ListenAndServe())
}
