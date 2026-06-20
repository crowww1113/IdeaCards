package com.xiejinyi.ideacards;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.xiejinyi.ideacards.data.db.AppDatabase;
import com.xiejinyi.ideacards.data.dao.NoteDao;
import com.xiejinyi.ideacards.data.entity.NoteEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 灵感回顾通知构建器。
 * <p>
 * 根据 {@link ReviewSettingsManager} 中的配置（回顾条数 + 标签范围），
 * 从数据库随机抽取笔记，用 InboxStyle 聚合展示在一条通知中。
 * <p>
 * 调用方需在子线程中调用 {@link #showReviewNotification(Context)}，
 * 因为内部会同步查询数据库。
 */
public class NotificationHelper {

    private static final String CHANNEL_ID = "note_review";
    private static final String CHANNEL_NAME = "灵感回顾";
    private static final int NOTIFICATION_ID = 2001;

    private NotificationHelper() {}

    /**
     * 查询 1 条随机笔记并展示通知。
     * 每次回顾固定展示 1 条，确保灵感不被信息淹没。
     * 必须在子线程调用（内部执行同步数据库查询）。
     *
     * @param context 上下文
     */
    public static void showReviewNotification(Context context) {
        // 1. 读取标签配置
        ReviewSettingsManager settings = ReviewSettingsManager.getInstance();
        Set<String> tags = settings.getReviewTags(context);

        // 2. 随机查询 1 条笔记
        NoteDao noteDao = AppDatabase.getInstance(context).noteDao();
        List<NoteEntity> notes;
        if (tags.isEmpty()) {
            notes = noteDao.getRandomNotes(1);
        } else {
            notes = noteDao.getRandomNotesByTags(new ArrayList<>(tags), 1);
        }

        // 无笔记则不发通知
        if (notes == null || notes.isEmpty()) return;

        // 3. 确保通知渠道已创建（Android 8.0+）
        ensureChannel(context);

        // 4. 构建点击意图：打开主界面
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // 5. 构建通知：直接展示单条笔记内容
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_remind)
                .setContentTitle("还记得你写下过的灵感吗")
                .setContentText(truncate(notes.get(0).getContent(), 80))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        // 6. 发送通知
        NotificationManager manager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, builder.build());
    }

    /**
     * 确保通知渠道已注册（Android 8.0+ 必需）。
     * 重复调用不会创建重复渠道。
     */
    private static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT);
                channel.setDescription("随机抽取历史灵感，帮助你回顾美好想法");
                manager.createNotificationChannel(channel);
            }
        }
    }

    /**
     * 截断文本到指定长度，超出部分用省略号代替。
     */
    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        String clean = text.replaceAll("\\s+", " ").trim();
        return clean.length() > maxLen ? clean.substring(0, maxLen) + "…" : clean;
    }
}
