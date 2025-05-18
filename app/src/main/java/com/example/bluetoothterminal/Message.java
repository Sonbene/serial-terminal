package com.example.bluetoothterminal;

public class Message {
    private final String time;
    private final String content;

    public Message(String time, String content) {
        this.time = time;
        this.content = content;
    }

    public String getTime() {
        return time;
    }

    public String getContent() {
        return content;
    }
}
