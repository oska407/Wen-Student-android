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

        private final MultiFormatReader reader = new MultiFormatReader();

        {
            Map<DecodeHintType, Object> hints = new HashMap<>();
            hints.put(DecodeHintType.POSSIBLE_FORMATS, Arrays.asList(BarcodeFormat.QR_CODE));
            reader.setHints(hints);
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

            if (raw == null) return;
            decoded.set(true);
            finishOk(raw.getText());
        }

        private Result decode(ImageProxy image) throws NotFoundException {
            LuminanceSource source = buildSource(image);
            if (source == null) throw NotFoundException.getNotFoundInstance();
            return reader.decode(new BinaryBitmap(new HybridBinarizer(source)));
        }

        /** 相机 YUV 帧 → ZXing 亮度源。 */
        private LuminanceSource buildSource(ImageProxy image) {
            try {
                ByteBuffer yBuf = image.getPlanes()[0].getBuffer();
                int w = image.getWidth();
                int h = image.getHeight();
                int rowStride = image.getPlanes()[0].getRowStride();
                byte[] y = new byte[w * h];
                yBuf.rewind();
                yBuf.get(y);
                return new PlanarYUVLuminanceSource(y, w, h, 0, 0, w, h, false);
            } catch (Throwable t) {
                return null;
            }
        }
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
