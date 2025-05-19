package com.example.bluetoothterminal;

import android.annotation.SuppressLint;
import android.app.Activity;
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
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * BleManager: reuse in any Activity/Fragment for BLE scan/connect/read/write/notify.
 * 1) BleManager.init(context) in Application.onCreate()
 * 2) BleManager.get().setListener(...)
 * 3) BleManager.get().startScan()/connect()/write()/etc.
 */
public class BleManager {
    private static final long SCAN_PERIOD_MS = 10_000;
    private static BleManager INSTANCE;

    private final BluetoothAdapter adapter;
    private final BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean scanning;
    private OnBleEventListener listener;

    private boolean connected = false;

    private BluetoothDevice lastDevice;

    private UUID writeServiceUuid;
    private UUID writeCharUuid;

    private BluetoothGatt bluetoothGatt;

    private UUID notifyServiceUuid;
    private UUID notifyCharUuid;

    private static final int REQ_ENABLE_BT = 3001;
    private final Context context;


    public  static  final int get_REQ_ENABLE_BT()
    {
        return REQ_ENABLE_BT;
    }


    // Private constructor
    private BleManager(Context ctx) {
        this.context = ctx;
        BluetoothManager mgr = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = mgr != null ? mgr.getAdapter() : null;
        scanner = adapter != null ? adapter.getBluetoothLeScanner() : null;
    }

    /**
     * Initialize singleton; call once (e.g. in Application)
     */
    public static synchronized void init(Context context) {
        if (INSTANCE == null) {
            INSTANCE = new BleManager(context.getApplicationContext());
        }
    }

    /**
     * Get instance; must call init() first
     */
    public static BleManager get() {
        if (INSTANCE == null) {
            throw new IllegalStateException("BleManager not initialized");
        }
        return INSTANCE;
    }

    /** Set listener for BLE events */
    public void setListener(OnBleEventListener l) {
        listener = l;
    }

    /** Start BLE scan; suppress permission warnings (request externally) */
    @SuppressLint("MissingPermission")
    public void startScan(Activity activity) {
        if (!ensureBluetoothEnabled(activity)) return;
        if (scanning || adapter == null || !adapter.isEnabled() || scanner == null) return;
        scanning = true;
        handler.postDelayed(this::stopScan, SCAN_PERIOD_MS);
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        scanner.startScan(null, settings, scanCallback);
    }

    /** Stop BLE scan */
    @SuppressLint("MissingPermission")
    public void stopScan() {
        if (!scanning || scanner == null) return;
        scanning = false;
        scanner.stopScan(scanCallback);
    }

    /** Connect to device; suppress permission warnings (request externally) */
    @SuppressLint("MissingPermission")
    public void connect(Activity activity, BluetoothDevice device) {
        if (!ensureBluetoothEnabled(activity)) return;
        lastDevice = device;
        disconnect();
        gatt = device.connectGatt(context, false, gattCallback);
        bluetoothGatt = gatt;
    }

    public BluetoothDevice getLastDevice() {
        return lastDevice;
    }

    /** Disconnect and cleanup GATT */
    @SuppressLint("MissingPermission")
    public void disconnect() {
        if (gatt != null) {
            gatt.disconnect();
            gatt = null;
        }
        connected = false;
    }

    public boolean isConnected() {
        return connected && gatt != null;
    }


    /** Write to a characteristic */
    @SuppressLint("MissingPermission")
    public void write(String text) {

        if (gatt == null || writeServiceUuid == null || writeCharUuid == null)
        {
            Log.e("BLE","Write UUID null!");
            return;
        }

        BluetoothGattService svc = gatt.getService(writeServiceUuid);
        if (svc == null) return;
        BluetoothGattCharacteristic c = svc.getCharacteristic(writeCharUuid);
        if (c == null) return;
        c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        boolean ok = c.setValue(text.getBytes(StandardCharsets.UTF_8));
        Log.d("BLE","setValue returned "+ok);
        boolean res = gatt.writeCharacteristic(c);
        Log.d("BLE","writeCharacteristic returned "+res);
    }

