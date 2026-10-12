package com.wenwen.student;

import android.Manifest;
import androidx.appcompat.app.AppCompatActivity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Size;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 扫码连接电脑。
 * CameraX 负责出画面，ZXing 负责把画面里的二维码解成文本，
 * 解析后把原文塞回 Intent 交回 MainActivity（具体格式由 MainActivity 自己认）。
 */
public class ScanActivity extends AppCompatActivity {

    public static final String EXTRA_RESULT = "scan_result";
    private static final int REQ_CAMERA = 1001;

    private PreviewView previewView;
    private TextView hintText;
    private ProcessCameraProvider cameraProvider;
    private final AtomicBoolean decoded = new AtomicBoolean(false);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan);

        previewView = findViewById(R.id.preview);
        hintText = findViewById(R.id.hintText);
        findViewById(R.id.closeBtn).setOnClickListener(v -> finish());

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;   // 拿到授权后再开相机
        }
        openCamera();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            openCamera();
        } else {
            hintText.setText("没给相机权限，扫不了。用「手动输入」就好");
        }
    }

    private void openCamera() {
        ProcessCameraProvider.getInstance(this).addListener(() -> {
            try {
                ProcessCameraProvider provider = ProcessCameraProvider.getInstance(this).get();
                cameraProvider = provider;

                androidx.camera.core.Preview preview =
                        new androidx.camera.core.Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                CameraSelector selector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                        .build();

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(Executors.newSingleThreadExecutor(), new QrAnalyzer());

                provider.bindToLifecycle(this, selector, preview, analysis);
            } catch (Exception e) {
                hintText.setText("相机打不开：" + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    /** ZXing 解码器：一帧一帧看，解出来立刻回传并停止。 */
    private class QrAnalyzer implements ImageAnalysis.Analyzer {

        private final MultiFormatReader reader = new MultiFormatReader();      // 正方向：TRY_HARDER，认得更稳
        private final MultiFormatReader readerFast = new MultiFormatReader();  // 旋转帧：不加重试，图快
        private int frameCount = 0;

        {
            Map<DecodeHintType, Object> hard = new HashMap<>();
            hard.put(DecodeHintType.POSSIBLE_FORMATS, Arrays.asList(BarcodeFormat.QR_CODE));
            hard.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            reader.setHints(hard);

            Map<DecodeHintType, Object> soft = new HashMap<>();
            soft.put(DecodeHintType.POSSIBLE_FORMATS, Arrays.asList(BarcodeFormat.QR_CODE));
            readerFast.setHints(soft);
        }

        @Override
        public void analyze(@NonNull ImageProxy image) {
            if (decoded.get()) {
                image.close();
                return;
            }
            Result raw = null;
            try {
                raw = decode(image);
            } catch (NotFoundException e) {
                // 这帧没扫到，下一帧接着来
            } catch (Throwable t) {
                // 尺寸/内存之类的异常也别让相机崩掉
            }
            image.close();

            frameCount++;
            if (raw == null) {
                // 让用户看得见「相机有没有在出画面」，别对着黑屏干等
                if (frameCount == 5 || frameCount == 90) {
                    final int n = frameCount;
                    runOnUiThread(() -> hintText.setText(
                            n < 10 ? "相机已开启，对准电脑上的二维码" : "还没认出来…也可返回用「手动输入」"));
                }
                return;
            }
            decoded.set(true);
            finishOk(raw.getText());
        }

        private Result decode(ImageProxy image) throws NotFoundException {
            Frame f = grabY(image);
            if (f == null) throw NotFoundException.getNotFoundInstance();
            NotFoundException last = null;
            // ① 原方向先试（绝大多数手机走这条）
            try { return tryDecode(f.data, f.w, f.h, true); } catch (NotFoundException e) { last = e; }
            // ② 相机帧方向跟屏幕不一致的机型：再试 90° / 180° / 270°
            byte[] r90 = rotate90(f.data, f.w, f.h);
            try { return tryDecode(r90, f.h, f.w, false); } catch (NotFoundException e) { last = e; }
            byte[] r180 = rotate90(r90, f.h, f.w);
            try { return tryDecode(r180, f.w, f.h, false); } catch (NotFoundException e) { last = e; }
            byte[] r270 = rotate90(r180, f.w, f.h);
            try { return tryDecode(r270, f.h, f.w, false); } catch (NotFoundException e) { last = e; }
            throw (last != null) ? last : NotFoundException.getNotFoundInstance();
        }

        private Result tryDecode(byte[] y, int w, int h, boolean hard) throws NotFoundException {
            PlanarYUVLuminanceSource src = new PlanarYUVLuminanceSource(y, w, h, 0, 0, w, h, false);
            MultiFormatReader r = hard ? reader : readerFast;
            return r.decode(new BinaryBitmap(new HybridBinarizer(src)));
        }
    }

    /** 一帧的灰度数据（已按行紧密排好，不含填充） */
    private static class Frame {
        byte[] data;
        int w;
        int h;
    }

    /**
     * 相机 YUV 帧 → 紧密排列的 Y 平面。
     * 关键点：必须按 rowStride 逐行取，不能直接 get(w*h) 个字节——
     * 相机缓冲每行末尾通常有对齐填充，整块拷会把画面拉成斜的，二维码永远解不出来。
     */
    private static Frame grabY(ImageProxy image) {
        try {
            ImageProxy.PlaneProxy plane = image.getPlanes()[0];
            ByteBuffer buf = plane.getBuffer();
            int w = image.getWidth();
            int h = image.getHeight();
            int rowStride = plane.getRowStride();
            buf.rewind();
            int rem = buf.remaining();
            byte[] y = new byte[w * h];
            if (rowStride == w) {
                buf.get(y, 0, Math.min(w * h, rem));
            } else {
                for (int r = 0; r < h; r++) {
                    int pos = r * rowStride;
                    if (pos >= rem) break;
                    buf.position(pos);
                    buf.get(y, r * w, Math.min(w, rem - pos));
                }
            }
            Frame f = new Frame();
            f.data = y;
            f.w = w;
            f.h = h;
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 灰度图顺时针旋转 90°：尺寸由 w×h 变成 h×w */
    private static byte[] rotate90(byte[] src, int w, int h) {
        byte[] dst = new byte[w * h];
        for (int row = 0; row < h; row++) {
            int srcOff = row * w;
            for (int col = 0; col < w; col++) {
                dst[col * h + (h - 1 - row)] = src[srcOff + col];
            }
        }
        return dst;
    }

    private void finishOk(String text) {
        if (text == null || text.trim().length() == 0) {
            hintText.setText("扫到的内容为空，再试一次");
            decoded.set(false);
            return;
        }
        Intent data = new Intent();
        data.putExtra(EXTRA_RESULT, text.trim());
        setResult(RESULT_OK, data);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraProvider != null) {
            try {
                cameraProvider.unbindAll();
            } catch (Exception ignored) {
            }
        }
    }
}
