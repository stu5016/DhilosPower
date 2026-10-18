package io.github.stu5016.dhilos;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * WebView 外殼：網頁打包在 assets/www，以 https://appassets.dhilos/ 提供（安全來源，localStorage 可用）。
 * Android WebView 不支援 Web Bluetooth，所以由這裡用原生 BLE 連線，透過 window.DhilosNative 與網頁溝通：
 *   網頁 → 原生：DhilosNative.connect() / disconnect() / write(id, hex)
 *   原生 → 網頁：window.dhilosNativeEvent(jsonString)，type 為 connected / cancelled / error / packet / writeDone / disconnected
 */
@SuppressLint("MissingPermission")
public class MainActivity extends Activity {
    private static final String HOST = "appassets.dhilos";
    private static final String START_URL = "https://" + HOST + "/index.html";

    private static final UUID SVC = UUID.fromString("00001523-1212-efde-1523-785feabcd123");
    private static final UUID NTF = UUID.fromString("00001524-1212-efde-1523-785feabcd123");
    private static final UUID WR = UUID.fromString("00001525-1212-efde-1523-785feabcd123");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final int REQ_PERMS = 1;
    private static final int REQ_ENABLE_BT = 2;
    private static final long SCAN_MS = 10000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView web;
    private BluetoothAdapter adapter;

    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private AlertDialog picker;
    private ArrayAdapter<String> pickerItems;
    private final List<BluetoothDevice> found = new ArrayList<>();
    private final Runnable scanTimeout = this::onScanTimeout;

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar;
    private String deviceName;
    private boolean connecting;

