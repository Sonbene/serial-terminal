package com.example.bluetoothterminal;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.appcompat.app.ActionBarDrawerToggle;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.navigation.NavigationView;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class WS1812B_Activity extends AppCompatActivity
        implements NavigationView.OnNavigationItemSelectedListener {

    private static final String PREFS_NAME    = "macros_prefs";
    private static final int    REQ_BT_PERMS  = 1001;

    private DrawerLayout drawer;
    private RecyclerView rvMessages;
    private ImageButton  btnSend;
    private EditText etMessage;

    // --- MỚI ---
    private AlertDialog        scanDialog;
    private ScanDeviceAdapter  scanAdapter;

    private MessageAdapter messageAdapter;
    private final SimpleDateFormat sdf =
            new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private MenuItem statusItem;

    SpeechRecognizerManager speechManager;

    private static final int REQ_AUDIO_PERM = 200;

    // 1) Thêm TextSpeaker để TTS hỏi ngược lại
    private TextSpeaker speaker;

    // 2) Trạng thái vòng lặp
    private boolean isHotwordPhase = true;        // đang chờ “ê cu”
    private boolean isRecordingPhase = false;     // đang chờ user trả lời
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable silenceTimeout;

    // 3) Thời gian chờ silence (ms)
    private static final long SILENCE_TIMEOUT_MS = 3000;
    private static final String TAG = "WS1812B_Activity";

    private static final long RETRY_DELAY_MS = 500;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.ws2812b_layout);

        // --- Layout init ---
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{ Manifest.permission.RECORD_AUDIO },
                    REQ_AUDIO_PERM);
        } else {
            SpeechRecognizerInit();
        }

        btnSend    = findViewById(R.id.btn_send);
        rvMessages = findViewById(R.id.rv_messages);
        drawer     = findViewById(R.id.drawer_layout);
        etMessage = findViewById(R.id.et_message);


        rvMessages.setLayoutManager(new LinearLayoutManager(this));
        messageAdapter = new MessageAdapter(new ArrayList<>());
        rvMessages.setAdapter(messageAdapter);

        // Drawer + NavigationView
        NavigationView navView = findViewById(R.id.nav_view);
        navView.setNavigationItemSelectedListener(this);
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, drawer, toolbar,
                R.string.navigation_drawer_open,
                R.string.navigation_drawer_close
        );
        drawer.addDrawerListener(toggle);
        toggle.syncState();

        // Macros M1…M7
        initBtnM1(); initBtnM2(); initBtnM3();
        initBtnM4(); initBtnM5(); initBtnM6(); initBtnM7();

        // Mic & Send
        btnSend.setOnClickListener(v -> {
            btnSendMessageClick();
        });

        // Khởi Text-to-Speech
        speaker = new TextSpeaker(this);

        // BLE manager init + listener
        BleManager.init(getApplicationContext());
        BleManager.get().setListener(new BleManager.OnBleEventListener() {
            @Override
            public void onDeviceFound(BluetoothDevice device, int rssi) {
                // mỗi khi tìm được device mới, thêm vào scanAdapter
                runOnUiThread(() -> {
                    if (scanAdapter != null) {
                        scanAdapter.addOrUpdate(device);
                    }
                });
            }
            @Override public void onScanError(int errorCode) { /* TODO: show error */ }
            public void onConnectionState(int newState) {
                runOnUiThread(() -> {
                    onBleDisconnected(newState);
                    if (newState == BluetoothGatt.STATE_CONNECTED) {
                        // Yêu cầu đàm phán MTU ngay khi kết nối
                        BleManager.get().requestMtu(517);

                    }
                });
            }

            @Override
            public void onMtuChanged(int mtu) {
                runOnUiThread(() ->
                        Toast.makeText(WS1812B_Activity.this,
                                "MTU negotiated: " + mtu,
                                Toast.LENGTH_SHORT).show()
                );
            }

            @Override public void onServicesDiscovered(List<BluetoothGattService> services) { }
            @Override public void onDataRead(BluetoothGattCharacteristic characteristic) { }
            @Override
            public void onDataChanged(BluetoothGattCharacteristic c) {
                runOnUiThread(() -> {
                    String time = sdf.format(new Date());
                    String val  = new String(c.getValue(), StandardCharsets.UTF_8);
                    messageAdapter.addMessage(new Message(time, "<= " + val));
                    rvMessages.smoothScrollToPosition(messageAdapter.getItemCount() - 1);
                });
            }

            @Override public void onDataWritten(BluetoothGattCharacteristic characteristic) { }
        });

        // Đăng ký receiver
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenOnReceiver, filter);
    }

    private final BroadcastReceiver screenOnReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                BluetoothDevice last = BleManager.get().getLastDevice();
                if (!BleManager.get().isConnected() && last != null) {
                    BleManager.get().connect(WS1812B_Activity.this, last);
                }
            }
        }
    };


    private void SpeechRecognizerInit() {
        speechManager = new SpeechRecognizerManager(
                this,
                new SpeechRecognizerManager.Callback() {
                    @Override
                    public void onReady() {
                        Log.d(TAG, "SpeechRecognizer onReady()");

                    }

                    @Override
                    public void onPartialResult(String text) {
                        Log.d(TAG, "onPartialResult: \"" + text + "\" | isHotwordPhase=" + isHotwordPhase + " | isRecordingPhase=" + isRecordingPhase);
                        runOnUiThread(() -> {
                            if (isHotwordPhase) {
                                // nếu chưa detect hot-word
                                String lower = text.toLowerCase(Locale.ROOT);
                                if ((lower.contains("ê cu") || lower.contains("ecu"))) {
                                    Log.d(TAG, "Hotword detected in partialResult");
                                    enterRecordingPhase();
                                }
                            } else if (isRecordingPhase) {
                                // cập nhật live text và reset timeout
                                Log.d(TAG, "Updating live text & resetting timeout");
                                etMessage.setText(text);
                                resetSilenceTimeout();
                            }
                        });
                    }

                    @Override
                    public void onResult(String text) {
                        Log.d(TAG, "onResult: \"" + text + "\" | isRecordingPhase=" + isRecordingPhase);
                        runOnUiThread(() -> {
                            if (isRecordingPhase) {
                                handler.removeCallbacks(silenceTimeout);
                                etMessage.setText(text);
                                Log.d(TAG, "Final result received, sending via BLE");
                                sendViaBle(text);
                                exitToHotwordPhase();
                            }
                            else{
                                Log.d(TAG, "Result received but not in recording phase, ignoring");
                                if(isHotwordPhase)
                                {
                                    safeStartListening();
                                }
                            }
                        });
                    }

                    @Override
                    public void onError(String err) {
                        Log.d(TAG, "onError: \"" + err + "\" | isRecordingPhase=" + isRecordingPhase);
                        runOnUiThread(() -> {
                            // Nếu đang recording ⇒ báo lỗi rồi quay về hot-word
                            if (isRecordingPhase) {
                                Toast.makeText(WS1812B_Activity.this, err, Toast.LENGTH_SHORT).show();
                                exitToHotwordPhase();
                            } else {
                                // lỗi ở phase chờ hot-word → restart listening
                                Log.d(TAG, "Error during hotword phase, restarting listening");
                                resetSpeechRecognizer();
                            }
                        });
                    }

                    @Override
                    public void onEndOfSpeech() {
                        Log.d(TAG, "onEndOfSpeech() called");
                    }
                },
                /* forceOffline= */ false
        );
//        Log.d(TAG, "SpeechRecognizerInit(): bắt đầu hotword listening");
//        speechManager.startListening();

        Log.d(TAG, "SpeechRecognizerInit(): done, scheduling first listen");
        // bắt đầu bằng safeStartListening
        handler.postDelayed(this::safeStartListening, 100);
    }

    /** Chuyển từ phase chờ hotword → phase hỏi & ghi âm */
    private void enterRecordingPhase() {
        Log.d(TAG, "enterRecordingPhase() — TTS will ask then start listening");

        speechManager.stopListening();

        isHotwordPhase = false;
        isRecordingPhase = false;       // tạm chưa record ngay
        // 1) TTS hỏi
        speaker.speak("Anh Lâm đẹp trai muốn gửi gì nhỉ?", () -> {
            Log.d(TAG, "TTS onDone(), now UI hint & startListening()");
            runOnUiThread(() -> {
                // 2) Hiệu ứng UI
                etMessage.setText("");
                etMessage.setHint("Đang ghi âm…");
                // 3) Bật mic và ghi âm
                isRecordingPhase = true;
                speechManager.startListening();
                // 4) Khởi timeout im lặng
                startSilenceTimeout();
            });
        });
    }

    /** Đặt hoặc reset timeout 3s khi recording */
    private void startSilenceTimeout() {
        if (silenceTimeout == null) {
            silenceTimeout = () -> {
                Log.d(TAG, "silenceTimeout triggered — no speech for 3s");
                if (isRecordingPhase) {
                    // dừng ghi âm
                    speechManager.stopListening();
                    // gửi text hiện tại (nếu có)
                    String msg = etMessage.getText().toString().trim();
                    Log.d(TAG, "silenceTimeout sending msg=\"" + msg + "\"");
                    sendViaBle(msg);
                    exitToHotwordPhase();
                }
            };
        }
        handler.removeCallbacks(silenceTimeout);
        handler.postDelayed(silenceTimeout, SILENCE_TIMEOUT_MS);
        Log.d(TAG, "startSilenceTimeout() scheduled in " + SILENCE_TIMEOUT_MS + "ms");
    }

    private void resetSilenceTimeout() {
        Log.d(TAG, "resetSilenceTimeout()");
        handler.removeCallbacks(silenceTimeout);
        handler.postDelayed(silenceTimeout, SILENCE_TIMEOUT_MS);
    }

    /** Quay về phase chờ hotword, reset UI & restart mic */
    private void exitToHotwordPhase() {
        Log.d(TAG, "exitToHotwordPhase() — prepare for hotword");
        isHotwordPhase = true;
        isRecordingPhase = false;
        handler.removeCallbacks(silenceTimeout);
        etMessage.setHint("Nói “ê cu” để gửi");
        etMessage.setText("");
        // recreate recognizer & schedule safeStartListening()
        speechManager.destroy();
        SpeechRecognizerInit();
    }


    private void safeStartListening() {
        try {
            Log.d(TAG, "safeStartListening(): attempting startListening()");
            speechManager.startListening();
        } catch (RuntimeException e) {
            Log.d(TAG, "safeStartListening(): failed, will retry in " + RETRY_DELAY_MS + "ms", e);
            handler.postDelayed(this::safeStartListening, RETRY_DELAY_MS);
        }
    }

    private void resetSpeechRecognizer() {
        Log.d(TAG, "resetSpeechRecognizer() — destroy & re-init");
        // 1) Hủy recognizer cũ
        speechManager.destroy();
        // 2) Tạo lại và auto startListening()
        SpeechRecognizerInit();
    }

    /** Dừng cũ rồi start lại hot-word listening */
    private void restartHotwordListening() {
        Log.d(TAG, "restartHotwordListening()");
        speechManager.stopListening();
        speechManager.startListening();
    }


    // --- Toolbar menu (connect/delete/more) ---
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.toolbar_menu, menu);
        // tìm và lưu reference
        statusItem = menu.findItem(R.id.action_status_connect);
        // khởi tạo icon ban đầu
        updateStatusIcon(BleManager.get().isConnected());
        return true;
    }

    private void updateStatusIcon(boolean connected) {
        if (statusItem == null) return;
        // chọn drawable tương ứng
        int iconRes = connected
                ? R.drawable.ic_status_connected   // icon khi đã kết nối
                : R.drawable.ic_status_disconnected; // icon khi ngắt kết nối
        statusItem.setIcon(iconRes);
    }



    private void btnSendMessageClick()
    {
        if(BleManager.get().isConnected())
        {
            String msg = ((EditText)findViewById(R.id.et_message)).getText().toString().trim();
            if (msg.isEmpty()) return;
            sendViaBle(msg);
            ((EditText)findViewById(R.id.et_message)).setText("");
        }
        else
        {
            Toast.makeText(this, "Chưa kết nối bluetooth", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_connect) {
            checkPermissionsAndScan();
            return true;
        } else if (id == R.id.action_delete) {
            messageAdapter.clearMessages();
            // (tuỳ chọn) cuộn lên đầu
            rvMessages.scrollToPosition(0);
            return true;
        } else if (id == R.id.action_more) {
            // TODO: show extra options
            return true;
        } else if (id == R.id.action_status_connect) {
            if (BleManager.get().isConnected()) {
                updateStatusIcon(false);
                // đang kết nối → ngắt
                BleManager.get().disconnect();
            } else {
                // chưa kết nối → thử reconnect với lastDevice nếu có
                BluetoothDevice last = BleManager.get().getLastDevice();
                if (last != null) {
                    // cần kiểm tra permission trước khi connect
                    if (hasBleConnectPermission()) {
                        BleManager.get().connect(this, last);
                        if(BleManager.get().isConnected())
                        {
                            updateStatusIcon(true);
                        }

                    } else {
                        // nếu chưa có quyền thì request rồi mới connect
                        checkPermissionsAndScan();
                    }
                } else {
                    // lần đầu hoặc chưa chọn device nào → scan & chọn như cũ
                    checkPermissionsAndScan();
                }
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** Kiểm tra xem có quyền BLUETOOTH_CONNECT hay không */
    private boolean hasBleConnectPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void onBleDisconnected(int newState) {
        String time = sdf.format(new Date());

        if (newState == BluetoothGatt.STATE_CONNECTED) {
            @SuppressLint("MissingPermission") String name = BleManager.get()
                    .getLastDevice().getName();
            messageAdapter.addMessage(
                    new Message(time, "✓ Connected to \"" + name + "\"")
            );

        }
        else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
            messageAdapter.addMessage(
                    new Message(time, "✗ Disconnected")
            );
        }

        boolean connected = (newState == BluetoothGatt.STATE_CONNECTED);
        updateStatusIcon(connected);

        rvMessages.smoothScrollToPosition(
                messageAdapter.getItemCount() - 1
        );
    }

    // --- Permission & scan BLE → show dialog ---
    private void checkPermissionsAndScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                            != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT
                }, REQ_BT_PERMS);
                return;
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION
                }, REQ_BT_PERMS);
                return;
            }
        }
        showScanDialogAndStart();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BT_PERMS) {
            boolean ok = true;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) {
                    ok = false;
                    break;
                }
            }
            if (ok) showScanDialogAndStart();
            else {
                // TODO: thông báo user cần cấp quyền
            }
        }

        if (requestCode == REQ_AUDIO_PERM) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                SpeechRecognizerInit();
            } else {
                Toast.makeText(this,
                        "Ứng dụng cần quyền Microphone để ghi âm",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == BleManager.get_REQ_ENABLE_BT()) {
            if (resultCode == RESULT_OK) {
                Toast.makeText(this, "Bluetooth đã được bật", Toast.LENGTH_SHORT).show();
                // Nếu bạn muốn tự động scan hoặc connect lại, gọi ở đây
            } else {
                Toast.makeText(this, "Cần bật Bluetooth để tiếp tục", Toast.LENGTH_SHORT).show();
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Nếu chưa kết nối và đã có thiết bị trước đó
        if (!BleManager.get().isConnected() && BleManager.get().getLastDevice() != null) {
            BleManager.get().connect(this, BleManager.get().getLastDevice());
            updateStatusIcon(true); // nếu bạn muốn cập nhật icon ngay lập tức
        }
    }



    /**
     * Hiện dialog scan devices và gọi startScan()
     */
    @SuppressLint("MissingPermission")
    private void showScanDialogAndStart() {
        // Inflate layout chứa RecyclerView
        View dlgView = LayoutInflater.from(this)
                .inflate(R.layout.dialog_scan_devices, null);
        RecyclerView rv = dlgView.findViewById(R.id.rv_scan_results);
        rv.setLayoutManager(new LinearLayoutManager(this));

        // Tạo adapter và click listener
        scanAdapter = new ScanDeviceAdapter(device -> {
            BleManager.get().stopScan();
            if (scanDialog != null && scanDialog.isShowing()) {
                scanDialog.dismiss();
            }
            // TODO: sau khi chọn, connect lên
            BleManager.get().connect(this, device);
        });
        rv.setAdapter(scanAdapter);

        // Tạo AlertDialog
        scanDialog = new AlertDialog.Builder(this)
                .setTitle("Scanning BLE Devices")
                .setView(dlgView)
                .setNegativeButton("Cancel", (d, w) -> {
                    BleManager.get().stopScan();
                })
                .create();
        scanDialog.show();

        // Bắt đầu quét
        BleManager.get().startScan(this);
    }

    // --- Drawer back handling ---
    @Override
    public void onBackPressed() {
        if (drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.closeDrawer(GravityCompat.START);
        } else {
            super.onBackPressed();
        }
    }

    // --- NavigationView item click ---

    // --- Macro buttons logic (unchanged) ---
    private void initBtnM1() { initMacroButton(R.id.btn_m1, 1); }
    private void initBtnM2() { initMacroButton(R.id.btn_m2, 2); }
    private void initBtnM3() { initMacroButton(R.id.btn_m3, 3); }
    private void initBtnM4() { initMacroButton(R.id.btn_m4, 4); }
    private void initBtnM5() { initMacroButton(R.id.btn_m5, 5); }
    private void initBtnM6() { initMacroButton(R.id.btn_m6, 6); }
    private void initBtnM7() { initMacroButton(R.id.btn_m7, 7); }

    private void initMacroButton(int viewId, int idx) {
        MaterialButton btn = findViewById(viewId);
        String savedName = getPrefs().getString("macro" + idx + "_name", "M" + idx);
        btn.setText(savedName);
        btn.setOnClickListener(v -> onMacroClicked(idx));
        btn.setOnLongClickListener(v -> {
            showMacroConfigDialog(idx, viewId);
            return true;
        });
    }

    private void onMacroClicked(int idx) {
        String value = getPrefs().getString("macro" + idx + "_value", "");
        if (!value.isEmpty()) {
            sendViaBle(value);
        }
    }

    private void showMacroConfigDialog(int idx, int btnViewId) {
        View dlg = LayoutInflater.from(this).inflate(R.layout.dialog_macro_config, null);
        EditText etName  = dlg.findViewById(R.id.et_macro_name);
        EditText etValue = dlg.findViewById(R.id.et_macro_value);
        RadioGroup rgMode   = dlg.findViewById(R.id.rg_edit_mode);
        RadioGroup rgAction = dlg.findViewById(R.id.rg_action);
        CheckBox cbRepeat   = dlg.findViewById(R.id.cb_repeat);

        SharedPreferences prefs = getPrefs();
        etName.setText(prefs.getString("macro" + idx + "_name", "M" + idx));
        etValue.setText(prefs.getString("macro" + idx + "_value", ""));
        rgMode.check(prefs.getInt("macro" + idx + "_mode", R.id.rb_mode_text));
        rgAction.check(prefs.getInt("macro" + idx + "_action", R.id.rb_action_send));
        cbRepeat.setChecked(prefs.getBoolean("macro" + idx + "_repeat", false));

        new AlertDialog.Builder(this)
                .setTitle("Edit Macro " + idx)
                .setView(dlg)
                .setPositiveButton("Save", (d, w) -> {
                    SharedPreferences.Editor e = getPrefs().edit();
                    e.putString("macro" + idx + "_name", etName.getText().toString());
                    e.putString("macro" + idx + "_value", etValue.getText().toString());
                    e.putInt("macro" + idx + "_mode", rgMode.getCheckedRadioButtonId());
                    e.putInt("macro" + idx + "_action", rgAction.getCheckedRadioButtonId());
                    e.putBoolean("macro" + idx + "_repeat", cbRepeat.isChecked());
                    e.apply();
                    ((MaterialButton)findViewById(btnViewId))
                            .setText(etName.getText().toString());
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @SuppressLint("MissingPermission")
    private void sendViaBle(String text) {
        if (!BleManager.get().isConnected()) {
            Toast.makeText(this, "Chưa kết nối BLE", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!BleManager.get().hasWriteCharacteristic()) {
            Toast.makeText(this, "Chưa xác định được characteristic để ghi", Toast.LENGTH_SHORT).show();
            return;
        }
        // ghi text qua BLE
        BleManager.get().write(text);

        // và log lại trên RecyclerView
        String time = sdf.format(new Date());
        messageAdapter.addMessage(new Message(time, "→ " + text));
        rvMessages.smoothScrollToPosition(messageAdapter.getItemCount() - 1);
    }

    private void onMicClicked() {
        if(speechManager.isListening() ==  true)
        {
            speechManager.stopListening();
            etMessage.setHint("Nhập tin nhắn...");
        }
        else
        {
            etMessage.setHint("Đang ghi âm...");
            speechManager.startListening();
        }
    }

    private void onSendClicked(String message) {
        if (message.trim().isEmpty()) return;
        sendViaBle(message);

        // log lên RecyclerView
        String time = sdf.format(new Date());
        messageAdapter.addMessage(new Message(time, "→ " + message));
        rvMessages.smoothScrollToPosition(messageAdapter.getItemCount() - 1);

        ((EditText)findViewById(R.id.et_message)).setText("");
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
    }

    /** Hủy recognizer cũ và tạo mới, đồng thời start hotword listening */


    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        speechManager.destroy();
        speaker.destroy();
        unregisterReceiver(screenOnReceiver);
    }


    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        drawer.closeDrawer(GravityCompat.START);

        Intent intent = null;
        int id = item.getItemId();
        if (id == R.id.nav_terminal) intent = new Intent(this, terminal_Activity.class);
        // TODO: xử lý từng mục trong drawer
        if (intent != null) { startActivity(intent); finish(); }
        return true;
    }
}
