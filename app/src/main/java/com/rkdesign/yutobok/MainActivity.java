package com.rkdesign.yutobok;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Locale;

import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

/**
 * Nanas: two permanent home tabs plus closable temporary tabs for pop-ups and
 * cross-site links. The number of temporary WebViews is intentionally limited
 * to help keep memory use under control.
 */
public class MainActivity extends Activity {
    private static final String HOME_YUTOBOK = "https://yutobok.netlify.app/";
    private static final String HOME_MUZIKASA = "https://muzikasa.netlify.app/";
    private static final int MAX_TEMP_TABS = 4;
    private static final int FILE_CHOOSER_REQUEST = 8127;
    private static final int DOWNLOAD_PERMISSION_REQUEST = 8128;

    private static final int C_WHITE = Color.WHITE;
    private static final int C_TEXT = Color.rgb(28, 28, 28);
    private static final int C_MUTED = Color.rgb(105, 105, 105);
    private static final int C_YELLOW = Color.rgb(255, 196, 0);
    private static final int C_SELECTED = Color.rgb(255, 246, 204);
    private static final int C_TAB_IDLE = Color.rgb(246, 247, 248);
    private static final int C_BORDER = Color.rgb(229, 231, 235);

    private FrameLayout rootFrame;
    private LinearLayout appColumn;
    private LinearLayout tabStrip;
    private HorizontalScrollView tabsScroller;
    private FrameLayout webHost;
    private ProgressBar pageProgress;

    private final ArrayList<AppTab> tabs = new ArrayList<>();
    private AppTab yutoTab;
    private AppTab muzikasaTab;
    private AppTab currentTab;
    private int lastMainTabIndex = 0;

    private ValueCallback<Uri[]> fileChooserCallback;
    private String pendingDownloadUrl;
    private String pendingDownloadUserAgent;
    private String pendingDownloadDisposition;
    private String pendingDownloadMimeType;
    private View fullScreenView;
    private WebChromeClient.CustomViewCallback fullScreenCallback;
    private OnBackInvokedCallback backCallback;

    private static final class AppTab {
        final boolean permanent;
        final int mainIndex; // 0 = YutoBok, 1 = Muzikasa, -1 = temporary
        final String homeUrl;
        String title;
        String lastUrl;
        WebView webView;
        boolean loaded;

        AppTab(boolean permanent, int mainIndex, String title, String homeUrl) {
            this.permanent = permanent;
            this.mainIndex = mainIndex;
            this.title = title;
            this.homeUrl = homeUrl;
            this.lastUrl = homeUrl;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureSystemBars();
        buildInterface();

        yutoTab = new AppTab(true, 0, "YutoBok", HOME_YUTOBOK);
        muzikasaTab = new AppTab(true, 1, "Muzikasa", HOME_MUZIKASA);
        tabs.add(yutoTab);
        tabs.add(muzikasaTab);
        currentTab = yutoTab;
        lastMainTabIndex = 0;
        ensureWebView(yutoTab); // The second home tab loads only when first opened.
        renderTabs(false);
        showCurrentWebView();

        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = this::handleBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }
    }

    private void configureSystemBars() {
        Window window = getWindow();
        window.setStatusBarColor(C_WHITE);
        window.setNavigationBarColor(C_WHITE);
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
    }

