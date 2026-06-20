package com.xiejinyi.ideacards;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.xiejinyi.ideacards.data.entity.NoteEntity;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 微信风格聊天气泡适配器：多 ViewType 渲染时间分隔线 + 右对齐气泡。
 * 支持长按回调（用于弹出 PopupMenu）。
 */
public class BubbleAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_TIME = 0;
    private static final int TYPE_BUBBLE = 1;

    /** 两条笔记间隔超过此值（毫秒）则插入时间分隔线 */
    private static final long TIME_HEADER_THRESHOLD = 5 * 60 * 1000;

    /** 标签匹配正则：剔除内容中的行内标签 */
    private static final Pattern TAG_PATTERN = Pattern.compile("#[^\\s#]+");

    private final LayoutInflater inflater;

    /** 混合列表：String = 时间分隔线文字，NoteEntity = 气泡笔记 */
    private final List<Object> items = new ArrayList<>();

    /** 长按回调接口（携带触摸坐标，用于定位浮窗） */
    public interface OnNoteLongClickListener {
        void onNoteLongClick(long noteId, View anchorView, int touchX, int touchY);
    }

    private OnNoteLongClickListener longClickListener;

    public BubbleAdapter(Context context) {
        this.inflater = LayoutInflater.from(context);
    }

    public void setOnNoteLongClickListener(OnNoteLongClickListener listener) {
        this.longClickListener = listener;
    }

    /**
     * 将笔记列表转换为混合列表（时间分隔线 + 气泡），刷新 UI。
     */
    public void setData(List<NoteEntity> notes) {
        items.clear();

        for (int i = 0; i < notes.size(); i++) {
            NoteEntity note = notes.get(i);

            // 第一条笔记，或与上一条间隔超过阈值 → 插入时间分隔线
            if (i == 0) {
                items.add(formatSmartTime(note.getTimestamp()));
            } else {
                long prevTimestamp = notes.get(i - 1).getTimestamp();
                if (note.getTimestamp() - prevTimestamp > TIME_HEADER_THRESHOLD) {
                    items.add(formatSmartTime(note.getTimestamp()));
                }
            }

            items.add(note);
        }

        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return (items.get(position) instanceof String) ? TYPE_TIME : TYPE_BUBBLE;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_TIME) {
            View view = inflater.inflate(R.layout.item_time_header, parent, false);
            return new TimeViewHolder(view);
        } else {
            View view = inflater.inflate(R.layout.item_bubble, parent, false);
            return new BubbleViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof TimeViewHolder) {
            ((TimeViewHolder) holder).tvTime.setText((String) items.get(position));
        } else if (holder instanceof BubbleViewHolder) {
            BubbleViewHolder bubbleHolder = (BubbleViewHolder) holder;
            NoteEntity note = (NoteEntity) items.get(position);

            // 笔记内容：剔除行内标签
            bubbleHolder.tvContent.setText(stripTags(note.getContent()));

            // 标签 pill：有标签时显示
            if (note.getTag() != null && !note.getTag().trim().isEmpty()) {
                bubbleHolder.tvTag.setText("#" + note.getTag().trim());
                bubbleHolder.tvTag.setVisibility(View.VISIBLE);
            } else {
                bubbleHolder.tvTag.setVisibility(View.GONE);
            }

            // 记录触摸坐标，供长按浮窗定位使用
            final int[] touchPos = new int[2];
            bubbleHolder.itemView.setOnTouchListener((v, event) -> {
                if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                    touchPos[0] = (int) event.getRawX();
                    touchPos[1] = (int) event.getRawY();
                }
                return false; // 不消费，让长按事件正常触发
            });

            // 长按：触发回调（携带触摸坐标）
            bubbleHolder.itemView.setOnLongClickListener(v -> {
                if (longClickListener != null) {
                    longClickListener.onNoteLongClick(note.getId(), v,
                            touchPos[0], touchPos[1]);
                }
                return true;
            });
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    // ═══════════════════════════════════════
    //  时间格式化
    // ═══════════════════════════════════════

    /**
     * 智能时间格式化：
     * - 同一天：HH:mm（如 14:30）
     * - 昨天：昨天 HH:mm
     * - 同月：d号 HH:mm（如 15号 14:30）
     * - 同年：M月d号（如 6月15号）
     * - 跨年：yyyy年M月d号（如 2025年6月15号）
     */
    private String formatSmartTime(long timestamp) {
        Calendar note = Calendar.getInstance();
        note.setTimeInMillis(timestamp);
        Calendar now = Calendar.getInstance();

        int noteYear = note.get(Calendar.YEAR);
        int noteDay = note.get(Calendar.DAY_OF_YEAR);
        int nowYear = now.get(Calendar.YEAR);
        int nowDay = now.get(Calendar.DAY_OF_YEAR);

        boolean sameYear = (noteYear == nowYear);
        boolean sameDay = sameYear && (noteDay == nowDay);
        boolean isYesterday = sameYear && (nowDay - noteDay == 1);

        // 跨年检查：去年12月31日 → 今年1月1日也算昨天
        if (!sameYear && nowYear - noteYear == 1) {
            Calendar lastDayOfNoteYear = Calendar.getInstance();
            lastDayOfNoteYear.set(noteYear, Calendar.DECEMBER, 31);
            int lastDay = lastDayOfNoteYear.get(Calendar.DAY_OF_YEAR);
            if (noteDay == lastDay && nowDay == 1) {
                isYesterday = true;
            }
        }

        String timePart = String.format(Locale.CHINA, "%02d:%02d",
                note.get(Calendar.HOUR_OF_DAY), note.get(Calendar.MINUTE));

        if (sameDay) {
            return timePart;
        } else if (isYesterday) {
            return "昨天 " + timePart;
        } else if (sameYear) {
            return (note.get(Calendar.MONTH) + 1) + "月"
                    + note.get(Calendar.DAY_OF_MONTH) + "号 " + timePart;
        } else {
            return noteYear + "年" + (note.get(Calendar.MONTH) + 1) + "月"
                    + note.get(Calendar.DAY_OF_MONTH) + "号";
        }
    }

    // ═══════════════════════════════════════
    //  工具方法
    // ═══════════════════════════════════════

    /** 剔除文本中的行内标签（#xxx） */
    private String stripTags(String text) {
        if (text == null) return "";
        return TAG_PATTERN.matcher(text).replaceAll("").replaceAll("\\s+", " ").trim();
    }

    // ═══════════════════════════════════════
    //  ViewHolder
    // ═══════════════════════════════════════

    /** 时间分隔线 ViewHolder */
    static class TimeViewHolder extends RecyclerView.ViewHolder {
        final TextView tvTime;

        TimeViewHolder(@NonNull View itemView) {
            super(itemView);
            tvTime = itemView.findViewById(R.id.tv_time_header);
        }
    }

    /** 气泡 ViewHolder */
    static class BubbleViewHolder extends RecyclerView.ViewHolder {
        final TextView tvTag;
        final TextView tvContent;

        BubbleViewHolder(@NonNull View itemView) {
            super(itemView);
            tvTag = itemView.findViewById(R.id.tv_tag);
            tvContent = itemView.findViewById(R.id.tv_content);
        }
    }
}
