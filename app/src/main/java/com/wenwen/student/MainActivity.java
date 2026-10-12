package com.wenwen.student;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.JavascriptInterface;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Random;

/**
 * 雯雯做题系统 · 学生端安卓壳
 *
 * 只做几件事：
 *   1. 第一次／想换电脑：支持「扫二维码」和「手打地址」两种连接方式（存本地，以后不用重来）
 *   2. 用 WebView 打开 /student 页面，放行自签证书 + 自动给麦克风权限
 *   3. 顶部状态条显示 在线 / 离线 / N 场待同步（由注入的离线层经 WenwenBridge 上报）
 *   4. 顶栏小键 ⚙（家长设置，PIN 门控）+ ⟳（重新扫码连接，PIN 门控）
 *   5. 在页面脚本之前注入引导脚本，注册服务端下发的 sw.js；离线数据（outbox）由
 *      Service Worker 透明拦截写操作、联网后整场重放，student.js 一行不改。
 * 页面本身的出题、判分、录音，全部由服务端网页负责，这里一行都不改。
 */
public class MainActivity extends Activity {

    public static final String PREF_NAME = "wenwen_student";
    public static final String KEY_BASE_URL = "baseUrl";
    public static final String KEY_PARENT_PIN = "parentPin";
    private static final int REQ_SCAN = 2002;

    private String baseUrl;
    private boolean browserBuilt = false;
    private boolean audioUnlocked = false;

    private WebView webView;
    // 离线同步状态（由注入的 JS 层经 WenwenBridge 上报）：待重放场次数与在线标记
    private int pendingCount = 0;
    private boolean jsOnline = true;
    private TextView statusText;
    private TextView addrText;
    private View setupPanel;
    private TextView setupTitle;
    private EditText hostInput;
    private Button cancelBtn;
    private View settingsPanel;
    private TextView settingsAddr;
    private EditText pinInput;
    private EditText pinConfirm;
    private TextView pinHint;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 做题时不许息屏
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        baseUrl = loadBaseUrl();

        setContentView(R.layout.activity_main);
        bindViews();

        // 顶栏两枚小键（⚙ 设置 / ⟳ 重连），都受父母 PIN 门控
        findViewById(R.id.btnSettings).setOnClickListener(v -> {
            if (hasPin()) promptPin(this::showSettings);
            else showSettings();
        });
        findViewById(R.id.btnReconnect).setOnClickListener(v -> {
            if (hasPin()) promptPin(this::startScan);
            else startScan();
        });

        // 连接面板上的按钮
        findViewById(R.id.connectBtn).setOnClickListener(v -> applyInput(hostInput.getText().toString()));
        findViewById(R.id.scanBtn).setOnClickListener(v -> startScan());
        cancelBtn.setOnClickListener(v -> {
            if (browserBuilt) hidePanels();
            else finish();
        });

        // 设置面板上的按钮
        findViewById(R.id.changeServerBtn).setOnClickListener(v -> showSetup("换服务器 / 重连"));
        findViewById(R.id.savePinBtn).setOnClickListener(v -> savePin());
        findViewById(R.id.settingsCloseBtn).setOnClickListener(v -> hidePanels());

        buildBrowser();

