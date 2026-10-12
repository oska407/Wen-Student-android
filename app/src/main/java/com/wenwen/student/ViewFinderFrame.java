package com.wenwen.student;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** 扫码时画在相机画面上的取景框：四个直角 + 一条中线。 */
public class ViewFinderFrame extends View {

    private final Paint cornerPaint;
    private final Paint linePaint;

    public ViewFinderFrame(Context context) {
        this(context, null);
    }

    public ViewFinderFrame(Context context, AttributeSet attrs) {
        super(context, attrs);
        cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        cornerPaint.setColor(Color.parseColor("#0F6E56"));
        cornerPaint.setStrokeWidth(6 * getResources().getDisplayMetrics().density + 1);
        cornerPaint.setStyle(Paint.Style.STROKE);

        linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setColor(Color.parseColor("#99FFFFFF"));
        linePaint.setStrokeWidth(2);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        int len = Math.min(w, h) / 5;   // 每个角的边长
        int pad = 26;                    // 内缩，别贴边
        int left = pad;
        int top = pad;
        int right = w - pad;
        int bottom = h - pad;

        // 四角
        canvas.drawLine(left, top + len, left, top, cornerPaint);
        canvas.drawLine(left, top, left + len, top, cornerPaint);

        canvas.drawLine(right - len, top, right, top, cornerPaint);
        canvas.drawLine(right, top, right, top + len, cornerPaint);

        canvas.drawLine(left, bottom - len, left, bottom, cornerPaint);
        canvas.drawLine(left, bottom, left + len, bottom, cornerPaint);

        canvas.drawLine(right - len, bottom, right, bottom, cornerPaint);
        canvas.drawLine(right, bottom, right, bottom - len, cornerPaint);

        // 中线（扫过去的那条）
        int cx = w / 2;
        int cy = h / 2;
        canvas.drawLine(cx, top + 4, cx, bottom - 4, linePaint);

        // 框外压暗，方便识别
        Paint mask = new Paint(Paint.ANTI_ALIAS_FLAG);
        mask.setColor(Color.parseColor("#55000000"));
        canvas.drawRect(new RectF(0, 0, w, top), mask);
        canvas.drawRect(new RectF(0, bottom, w, h), mask);
        canvas.drawRect(new RectF(0, 0, left, h), mask);
        canvas.drawRect(new RectF(right, 0, w, h), mask);
    }
}
