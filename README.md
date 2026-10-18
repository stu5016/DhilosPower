# DhilosPower BLE Control

DhilosPower 行動電源的**非官方**藍牙控制工具。原廠已停止營運並關閉伺服器，官方 App 無法再登入；這個專案透過分析 App 的藍牙協議，讓裝置可以繼續被查看與設定。

> 本專案與原廠無任何關係。協議內容是由 App 反組譯推導而來，未經原廠確認，使用風險自負。

## 功能

- 即時狀態：電量、可用 / 充滿時間、電池與電路板溫度、各埠電壓電流功率
- 工作模式：UPS / POWER BANK
- 充電設定：出差 / 一般 / 保養三種預設，或自訂充滿上限、充電電流
- DC 輸出電壓：5–24 V、電流上限 4–10 A（送出前會再次確認）
- 電池健康：健康度、充放電次數、設計容量、歷史溫度、近 16 天電量範圍
- 進階：指令選單（裝置狀態、歷史容量、電量計 SBS 暫存器 / MAC 子指令、log、自訂 hex），送出後直接解讀回應，並保留完整通訊紀錄
- 示範模式：不需要裝置也能看畫面
- 可「加到主畫面」當 App 使用（PWA）

不支援韌體更新（原廠韌體檔已無法取得）。

## 使用方式

需要支援 Web Bluetooth 的瀏覽器：**Android 的 Chrome**，或 Windows / macOS / Linux 的 **Chrome / Edge**。iPhone / iPad 的瀏覽器都不支援。

### 線上版（GitHub Pages）

開啟本 repo 的 GitHub Pages 網址，按「連線」後選擇名稱含 `Dhilos` 的裝置即可。
網址後加上 `?demo=1` 會直接進入示範模式。

### 本機執行

Web Bluetooth 只能在 `https://` 或 `http://localhost` 下使用，所以需要一個本機伺服器（需安裝 [Go](https://go.dev/)）：

```bash
go run web/serve.go -dir web
```

然後用 Chrome 開啟 <http://localhost:8080>。

手機要連電腦上的本機伺服器時，可以：

- USB 連接並開啟 USB 偵錯，執行 `adb reverse tcp:8080 tcp:8080`，手機開 `http://localhost:8080`
- 或執行 `go run web/serve.go -dir web -host 0.0.0.0`，並在手機 Chrome 的 `chrome://flags/#unsafely-treat-insecure-origin-as-secure` 加入電腦的區網網址

### Python 測試腳本

[`ble_test.py`](ble_test.py) 會連上裝置並送出一輪讀取指令（不會改任何設定），方便在電腦上驗證協議：

```bash
pip install bleak
python ble_test.py
```

## 支援機型

| 型號碼 | App 中的機型 | 埠 |
|---|---|---|
| 0、1 | 99C2 | USB-A、Type-C1（雙向）、Type-C2、DC 輸出 |
| 2、3 | AC 機型 | 同上（App 中幾乎沒有對應畫面） |
| 4、5 | 150C / 150C Plus | USB-A、Type-C、DC 輸出、DC 輸入 |

實際測試過的機型：尚未在實機上驗證。歡迎回報結果。

## 協議

完整的 GATT UUID、封包格式、指令與回應欄位說明在 [BLE_Protocol.md](BLE_Protocol.md)。簡述：

- Service `00001523-1212-efde-1523-785feabcd123`，Notify `…1524…`，Write `…1525…`
- 封包固定 16 bytes：`[CMD] 12 34 [參數…] [SUM]`，SUM 為前 15 bytes 相加取低 8 位
- 沒有配對、密碼或加密

## 專案結構

```
web/
  index.html            控制頁（單一檔案，無外部相依）
  manifest.webmanifest  PWA 設定
  sw.js                 離線快取（網路優先）
  icon.svg              圖示
  serve.go              本機靜態伺服器
ble_test.py             Python 讀取測試腳本
BLE_Protocol.md         協議分析文件
.github/workflows/pages.yml  自動部署 web/ 到 GitHub Pages
```

## 授權

[MIT](LICENSE)
