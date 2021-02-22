package com.example.beskar;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.view.View;

import androidx.preference.PreferenceManager;

import com.example.beskar.server.AbstractDnsServer;
import com.example.beskar.server.DnsServer;
import com.example.beskar.server.DnsServerHelper;
import com.example.beskar.service.BeskarVpnService;
import com.example.beskar.util.Logger;
import com.example.beskar.util.Rule;
import com.example.beskar.util.RuleResolver;
import com.google.android.material.snackbar.Snackbar;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class Beskar extends Application {

    private static final String SHORTCUT_ID_ACTIVATE = "shortcut_activate";

    public static final List<DnsServer> DNS_SERVERS = new ArrayList<DnsServer>() {{
        add(new DnsServer("8.8.8.8", R.string.filter_none));
        add(new DnsServer("185.228.168.9", R.string.filter_low));
        add(new DnsServer("185.228.168.10", R.string.filter_medium));
        add(new DnsServer("185.228.168.168", R.string.filter_high));
        add(new DnsServer("208.67.222.123", R.string.filter_backup_primary));
        add(new DnsServer("208.67.220.123", R.string.filter_backup_secondary));
    }};

    public static final ArrayList<Rule> RULES = new ArrayList<Rule>() {{
        add(new Rule("chadmayfield/porn-top1m", "chadmayfield.dnsmasq",
                "https://raw.githubusercontent.com/chadmayfield/my-pihole-blocklists/master/lists" +
                        "/pi_blocklist_porn_top1m.list"));

        add(new Rule("notracking/hosts-blacklists", "notracking.dnsmasq",
                "https://raw.githubusercontent.com/notracking/hosts-blocklists/master/dnsmasq" +
                        "/dnsmasq.blacklist.txt"));
    }};

    public static HashMap<String, String> customDomains = new HashMap<>();

    public static String rulePath;
    public static String logPath;

    private static Beskar instance;
    private SharedPreferences prefs;
    private Thread mResolver;

    // Stuff required for rule syncing
    private Thread mThread = null;
    private RuleConfigHandler mHandler = null;
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    @Override
    public void onCreate() {
        super.onCreate();

        instance = this;
        Logger.init();
        mResolver = new Thread(new RuleResolver());
        mResolver.start();
        initData();
    }

    private void initDirectory(String dir) {
        File directory = new File(dir);
        if (!directory.isDirectory()) {
            Logger.warning(dir + " is not a directory. Delete result: " + directory.delete());
        }
        if (!directory.exists()) {
            Logger.debug(dir + " does not exist. Create result: " + directory.mkdirs());
        }
    }

    private void initData() {
        PreferenceManager.setDefaultValues(this, R.xml.pref_settings, false);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        if (getExternalFilesDir(null) != null) {
            rulePath = getExternalFilesDir(null).getPath() + "/rules/";
            logPath = getExternalFilesDir(null).getPath() + "/logs/";

            initDirectory(rulePath);
            initDirectory(logPath);
        }

        // Load preferences
        selectRule(RULES.get(0), prefs.getBoolean("home_adult_switch_checked", false));
        selectRule(RULES.get(1), prefs.getBoolean("home_ads_switch_checked", false));

        int sliderIdx = prefs.getInt("home_slider_index", 3);
        if (sliderIdx > 0) {
            prefs.edit().putBoolean("settings_use_system_dns", false).apply();
            prefs.edit().putString("primary_server", String.valueOf(sliderIdx)).apply();
            updateUpstreamServers();
        } else {
            prefs.edit().putBoolean("settings_use_system_dns", true).apply();
            BeskarVpnService.updateUpstreamToSystemDNS(getApplicationContext());
        }
        Logger.debug("Loaded preferences from file.");
    }

    public static void initRuleResolver() {
        ArrayList<String> pendingLoad = new ArrayList<>();
        ArrayList<Rule> usingRules = new ArrayList<>();

        for (Rule rule : Beskar.RULES) {
            if (rule.isUsing()) {
                usingRules.add(rule);
            }
        }

        if (usingRules != null && usingRules.size() > 0) {
            for (Rule rule : usingRules) {
                if (rule.isUsing()) {
                    pendingLoad.add(rulePath + rule.getFileName());
                }
            }
            if (pendingLoad.size() > 0) {
                String[] arr = new String[pendingLoad.size()];
                pendingLoad.toArray(arr);
                RuleResolver.startLoadDnsmasq(arr);
            } else {
                RuleResolver.clear();
            }
        } else {
            RuleResolver.clear();
        }

        if (!customDomains.isEmpty()) {
            RuleResolver.startLoadCustom();
        }
    }

    public static void addCustomDomain(String key) {
        customDomains.put(key, "0.0.0.0");
        RuleResolver.addCustom(key);
    }

    public static void removeCustomDomain(String key) {
        customDomains.remove(key);
        RuleResolver.removeCustom(key);
    }

    public static void setRulesChanged() {
        if (BeskarVpnService.isActivated()) {
            initRuleResolver();
        }
    }

    public static SharedPreferences getPrefs() {
        return getInstance().prefs;
    }

    public static void selectRule(Rule rule, boolean isUsing) {
        if (rule.isUsing() == isUsing) return;
        if (isUsing && !rule.getDownloaded()) {
            Beskar.getInstance().ruleSync(rule);
            rule.setDownloaded(true);
        }
        rule.setUsing(isUsing);
        Beskar.setRulesChanged();
    }

    @Override
    public void onTerminate() {
        super.onTerminate();

        instance = null;
        prefs = null;
        RuleResolver.shutdown();
        mResolver.interrupt();
        RuleResolver.clear();
        mResolver = null;
        Logger.shutdown();
    }

    public static Intent getServiceIntent(Context context) {
        return new Intent(context, BeskarVpnService.class);
    }

    public static boolean switchService() {
        if (BeskarVpnService.isActivated()) {
            deactivateService(instance);
            return false;
        } else {
            prepareAndActivateService(instance);
            return true;
        }
    }

    public static boolean prepareAndActivateService(Context context) {
        Intent intent = VpnService.prepare(context);
        if (intent != null) {
            return false;
        } else {
            activateService(context);
            return true;
        }
    }

    public static void activateService(Context context) {
        updateUpstreamServers();
        Logger.info("Starting VPN service in background.");
        context.startService(Beskar.getServiceIntent(context).setAction(BeskarVpnService.ACTION_ACTIVATE));
    }

    public static void updateUpstreamServers() {
        BeskarVpnService.primaryServer =
                (AbstractDnsServer) DnsServerHelper.getServerById(DnsServerHelper.getPrimary()).clone();
        BeskarVpnService.secondaryServer =
                (AbstractDnsServer) DnsServerHelper.getServerById(DnsServerHelper.getSecondary()).clone();
        Logger.info("Upstream DNS set to: " + BeskarVpnService.primaryServer.getAddress() + " " + BeskarVpnService.secondaryServer.getAddress());
    }

    public static void deactivateService(Context context) {
        context.startService(getServiceIntent(context).setAction(BeskarVpnService.ACTION_DEACTIVATE));
        context.stopService(getServiceIntent(context));
    }

    public static void updateShortcut(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            Logger.info("Updating shortcut");
            boolean activate = BeskarVpnService.isActivated();
            String notice = activate ? context.getString(R.string.button_text_deactivate) : context.getString(R.string.button_text_activate);
            ShortcutInfo info = new ShortcutInfo.Builder(context, Beskar.SHORTCUT_ID_ACTIVATE)
                    .setLongLabel(notice)
                    .setShortLabel(notice)
                    .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
                    .setIntent(new Intent(context, MainActivity.class).setAction(Intent.ACTION_VIEW)
                            .putExtra(MainActivity.LAUNCH_ACTION, activate ? MainActivity.LAUNCH_ACTION_DEACTIVATE : MainActivity.LAUNCH_ACTION_ACTIVATE))
                    .build();
            ShortcutManager shortcutManager = (ShortcutManager) context.getSystemService(SHORTCUT_SERVICE);
            shortcutManager.addDynamicShortcuts(Collections.singletonList(info));
        }
    }

    public static void donate() {
        openUri("https://qr.alipay.com/FKX04751EZDP0SQ0BOT137");
    }

    public static void openUri(String uri) {
        try {
            instance.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Logger.logException(e);
        }
    }

    public boolean ruleSync(Rule rule) {
        String ruleFilename = rule.getFileName();
        String ruleDownloadUrl = rule.getDownloadUrl();

        if (mThread == null) {
            if (ruleDownloadUrl.startsWith("content://")) {
                mThread = new Thread(() -> {
                    try {
                        InputStream inputStream = getContentResolver().openInputStream(Uri.parse(ruleDownloadUrl));
                        int readLen;
                        byte[] data = new byte[1024];
                        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                        while ((readLen = inputStream.read(data)) != -1) {
                            buffer.write(data, 0, readLen);
                        }
                        inputStream.close();
                        buffer.flush();
                        mHandler.obtainMessage(RuleConfigHandler.MSG_RULE_DOWNLOADED,
                                new RuleData(ruleFilename, buffer.toByteArray())).sendToTarget();
                    } catch (Exception e) {
                        Logger.logException(e);
                    } finally {
                        stopThread();
                    }
                });
            } else {
                mThread = new Thread(() -> {
                    try {
                        Request request = new Request.Builder()
                                .url(ruleDownloadUrl).get().build();
                        Response response = HTTP_CLIENT.newCall(request).execute();
                        Logger.info("Downloaded " + ruleDownloadUrl);
                        if (response.isSuccessful() && mHandler != null) {
                            mHandler.obtainMessage(RuleConfigHandler.MSG_RULE_DOWNLOADED,
                                    new RuleData(ruleFilename, response.body().bytes())).sendToTarget();
                        }
                    } catch (Exception e) {
                        Logger.logException(e);
                        if (mHandler != null) {
                            mHandler.obtainMessage(RuleConfigHandler.MSG_RULE_DOWNLOADED,
                                    new RuleData(ruleFilename, new byte[0])).sendToTarget();
                        }
                    } finally {
                        stopThread();
                    }
                });
            }
            mThread.start();
        }
        return false;
    }

    private void stopThread() {
        if (mThread != null) {
            mThread.interrupt();
            mThread = null;
        }
    }

    private class RuleData {
        private byte[] data;
        private String filename;

        RuleData(String filename, byte[] data) {
            this.data = data;
            this.filename = filename;
        }

        byte[] getData() {
            return data;
        }

        String getFilename() {
            return filename;
        }
    }

    private static class RuleConfigHandler extends Handler {
        static final int MSG_RULE_DOWNLOADED = 0;

        private View view = null;

        RuleConfigHandler setView(View view) {
            this.view = view;
            return this;
        }

        void shutdown() {
            view = null;
        }

        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);

            switch (msg.what) {
                case MSG_RULE_DOWNLOADED:
                    RuleData ruleData = (RuleData) msg.obj;
                    if (ruleData.data.length == 0) {
                        if (view != null) {
                            Snackbar.make(view, R.string.notice_download_failed, Snackbar.LENGTH_SHORT).show();
                        }
                        break;
                    }
                    try {
                        File file = new File(Beskar.rulePath + ruleData.getFilename());
                        FileOutputStream stream = new FileOutputStream(file);
                        stream.write(ruleData.getData());
                        stream.close();
                    } catch (Exception e) {
                        Logger.logException(e);
                    }

                    if (view != null) {
                        Snackbar.make(view, R.string.notice_downloaded, Snackbar.LENGTH_SHORT).show();
                    }
                    break;
            }
        }
    }

    public static Beskar getInstance() {
        return instance;
    }
}
