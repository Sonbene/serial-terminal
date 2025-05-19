package com.example.bluetoothterminal;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;
import java.util.Locale;

public class SpeechRecognizerManager {

    public interface Callback {
        void onReady();
        void onResult(String text);
        void onError(String errorMessage);
        void onEndOfSpeech();
        void onPartialResult(String text);
    }

    private final SpeechRecognizer speechRecognizer;
    private final Intent recognizerIntent;
    private final Callback callback;
    private final boolean forceOffline;
    private boolean isListening = false;

    public boolean isListening() { return isListening; }



    /**
     * @param context       Context
     * @param callback      Nhận kết quả và trạng thái
     * @param forceOffline  true: luôn dùng offline; false: tự chọn online/offline
     */
    public SpeechRecognizerManager(Context context,
                                   Callback callback,
                                   boolean forceOffline) {
        this.callback = callback;
        this.forceOffline = forceOffline;

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {
                callback.onReady();
                isListening = true;
            }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float rmsdB) { }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() {
                callback.onEndOfSpeech();
            }
            @Override public void onError(int error) {
                String msg;
                switch (error) {
                    case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                    case SpeechRecognizer.ERROR_NETWORK:
                        msg = "Lỗi mạng, vui lòng kiểm tra kết nối";
                        break;
                    case SpeechRecognizer.ERROR_AUDIO:
                        msg = "Lỗi ghi âm";
                        break;
                    case SpeechRecognizer.ERROR_NO_MATCH:
                        msg = "Không nhận diện được giọng nói";
                        break;
                    case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                        msg = "Máy bận, thử lại sau";
                        break;
                    case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                        msg = "Chưa có quyền Microphone";
                        break;
                    default:
                        msg = "Lỗi không xác định (" + error + ")";
                }
                callback.onError(msg);
                isListening = false;
            }



            @Override public void onResults(Bundle results) {
                isListening = false;
                ArrayList<String> matches =
                        results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    callback.onResult(matches.get(0));
                } else {
                    callback.onError("Kết quả trống");
                }

            }
            public void onPartialResults(Bundle partialResults) {
                ArrayList<String> partial = partialResults.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION);
                if (partial != null && !partial.isEmpty()) {
                    callback.onPartialResult(partial.get(0));
                }
            }
            @Override public void onEvent(int eventType, Bundle params) { }
        });

        recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        recognizerIntent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        recognizerIntent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                Locale.getDefault());
        // Cho phép offline nếu cần
        recognizerIntent.putExtra(
                RecognizerIntent.EXTRA_PREFER_OFFLINE,
                forceOffline || !isNetworkAvailable(context));

        // Sau khi tạo recognizerIntent:
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        // giảm thời gian chờ silence sau khi bạn ngừng nói (ms)
        recognizerIntent.putExtra("android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 300);
        // giảm thời gian tối thiểu cần nói để kết thúc (ms)
        recognizerIntent.putExtra("android.speech.extra.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS", 150);


    }

    /** Bắt đầu ghi âm và nhận kết quả */
    public void startListening() {
        if (!isListening) {
            // cập nhật lại EXTRA_PREFER_OFFLINE theo thời điểm gọi
            recognizerIntent.putExtra(
                    RecognizerIntent.EXTRA_PREFER_OFFLINE,
                    forceOffline);
            speechRecognizer.startListening(recognizerIntent);
        }
    }

    /** Dừng ghi âm */
    public void stopListening() {
        if (isListening) {
            speechRecognizer.stopListening();
        }
    }


    /** Giải phóng tài nguyên */
    public void destroy() {
        speechRecognizer.destroy();
    }

    /** Kiểm tra mạng */
    private boolean isNetworkAvailable(Context ctx) {
        ConnectivityManager cm =
                (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkInfo info = cm.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }
}