    private void buildInterface() {
        rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(C_WHITE);
        if (Build.VERSION.SDK_INT >= 35) {
            rootFrame.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(
                        android.view.WindowInsets.Type.systemBars()
                                | android.view.WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        }

        appColumn = new LinearLayout(this);
        appColumn.setOrientation(LinearLayout.VERTICAL);
        appColumn.setBackgroundColor(C_WHITE);
        rootFrame.addView(appColumn, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(9), dp(7), dp(7), dp(7));
        toolbar.setBackgroundColor(C_WHITE);
        toolbar.setElevation(dp(2));
        appColumn.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));

        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(com.rkdesign.yutobok.R.drawable.brand_logo);
        logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        brand.addView(logo, new LinearLayout.LayoutParams(dp(39), dp(39)));
        TextView brandName = new TextView(this);
        brandName.setText("Nanas");
        brandName.setTextColor(C_TEXT);
        brandName.setTextSize(14);
        brandName.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        brandName.setPadding(dp(5), 0, dp(8), 0);
        brand.addView(brandName, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        toolbar.addView(brand, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

        tabsScroller = new HorizontalScrollView(this);
        tabsScroller.setHorizontalScrollBarEnabled(false);
        tabsScroller.setFillViewport(false);
        tabsScroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        tabStrip = new LinearLayout(this);
        tabStrip.setOrientation(LinearLayout.HORIZONTAL);
        tabStrip.setGravity(Gravity.CENTER_VERTICAL);
        tabsScroller.addView(tabStrip, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        toolbar.addView(tabsScroller, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        pageProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pageProgress.setMax(100);
        pageProgress.setProgressTintList(ColorStateList.valueOf(C_YELLOW));
        pageProgress.setIndeterminateTintList(ColorStateList.valueOf(C_YELLOW));
        pageProgress.setVisibility(View.GONE);
        appColumn.addView(pageProgress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(2)));

        webHost = new FrameLayout(this);
        webHost.setBackgroundColor(C_WHITE);
        appColumn.addView(webHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(rootFrame);
    }

    private void ensureWebView(AppTab tab) {
        if (tab.webView == null) {
            tab.webView = createWebView(tab);
            webHost.addView(tab.webView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            tab.webView.setVisibility(tab == currentTab ? View.VISIBLE : View.GONE);
            if (!tab.loaded && tab.permanent) {
                tab.loaded = true;
                tab.lastUrl = tab.homeUrl;
                tab.webView.loadUrl(tab.homeUrl);
            }
        }
    }

    private WebView createWebView(final AppTab tab) {
        WebView web = new WebView(this);
        web.setBackgroundColor(C_WHITE);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        if (Build.VERSION.SDK_INT >= 26) {
            settings.setSafeBrowsingEnabled(true);
        }

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) {
            cookieManager.setAcceptThirdPartyCookies(web, true);
        }

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                String target = request.getUrl() == null ? "" : request.getUrl().toString();
                if (target.isEmpty()) return false;

                Uri uri = Uri.parse(target);
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    // Keep a home tab pinned in place when a tap heads to another site.
                    if (tab.permanent && !sameSite(view.getUrl(), target)) {
                        // hasGesture() exists from API 24. On API 23, default to opening the
                        // cross-site navigation in a disposable tab rather than replacing home.
                        boolean userGesture = Build.VERSION.SDK_INT < 24 || request.hasGesture();
                        if (userGesture) {
                            openTemporaryTabFromMain(tab, target);
                        } else {
                            if (tab == currentTab) showToast("Pengalihan otomatis diblokir agar tab utama tetap aman");
                        }
                        return true;
                    }
                    return false;
                }

                // Phone, email, and app links are handled by Android, not loaded as web pages.
                openExternalScheme(target);
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                tab.lastUrl = url;
                if (tab == currentTab) {
                    pageProgress.setProgress(5);
                    pageProgress.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                tab.lastUrl = url;
                if (tab == currentTab) {
                    pageProgress.setProgress(100);
                    pageProgress.setVisibility(View.GONE);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    if (tab == currentTab) pageProgress.setVisibility(View.GONE);
                    if (tab == currentTab) showToast("Halaman gagal dimuat. Periksa koneksi internet lalu coba lagi.");
                }
            }

            @Override
            public void onReceivedSslError(WebView view,
                                           android.webkit.SslErrorHandler handler,
                                           android.net.http.SslError error) {
                // Never bypass an invalid HTTPS certificate.
                handler.cancel();
            }

            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                recoverAfterRendererExit(tab, view);
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog,
                                          boolean isUserGesture, android.os.Message resultMsg) {
                // Do not allow unsolicited JavaScript pop-ups.
                if (!isUserGesture || countTemporaryTabs() >= MAX_TEMP_TABS) {
                    if (countTemporaryTabs() >= MAX_TEMP_TABS) {
                        showToast("Maksimal 4 tab tambahan. Tutup tab lama dulu.");
                    }
                    return false;
                }

                AppTab popupTab = createTemporaryTab(null);
                if (popupTab == null || popupTab.webView == null) return false;
                try {
                    WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                    transport.setWebView(popupTab.webView);
                    resultMsg.sendToTarget();
                    return true;
                } catch (RuntimeException ex) {
                    closeTemporaryTab(popupTab, true);
                    return false;
                }
            }

            @Override
            public void onCloseWindow(WebView window) {
                if (!tab.permanent) closeTemporaryTab(tab, tab == currentTab);
                else super.onCloseWindow(window);
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                if (!tab.permanent && !TextUtils.isEmpty(title)) {
                    String cleaned = title.trim();
                    if (!cleaned.isEmpty() && !cleaned.equals(tab.title)) {
                        tab.title = cleaned;
                        renderTabs(tab == currentTab);
                    }
                }
            }

            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (tab == currentTab) {
                    pageProgress.setProgress(newProgress);
                    pageProgress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
                }
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> uploadCallback,
                                             FileChooserParams fileChooserParams) {
                if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
                fileChooserCallback = uploadCallback;
                try {
                    Intent picker = fileChooserParams.createIntent();
                    startActivityForResult(picker, FILE_CHOOSER_REQUEST);
                    return true;
                } catch (RuntimeException ex) {
                    fileChooserCallback.onReceiveValue(null);
                    fileChooserCallback = null;
                    showToast("Pemilih berkas tidak tersedia di perangkat ini");
                    return false;
                }
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                enterFullscreen(view, callback);
            }

            @Override
            public void onHideCustomView() {
                exitFullscreen();
            }
        });

        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimetype, long contentLength) {
                startDownload(url, userAgent, contentDisposition, mimetype);
            }
        });
        return web;
    }

    private void openExternalScheme(String target) {
        try {
            Intent intent;
            if (target.startsWith("intent:")) {
                intent = Intent.parseUri(target, Intent.URI_INTENT_SCHEME);
                if (intent.resolveActivity(getPackageManager()) == null) {
                    String fallback = intent.getStringExtra("browser_fallback_url");
                    if (!TextUtils.isEmpty(fallback) && (fallback.startsWith("https://") || fallback.startsWith("http://"))) {
                        if (currentTab != null && !currentTab.permanent) currentTab.webView.loadUrl(fallback);
                        else if (currentTab != null) openTemporaryTabFromMain(currentTab, fallback);
                    }
                    return;
                }
            } else {
                intent = new Intent(Intent.ACTION_VIEW, Uri.parse(target));
            }
            startActivity(intent);
        } catch (ActivityNotFoundException | java.net.URISyntaxException | SecurityException ex) {
            showToast("Tidak ada aplikasi yang bisa membuka tautan ini");
        }
    }

    private void startDownload(String url, String userAgent, String contentDisposition, String mimeType) {
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingDownloadUrl = url;
            pendingDownloadUserAgent = userAgent;
            pendingDownloadDisposition = contentDisposition;
            pendingDownloadMimeType = mimeType;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, DOWNLOAD_PERMISSION_REQUEST);
            showToast("Izinkan akses penyimpanan untuk mengunduh berkas.");
            return;
        }
        enqueueDownload(url, userAgent, contentDisposition, mimeType);
    }

    private void enqueueDownload(String url, String userAgent, String contentDisposition, String mimeType) {
        try {
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) {
                showToast("Tautan unduhan tidak didukung");
                return;
            }
            DownloadManager.Request request = new DownloadManager.Request(uri);
            String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
            request.setTitle(fileName);
            request.setDescription("Mengunduh dari Nanas");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            if (!TextUtils.isEmpty(mimeType)) request.setMimeType(mimeType);
            if (!TextUtils.isEmpty(userAgent)) request.addRequestHeader("User-Agent", userAgent);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (!TextUtils.isEmpty(cookie)) request.addRequestHeader("Cookie", cookie);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (manager != null) {
                manager.enqueue(request);
                showToast("Unduhan dimulai. Periksa notifikasi ponsel.");
            } else {
                showToast("Pengunduh tidak tersedia di perangkat ini");
            }
        } catch (RuntimeException ex) {
            showToast("Unduhan tidak dapat dimulai");
        }
    }

    private void openTemporaryTabFromMain(AppTab source, String url) {
        if (countTemporaryTabs() >= MAX_TEMP_TABS) {
            showToast("Maksimal 4 tab tambahan. Tutup tab lama dulu.");
            return;
        }
        if (source.permanent) lastMainTabIndex = source.mainIndex;
        createTemporaryTab(url);
    }

    /** Creates a disposable tab; null means that the temporary-tab cap was reached. */
    private AppTab createTemporaryTab(String initialUrl) {
        if (countTemporaryTabs() >= MAX_TEMP_TABS) return null;
        AppTab tab = new AppTab(false, -1, "Tab baru", null);
        tab.loaded = true; // The target blank is supplied by WebView, or loaded explicitly below.
        tabs.add(tab);
        ensureWebView(tab);
        if (!TextUtils.isEmpty(initialUrl)) {
            tab.lastUrl = initialUrl;
            tab.webView.loadUrl(initialUrl);
        }
        selectTab(tab, true);
        return tab;
    }

    private int countTemporaryTabs() {
        int count = 0;
        for (AppTab tab : tabs) if (!tab.permanent) count++;
        return count;
    }

    private void selectTab(AppTab tab, boolean scrollToSelection) {
        if (tab == null || !tabs.contains(tab)) return;
        currentTab = tab;
        if (tab.permanent) lastMainTabIndex = tab.mainIndex;
        ensureWebView(tab);
        showCurrentWebView();
        pageProgress.setVisibility(View.GONE);
        renderTabs(scrollToSelection);
    }

    private void showCurrentWebView() {
        for (AppTab tab : tabs) {
            if (tab.webView != null) tab.webView.setVisibility(tab == currentTab ? View.VISIBLE : View.GONE);
        }
    }

    private void renderTabs(boolean scrollToEnd) {
        if (tabStrip == null) return;
        final int oldScroll = tabsScroller == null ? 0 : tabsScroller.getScrollX();
        tabStrip.removeAllViews();
        for (final AppTab tab : tabs) {
            LinearLayout pill = new LinearLayout(this);
            pill.setOrientation(LinearLayout.HORIZONTAL);
            pill.setGravity(Gravity.CENTER_VERTICAL);
            pill.setPadding(dp(11), 0, dp(tab.permanent ? 11 : 5), 0);
            pill.setBackground(makePill(tab == currentTab));
            pill.setClickable(true);
            pill.setFocusable(true);

            TextView label = new TextView(this);
            label.setText(tab.title);
            label.setTextColor(tab == currentTab ? C_TEXT : C_MUTED);
            label.setTextSize(12);
            label.setTypeface(Typeface.DEFAULT, tab == currentTab ? Typeface.BOLD : Typeface.NORMAL);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setMaxWidth(dp(102));
            pill.addView(label, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

            if (!tab.permanent) {
                TextView close = new TextView(this);
                close.setText("×");
                close.setTextColor(C_MUTED);
                close.setTextSize(19);
                close.setGravity(Gravity.CENTER);
                close.setPadding(dp(6), 0, dp(2), dp(1));
                pill.addView(close, new LinearLayout.LayoutParams(dp(25), dp(38)));
                close.setOnClickListener(v -> closeTemporaryTab(tab, tab == currentTab));
            }

            pill.setOnClickListener(v -> selectTab(tab, false));
            LinearLayout.LayoutParams pillParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
            pillParams.setMargins(dp(3), 0, dp(3), 0);
            tabStrip.addView(pill, pillParams);
        }

        if (tabsScroller != null) {
            tabsScroller.post(() -> {
                int destination = scrollToEnd ? Math.max(0, tabStrip.getWidth() - tabsScroller.getWidth()) : oldScroll;
                tabsScroller.scrollTo(destination, 0);
            });
        }
    }

    private GradientDrawable makePill(boolean selected) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(selected ? C_SELECTED : C_TAB_IDLE);
        drawable.setCornerRadius(dp(13));
        drawable.setStroke(dp(1), selected ? C_YELLOW : C_BORDER);
        return drawable;
    }

    private void closeTemporaryTab(AppTab tab, boolean returnToMain) {
        if (tab == null || tab.permanent || !tabs.contains(tab)) return;
        boolean wasCurrent = tab == currentTab;
        tabs.remove(tab);
        WebView web = tab.webView;
        tab.webView = null;
        if (web != null) {
            web.stopLoading();
            web.setWebChromeClient(null);
            web.setWebViewClient(null);
            web.setDownloadListener(null);
            if (web.getParent() instanceof ViewGroup) ((ViewGroup) web.getParent()).removeView(web);
            web.clearHistory();
            web.destroy();
        }

        if (wasCurrent || (returnToMain && currentTab == tab)) {
            AppTab main = lastMainTabIndex == 1 ? muzikasaTab : yutoTab;
            if (main == null || !tabs.contains(main)) main = yutoTab;
            selectTab(main, false);
        } else {
            renderTabs(false);
        }
    }

    private void handleBack() {
        if (fullScreenView != null) {
            exitFullscreen();
            return;
        }
        // Back from any advertisement/popup tab closes it immediately and reveals a pinned home tab.
        if (currentTab != null && !currentTab.permanent) {
            closeTemporaryTab(currentTab, true);
            return;
        }
        if (currentTab != null && currentTab.webView != null && currentTab.webView.canGoBack()) {
            currentTab.webView.goBack();
            return;
        }
        if (currentTab == muzikasaTab) {
            selectTab(yutoTab, false);
            return;
        }
        // Keep standard Android behavior when the first home tab is already at its start page.
        if (Build.VERSION.SDK_INT < 33) super.onBackPressed();
        else finish();
    }

    @Override
    public void onBackPressed() {
        // Android versions older than 13 use this callback; newer versions use OnBackInvokedDispatcher.
        if (Build.VERSION.SDK_INT < 33) handleBack();
        else super.onBackPressed();
    }

    private void enterFullscreen(View view, WebChromeClient.CustomViewCallback callback) {
        if (fullScreenView != null) {
            callback.onCustomViewHidden();
            return;
        }
        fullScreenView = view;
        fullScreenCallback = callback;
        appColumn.setVisibility(View.GONE);
        rootFrame.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void exitFullscreen() {
        if (fullScreenView == null) return;
        rootFrame.removeView(fullScreenView);
        fullScreenView = null;
        appColumn.setVisibility(View.VISIBLE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (fullScreenCallback != null) {
            fullScreenCallback.onCustomViewHidden();
            fullScreenCallback = null;
        }
    }

    private void recoverAfterRendererExit(AppTab tab, WebView oldWebView) {
        String reloadUrl = oldWebView.getUrl();
        if (TextUtils.isEmpty(reloadUrl) || "about:blank".equals(reloadUrl)) reloadUrl = tab.lastUrl;
        if (oldWebView.getParent() instanceof ViewGroup) {
            ((ViewGroup) oldWebView.getParent()).removeView(oldWebView);
        }
        oldWebView.setWebChromeClient(null);
        oldWebView.setWebViewClient(null);
        oldWebView.destroy();
        tab.webView = null;
        // Mark loaded to prevent ensureWebView from first loading the home URL as well.
        tab.loaded = true;
        ensureWebView(tab);
        if (!TextUtils.isEmpty(reloadUrl)) {
            tab.lastUrl = reloadUrl;
            tab.webView.loadUrl(reloadUrl);
        } else if (tab.permanent) {
            tab.webView.loadUrl(tab.homeUrl);
        } else {
            closeTemporaryTab(tab, tab == currentTab);
        }
    }

    private boolean sameSite(String currentUrl, String targetUrl) {
        try {
            Uri current = Uri.parse(currentUrl);
            Uri target = Uri.parse(targetUrl);
            String currentScheme = current.getScheme();
            String targetScheme = target.getScheme();
            if (currentScheme == null || targetScheme == null) return true;
            if (!(currentScheme.equalsIgnoreCase("http") || currentScheme.equalsIgnoreCase("https"))) return true;
            if (!(targetScheme.equalsIgnoreCase("http") || targetScheme.equalsIgnoreCase("https"))) return true;
            String a = normalizeHost(current.getHost());
            String b = normalizeHost(target.getHost());
            return !TextUtils.isEmpty(a) && a.equalsIgnoreCase(b);
        } catch (RuntimeException ex) {
            return true;
        }
    }

    private String normalizeHost(String host) {
        if (host == null) return "";
        String out = host.toLowerCase(Locale.ROOT);
        if (out.startsWith("www.")) out = out.substring(4);
        return out;
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    // Basic file upload support for HTML forms on either web site.
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST && fileChooserCallback != null) {
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) results[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            fileChooserCallback.onReceiveValue(results);
            fileChooserCallback = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == DOWNLOAD_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED
                    && !TextUtils.isEmpty(pendingDownloadUrl)) {
                enqueueDownload(pendingDownloadUrl, pendingDownloadUserAgent,
                        pendingDownloadDisposition, pendingDownloadMimeType);
            } else {
                showToast("Izin tidak diberikan; unduhan dibatalkan.");
            }
            pendingDownloadUrl = null;
            pendingDownloadUserAgent = null;
            pendingDownloadDisposition = null;
            pendingDownloadMimeType = null;
        }
    }

    @Override
    protected void onPause() {
        for (AppTab tab : tabs) if (tab.webView != null) tab.webView.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        for (AppTab tab : tabs) if (tab.webView != null) tab.webView.onResume();
    }

    @Override
    protected void onDestroy() {
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        }
        if (fileChooserCallback != null) {
            fileChooserCallback.onReceiveValue(null);
            fileChooserCallback = null;
        }
        if (fullScreenView != null) {
            try { exitFullscreen(); } catch (RuntimeException ignored) { }
        }
        for (AppTab tab : tabs) {
            WebView web = tab.webView;
            tab.webView = null;
            if (web != null) {
                try {
                    web.stopLoading();
                    web.setWebChromeClient(null);
                    web.setWebViewClient(null);
                    web.setDownloadListener(null);
                    if (web.getParent() instanceof ViewGroup) ((ViewGroup) web.getParent()).removeView(web);
                    web.destroy();
                } catch (RuntimeException ignored) { }
            }
        }
        tabs.clear();
        super.onDestroy();
    }
}
