package com.osupatch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * osu! Mod Hider v7.0 - Phone Universal
 */
public class MainActivity extends Activity {

    private static final String[] OSU_PACKAGES = {
        "sh.ppy.osulazer", "sh.ppy.osu", "sh.ppy.osulazer.dev",
        "sh.ppy.osulazer.canary", "sh.ppy.osulazer.beta",
        "com.reco1l.rimu", "com.reco1l.rimu.stable"
    };

    private static final String[] SU_PATHS = {
        "/system/bin/su", "/system/xbin/su", "/sbin/su", 
        "/system/sbin/su", "/vendor/bin/su", "/data/local/bin/su"
    };

    private TextView tvLog, tvStatus;
    private Button btnApply, btnUnmount;
    private ScrollView scrollLog;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private String suPath = null;
    private String detectedPackageName;
    private String detectedPackagePath;
    private String backupDir;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvLog = findViewById(R.id.tv_log);
        tvStatus = findViewById(R.id.tv_status);
        btnApply = findViewById(R.id.btn_apply);
        btnUnmount = findViewById(R.id.btn_unmount);
        scrollLog = findViewById(R.id.scroll_log);

        btnApply.setEnabled(false);
        btnUnmount.setEnabled(false);
        btnApply.setText("ВКЛ");
        btnUnmount.setText("ВЫКЛ");

        btnApply.setOnClickListener(v -> hideMods());
        btnUnmount.setOnClickListener(v -> unhideMods());

        initializeApp();
    }

    private void initializeApp() {
        executor.execute(() -> {
            log("OSU! MOD SPOOFER v7.0");
            log("");
            
            if (!checkRootAccess()) {
                mainHandler.post(() -> setStatus("Нужен ROOT", "#F44336"));
                return;
            }
            
            log("Root OK!");
            detectOsu();
            
            if (detectedPackageName != null) {
                mainHandler.post(() -> {
                    setStatus("ГОТОВ", "#4CAF50");
                    btnApply.setEnabled(true);
                });
            } else {
                mainHandler.post(() -> setStatus("osu! не найден", "#FF9800"));
            }
        });
    }

    private boolean checkRootAccess() {
        suPath = findSu();
        if (suPath == null) {
            log("su not found");
            return false;
        }
        log("su: " + suPath);
        
        // Try su -c id - this prompts Magisk
        return tryRootCmd(new String[]{suPath, "-c", "id"});
    }

    private boolean tryRootCmd(String[] cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            
            int timeout = 0;
            while (timeout < 30 && p.isAlive()) {
                Thread.sleep(100);
                timeout++;
            }
            
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
            
            p.waitFor();
            String output = sb.toString();
            log("Output: " + output.substring(0, Math.min(50, output.length())));
            
            return output.contains("uid=0");
            
        } catch (Exception e) {
            log("Error: " + e.getMessage());
            return false;
        }
    }

    private String findSu() {
        for (String path : SU_PATHS) {
            if (new File(path).exists()) return path;
        }
        return null;
    }

    private void detectOsu() {
        for (String pkg : OSU_PACKAGES) {
            try {
                PackageManager pm = getPackageManager();
                PackageInfo pi = pm.getPackageInfo(pkg, 0);
                if (pi != null && pi.applicationInfo != null) {
                    detectedPackageName = pkg;
                    detectedPackagePath = pi.applicationInfo.sourceDir;
                    if (detectedPackagePath != null && detectedPackagePath.endsWith(".apk")) {
                        detectedPackagePath = detectedPackagePath.substring(0, detectedPackagePath.lastIndexOf('/'));
                    }
                    log("Found: " + pkg);
                    return;
                }
            } catch (Exception ignored) {}
        }
    }

    private void hideMods() {
        btnApply.setEnabled(false);
        setStatus("Включаю...", "#FF9800");
        
        executor.execute(() -> {
            try {
                String dllPath = findDll();
                if (dllPath == null) {
                    fail("DLL не найден");
                    return;
                }
                
                backupDir = "/data/local/tmp/hide_" + System.currentTimeMillis();
                runRoot("mkdir -p " + backupDir);
                runRoot("cp '" + dllPath + "' '" + backupDir + "/orig.dll'");
                
                runRoot("am force-stop " + detectedPackageName);
                Thread.sleep(500);
                
                fullHidePatch(dllPath);
                restartOsu();
                
                mainHandler.post(() -> {
                    setStatus("ВКЛ", "#4CAF50");
                    btnUnmount.setEnabled(true);
                });
                
            } catch (Exception e) {
                fail(e.getMessage());
            }
        });
    }

    private String findDll() {
        if (detectedPackagePath == null) return null;
        
        String[] paths = {detectedPackagePath, detectedPackagePath + "/files", detectedPackagePath + "/files/shared_assemblies"};
        
        for (String p : paths) {
            String result = runRoot("find '" + p + "' -name 'Assembly-CSharp.dll' -type f 2>/dev/null | head -1").trim();
            if (!result.isEmpty()) return result;
        }
        return null;
    }

    private void fullHidePatch(String dllPath) {
        runRoot("chmod 644 '" + dllPath + "'");
        runRoot("sed -i 's/UserPlayable = true/UserPlayable = false/g' '" + dllPath + "' 2>/dev/null || true");
    }

    private void restartOsu() {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(detectedPackageName);
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            }
        } catch (Exception e) {
            runRoot("monkey -p " + detectedPackageName + " 1");
        }
    }

    private void restoreBackup() {
        if (backupDir == null) return;
        String dll = findDll();
        if (dll != null) {
            runRoot("cp '" + backupDir + "/orig.dll' '" + dll + "'");
            runRoot("chmod 644 '" + dll + "'");
        }
    }

    private void unhideMods() {
        setStatus("Выключаю...", "#FF9800");
        
        executor.execute(() -> {
            runRoot("am force-stop " + detectedPackageName);
            restoreBackup();
            restartOsu();
            
            mainHandler.post(() -> {
                setStatus("ВЫКЛ", "#888888");
                btnUnmount.setEnabled(false);
                btnApply.setEnabled(true);
            });
        });
    }

    private String runRoot(String cmd) {
        if (suPath == null) return "";
        
        try {
            ProcessBuilder pb = new ProcessBuilder(suPath, "-c", cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            
            int timeout = 0;
            while (timeout < 50 && p.isAlive()) {
                Thread.sleep(100);
                timeout++;
            }
            
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
            
            p.waitFor();
            return sb.toString().trim();
            
        } catch (Exception e) {
            return "";
        }
    }

    private void log(String msg) {
        mainHandler.post(() -> {
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
            tvLog.append("[" + sdf.format(new Date()) + "] " + msg + "\n");
            scrollLog.post(() -> scrollLog.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void setStatus(String s, String c) {
        mainHandler.post(() -> {
            tvStatus.setText(s);
            try {
                tvStatus.setTextColor(android.graphics.Color.parseColor(c));
            } catch (Exception ignored) {}
        });
    }

    private void fail(String reason) {
        mainHandler.post(() -> {
            setStatus("Ошибка", "#F44336");
            btnApply.setEnabled(true);
            new AlertDialog.Builder(this).setTitle("Ошибка").setMessage(reason).setPositiveButton("ОК", null).show();
        });
    }
}