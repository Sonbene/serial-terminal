package com.example.bluetoothterminal;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.Locale;
import java.util.UUID;

/**
 * TextSpeaker: reusable TextToSpeech manager
 */
public class TextSpeaker {
    private TextToSpeech tts;
    private boolean ready = false;

    /**
     * Khởi tạo TextToSpeech.
     * @param context Context của ứng dụng (dùng getApplicationContext())
     */
    public TextSpeaker(Context context) {
        tts = new TextToSpeech(context.getApplicationContext(), status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.getDefault());
                ready = true;
            }
        });
    }

    /**
     * Phát âm văn bản.
     * @param text  Văn bản cần nói.
     * @param onDone  Callback chạy khi đã nói xong (nullable).
     */
    public void speak(String text, Runnable onDone) {
        if (!ready) return;
        String utteranceId = UUID.randomUUID().toString();

        if (onDone != null) {
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String id) { }
                @Override
                public void onError(String id) { }
                @Override
                public void onDone(String id) {
                    if (id.equals(utteranceId)) {
                        onDone.run();
                    }
                }
            });
        }

        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
    }

    /**
     * Kiểm tra đã sẵn sàng chưa.
     */
    public boolean isReady() {
        return ready;
    }

    /**
     * Giải phóng tài nguyên khi không dùng nữa.
     */
    public void destroy() {
        if (tts != null) {
            tts.shutdown();
            tts = null;
            ready = false;
        }
    }
}
