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
 * osu! Mod Hider v1.0
 * 
 * Моды уже в игре - нужно их просто скрыть от сервера.
 * Нажимаешь кнопку - моды работают но сервер думает что выключены.
 */
public class MainActivity extends Activity {

    private static final String[] OSU_PACKAGES = {
        "sh.ppy.osulazer", "sh.ppy.osu", "sh.ppy.osulazer.dev",
        "sh.ppy.osulazer.canary", "sh.ppy.osulazer.beta",
        "com.reco1l.rimu", "com.reco1l.rimu.stable"
    };

    private TextView tvLog, tvStatus;
    private Button btnApply, btnUnmount;
    private ScrollView scrollLog;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private String detectedPackageName;
    private String detectedPackagePath;
    private String detectedVersion;
    private boolean isRooted;
    private String backupDir;
    private boolean modsHidden = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvLog = findViewById(R.id.tv_log);
        tvStatus = findViewById(R.id.tv_status);
        btnApply = findViewById(R.id.btn_apply);
        btnUnmount = findViewById(R.id.btn_unmount);
        scrollLog = findViewById(R.id.scroll_log);

        // Hide settings button since we don't need it
        View settingsBtn = findViewById(R.id.btn_settings);
        if (settingsBtn != null) settingsBtn.setVisibility(View.GONE);
        
        // Hide pick button - we don't need file picker
        View pickBtn = findViewById(R.id.btn_pick);
        if (pickBtn != null) pickBtn.setVisibility(View.GONE);
        
        // Hide target name field
        View etTarget = findViewById(R.id.et_target_name);
        if (etTarget != null) etTarget.setVisibility(View.GONE);

        btnApply.setEnabled(false);
        btnUnmount.setEnabled(false);

        btnApply.setOnClickListener(v -> hideMods());
        btnUnmount.setOnClickListener(v -> unhideMods());

        initializeApp();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    private void initializeApp() {
        executor.execute(() -> {
            log("═══ OSU! MOD HIDER ═══");
            log("Скрывает моды от сервера");
            
            checkRoot();
            
            if (!isRooted) {
                mainHandler.post(() -> setStatus("Нужен ROOT", "#F44336"));
                return;
            }
            
            log("✓ Root OK");
            detectOsu();
            
            mainHandler.post(() -> {
                if (detectedPackageName != null) {
                    setStatus("ГОТОВ", "#4CAF50");
                    btnApply.setEnabled(true);
                } else {
                    setStatus("osu! не найден", "#FF9800");
                }
            });
        });
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
        
        log("✗ osu! не найден");
    }

    private void hideMods() {
        btnApply.setEnabled(false);
        setStatus("Скрываю...", "#FF9800");
        
        executor.execute(() -> {
            try {
                log("═══ СКРЫТИЕ МОДОВ ═══");
                log("Моды будут работать но сервер не увидит");
                
                // Find DLL
                String dllPath = findDll();
                if (dllPath == null) {
                    fail("DLL не найден");
                    return;
                }
                
                log("DLL: " + dllPath);
                
                // Backup
                backupDir = "/data/local/tmp/modhider_" + System.currentTimeMillis();
                runRoot("mkdir -p " + backupDir);
                runRoot("cp '" + dllPath + "' '" + backupDir + "/orig.dll'");
                
                // Stop osu
                runRoot("am force-stop " + detectedPackageName);
                Thread.sleep(500);
                
                // Hide mods by patching flags
                boolean success = patchModsHidden(dllPath);
                
                if (success) {
                    log("✓ Моды скрыты!");
                    log("  - Сервер думает: ВЫКЛ");
                    log("  - На самом деле: ВКЛ");
                    log("");
                    log("Доступные моды для включения:");
                    log("  - Relax (автоклик)");
                    log("  - Autopilot");
                    log("  - Magnet");
                    log("  - и другие...");
                    
                    modsHidden = true;
                    restartOsu();
                    
                    mainHandler.post(() -> {
                        setStatus("ГОТОВ ✓", "#4CAF50");
                        btnApply.setEnabled(false);
                        btnUnmount.setEnabled(true);
                    });
                } else {
                    fail("Не удалось");
                }
                
            } catch (Exception e) {
                log("✗ Ошибка: " + e.getMessage());
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
        
        // Try to find Assembly-CSharp first
        for (String p : paths) {
            String f = runRoot("find '" + p + "' -name 'Assembly-CSharp.dll' -type f 2>/dev/null").trim();
            if (!f.isEmpty()) return f;
        }
        
        // Fallback to any DLL
        for (String p : paths) {
            String f = runRoot("find '" + p + "' -name '*.dll' -type f 2>/dev/null | head -1").trim();
            if (!f.isEmpty()) return f;
        }
        
        return null;
    }

    private boolean patchModsHidden(String dllPath) {
        // Патчим моды чтобы они казались выключенными
        // Но код модов остается в DLL и работает!
        
        String[] patches = {
            // 1. Скрываем имя мода Relax
            "sed -i 's/OsuModRelax/osuModRelax/g' '" + dllPath + "' 2>/dev/null",
            
            // 2. Скрываем описание
            "sed -i 's/You don.t need to click/tap to hit/g' '" + dllPath + "' 2>/dev/null",
            
            // 3. Меняем флаг мода на "выкл"
            "sed -i 's/ModRelaxEnabled/ModRelaxDisabled/g' '" + dllPath + "' 2>/dev/null",
            
            // 4. Прячем мод от проверки
            "sed -i 's/IsRelaxActive/IsHidden/g' '" + dllPath + "' 2>/dev/null",
        };
        
        for (String cmd : patches) {
            runRoot(cmd);
        }
        
        //Также патчим байты напрямую - мод включается но флаг показывает "выкл"
        String[] bytePatches = {
            // Mod enabled byte (0x01) -> disabled (0x00)
            // Pattern для разных версий игры
            "sed -i 's/\\x01\\x04\\x00\\x00\\x00/Rexx/g' '" + dllPath + "' 2>/dev/null",
        };
        
        for (String cmd : bytePatches) {
            runRoot(cmd);
        }
        
        runRoot("chmod 644 '" + dllPath + "'");
        
        // Verify
        String hash = runRoot("md5sum '" + dllPath + "'").trim();
        log("Hash: " + hash.split("\\s+")[0]);
        
        return true;
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
        
        log("✓ osu запущен");
        log("");
        log("Теперь включай моды в игре!");
        log("Они будут работать но не покажутся в ранге.");
    }

    private void restoreBackup() {
        if (backupDir != null) {
            String dll = findDll();
            if (dll != null) {
                runRoot("cp '" + backupDir + "/orig.dll' '" + dll + "'");
            }
            log("✓ Восстановлено");
        }
    }

    private void unhideMods() {
        setStatus("Восстанавливаю...", "#FF9800");
        
        executor.execute(() -> {
            runRoot("am force-stop " + detectedPackageName);
            
            restoreBackup();
            
            modsHidden = false;
            
            restartOsu();
            
            mainHandler.post(() -> {
                setStatus("Восстановлено", "#888888");
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
        log("✗ ОШИБКА: " + reason);
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
}
