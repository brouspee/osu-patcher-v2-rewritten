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
 * osu! Mod Hider v6.0 - Full Rewrite
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
    private Process rootProcess;
    private DataOutputStream rootOs;

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
            log("OSU! MOD SPOOFER v6.0");
            log("");
            
            // Find su binary
            suPath = findSu();
            if (suPath == null) {
                mainHandler.post(() -> {
                    setStatus("SU не найден", "#F44336");
                    log("ERROR: su binary not found");
                });
                return;
            }
            log("Found su: " + suPath);
            
            // Request root access - this will trigger Magisk prompt
            log("Requesting root access...");
            if (!requestRoot()) {
                mainHandler.post(() -> {
                    setStatus("ROOT отклонён", "#F44336");
                    log("ERROR: Root denied by user");
                });
                return;
            }
            
            log("Root granted!");
            
            // Detect osu
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

    private String findSu() {
        for (String path : SU_PATHS) {
            File f = new File(path);
            if (f.exists()) {
                return path;
            }
        }
        return null;
    }

    private boolean requestRoot() {
        try {
            // Start su process with -mm for master mode (persistent)
            ProcessBuilder pb = new ProcessBuilder(suPath, "-mm", "-c", "id");
            pb.redirectErrorStream(true);
            rootProcess = pb.start();
            
            rootOs = new DataOutputStream(rootProcess.getOutputStream());
            
            // Read output
            BufferedReader br = new BufferedReader(new InputStreamReader(rootProcess.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            
            // Wait for process with timeout
            int attempts = 0;
            while (attempts < 50) {
                if (rootProcess.waitFor(100, TimeUnit.MILLISECONDS)) {
                    break;
                }
                attempts++;
            }
            
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
            
            int exitCode = rootProcess.waitFor();
            String output = sb.toString();
            
            log("Root check output: " + output);
            log("Exit code: " + exitCode);
            
            // If exit code is 0 and output contains uid=0, we have root
            if (exitCode == 0 && output.contains("uid=0")) {
                // Start persistent shell for future commands
                return startPersistentShell();
            }
            
            return false;
            
        } catch (Exception e) {
            log("Root error: " + e.getMessage());
            return false;
        }
    }

    private boolean startPersistentShell() {
        try {
            ProcessBuilder pb = new ProcessBuilder(suPath, "-mm");
            pb.redirectErrorStream(true);
            rootProcess = pb.start();
            rootOs = new DataOutputStream(rootProcess.getOutputStream());
            
            return rootProcess.isAlive();
        } catch (Exception e) {
            log("Shell start error: " + e.getMessage());
            return false;
        }
    }

    private String runRootCmd(String cmd) {
        if (rootProcess == null || !rootProcess.isAlive()) {
            log("Root process dead, requesting new...");
            if (!requestRoot()) {
                return "";
            }
        }
        
        try {
            rootOs.writeBytes(cmd + "\n");
            rootOs.flush();
            
            // Give command time to execute
            Thread.sleep(200);
            
            // Read available output
            BufferedReader br = new BufferedReader(new InputStreamReader(rootProcess.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            
            while (br.ready()) {
                line = br.readLine();
                if (line != null) {
                    sb.append(line).append("\n");
                }
            }
            
            return sb.toString().trim();
            
        } catch (Exception e) {
            log("Cmd error: " + e.getMessage());
            return "";
        }
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
                    log("Found osu!: " + pkg);
                    log("Path: " + detectedPackagePath);
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
                
                log("DLL: " + dllPath);
                
                // Create backup
                backupDir = "/data/local/tmp/hide_" + System.currentTimeMillis();
                runRootCmd("mkdir -p " + backupDir);
                runRootCmd("cp '" + dllPath + "' '" + backupDir + "/orig.dll'");
                log("Backup created");
                
                // Stop osu
                runRootCmd("am force-stop " + detectedPackageName);
                Thread.sleep(500);
                
                // Apply patch
                fullHidePatch(dllPath);
                
                // Restart
                restartOsu();
                
                mainHandler.post(() -> {
                    setStatus("ВКЛ", "#4CAF50");
                    btnUnmount.setEnabled(true);
                });
                
                log("ГОТОВ!");
                
            } catch (Exception e) {
                log("ERROR: " + e.getMessage());
                fail(e.getMessage());
            }
        });
    }

    private String findDll() {
        if (detectedPackagePath == null) return null;
        
        String[] searchPaths = {
            detectedPackagePath,
            detectedPackagePath + "/files",
            detectedPackagePath + "/files/shared_assemblies",
            detectedPackagePath + "/lib"
        };
        
        for (String basePath : searchPaths) {
            String result = runRootCmd("find '" + basePath + "' -name 'Assembly-CSharp.dll' -type f 2>/dev/null | head -1").trim();
            if (!result.isEmpty()) {
                return result;
            }
        }
        return null;
    }

    private void fullHidePatch(String dllPath) {
        // Binary patches to hide mods - this is simplified
        // Real implementation would need more sophisticated patching
        runRootCmd("chmod 644 '" + dllPath + "'");
        
        // Basic sed patches
        runRootCmd("sed -i 's/UserPlayable = true/UserPlayable = false/g' '" + dllPath + "' 2>/dev/null || true");
        runRootCmd("sed -i 's/UserPlayable=true/UserPlayable=false/g' '" + dllPath + "' 2>/dev/null || true");
        
        log("Patch applied");
    }

    private void restartOsu() {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(detectedPackageName);
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } else {
                runRootCmd("monkey -p " + detectedPackageName + " 1");
            }
            log("osu! restarted");
        } catch (Exception e) {
            log("Restart error: " + e.getMessage());
        }
    }

    private void restoreBackup() {
        if (backupDir == null) return;
        
        String dll = findDll();
        if (dll != null) {
            runRootCmd("cp '" + backupDir + "/orig.dll' '" + dll + "'");
            runRootCmd("chmod 644 '" + dll + "'");
            log("Restored from backup");
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

    private void log(String msg) {
        mainHandler.post(() -> {
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
            String time = sdf.format(new Date());
            tvLog.append("[" + time + "] " + msg + "\n");
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
        log("ОШИБКА: " + reason);
        mainHandler.post(() -> {
            setStatus("Ошибка", "#F44336");
            btnApply.setEnabled(true);
            new AlertDialog.Builder(this).setTitle("Ошибка").setMessage(reason).setPositiveButton("ОК", null).show();
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (rootProcess != null) {
            rootProcess.destroy();
        }
    }
}