    /** Enable or disable notifications */
    @SuppressLint("MissingPermission")
    public void setNotify(UUID serviceUuid, UUID charUuid, boolean enable) {
        if (gatt == null) return;
        BluetoothGattService service = gatt.getService(serviceUuid);
        if (service == null) return;
        BluetoothGattCharacteristic c = service.getCharacteristic(charUuid);
        gatt.setCharacteristicNotification(c, enable);
    }

    // BLE scan events
    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (listener != null) listener.onDeviceFound(result.getDevice(), result.getRssi());
        }
        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult r : results) onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, r);
        }
        @Override
        public void onScanFailed(int error) {
            stopScan();
            if (listener != null) listener.onScanError(error);
        }
    };

    // GATT events
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        @SuppressLint("MissingPermission")
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            Log.d("BLE","onConnectionStateChange: newState=" + newState);
            gatt = bluetoothGatt = g;

            // Thông báo ngay cho listener
            if (listener != null) {
                listener.onConnectionState(newState);
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true;
                // Bắt đầu discover services
                g.discoverServices();
            }
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false;
                // Giờ mới được close() và hủy gatt
                g.close();
                if (g == gatt) {  // nếu bạn giữ reference gatt ở đây
                    gatt = null;
                }
            }
        }
        @SuppressLint("MissingPermission")
        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (listener != null) listener.onServicesDiscovered(g.getServices());

            if (listener != null && status == BluetoothGatt.GATT_SUCCESS) {
                listener.onServicesDiscovered(gatt.getServices());
            }

            for (BluetoothGattService svc : g.getServices()) {
                for (BluetoothGattCharacteristic c : svc.getCharacteristics()) {
                    int prop = c.getProperties();

                    // tìm characteristic để ghi
                    if ((prop & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                            (prop & BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) != 0) {
                        writeServiceUuid = svc.getUuid();
                        writeCharUuid = c.getUuid();
                    }

                    // tìm characteristic để notify
                    if ((prop & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ||
                            (prop & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                        notifyServiceUuid = svc.getUuid();
                        notifyCharUuid = c.getUuid();

                        // đăng ký notification ngay
                        g.setCharacteristicNotification(c, true);
                        // viết descriptor 0x2902 để bật Notification
                        BluetoothGattDescriptor desc = c.getDescriptor(
                                UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
                        if (desc != null) {
                            desc.setValue(
                                    (prop & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                                            ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                                            : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            );
                            g.writeDescriptor(desc);
                        }
                    }
                }
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (listener != null) listener.onDataRead(c);
        }
        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            Log.d("BLE","onCharacteristicChanged: uuid=" + c.getUuid()
                    + " value=" + new String(c.getValue(), StandardCharsets.UTF_8));
            if (listener != null) listener.onDataChanged(c);
        }
        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            Log.d("BLE", "onCharacteristicWrite: uuid=" + c.getUuid() + " status=" + status);
            if (listener != null) listener.onDataWritten(c);
        }

        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            if (listener != null && status == BluetoothGatt.GATT_SUCCESS) {
                listener.onMtuChanged(mtu);
            }
        }

    };

    public boolean hasWriteCharacteristic() {
        return writeServiceUuid != null && writeCharUuid != null;
    }

    /** Cho phép Activity/Fragment gọi MTU negotiation */
    @SuppressLint("MissingPermission")
    public boolean requestMtu(int mtu) {
        return bluetoothGatt != null && bluetoothGatt.requestMtu(mtu);
    }

    @SuppressLint("MissingPermission")
    private boolean ensureBluetoothEnabled(Activity activity) {
        if (adapter == null) {  }
        if (!adapter.isEnabled()) {
            Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            activity.startActivityForResult(enableIntent, REQ_ENABLE_BT);
            return false;
        }
        return true;
    }


    /** Listener for BLE events */
    public interface OnBleEventListener {
        void onDeviceFound(BluetoothDevice device, int rssi);
        void onScanError(int errorCode);
        void onConnectionState(int newState);
        void onServicesDiscovered(List<BluetoothGattService> services);
        void onDataRead(BluetoothGattCharacteristic characteristic);
        void onDataChanged(BluetoothGattCharacteristic characteristic);
        void onDataWritten(BluetoothGattCharacteristic characteristic);
        void onMtuChanged(int mtu);
    }
}