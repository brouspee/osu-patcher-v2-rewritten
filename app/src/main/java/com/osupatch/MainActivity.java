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
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * osu! Mod Hider v9.0 - Proper Root
 */
public class MainActivity extends Activity {

    private static final String[] OSU_PACKAGES = {
        "sh.ppy.osulazer", "sh.ppy.osu", "sh.ppy.osulazer.dev",
        "sh.ppy.osulazer.canary", "sh.ppy.osulazer.beta",
        "com.reco1l.rimu", "com.reco1l.rimu.stable"
    };

    private static final String[] SU_PATHS = {
        "/system/bin/su", "/system/xbin/su", "/sbin/su",
        "/data/local/bin/su", "/data/adb/magisk/su"
    };

    private TextView tvLog, tvStatus;
    private Button btnApply, btnUnmount;
    private ScrollView scrollLog;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private String suPath = null;
    private Process suProcess = null;
    private OutputStreamWriter suWriter = null;
    private BufferedReader suReader = null;
    
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
            log("=== OSU! MOD SPOOFER v9.0 ===");
            
            // Find su
            suPath = findSu();
            if (suPath == null) {
                log("ERROR: su not found");
                mainHandler.post(() -> setStatus("SU не найден", "#F44336"));
                return;
            }
            log("su: " + suPath);
            log("Requesting root...");
            
            // Start persistent root shell - THIS triggers Magisk prompt
            if (!startRootShell()) {
                log("ERROR: Root denied");
                mainHandler.post(() -> setStatus("ROOT отклонён", "#F44336"));
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

    private String findSu() {
        for (String path : SU_PATHS) {
            if (new File(path).exists()) return path;
        }
        return null;
    }

    /**
     * Start persistent su shell - writes commands to stdin
     * This is the CORRECT way to request root on Android
     */
    private boolean startRootShell() {
        try {
            // Start just "su" - no -c flag to get interactive shell
            ProcessBuilder pb = new ProcessBuilder(suPath);
            pb.redirectErrorStream(true);
            suProcess = pb.start();
            
            suWriter = new OutputStreamWriter(suProcess.getOutputStream());
            suReader = new BufferedReader(new InputStreamReader(suProcess.getInputStream()));
            
            // Write "id" command to trigger Magisk and check root
            suWriter.write("id\n");
            suWriter.flush();
            
            // Read output
            StringBuilder sb = new StringBuilder();
            String line;
            
            // Wait for output (with timeout)
            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < 5000) {
                if (suReader.ready()) {
                    line = suReader.readLine();
                    if (line != null) sb.append(line).append("\n");
                }
                if (!suProcess.isAlive()) break;
                Thread.sleep(50);
            }
            
            String output = sb.toString();
            log("Root check: " + output.trim());
            
            // Check if we got root (uid=0)
            if (output.contains("uid=0")) {
                log("Got root access!");
                return true;
            }
            
            // Check if process exited (denied)
            if (!suProcess.isAlive()) {
                log("Root process exited");
                return false;
            }
            
            return true; // Process is alive, assume got root
            
        } catch (Exception e) {
            log("Root error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Run command in persistent su shell
     */
    private String runRoot(String cmd) {
        if (suProcess == null || !suProcess.isAlive()) {
            log("No su process, restarting...");
            if (!startRootShell()) {
                return "";
            }
        }
        
        try {
            // Write command
            suWriter.write(cmd + "\n");
            suWriter.flush();
            
            // Read output
            StringBuilder sb = new StringBuilder();
            String line;
            
            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < 5000) {
                if (suReader.ready()) {
                    line = suReader.readLine();
                    if (line != null) sb.append(line).append("\n");
                }
                if (!suProcess.isAlive()) break;
                Thread.sleep(50);
            }
            
            return sb.toString().trim();
            
        } catch (Exception e) {
            log("Run error: " + e.getMessage());
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
                    detectedPackagePath = pi.applicationInfo.dataDir;
                    log("Found: " + pkg);
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
                
                backupDir = "/data/data/" + detectedPackageName + "/files/hide_" + System.currentTimeMillis();
                runRoot("mkdir -p " + backupDir);
                runRoot("cp '" + dllPath + "' '" + backupDir + "/orig.dll'");
                log("Backup done");
                
                runRoot("am force-stop " + detectedPackageName);
                Thread.sleep(500);
                
                runRoot("chmod 644 '" + dllPath + "'");
                runRoot("sed -i 's/UserPlayable = true/UserPlayable = false/g' '" + dllPath + "' 2>/dev/null || true");
                log("Patch done");
                
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
        
        String[] paths = {
            detectedPackagePath + "/files",
            detectedPackagePath + "/files/shared_assemblies"
        };
        
        for (String p : paths) {
            String result = runRoot("find '" + p + "' -name 'Assembly-CSharp.dll' 2>/dev/null | head -1").trim();
            if (!result.isEmpty()) return result;
        }
        return null;
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (suProcess != null && suProcess.isAlive()) {
                suWriter.write("exit\n");
                suWriter.flush();
                suProcess.destroy();
            }
        } catch (Exception ignored) {}
    }
}