    // GATT 一次只能有一個寫入在進行，其餘排隊
    private final ArrayDeque<String[]> writeQueue = new ArrayDeque<>();
    private String writingId;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(10, 13, 9));
        getWindow().setNavigationBarColor(Color.rgb(10, 13, 9));

        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        adapter = bm == null ? null : bm.getAdapter();

        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(10, 13, 9));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        web.setWebViewClient(new AssetClient());
        web.addJavascriptInterface(new Bridge(), "DhilosNative");
        setContentView(web);
        web.loadUrl(START_URL);
    }

    @Override
    protected void onDestroy() {
        stopScan();
        closeGatt();
        if (web != null) web.destroy();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    // ---------- 網頁檔案 ----------

    private class AssetClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (!HOST.equals(u.getHost())) return null;
            String path = u.getPath();
            if (path == null || path.equals("/")) path = "/index.html";
            try {
                InputStream in = getAssets().open("www" + path);
                return new WebResourceResponse(mimeOf(path), "utf-8", in);
            } catch (IOException e) {
                Map<String, String> h = new HashMap<>();
                return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", h, null);
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (HOST.equals(u.getHost())) return false;
            // 外部連結交給瀏覽器
            try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
            return true;
        }
    }

    private static String mimeOf(String path) {
        if (path.endsWith(".html")) return "text/html";
        if (path.endsWith(".js")) return "application/javascript";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".json") || path.endsWith(".webmanifest")) return "application/json";
        if (path.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }

    // ---------- 網頁 → 原生 ----------

    private class Bridge {
        @JavascriptInterface
        public void connect() { main.post(MainActivity.this::startConnect); }

        @JavascriptInterface
        public void disconnect() {
            main.post(() -> {
                stopScan();
                if (gatt != null) gatt.disconnect();
            });
        }

        @JavascriptInterface
        public void write(String id, String hex) { main.post(() -> enqueueWrite(id, hex)); }
    }

    // ---------- 原生 → 網頁 ----------

    private void emit(String type, Object... kv) {
        JSONObject o = new JSONObject();
        try {
            o.put("type", type);
            for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
        } catch (JSONException ignored) { }
        String js = "window.dhilosNativeEvent&&window.dhilosNativeEvent(" + JSONObject.quote(o.toString()) + ")";
        main.post(() -> { if (web != null) web.evaluateJavascript(js, null); });
    }

    // ---------- 權限與藍牙開關 ----------

    private String[] neededPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private boolean hasPermissions() {
        for (String p : neededPermissions()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    private void startConnect() {
        if (adapter == null) { emit("error", "message", "這支手機沒有藍牙"); return; }
        if (gatt != null || picker != null) return;
        if (!hasPermissions()) { requestPermissions(neededPermissions(), REQ_PERMS); return; }
        if (!adapter.isEnabled()) {
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE_BT);
            return;
        }
        startScan();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode != REQ_PERMS) return;
        if (hasPermissions()) startConnect();
        else emit("error", "message", "需要「附近的裝置」（或定位）權限才能連線，請到系統設定允許");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_ENABLE_BT) return;
        if (resultCode == RESULT_OK) startScan();
        else emit("cancelled");
    }

    // ---------- 掃描與選擇裝置 ----------

    private void startScan() {
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { emit("error", "message", "無法開始藍牙掃描"); return; }
        found.clear();
        pickerItems = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        picker = new AlertDialog.Builder(this)
                .setTitle("搜尋中…")
                .setAdapter(pickerItems, (d, which) -> {
                    BluetoothDevice dev = found.get(which);
                    String name = pickerItems.getItem(which);
                    stopScan();
                    picker = null;
                    connectTo(dev, name);
                })
                .setNegativeButton("取消", (d, w) -> cancelPicker())
                .setOnCancelListener(d -> cancelPicker())
                .create();
        picker.show();

        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult r) { onFound(r); }

            @Override
            public void onBatchScanResults(List<ScanResult> results) { for (ScanResult r : results) onFound(r); }

            @Override
            public void onScanFailed(int errorCode) {
                main.post(() -> {
                    dismissPicker();
                    emit("error", "message", "藍牙掃描失敗（" + errorCode + "）");
                });
            }
        };
        ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        scanner.startScan(null, settings, scanCallback);
        main.postDelayed(scanTimeout, SCAN_MS);
    }

    private void onFound(ScanResult r) {
        BluetoothDevice dev = r.getDevice();
        ScanRecord rec = r.getScanRecord();
        String name = rec != null ? rec.getDeviceName() : null;
        if (name == null) name = dev.getName();
        boolean hasService = rec != null && rec.getServiceUuids() != null && rec.getServiceUuids().contains(new ParcelUuid(SVC));
        boolean isDhilos = name != null && name.contains("Dhilos");
        if (!hasService && !isDhilos) return;
        final String label = name != null ? name : dev.getAddress();
        main.post(() -> {
            if (picker == null) return;
            for (BluetoothDevice d : found) if (d.getAddress().equals(dev.getAddress())) return;
            found.add(dev);
            pickerItems.add(label);
            picker.setTitle("選擇裝置");
        });
    }

    private void onScanTimeout() {
        stopScan();
        if (picker != null && found.isEmpty()) {
            picker.setTitle(Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                    ? "找不到裝置（請確認行動電源已開機，且手機定位已開啟）"
                    : "找不到裝置（請確認行動電源已開機）");
        }
    }

    private void stopScan() {
        main.removeCallbacks(scanTimeout);
        if (scanner != null && scanCallback != null) {
            try { scanner.stopScan(scanCallback); } catch (Exception ignored) { }
        }
        scanCallback = null;
    }

    private void cancelPicker() {
        stopScan();
        if (picker != null) { picker = null; emit("cancelled"); }
    }

    private void dismissPicker() {
        stopScan();
        if (picker != null) { AlertDialog p = picker; picker = null; p.dismiss(); }
    }

    // ---------- GATT ----------

    private void connectTo(BluetoothDevice dev, String name) {
        deviceName = name;
        connecting = true;
        gatt = dev.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
    }

    private void closeGatt() {
        writeQueue.clear();
        writingId = null;
        writeChar = null;
        if (gatt != null) { gatt.close(); gatt = null; }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            main.post(() -> {
                if (g != gatt) return;
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    g.discoverServices();
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    boolean wasConnecting = connecting;
                    connecting = false;
                    failPendingWrites("連線中斷");
                    closeGatt();
                    if (wasConnecting) emit("error", "message", "連線失敗（狀態碼 " + status + "），請靠近一點再試");
                    else emit("disconnected");
                }
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            main.post(() -> {
                if (g != gatt) return;
                BluetoothGattService svc = g.getService(SVC);
                BluetoothGattCharacteristic ntf = svc == null ? null : svc.getCharacteristic(NTF);
                BluetoothGattCharacteristic wr = svc == null ? null : svc.getCharacteristic(WR);
                if (ntf == null || wr == null) { failConnect("找不到 DhilosPower 的藍牙服務"); return; }
                writeChar = wr;
                g.setCharacteristicNotification(ntf, true);
                BluetoothGattDescriptor cccd = ntf.getDescriptor(CCCD);
                if (cccd == null) { failConnect("無法開啟通知（找不到 CCCD）"); return; }
                byte[] on = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
                boolean started;
                if (Build.VERSION.SDK_INT >= 33) {
                    started = g.writeDescriptor(cccd, on) == BluetoothGatt.GATT_SUCCESS;
                } else {
                    cccd.setValue(on);
                    started = g.writeDescriptor(cccd);
                }
                if (!started) failConnect("無法開啟通知");
            });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            main.post(() -> {
                if (g != gatt || !connecting) return;
                if (status != BluetoothGatt.GATT_SUCCESS) { failConnect("開啟通知失敗（" + status + "）"); return; }
                connecting = false;
                emit("connected", "name", deviceName != null ? deviceName : "Dhilos");
            });
        }

        // Android 13 以上
        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            if (NTF.equals(c.getUuid())) emit("packet", "hex", toHex(value));
        }

        // Android 12 以下
        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (Build.VERSION.SDK_INT >= 33) return;
            if (NTF.equals(c.getUuid())) emit("packet", "hex", toHex(c.getValue()));
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            main.post(() -> {
                if (writingId == null) return;
                String id = writingId;
                writingId = null;
                boolean ok = status == BluetoothGatt.GATT_SUCCESS;
                emit("writeDone", "id", id, "ok", ok, "message", ok ? "" : "寫入失敗（" + status + "）");
                pumpWrites();
            });
        }
    };

    private void failConnect(String message) {
        connecting = false;
        closeGatt();
        emit("error", "message", message);
    }

    private void enqueueWrite(String id, String hex) {
        if (gatt == null || writeChar == null) {
            emit("writeDone", "id", id, "ok", false, "message", "尚未連線");
            return;
        }
        writeQueue.add(new String[]{id, hex});
        pumpWrites();
    }

    private void pumpWrites() {
        if (writingId != null || writeQueue.isEmpty() || gatt == null || writeChar == null) return;
        String[] job = writeQueue.poll();
        byte[] data;
        try { data = fromHex(job[1]); } catch (IllegalArgumentException e) {
            emit("writeDone", "id", job[0], "ok", false, "message", "封包格式錯誤");
            pumpWrites();
            return;
        }
        int type = (writeChar.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        boolean started;
        if (Build.VERSION.SDK_INT >= 33) {
            started = gatt.writeCharacteristic(writeChar, data, type) == BluetoothGatt.GATT_SUCCESS;
        } else {
            writeChar.setWriteType(type);
            writeChar.setValue(data);
            started = gatt.writeCharacteristic(writeChar);
        }
        if (started) {
            writingId = job[0];
        } else {
            emit("writeDone", "id", job[0], "ok", false, "message", "藍牙忙碌，寫入失敗");
            pumpWrites();
        }
    }

    private void failPendingWrites(String message) {
        if (writingId != null) emit("writeDone", "id", writingId, "ok", false, "message", message);
        for (String[] job : writeQueue) emit("writeDone", "id", job[0], "ok", false, "message", message);
        writingId = null;
        writeQueue.clear();
    }

    // ---------- 工具 ----------

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02X", x & 0xFF));
        return sb.toString();
    }

    private static byte[] fromHex(String s) {
        if (s == null || s.length() % 2 != 0) throw new IllegalArgumentException();
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16), lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException();
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
