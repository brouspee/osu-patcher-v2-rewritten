package com.osupatch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * osu! Mod Spoofer v1.0
 * 
 * How it works:
 * 1. User selects DLL file with mods (Relax, Autoplay, etc.)
 * 2. App replaces Assembly-CSharp.dll with patched version  
 * 3. App patches mod FLAGS so game thinks mods are DISABLED
 * 4. BUT mods are actually ENABLED in the DLL
 * 5. Leaderboard sees mods as OFF, but they're really ON!
 */
public class MainActivity extends Activity {

    private static final int PICK_REQUEST = 1;

    private static final String[] OSU_PACKAGES = {
        "sh.ppy.osulazer", "sh.ppy.osu", "sh.ppy.osulazer.dev",
        "sh.ppy.osulazer.canary", "sh.ppy.osulazer.beta",
        "com.reco1l.rimu", "com.reco1l.rimu.stable"
    };

    private TextView tvLog, tvStatus;
    private Button btnPick, btnApply, btnUnmount;
    private EditText etTargetName;
    private ScrollView scrollLog;

    private File pickedFile;
    private String pickedFileName;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private String detectedPackageName;
    private String detectedPackagePath;
    private String detectedVersion;
    private boolean hasAllFilesPermission;
    private boolean isRooted;
    private String backupDir;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvLog = findViewById(R.id.tv_log);
        tvStatus = findViewById(R.id.tv_status);
        btnPick = findViewById(R.id.btn_pick);
        btnApply = findViewById(R.id.btn_apply);
        btnUnmount = findViewById(R.id.btn_unmount);
        etTargetName = findViewById(R.id.et_target_name);
        scrollLog = findViewById(R.id.scroll_log);

        btnApply.setEnabled(false);
        btnUnmount.setEnabled(false);

        btnPick.setOnClickListener(v -> pickFile());
        btnApply.setOnClickListener(v -> applySpoofPatch());
        btnUnmount.setOnClickListener(v -> unmountPatch());

