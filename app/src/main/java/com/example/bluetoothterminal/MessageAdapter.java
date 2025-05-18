package com.example.bluetoothterminal;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.VH> {
    private final List<Message> messages;

    public MessageAdapter(List<Message> messages) {
        this.messages = messages;
    }

    public void addMessage(Message msg) {
        messages.add(msg);
        notifyItemInserted(messages.size() - 1);
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_message, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        Message m = messages.get(position);
        holder.tvTime.setText(m.getTime());
        holder.tvContent.setText(m.getContent());
    }

    @Override public int getItemCount() {
        return messages.size();
    }

    public void clearMessages() {
        int size = messages.size();
        messages.clear();
        // Thông báo adapter rằng items đã bị xoá
        notifyItemRangeRemoved(0, size);
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvTime, tvContent;
        VH(View itemView) {
            super(itemView);
            tvTime    = itemView.findViewById(R.id.tvTime);
            tvContent = itemView.findViewById(R.id.tvContent);
        }
    }
}
