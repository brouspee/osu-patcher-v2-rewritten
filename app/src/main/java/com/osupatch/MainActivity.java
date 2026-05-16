package com.osupatch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * osu! Mod Hider v8.0 - Debug Root
 */
public class MainActivity extends Activity {

    private static final String[] OSU_PACKAGES = {
        "sh.ppy.osulazer", "sh.ppy.osu", "sh.ppy.osulazer.dev",
        "sh.ppy.osulazer.canary", "sh.ppy.osulazer.beta",
        "com.reco1l.rimu", "com.reco1l.rimu.stable"
    };

    // More complete su paths
    private static final String[] SU_PATHS = {
        "/system/bin/su",
        "/system/xbin/su", 
        "/sbin/su",
        "/system/sbin/su",
        "/vendor/bin/su",
        "/data/local/bin/su",
        "/data/adb/magisk/su",
        "/data/adb/magisk/bin/su",
        "/system/app/Superuser/Superuser.apk"
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
    private boolean hasRoot = false;

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

        log("Device: " + Build.MANUFACTURER + " " + Build.MODEL);
        log("Android: " + Build.VERSION.RELEASE);
        
        initializeApp();
    }

    private void initializeApp() {
        executor.execute(() -> {
            log("=== OSU! MOD SPOOFER v8.0 ===");
            log("");
            
            // List all su paths
            log("Checking su locations...");
            for (String p : SU_PATHS) {
                boolean exists = new File(p).exists();
                log(p + " = " + (exists ? "EXISTS" : "not found"));
                if (exists && suPath == null) {
                    suPath = p;
                }
            }
            
            if (suPath == null) {
                log("ERROR: No su found!");
                mainHandler.post(() -> {
                    setStatus("SU не найден", "#F44336");
                });
                return;
            }
            
            log("");
            log("Using su: " + suPath);
            log("Requesting root...");
            
            // Try to get root - this should trigger Magisk
            hasRoot = requestRoot();
            
            log("Root result: " + (hasRoot ? "GRANTED" : "DENIED/FAILED"));
            
            if (!hasRoot) {
                mainHandler.post(() -> {
                    setStatus("ROOT отклонён", "#F44336");
                });
                return;
            }
            
            detectOsu();
            
            if (detectedPackageName != null) {
                mainHandler.post(() -> {
                    setStatus("ГОТОВ", "#4CAF50");
                    btnApply.setEnabled(true);
                });
            } else {
                mainHandler.post(() -> {
                    setStatus("osu! не найден", "#FF9800");
                });
            }
        });
    }

    private boolean requestRoot() {
        try {
            // Execute su -c id
            Process process = Runtime.getRuntime().exec(suPath + " -c id");
            
            // Read output
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()));
            
            StringBuilder output = new StringBuilder();
            String line;
            
            // Wait for output with timeout
            long startTime = System.currentTimeMillis();
            while (System.currentTimeMillis() - startTime < 10000) {
                if (!process.isAlive()) break;
                Thread.sleep(50);
            }
            
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            
            int exitCode = process.waitFor();
            String result = output.toString();
            
            log("id output: " + result.trim());
            log("exit code: " + exitCode);
            
            // Check if we got root (uid=0)
            return result.contains("uid=0");
            
        } catch (Exception e) {
            log("Root request error: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private void detectOsu() {
        log("Searching for osu...");
        for (String pkg : OSU_PACKAGES) {
            try {
                PackageManager pm = getPackageManager();
                PackageInfo pi = pm.getPackageInfo(pkg, 0);
                if (pi != null && pi.applicationInfo != null) {
                    detectedPackageName = pkg;
                    // Get the actual data directory
                    detectedPackagePath = pi.applicationInfo.dataDir;
                    
                    log("Found: " + pkg);
                    log("Data dir: " + detectedPackagePath);
                    return;
                }
            } catch (Exception e) {
                // Package not found, continue
            }
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
                
                log("DLL: " + dllPath);
                
                // Backup
                backupDir = "/data/data/" + detectedPackageName + "/files/hide_" + System.currentTimeMillis();
                runRootCmd("mkdir -p " + backupDir);
                runRootCmd("cp '" + dllPath + "' '" + backupDir + "/orig.dll'");
                log("Backup done");
                
                // Stop osu
                runRootCmd("am force-stop " + detectedPackageName);
                Thread.sleep(500);
                
                // Patch
                runRootCmd("chmod 644 '" + dllPath + "'");
                runRootCmd("sed -i 's/UserPlayable = true/UserPlayable = false/g' '" + dllPath + "' 2>/dev/null || true");
                log("Patch done");
                
                // Restart
                restartOsu();
                
                mainHandler.post(() -> {
                    setStatus("ВКЛ", "#4CAF50");
                    btnUnmount.setEnabled(true);
                });
                
                log("DONE!");
                
            } catch (Exception e) {
                log("ERROR: " + e.getMessage());
                fail(e.getMessage());
            }
        });
    }

    private String findDll() {
        if (detectedPackagePath == null) return null;
        
        // Try common locations
        String[] searchPaths = {
            detectedPackagePath + "/files",
            detectedPackagePath + "/files/shared_assemblies",
            detectedPackagePath + "/lib"
        };
        
        for (String base : searchPaths) {
            try {
                String result = runRootCmd("find '" + base + "' -name 'Assembly-CSharp.dll' 2>/dev/null | head -1").trim();
                if (!result.isEmpty()) {
                    return result;
                }
            } catch (Exception e) {
                // Continue
            }
        }
        return null;
    }

    private void restartOsu() {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(detectedPackageName);
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                log("Started osu!");
            }
        } catch (Exception e) {
            runRootCmd("monkey -p " + detectedPackageName + " 1");
        }
    }

    private void restoreBackup() {
        if (backupDir == null) return;
        
        String dll = findDll();
        if (dll != null) {
            runRootCmd("cp '" + backupDir + "/orig.dll' '" + dll + "'");
            runRootCmd("chmod 644 '" + dll + "'");
            log("Restored");
        }
    }

    private void unhideMods() {
        setStatus("Выключаю...", "#FF9800");
        
        executor.execute(() -> {
            runRootCmd("am force-stop " + detectedPackageName);
            restoreBackup();
            restartOsu();
            
            mainHandler.post(() -> {
                setStatus("ВЫКЛ", "#888888");
                btnUnmount.setEnabled(false);
                btnApply.setEnabled(true);
            });
        });
    }

    private String runRootCmd(String cmd) {
        if (suPath == null || !hasRoot) {
            log("No root, skipping: " + cmd);
            return "";
        }
        
        try {
            Process process = Runtime.getRuntime().exec(suPath + " -c " + cmd);
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()));
            
            StringBuilder output = new StringBuilder();
            String line;
            
            long startTime = System.currentTimeMillis();
            while (System.currentTimeMillis() - startTime < 10000) {
                if (!process.isAlive()) break;
                Thread.sleep(50);
            }
            
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            
            process.waitFor();
            return output.toString().trim();
            
        } catch (Exception e) {
            log("Cmd error: " + e.getMessage());
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
            new AlertDialog.Builder(this)
                .setTitle("Ошибка")
                .setMessage(reason)
                .setPositiveButton("ОК", null)
                .show();
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}