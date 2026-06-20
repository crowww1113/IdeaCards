package com.xiejinyi.ideacards;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

/**
 * 统一浮动菜单工具：在触摸点附近弹出圆角卡片式浮窗。
 * 供 MainActivity、ArchiveActivity 等所有需要长按弹窗的页面复用。
 */
public final class FloatingMenuHelper {

    private FloatingMenuHelper() {}

    /** 点击回调 */
    @FunctionalInterface
    public interface OnItemClickListener {
        void onItemClick(int index);
    }

    /**
     * 在触摸点附近弹出浮动菜单。
     *
     * @param anchorView 触发长按的 View（用于获取窗口令牌）
     * @param items      菜单文字数组
     * @param touchX     触摸点屏幕 X 坐标（getRawX）
     * @param touchY     触摸点屏幕 Y 坐标（getRawY）
     * @param onSelect   点击回调
     */
    public static void show(View anchorView, String[] items,
                            int touchX, int touchY,
                            OnItemClickListener onSelect) {
        Context context = anchorView.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        int padH = (int) (18 * density);
        int padV = (int) (10 * density);

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundResource(R.drawable.bg_floating_menu);
        container.setElevation(8 * density);

        PopupWindow[] windowRef = new PopupWindow[1];

        for (int i = 0; i < items.length; i++) {
            TextView item = new TextView(context);
            item.setText(items[i]);
            item.setTextSize(15f);
            item.setTextColor(context.getResources().getColor(R.color.text_primary));
            item.setPadding(padH, padV, padH, padV);
            item.setGravity(Gravity.CENTER);
            item.setBackgroundResource(R.drawable.bg_floating_menu_item_ripple);
            item.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            final int index = i;
            item.setOnClickListener(v -> {
                if (windowRef[0] != null) windowRef[0].dismiss();
                onSelect.onItemClick(index);
            });
            container.addView(item);

            // 分割线（最后一项不加）
            if (i < items.length - 1) {
                View divider = new View(context);
                divider.setBackgroundColor(
                        context.getResources().getColor(R.color.divider_color));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, (int) (1 * density));
                lp.leftMargin = padH;
                lp.rightMargin = padH;
                container.addView(divider, lp);
            }
        }

        int wSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        int hSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        container.measure(wSpec, hSpec);

        PopupWindow popup = new PopupWindow(container,
                (int) (140 * density),
                LinearLayout.LayoutParams.WRAP_CONTENT, true);
        popup.setElevation(8 * density);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(null);
        windowRef[0] = popup;

        popup.showAtLocation(anchorView, Gravity.NO_GRAVITY, touchX, touchY);
    }
}
