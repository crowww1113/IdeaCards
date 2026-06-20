package com.xiejinyi.ideacards;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.TileService;

/**
 * 控制中心快捷磁贴服务。
 * <p>
 * 用户下拉状态栏点击"记下灵感"磁贴后，弹出极速输入浮窗。
 * 使用 {@link PendingIntent} 启动 Activity，兼容 Android 12+ 后台启动限制。
 */
public class QuickNoteTileService extends TileService {

    @Override
    public void onClick() {
        super.onClick();

        // 构建指向极速输入页的 Intent
        // FLAG_ACTIVITY_NEW_TASK + MULTIPLE_TASK：强制创建独立任务栈，
        // finish 后不会把主页带到前台
        Intent intent = new Intent(this, QuickInputActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34+：必须使用 PendingIntent 版本的 startActivityAndCollapse
            PendingIntent pi = PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            startActivityAndCollapse(pi);
        } else {
            // API 33 及以下：直接启动 + 收起通知栏
            startActivity(intent);
            sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
        }
    }
}