        if (TextUtils.isEmpty(baseUrl)) {
            showSetup("第一次使用");          // 第一次用：直接把连接面板摆出来
        } else {
            addrText.setText(baseUrl.replace("https://", "").replace("http://", ""));
            hidePanels();
            loadStudentPage();
        }
    }

    private void bindViews() {
        webView = findViewById(R.id.webView);
        statusText = findViewById(R.id.statusText);
        addrText = findViewById(R.id.addrText);
        setupPanel = findViewById(R.id.setupPanel);
        setupTitle = findViewById(R.id.setupTitle);
        hostInput = findViewById(R.id.hostInput);
        cancelBtn = findViewById(R.id.cancelBtn);
        settingsPanel = findViewById(R.id.settingsPanel);
        settingsAddr = findViewById(R.id.settingsAddr);
        pinInput = findViewById(R.id.pinInput);
        pinConfirm = findViewById(R.id.pinConfirm);
        pinHint = findViewById(R.id.pinHint);
    }

    // ---------- 面板管理（同一时刻只显示一个，绝不浮在页面上） ----------

    private void showSetup(String title) {
        setupTitle.setText(title);
        settingsPanel.setVisibility(View.GONE);
        setupPanel.setVisibility(View.VISIBLE);
        cancelBtn.setVisibility(browserBuilt ? View.VISIBLE : View.GONE);
        if (!TextUtils.isEmpty(baseUrl)) {
            hostInput.setText(baseUrl.replace("https://", "").replace("http://", ""));
        } else {
            hostInput.setText("");
        }
    }

    private void showSettings() {
        setupPanel.setVisibility(View.GONE);
        settingsPanel.setVisibility(View.VISIBLE);
        settingsAddr.setText(TextUtils.isEmpty(baseUrl) ? "（未连接）" : baseUrl);
        pinInput.setText("");
        pinConfirm.setText("");
        pinHint.setText(hasPin() ? "已设 PIN；重填并保存可修改" : "未设 PIN；填 4–6 位并保存即启用");
    }

    private void hidePanels() {
        setupPanel.setVisibility(View.GONE);
        settingsPanel.setVisibility(View.GONE);
        cancelBtn.setVisibility(View.GONE);
    }

    // ---------- 地址面板 ----------

    private void applyInput(String raw) {
        String url = parseServer(raw);
        if (url == null) {
            hostInput.setError("填 192.168.3.15:3000 这样，只写数字不行");
            return;
        }
        commit(url);
    }

    /** 扫码结果走这里 */
    private void commit(String url) {
        saveBaseUrl(url);
        baseUrl = url;
        addrText.setText(url.replace("https://", "").replace("http://", ""));
        hidePanels();
        loadStudentPage();
    }

    // ---------- 扫码 ----------

    private void startScan() {
        Intent it = new Intent(this, ScanActivity.class);
        startActivityForResult(it, REQ_SCAN);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_SCAN || resultCode != RESULT_OK || data == null) return;
        String text = data.getStringExtra(ScanActivity.EXTRA_RESULT);
        if (TextUtils.isEmpty(text)) return;

        String url = parseServer(text);
        if (url == null) {
            Toast.makeText(this, "扫到的不像是电脑地址，换一个二维码试试", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "连上了 " + url, Toast.LENGTH_SHORT).show();
        commit(url);
    }

    // ---------- 父母 PIN ----------

    private boolean hasPin() {
        return !TextUtils.isEmpty(loadPinHash());
    }

    private String loadPinHash() {
        SharedPreferences sp = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return sp.getString(KEY_PARENT_PIN, null);
    }

    private boolean checkPin(String input) {
        String stored = loadPinHash();
        if (stored == null) return true;            // 没设 PIN 视作通过
        String[] parts = stored.split(":");
        if (parts.length != 2) return false;
        return hashPin(input, parts[0]).equals(parts[1]);
    }

    private void setPin(String input) {
        String salt = randomHex(8);
        String h = hashPin(input, salt);
        getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_PARENT_PIN, salt + ":" + h).apply();
    }

    private void savePin() {
        String p1 = pinInput.getText().toString();
        String p2 = pinConfirm.getText().toString();
        if (p1.length() < 4 || p1.length() > 6) {
            pinHint.setText("PIN 需 4–6 位数字");
            return;
        }
        if (!p1.equals(p2)) {
            pinHint.setText("两次输入不一致");
            return;
        }
        setPin(p1);
        pinInput.setText("");
        pinConfirm.setText("");
        pinHint.setText("PIN 已保存");
        Toast.makeText(this, "父母 PIN 已设置", Toast.LENGTH_SHORT).show();
    }

    /** 输 PIN 弹窗；正确才执行 onOk（忘记 PIN 只能清应用数据重置，连连接记录一起清） */
    private void promptPin(Runnable onOk) {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("请输入父母 PIN");
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        et.setMaxLines(1);
        b.setView(et);
        b.setPositiveButton("确定", (d, w) -> {
            if (checkPin(et.getText().toString())) onOk.run();
            else Toast.makeText(this, "PIN 错误", Toast.LENGTH_SHORT).show();
        });
        b.setNegativeButton("取消", null);
        b.show();
    }

    private static String hashPin(String pin, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] b = md.digest((salt + pin).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String randomHex(int n) {
        Random r = new Random();
        String chars = "0123456789abcdef";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(chars.charAt(r.nextInt(16)));
        return sb.toString();
    }

    // ---------- 二维码解析（认得三种内容） ----------

    /** 认得三种二维码内容，全都能还原成 "协议://host[:port]" */
    static String parseServer(String raw) {
        if (raw == null) return null;
        String s = raw.trim();

        // ① 自定义格式：wenwen://connect?h=192.168.3.15&p=3000&s=1
        if (s.toLowerCase().startsWith("wenwen://")) {
            try {
                String q = s.substring(s.indexOf("?") + 1);
                String host = null, port = null, secure = null;
                for (String kv : q.split("&")) {
                    int i = kv.indexOf('=');
                    if (i < 0) continue;
                    String k = kv.substring(0, i).toLowerCase();
                    String v = kv.substring(i + 1);
                    if (k.equals("h") || k.equals("host")) host = v;
                    else if (k.equals("p") || k.equals("port")) port = v;
                    else if (k.equals("s") || k.equals("secure")) secure = v;
                }
                if (host == null) return null;
                // 默认 https（麦克风要安全上下文）；但显式写了 s=0 就跟着走 http
                boolean https = !"0".equals(secure) && !"http".equals(secure);
                String scheme = https ? "https" : "http";
                if (port != null && port.length() > 0) host = host + ":" + port;
                return scheme + "://" + host;
            } catch (Exception e) {
                return null;
            }
        }

        // ② 普通网址：https://192.168.3.15:3000/student
        //    只取「协议 + 主机端口」，路径一律丢掉（App 自己会拼 /student，
        //    带上路径会变成 /student/student）。明文 http 就跟着走 http，不自作主张升级。
        if (s.startsWith("http://") || s.startsWith("https://")) {
            String h = hostOf(s);
            if (h == null) return null;
            boolean isHttps = s.startsWith("https://");
            return normalizeUrl((isHttps ? "https://" : "http://") + h);
        }

        // ③ 纯地址：192.168.3.15:3000
        return isHostLike(s) ? normalizeUrl("https://" + s) : null;
    }

    /** 从网址里抠出 host[:port]，路径、查询串全部扔掉 */
    private static String hostOf(String url) {
        try {
            java.net.URI u = java.net.URI.create(url);
            if (u.getHost() == null) return null;
            int port = u.getPort();
            return port > 0 ? u.getHost() + ":" + port : u.getHost();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isHostLike(String s) {
        if (s.contains("/") || s.contains(" ") || s.contains("://")) return false;
        // 至少要有「点」，或者 localhost
        return s.contains(".") || s.equalsIgnoreCase("localhost");
    }

    private static String normalizeUrl(String raw) {
        String u = raw.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            u = "https://" + u;              // 默认 https：自签证书 + 麦克风要安全上下文
        }
        return u.replaceAll("/+$", "");
    }

    // ---------- WebView ----------

    @SuppressLint("SetJavaScriptEnabled")
    private void buildBrowser() {
        if (browserBuilt) return;
        browserBuilt = true;

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        // 提示音自动播放（不用先点一下屏幕）
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        // 需求1：视口宽度 = 设备物理宽度（配合 student.html 的 width=device-width），
        // 页面铺满硬件宽度；用户捏合缩放已由 meta user-scalable=no 禁止。
        s.setUseWideViewPort(false);
        s.setLoadWithOverviewMode(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setUserAgentString(s.getUserAgentString() + " wenwen-student-android/1.2");

        // 首次触摸解锁 AudioContext（避免 direct 模式首题无声）
        webView.setOnTouchListener((v, e) -> {
            if (!audioUnlocked && e.getAction() == MotionEvent.ACTION_DOWN) {
                audioUnlocked = true;
                webView.evaluateJavascript(
                        "try{var c=new (window.AudioContext||window.webkitAudioContext)();if(c&&c.resume)c.resume();}catch(e){}",
                        null);
            }
            return false;
        });

        webView.setWebViewClient(new WebViewClient() {

            // 自签证书：app 自己放行（按用户拍板维持无差别放行；不装系统 CA）
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler,
                                           android.net.http.SslError error) {
                handler.proceed();
            }

            // 页面内跳转全部留在 WebView，不开系统浏览器
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // 兜底：个别 WebView 版本不支持 addScriptToEvaluateOnNewDocument，
                // 这里再跑一次引导（注册 SW + 转发状态）。注册幂等，重复执行无害。
                try { view.evaluateJavascript(BOOTSTRAP_JS, null); } catch (Exception e) {}
                refreshStatus();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                statusText.setText("打不开 · 检查电脑是否开机、手机是否连同一个 WiFi");
                statusText.setTextColor(Color.parseColor("#BA7517"));
            }
        });

        // 麦克风权限自动给（朗读题 / 录音题要用）
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                request.grant(request.getResources());
            }
        });

        // 离线握手：把状态上报桥（WenwenBridge）注入页面，并把 SW 引导脚本在
        // 页面脚本之前执行（addScriptToEvaluateOnNewDocument），这样 SW 能抢在
        // student.js 之前注册、透明拦截写操作。student.js 一行不用改。
        webView.addJavascriptInterface(new WenwenBridge(), "WenwenBridge");
        try {
            java.lang.reflect.Method m = webView.getClass().getMethod("addScriptToEvaluateOnNewDocument", String.class);
            m.invoke(webView, BOOTSTRAP_JS);
        } catch (Exception e) {
            // 低版本 WebView 不支持该方法则退回 onPageFinished 注册（见 injectServiceWorker）
        }
    }

    private void loadStudentPage() {
        if (TextUtils.isEmpty(baseUrl)) return;
        buildBrowser();
        webView.loadUrl(baseUrl + "/student");
        statusText.setText("正在连接…");
    }

    /** 页面脚本之前注入的引导：注册 sw.js + 转发 SW 状态到 WenwenBridge。
     *  不在 SW 激活时盲刷页面，只在「首次取得控制」时 reload 一次（避免无限刷新）。 */
    private static final String BOOTSTRAP_JS = "(function(){"
            + "if(!('serviceWorker' in navigator))return;"
            + "var SW_DONE=false;"
            + "function postToSw(m){try{if(navigator.serviceWorker.controller)navigator.serviceWorker.controller.postMessage(m);"
            + "else navigator.serviceWorker.ready.then(function(r){if(r.active)r.active.postMessage(m);});}catch(e){}}"
            + "navigator.serviceWorker.addEventListener('message',function(e){"
            + "var d=e.data||{};"
            + "if(d.type==='sync-status'&&window.WenwenBridge){"
            + "window.WenwenBridge.updateSync(JSON.stringify({online:d.online,pending:d.pending}));}});"
            + "navigator.serviceWorker.register('/sw.js').then(function(reg){"
            + "if(!navigator.serviceWorker.controller){"
            + "navigator.serviceWorker.addEventListener('controllerchange',function(){if(!SW_DONE){SW_DONE=true;location.reload();}});}"
            + "if(reg&&reg.waiting)reg.waiting.postMessage({type:'skip'});"
            + "}).catch(function(){});"
            // 手动握手模型：不再由 online / visibilitychange 自动触发 sync，
            // 否则离线模式下一旦有网就会把积压的场次提前重放。
            // 现在只有学生端点「🤝 握手合并」才会 postToSw({type:'sync'})。
            + "})();";

    /**
     * JS → Java 桥：SW 离线层把同步状态（在线 / N 场待同步）上报到这里，
     * 顶栏状态条据此显示「在线 / 离线 / N 场待同步」。
     */
    private class WenwenBridge {
        @JavascriptInterface
        public void updateSync(String json) {
            boolean online = true;
            int pending = 0;
            try {
                org.json.JSONObject o = new org.json.JSONObject(json);
                online = o.optBoolean("online", true);
                pending = o.optInt("pending", 0);
            } catch (Exception e) {
                return;
            }
            pendingCount = pending;
            jsOnline = online;
            final boolean fOn = online;
            final int fP = pending;
            runOnUiThread(() -> applyStatus(fOn, fP));
        }
    }

    private void refreshStatus() {
        if (statusText == null) return;
        // 手动握手模型：online 的语义已改为「是否处于离线模式」（SW 用 FORCE_OFFLINE 上报），
        // 不再用 ConnectivityManager 覆盖——否则已分手但连着 WiFi 时会误显示「在线」。
        applyStatus(jsOnline, pendingCount);
    }

    /** 顶栏状态条统一出口：在线 / 离线模式 / N 场待合并（手动握手模型） */
    private void applyStatus(boolean online, int pending) {
        if (statusText == null) return;
        String txt;
        int color;
        if (!online && pending > 0) {
            txt = "📴 离线模式 · " + pending + " 场待上传（回家点「握手合并」）";
            color = Color.parseColor("#BA7517");
        } else if (online && pending > 0) {
            txt = "🤝 正在合并 · " + pending + " 场待上传";
            color = Color.parseColor("#BA7517");
        } else if (!online) {
            txt = "📴 离线模式（做的题先存着，回家握手上传）";
            color = Color.parseColor("#BA7517");
        } else {
            txt = "🟢 在线";
            color = Color.parseColor("#0F6E56");
        }
        if (baseUrl != null && baseUrl.startsWith("http://")) {
            txt = txt + " · http 下录音不可用";
            color = Color.parseColor("#BA7517");
        }
        statusText.setText(txt);
        statusText.setTextColor(color);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    public void onBackPressed() {
        if (setupPanel != null && setupPanel.getVisibility() == View.VISIBLE
                || settingsPanel != null && settingsPanel.getVisibility() == View.VISIBLE) {
            if (browserBuilt) hidePanels();
            else finish();
            return;
        }
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    // ---------- 小工具 ----------

    private void saveBaseUrl(String url) {
        SharedPreferences sp = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_BASE_URL, url).apply();
    }

    private String loadBaseUrl() {
        SharedPreferences sp = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return sp.getString(KEY_BASE_URL, null);
    }
}
