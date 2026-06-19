package com.xiejinyi.ideacards;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.xiejinyi.ideacards.data.entity.NoteEntity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 归档页 RecyclerView 适配器：
 * 支持普通模式（点击查看/长按菜单）和编辑模式（复选框多选/批量删除）。
 */
public class NoteListAdapter extends RecyclerView.Adapter<NoteListAdapter.ViewHolder> {

    private static final String DATE_FORMAT = "yyyy-MM-dd HH:mm";
    private static final int STATUS_NORMAL = 0;
    private static final int STATUS_ARCHIVED = 1;

    /** 标签匹配正则：与 MainActivity / BubbleAdapter 保持一致 */
    private static final Pattern TAG_PATTERN = Pattern.compile("#[^\\s#]+");

    private final LayoutInflater inflater;
    private final List<NoteEntity> notes = new ArrayList<>();

    // ═══ 回调接口 ═══

    /** 单击回调：普通模式下点击卡片 */
    public interface OnNoteClickListener {
        void onNoteClick(long noteId);
    }

    /** 长按回调：长按卡片时触发（用于弹出 PopupMenu） */
    public interface OnNoteLongClickListener {
        void onNoteLongClick(long noteId, View anchorView);
    }

    private OnNoteClickListener clickListener;
    private OnNoteLongClickListener longClickListener;

    // ═══ 编辑模式状态 ═══

    /** 是否处于编辑模式 */
    private boolean selectionMode = false;
    /** 已选中的笔记 ID 集合 */
    private final Set<Long> selectedIds = new HashSet<>();

    public NoteListAdapter(Context context) {
        this.inflater = LayoutInflater.from(context);
    }

    public void setOnNoteClickListener(OnNoteClickListener listener) {
        this.clickListener = listener;
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

    // ═══ 编辑模式控制 ═══

    /** 开启编辑模式：显示所有复选框 */
    public void setSelectionMode(boolean enabled) {
        if (this.selectionMode != enabled) {
            this.selectionMode = enabled;
            if (!enabled) {
                selectedIds.clear();
            }
            notifyDataSetChanged();
        }
    }

    /** 当前是否处于编辑模式 */
    public boolean isSelectionMode() {
        return selectionMode;
    }

    /** 获取所有已选中的笔记 ID */
    public List<Long> getSelectedIds() {
        return new ArrayList<>(selectedIds);
    }

    /** 清除所有选中状态 */
    public void clearSelection() {
        if (!selectedIds.isEmpty()) {
            selectedIds.clear();
            notifyDataSetChanged();
        }
    }

    // ═══ RecyclerView 核心方法 ═══

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View itemView = inflater.inflate(R.layout.item_note, parent, false);
        return new ViewHolder(itemView);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        NoteEntity note = notes.get(position);

        // 内容摘要：剔除行内 #标签，只显示纯正文
        holder.tvSummary.setText(stripTags(note.getContent()));

        // 时间戳
        SimpleDateFormat sdf = new SimpleDateFormat(DATE_FORMAT, Locale.CHINA);
        holder.tvTime.setText(sdf.format(new Date(note.getTimestamp())));

        // 标签：有标签显示 #标签名，无标签则隐藏
        if (note.getTag() != null && !note.getTag().isEmpty()) {
            holder.tvStatus.setText("#" + note.getTag());
            holder.tvStatus.setVisibility(View.VISIBLE);
        } else {
            holder.tvStatus.setVisibility(View.GONE);
        }

        if (selectionMode) {
            // ── 编辑模式：显示复选框，点击切换勾选状态 ──
            holder.cbSelect.setVisibility(View.VISIBLE);
            holder.cbSelect.setOnCheckedChangeListener(null);
            holder.cbSelect.setChecked(selectedIds.contains(note.getId()));
            holder.cbSelect.setOnCheckedChangeListener((btn, checked) -> {
                if (checked) {
                    selectedIds.add(note.getId());
                } else {
                    selectedIds.remove(note.getId());
                }
            });

            // 点击整个卡片也可以切换复选框
            holder.itemView.setOnClickListener(v -> holder.cbSelect.toggle());
        } else {
            // ── 普通模式：隐藏复选框，点击查看详情 ──
            holder.cbSelect.setVisibility(View.GONE);
            holder.cbSelect.setOnCheckedChangeListener(null);
            holder.cbSelect.setChecked(false);

            holder.itemView.setOnClickListener(v -> {
                if (clickListener != null) {
                    clickListener.onNoteClick(note.getId());
                }
            });
        }

        // 长按：两种模式都弹出 PopupMenu
        holder.itemView.setOnLongClickListener(v -> {
            if (longClickListener != null) {
                longClickListener.onNoteLongClick(note.getId(), v);
            }
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return notes.size();
    }

    /**
     * 剔除文本中的所有行内标签（#xxx），返回纯正文。
     * 与 BubbleAdapter.stripTags 逻辑一致。
     */
    private String stripTags(String text) {
        if (text == null) return "";
        return TAG_PATTERN.matcher(text).replaceAll("").replaceAll("\\s+", " ").trim();
    }

    /**
     * ViewHolder：持有 item_note.xml 中的所有控件引用。
     */
    static class ViewHolder extends RecyclerView.ViewHolder {

        final CheckBox cbSelect;
        final TextView tvSummary;
        final TextView tvTime;
        final TextView tvStatus;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            cbSelect = itemView.findViewById(R.id.cb_select);
            tvSummary = itemView.findViewById(R.id.tv_summary);
            tvTime = itemView.findViewById(R.id.tv_time);
            tvStatus = itemView.findViewById(R.id.tv_status);
        }
    }
}