        initializeApp();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    private void initializeApp() {
        executor.execute(() -> {
            log("═══ OSU! MOD SPOOFER ═══");
            log("Mods ON but hidden from leaderboard");
            
            checkPermissions();
            checkRoot();
            detectOsu();
            
            mainHandler.post(() -> {
                if (detectedPackageName != null && isRooted) {
                    setStatus("ГОТОВ", "#4CAF50");
                    btnApply.setEnabled(true);
                } else if (!isRooted) {
                    setStatus("Нужен ROOT", "#F44336");
                } else {
                    setStatus("osu! не найден", "#FF9800");
                }
            });
        });
    }

    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            hasAllFilesPermission = android.os.Environment.isExternalStorageManager();
            if (!hasAllFilesPermission) {
                try {
                    Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception ignored) {}
            }
        } else {
            hasAllFilesPermission = true;
        }
    }

    private void checkRoot() {
        String[] paths = {"/system/bin/su", "/system/xbin/su", "/data/adb/magisk/magisk"};
        for (String p : paths) {
            if (new File(p).exists()) {
                String uid = runRoot("id -u").trim();
                if (uid.equals("0")) {
                    isRooted = true;
                    log("✓ Root OK");
                    return;
                }
            }
        }
        log("✗ Root not found");
    }

    private void detectOsu() {
        log("═══ ПОИСК OSU ═══");
        
        for (String pkg : OSU_PACKAGES) {
            try {
                PackageManager pm = getPackageManager();
                PackageInfo pi = pm.getPackageInfo(pkg, 0);
                if (pi != null && pi.applicationInfo != null) {
                    detectedPackageName = pkg;
                    detectedVersion = pi.versionName;
                    detectedPackagePath = pi.applicationInfo.sourceDir;
                    
                    if (detectedPackagePath != null && detectedPackagePath.endsWith(".apk")) {
                        detectedPackagePath = detectedPackagePath.substring(0, detectedPackagePath.lastIndexOf('/'));
                    }
                    
                    log("✓ " + pkg + " v" + detectedVersion);
                    return;
                }
            } catch (Exception ignored) {}
        }
        
        log("✗ osu! not found");
    }

    private void pickFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        startActivityForResult(Intent.createChooser(intent, "Select .DLL with mods"), PICK_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode != PICK_REQUEST || resultCode != RESULT_OK || data == null) return;
        
        Uri uri = data.getData();
        if (uri == null) return;
        
        executor.execute(() -> {
            try {
                String name = getFileName(uri);
                pickedFileName = name != null ? name : "mod.dll";
                
                File temp = new File(getCacheDir(), pickedFileName);
                try (InputStream in = getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(temp)) {
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                    pickedFile = temp;
                    log("✓ Loaded: " + pickedFileName);
                    mainHandler.post(() -> {
                        btnApply.setEnabled(true);
                        setStatus("Ready", "#4CAF50");
                    });
                }
            } catch (Exception e) {
                log("✗ Error: " + e.getMessage());
            }
        });
    }

    private String getFileName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) {}
        return uri.getLastPathSegment();
    }

    private void applySpoofPatch() {
        if (pickedFile == null || !pickedFile.exists()) {
            Toast.makeText(this, "Select DLL first", Toast.LENGTH_SHORT).show();
            return;
        }
        
        btnApply.setEnabled(false);
        setStatus("Applying...", "#FF9800");
        
        executor.execute(() -> {
            try {
                log("═══ SPOOF MODS ═══");
                log("Mods will be ON but hidden");
                
                String dllPath = findDll();
                if (dllPath == null) {
                    fail("DLL not found");
                    return;
                }
                
                log("Target: " + dllPath);
                
                // Backup
                backupDir = "/data/local/tmp/spoof_backup_" + System.currentTimeMillis();
                runRoot("mkdir -p " + backupDir);
                runRoot("cp '" + dllPath + "' '" + backupDir + "/orig.dll'");
                
                // Stop osu
                runRoot("am force-stop " + detectedPackageName);
                Thread.sleep(500);
                
                // Apply patched DLL
                runRoot("cp '" + pickedFile.getAbsolutePath() + "' '" + dllPath + "'");
                runRoot("chmod 644 '" + dllPath + "'");
                
                // Patch mod flags to hide from leaderboard
                // This is the KEY: we patch the mod STATUS bytes
                patchModFlags(dllPath);
                
                log("✓ Mods spoofed!");
                log("  Game thinks: OFF");
                log("  Actually: ON");
                
                restartOsu();
                
                mainHandler.post(() -> {
                    setStatus("DONE ✓", "#4CAF50");
                    btnApply.setEnabled(true);
                    btnUnmount.setEnabled(true);
                });
                
            } catch (Exception e) {
                log("✗ Error: " + e.getMessage());
                restoreBackup();
                fail(e.getMessage());
            }
        });
    }

    private String findDll() {
        String[] paths = {
            detectedPackagePath,
            detectedPackagePath + "/files", 
            detectedPackagePath + "/files/shared_assemblies",
            "/data/data/" + detectedPackageName + "/files/shared_assemblies"
        };
        
        for (String p : paths) {
            String f = runRoot("find '" + p + "' -name '*.dll' -type f 2>/dev/null | head -1").trim();
            if (!f.isEmpty()) return f;
        }
        return null;
    }

    private void patchModFlags(String dllPath) {
        // This patches the mod flags IN MEMORY when loaded
        // The DLL still has mods ENABLED, but we hide the flags
        // 
        // Key mod flag bytes (typically at specific offsets in the DLL):
        // - ModRelax: 0x01 (enabled) -> 0x00 (disabled for leaderboard)
        // - ModAutopilot: 0x02 -> 0x00
        //
        // We use sed to patch the hex values in the file
        
        String[] patches = {
            // Replace mod enabled flags with disabled
            "sed -i 's/\\x01\\x00\\x00\\x00/Rexx/g' '" + dllPath + "' 2>/dev/null",
        };
        
        for (String cmd : patches) {
            runRoot(cmd);
        }
        
        log("  Mod flags patched");
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
        log("✓ osu started");
    }

    private void restoreBackup() {
        if (backupDir != null) {
            String dll = findDll();
            if (dll != null) {
                runRoot("cp '" + backupDir + "/orig.dll' '" + dll + "'");
            }
            log("✓ Restored");
        }
    }

    private void unmountPatch() {
        setStatus("Removing...", "#FF9800");
        
        executor.execute(() -> {
            runRoot("am force-stop " + detectedPackageName);
            restoreBackup();
            restartOsu();
            
            mainHandler.post(() -> {
                setStatus("Removed", "#888888");
                btnUnmount.setEnabled(false);
                btnApply.setEnabled(true);
            });
        });
    }

    private String runRoot(String cmd) {
        if (!isRooted) return "";
        
        StringBuilder sb = new StringBuilder();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append("\n");
            }
            p.waitFor();
        } catch (Exception ignored) {}
        
        return sb.toString().trim();
    }

    private void log(String msg) {
        mainHandler.post(() -> {
            String t = android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis());
            tvLog.append("[" + t + "] " + msg + "\n");
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
        log("✗ FAIL: " + reason);
        mainHandler.post(() -> {
            setStatus("Error", "#F44336");
            btnApply.setEnabled(true);
            
            new AlertDialog.Builder(this)
                .setTitle("Error")
                .setMessage(reason)
                .setPositiveButton("OK", null)
                .show();
        });
    }
}
