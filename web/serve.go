// 本機靜態伺服器：Web Bluetooth 只在 https 或 http://localhost 下可用。
// 用法（在 web 資料夾內）：go run serve.go
//   或在上一層：go run web/serve.go -dir web
package main

import (
	"flag"
	"fmt"
	"log"
	"net/http"
)

func main() {
	dir := flag.String("dir", ".", "要提供的資料夾")
	port := flag.Int("port", 8080, "埠號")
	host := flag.String("host", "127.0.0.1", "綁定位址；給同網段手機用時設 0.0.0.0")
	flag.Parse()
	addr := fmt.Sprintf("%s:%d", *host, *port)
	fmt.Printf("開啟 http://localhost:%d/  （Ctrl+C 結束）\n", *port)
	log.Fatal(http.ListenAndServe(addr, http.FileServer(http.Dir(*dir))))
}
