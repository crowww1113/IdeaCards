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
import java.util.regex.Pattern;

/**
 * 主页聊天气泡适配器：渲染笔记为气泡列表。
 * 支持长按回调（用于弹出 PopupMenu）。
 */
public class BubbleAdapter extends RecyclerView.Adapter<BubbleAdapter.ViewHolder> {

    private static final String DATE_FORMAT = "yyyy-MM-dd HH:mm";

    /** 标签匹配正则：与 MainActivity 保持一致，用于剔除内容中的行内标签 */
    private static final Pattern TAG_PATTERN = Pattern.compile("#[^\\s#]+");

    private final LayoutInflater inflater;
    private final List<NoteEntity> notes = new ArrayList<>();

    /** 长按回调接口：外部 Activity 实现，用于弹出 PopupMenu */
    public interface OnNoteLongClickListener {
        void onNoteLongClick(long noteId, View anchorView);
    }

    private OnNoteLongClickListener longClickListener;

    public BubbleAdapter(Context context) {
        this.inflater = LayoutInflater.from(context);
    }

    public void setOnNoteLongClickListener(OnNoteLongClickListener listener) {
        this.longClickListener = listener;
    }

    /**
     * 替换整个数据列表并刷新 UI。
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

        // 设置笔记内容：剔除行内 #标签，只显示纯正文
        // （兼容旧数据：修复前创建的笔记 content 中可能包含标签文字）
        String displayContent = stripTags(note.getContent());
        holder.tvContent.setText(displayContent);

        // 格式化时间戳
        SimpleDateFormat sdf = new SimpleDateFormat(DATE_FORMAT, Locale.CHINA);
        holder.tvTimestamp.setText(sdf.format(new Date(note.getTimestamp())));

        // 长按：触发回调，传入 anchorView 用于定位 PopupMenu
        holder.itemView.setOnLongClickListener(v -> {
            if (longClickListener != null) {
                longClickListener.onNoteLongClick(note.getId(), v);
            }
            return true;
        });
    }

    /**
     * 剔除文本中的所有行内标签（#xxx），返回纯正文。
     * 用于兼容修复前创建的旧笔记，避免标签文字显示在气泡中。
     */
    private String stripTags(String text) {
        if (text == null) return "";
        return TAG_PATTERN.matcher(text).replaceAll("").replaceAll("\\s+", " ").trim();
    }

    @Override
    public int getItemCount() {
        return notes.size();
    }

    /**
     * ViewHolder：持有 item_bubble.xml 中的控件引用。
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
