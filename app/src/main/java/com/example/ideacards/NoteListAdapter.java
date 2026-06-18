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
 * RecyclerView 适配器：将 NoteEntity 列表渲染为笔记卡片列表。
 * 每张卡片展示内容摘要、时间和状态三个字段。
 */
public class NoteListAdapter extends RecyclerView.Adapter<NoteListAdapter.ViewHolder> {

    /** 时间格式化模板，线程安全方式每次在绑定数据时使用 */
    private static final String DATE_FORMAT = "yyyy-MM-dd HH:mm";

    /** status=0 为普通笔记，status=1 为已归档笔记 */
    private static final int STATUS_NORMAL = 0;
    private static final int STATUS_ARCHIVED = 1;

    private final LayoutInflater inflater;
    private final List<NoteEntity> notes = new ArrayList<>();

    /** 点击回调接口：外部（Activity）实现此接口以响应卡片点击 */
    public interface OnNoteClickListener {
        void onNoteClick(long noteId);
    }

    private OnNoteClickListener clickListener;

    /**
     * 设置点击监听器。
     */
    public void setOnNoteClickListener(OnNoteClickListener listener) {
        this.clickListener = listener;
    }

    public NoteListAdapter(Context context) {
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
        View itemView = inflater.inflate(R.layout.item_note, parent, false);
        return new ViewHolder(itemView);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        NoteEntity note = notes.get(position);

        // 内容摘要：直接展示原文，TextView 的 maxLines+ellipsize 自动截断
        holder.tvSummary.setText(note.getContent());

        // 格式化时间戳为可读字符串
        SimpleDateFormat sdf = new SimpleDateFormat(DATE_FORMAT, Locale.CHINA);
        holder.tvTime.setText(sdf.format(new Date(note.getTimestamp())));

        // 根据 status 字段显示状态文案
        if (note.getStatus() == STATUS_ARCHIVED) {
            holder.tvStatus.setText("已归档");
        } else {
            holder.tvStatus.setText("普通");
        }

        // 卡片点击：将笔记 ID 回传给外部监听器（跳转详情页）
        holder.itemView.setOnClickListener(v -> {
            if (clickListener != null) {
                clickListener.onNoteClick(note.getId());
            }
        });
    }

    @Override
    public int getItemCount() {
        return notes.size();
    }

    /**
     * ViewHolder：持有 item_note.xml 中的三个控件引用。
     */
    static class ViewHolder extends RecyclerView.ViewHolder {

        final TextView tvSummary;
        final TextView tvTime;
        final TextView tvStatus;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvSummary = itemView.findViewById(R.id.tv_summary);
            tvTime = itemView.findViewById(R.id.tv_time);
            tvStatus = itemView.findViewById(R.id.tv_status);
        }
    }
}
