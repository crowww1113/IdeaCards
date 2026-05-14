package com.example.ideacards;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.ideacards.data.entity.NoteEntity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * RecyclerView 适配器：将 NoteEntity 列表渲染为聊天气泡列表。
 */
public class BubbleAdapter extends RecyclerView.Adapter<BubbleAdapter.ViewHolder> {

    /** 时间格式化器，线程安全方式每次在绑定数据时使用 */
    private static final String DATE_FORMAT = "yyyy-MM-dd HH:mm";

    private final LayoutInflater inflater;
    private final List<NoteEntity> notes = new ArrayList<>();

    public BubbleAdapter(Context context) {
        this.inflater = LayoutInflater.from(context);
    }

    /**
     * 替换整个数据列表并刷新 UI。
     * 调用方负责切换到主线程。
     */
    public void setData(List<NoteEntity> newNotes) {
        notes.clear();
        notes.addAll(newNotes);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View itemView = inflater.inflate(R.layout.item_bubble, parent, false);
        return new ViewHolder(itemView);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        NoteEntity note = notes.get(position);

        // 设置笔记内容
        holder.tvContent.setText(note.getContent());

        // 格式化时间戳为可读字符串
        SimpleDateFormat sdf = new SimpleDateFormat(DATE_FORMAT, Locale.CHINA);
        String timeStr = sdf.format(new Date(note.getTimestamp()));
        holder.tvTimestamp.setText(timeStr);
    }

    @Override
    public int getItemCount() {
        return notes.size();
    }

    /**
     * ViewHolder：持有 item_bubble.xml 中的各个控件引用。
     */
    static class ViewHolder extends RecyclerView.ViewHolder {

        final TextView tvContent;
        final TextView tvTimestamp;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvContent = itemView.findViewById(R.id.tv_content);
            tvTimestamp = itemView.findViewById(R.id.tv_timestamp);
        }
    }
}
