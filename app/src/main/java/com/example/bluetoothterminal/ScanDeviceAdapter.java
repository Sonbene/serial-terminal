package com.example.bluetoothterminal;  // <-- thêm dòng này

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

// ScanDeviceAdapter.java
public class ScanDeviceAdapter
        extends RecyclerView.Adapter<ScanDeviceAdapter.VH> {

    public interface OnClick {
        void onClick(BluetoothDevice device);
    }

    private final List<BluetoothDevice> devices = new ArrayList<>();
    private final OnClick listener;

    public ScanDeviceAdapter(OnClick l) { listener = l; }

    public void addOrUpdate(BluetoothDevice d) {
        // tránh duplicate
        for (BluetoothDevice dev : devices)
            if (dev.getAddress().equals(d.getAddress())) return;
        devices.add(d);
        notifyItemInserted(devices.size()-1);
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(android.R.layout.simple_list_item_2, parent, false);
        return new VH(v);
    }

    @SuppressLint("MissingPermission")  // <-- tắt cảnh báo tại đây
    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        BluetoothDevice d = devices.get(pos);
        h.title.setText(d.getName() != null? d.getName(): d.getAddress());
        h.subtitle.setText(d.getAddress());
        h.itemView.setOnClickListener(v -> listener.onClick(d));
    }

    @Override public int getItemCount() { return devices.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView title, subtitle;
        VH(View v) {
            super(v);
            title    = v.findViewById(android.R.id.text1);
            subtitle = v.findViewById(android.R.id.text2);
        }
    }
}
