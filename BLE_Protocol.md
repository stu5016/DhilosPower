# DhilosPower BLE 協議分析

來源：`DhilosPower_NoLogin.apk`（package `linkulife.com.dhilospower`），以 jadx 1.5.6 反編譯。
關鍵程式：`ble/UnoodBLE.java`（連線）、`ble/UnoodBLECommand.java`（指令）、`screen/DeviceState*.java`、`DeviceHistory.java`、`DeviceSetting.java`、`DeviceDCOk.java`（回應解析）。

## 1. 硬體推測

- MCU 是 **Nordic nRF5x**：主服務 UUID 用的是 Nordic LED Button Service 範本的 base UUID，OTA 用 Nordic Secure DFU（`0xFE59`）。
- 電池電量計是 **TI bq40zxx 系列（SBS 智慧電池）**：`C5` / `C4` 指令的參數正好是標準 SBS 暫存器及 bq40z50 的 MAC 子指令（見第 5 節）。MCU 只是把它們橋接到 BLE。
- **沒有任何配對、密碼或加密**。App 連上之後直接開 notify 然後寫指令，不需要伺服器授權，所以伺服器下架不影響藍牙控制（只影響登入、OTA 韌體下載、歷史上傳）。

## 2. GATT

| 項目 | UUID |
|---|---|
| Service | `00001523-1212-efde-1523-785feabcd123` |
| Notify（裝置 → 手機） | `00001524-1212-efde-1523-785feabcd123` |
| Write（手機 → 裝置） | `00001525-1212-efde-1523-785feabcd123` |
| OTA (DFU) Service | `0000FE59-0000-1000-8000-00805F9B34FB` |

- 掃描：以 Service UUID 過濾，裝置名稱含 `Dhilos`。
- 進 DFU 後裝置改名為 `ESH-OVA` 廣播。
- 流程：連線 → 對 `1524` 開 notify → 寫 `1525`。App 一次只送一條指令，收到對應回應約 100 ms 後才送下一條。

## 3. 封包格式（雙向都是固定 16 bytes）

```
byte:  0      1    2    3 .. 14         15
      [CMD] [12] [34] [參數, 不足補 00] [SUM]
```

- `byte1..2` 固定 `12 34`（DFU 指令例外，為 `00 00`）。
- **SUM = byte0..14 全部相加後取低 8 位**（App 裡叫 `calcCrc8`，但其實只是簡單加總，不是 CRC）。
- 收到的回應也用同樣方式驗證；byte0 是「回應類型」，資料從 byte3 開始。
- 多位元組數值除特別註明外為 **Big-Endian**。

## 4. 指令一覽（手機 → 裝置）

### 讀取類 `F6 12 34 <sub>`

| 指令 (含 SUM 的完整 hex) | 功能 | 回應 byte0 |
|---|---|---|
| `F6 12 34 01 … 3D` | 版本 / 型號 | `01` |
| `F6 12 34 04 … 40` | PCB 歷史最高/最低溫 | `04` |
| `F6 12 34 0A … 46` | USB-A 輸出 + PCB 溫度 | `0A` |
| `F6 12 34 0B … 47` | Type-C1 / Type-C2 | `0B` |
| `F6 12 34 0C … 48` | DC 輸出（150C：DC Out） | `0C` |
| `F6 12 34 0D … 49` | 150C：DC 輸入 | `0D` |
| `F6 12 34 0E … 4A` | DC 電壓電流（定義了但沒用到） | `0E` |
| `F6 12 34 0F … 4B` | 所有埠的開關/方向狀態 | `0F` |
| `F6 12 34 1F <g> …` | 電池歷史**最低**容量，g = 0..3 | `1F` |
| `F6 12 34 20 <g> …` | 電池歷史**最高**容量，g = 0..3 | `20` |

### 寫入/控制類

| 指令 | 功能 |
|---|---|
| `F7 12 34 <m>` | 工作模式：`01` = **UPS**、`00` = **POWER BANK**（完整：`F7123401…3E` / `F7123400…3D`） |
| `F4 12 34 <full> <low> <mode> <ampH> <ampL>` | 充電設定：充滿上限 %、低電量下限 %、模式、充電電流 (mA, BE)。`low` 在 App 上標為「低電量通知」，但 App 從未實作通知；依 App 說明文字，裝置在電量低於此值時會停止輸出 |
| `F2 12 34 <VH> <VL> <AH> <AL> 01` | 設定 DC 輸出電壓 (mV, BE) 與電流上限 (mA, BE)；回應 `70`，byte3 = `0x30` 表示成功 |
| `C5 12 34 <reg>` | 讀電量計 SBS 暫存器（word），回應 `25` |
| `C4 12 34 <hi> <lo>` | 電量計 MAC 子指令（block read），回應 `21`，分多包 |
| `C6 12 34 AA AA` | 開啟 log，回應 `22`（完整：`C61234AAAA…60`） |
| `C7 12 34` | 讀歷史 log，回應多包 `23` |
| `FD 00 00 55 AA 5A` | **進入 DFU（韌體更新）模式**，完整：`FD000055AA5A00000000000000000056` |
| `F1 …` | 設定 APPID（函式內容是空的、沒有使用） |

`F4` 在 App 中的四種預設：

