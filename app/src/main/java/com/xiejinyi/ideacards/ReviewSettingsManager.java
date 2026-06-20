package com.xiejinyi.ideacards;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/**
 * 灵感回顾偏好管理器（单例）。
 * 使用 SharedPreferences 持久化用户的回顾配置：
 * - times_per_day：每天随机回顾次数（默认 3，上限 10）
 * - review_tags：回顾的标签范围（空集合 = 回顾全部笔记）
 *
 * 每次回顾固定查询 1 条随机笔记。
 */
public class ReviewSettingsManager {

    private static final String PREFS_NAME = "review_settings";
    private static final String KEY_TIMES = "times_per_day";
    private static final String KEY_TAGS = "review_tags";

    /** 默认每天回顾次数 */
    private static final int DEFAULT_TIMES = 3;

    /** 最大每天回顾次数 */
    public static final int MAX_TIMES = 10;

    private static volatile ReviewSettingsManager instance;

    private ReviewSettingsManager() {}

    public static ReviewSettingsManager getInstance() {
        if (instance == null) {
            synchronized (ReviewSettingsManager.class) {
                if (instance == null) {
                    instance = new ReviewSettingsManager();
                }
            }
        }
        return instance;
    }

    // ═══════════════════════════════════════
    //  每天回顾次数
    // ═══════════════════════════════════════

    /**
     * 获取每天回顾次数，范围 [1, MAX_TIMES]。
     */
    public int getTimesPerDay(Context context) {
        int times = getPrefs(context).getInt(KEY_TIMES, DEFAULT_TIMES);
        return Math.max(1, Math.min(times, MAX_TIMES));
    }

    /**
     * 设置每天回顾次数，自动钳制到 [1, MAX_TIMES]。
     */
    public void setTimesPerDay(Context context, int times) {
        int clamped = Math.max(1, Math.min(times, MAX_TIMES));
        getPrefs(context).edit().putInt(KEY_TIMES, clamped).apply();
    }

    // ═══════════════════════════════════════
    //  回顾标签范围
    // ═══════════════════════════════════════

    /**
     * 获取回顾标签集合。
     * 空集合表示回顾全部笔记（不按标签过滤）。
     */
    public Set<String> getReviewTags(Context context) {
        Set<String> tags = getPrefs(context).getStringSet(KEY_TAGS, null);
        return tags != null ? new HashSet<>(tags) : new HashSet<>();
    }

    /**
     * 设置回顾标签范围。
     * 传入空集合表示回顾全部笔记。
     */
    public void setReviewTags(Context context, Set<String> tags) {
        getPrefs(context).edit()
                .putStringSet(KEY_TAGS, tags != null ? new HashSet<>(tags) : new HashSet<>())
                .apply();
    }

    /**
     * 当前是否按标签过滤（即标签集合非空）。
     */
    public boolean isFilterByTags(Context context) {
        return !getReviewTags(context).isEmpty();
    }

    // ═══════════════════════════════════════
    //  内部
    // ═══════════════════════════════════════

    private SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
