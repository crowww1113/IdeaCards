package com.xiejinyi.ideacards;

import com.xiejinyi.ideacards.data.entity.NoteEntity;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Markdown 导出格式工具类。
 * 提供统一的笔记 → Markdown 转换方法，供导出、Drive 同步、Obsidian 同步共用。
 */
public final class MarkdownUtils {

    private MarkdownUtils() {}

    /**
     * 将笔记列表拼接为标准导出格式的 Markdown（含 YAML Frontmatter）。
     *
     * @param notes        笔记列表
     * @param selectedTags 筛选标签（可为空列表，表示未筛选）
     * @return 完整 Markdown 文本
     */
    public static String buildExportMarkdown(List<NoteEntity> notes, List<String> selectedTags) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA);
        String exportTime = sdf.format(new Date());

        StringBuilder md = new StringBuilder();

        // 1. Obsidian 标准 YAML Frontmatter
        md.append("---\n");
        md.append("title: 💡灵感归档\n");
        md.append("date: ").append(exportTime).append("\n");
        if (selectedTags != null && !selectedTags.isEmpty()) {
            md.append("tags: [");
            for (int i = 0; i < selectedTags.size(); i++) {
                if (i > 0) md.append(", ");
                md.append(selectedTags.get(i));
            }
            md.append("]\n");
        }
        md.append("---\n\n");

        // 2. 笔记内容主体
        for (NoteEntity note : notes) {
            String noteTime = sdf.format(new Date(note.getTimestamp()));

            if (note.getTag() != null && !note.getTag().trim().isEmpty()) {
                md.append("**[").append(noteTime).append("] · ").append(note.getTag().trim()).append("**\n");
            } else {
                md.append("**[").append(noteTime).append("]**\n");
            }

            String cleanContent = note.getContent();
            if (cleanContent != null) {
                // 清理正文中多余的行内标签
                cleanContent = cleanContent.replaceAll("(?m)(^|\\s)#([^\\s#]+)", "").trim();
            }
            md.append(cleanContent).append("\n\n");
        }

        return md.toString();
    }
}