| 模式 | full | low | mode | 電流 |
|---|---|---|---|---|
| 出差（快充） | 100 | 10 | 0 | 5.0 A |
| 一般 | 80 | 30 | 1 | 3.0 A |
| 保養 | 70 | 40 | 2 | 1.5 A |
| 自訂 | 90–100 | 10–20 | 0/1 | 0.1–5.0 A |

`F2` DC 輸出範圍（UI 限制）：電壓 5.0–24.0 V（每格 0.1 V）、電流 4.0–10.0 A（每格 0.5 A）；台灣語系另限制 V×A ≤ 100 W。
範例 12.0 V / 3.0 A：`F2 12 34 2E E0 0B B8 01 00 00 00 00 00 00 00 0A`

## 5. 回應解析（裝置 → 手機）

以下「bN」指回應的第 N 個 byte（從 0 開始）。

| 回應 | 欄位 |
|---|---|
| `01` 版本 | b5–b6 = 韌體版本 (uint16)；b9 = 型號：`0`/`1` = 99C2、`2`/`3` = AC 機型、`4` = 150C、`5` = 150C Plus |
| `0F` 埠狀態 | b4 bit1 = C1 方向（0 輸入 / 1 輸出）、b4 bit2 = DC EN；b5 bit1 = USB-A 輸出中、bit7 = C1 啟用、bit6 = C2 EN、bit5 & bit3 = C2/DC 通路。C2 輸出 = DCEN=0 且 C2EN=0 且 bit5=bit3=1；DC 輸出 = DCEN=1 且 C2EN=0 且 bit5=bit3=1 |
| `0A` | b3–b4 = USB-A 電壓 mV、b5–b6 = 電流 mA、b9–b10 = PCB 溫度 (m°C) |
| `0B` | b3–b4 = C1 電壓、b5–b6 = C1 電流、b7–b8 = C2 電壓、b9–b10 = C2 電流（int16，mV/mA，電流取絕對值） |
| `0C` | 99C2/AC：b7–b8 = DC 電壓、b9–b10 = DC 電流（b3–b6 在型號 ≥ 2 時讀出但未顯示，可能是 AC）；150C：b3–b4 / b5–b6 = DC Out V / A |
| `0D` | 150C：b3–b4 / b5–b6 = DC In V / A |
| `04` | b3 = PCB 最高溫 °C、b4 = 最低溫 °C |
| `1F` / `20` | b3–b10 = 4 個 uint16，每個是一天的電池最低/最高容量 (%)；group g 對應「今天往前第 4g ~ 4g+3 天」，共 16 天 |
| `25`（C5 回應） | b3–b4 = 數值，**Little-Endian**（直接是 SBS word） |
| `21`（C4 回應） | b11–b12 = 分包序號；資料在 b3–b10 |
| `70`（F2 回應） | b3 = `0x30` 成功，其他 = 失敗 |
| `22` / `23` | log 開啟確認 / log 資料（App 直接原樣上傳伺服器，沒有解析） |

### App 用到的電量計暫存器（C5 / C4）

| 指令 | SBS / bq40z50 意義 | App 解讀 |
|---|---|---|
| `C5 0D` | RelativeStateOfCharge | 剩餘電量 % |
| `C5 12` | AverageTimeToEmpty | 放電剩餘時間（分鐘，65535 = N/A） |
| `C5 13` | AverageTimeToFull | 充滿所需時間（分鐘，65535 = N/A） |
| `C5 17` | CycleCount | 充放電次數 |
| `C5 18` | DesignCapacity | 設計容量 mAh |
| `C5 4F` | StateOfHealth | 健康度 %（≤33 差、≤66 普通、其他好） |
| `C4 00 72` | DAStatus2 | 第 0 包 b5–b6 = 電池溫度 (0.1 K)，°C = 值 × 0.1 − 273.15 |
| `C4 00 60` | LifetimeDataBlock1 | 第 3 包 b6 = 電池歷史最高溫、b7 = 最低溫 (°C) |

因為是標準 SBS，理論上也能用 `C5` 讀其他暫存器，例如 `09` Voltage、`0A` Current、`08` Temperature、`0F` RemainingCapacity、`10` FullChargeCapacity（App 沒用到，需實機確認韌體是否放行）。

## 6. App 進入裝置頁時的讀取順序

99C2 / AC（`DeviceState`）：
`F6-0F → F6-01 → F6-0C → F6-0A → F6-0B → C5-13 → C4-0072 → C5-0D → C5-12`

150C（`DeviceState150c`）：
`F6-0F → F6-01 → F6-0D → F6-0A → F6-0A → F6-0B → F6-0C → F6-0A → C5-13 → C4-0072 → C5-0D → C5-12`

歷史頁（`DeviceHistory`）：
`F6-04 → C5-17 → C4-0060 → C5-4F → C5-18 → F6-1F(g=0..3) → F6-20(g=0..3)`

## 7. 注意事項

- `FD 00 00 55 AA 5A` 會讓裝置重開進 bootloader；韌體檔原本從 `https://ups.eshtech.com.tw/` 下載，伺服器已下架，**沒有韌體檔就不要送這條**，否則可能要靠 nRF Connect 才救得回來。
- `F2` 會直接改 DC 輸出電壓，接著設備前請先確認負載可承受。
- App 的 `0F` 解析有個小 bug：`(b4 & 1) >> 1` 永遠是 0，所以 b4 bit0 的實際意義不明。
- 以上欄位都是從程式碼推出來的，尚未在實機上驗證；建議先用 `ble_test.py` 或 nRF Connect 讀取類指令確認。
