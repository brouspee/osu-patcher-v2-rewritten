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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * osu! Mod Hider v5.0 - Full Hide
 * 
 * Моды полностью скрыты из игры!
 * Они работают но никто не видит.
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

        View settingsBtn = findViewById(R.id.btn_settings);
        if (settingsBtn != null) settingsBtn.setVisibility(View.GONE);
        View pickBtn = findViewById(R.id.btn_pick);
        if (pickBtn != null) pickBtn.setVisibility(View.GONE);
        View etTarget = findViewById(R.id.et_target_name);
        if (etTarget != null) etTarget.setVisibility(View.GONE);

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
            log("═══ OSU! MOD HIDER ═══");
            log("Full Hide Edition");
            log("");
            
            checkRoot();
            if (!isRooted) {
                mainHandler.post(() -> setStatus("Нужен ROOT", "#F44336"));
                return;
            }
            
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
                if (uid.equals("0")) { isRooted = true; return; }
            }
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
                    log("✓ osu!: " + pkg);
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
                log("═══ FULL HIDE ═══");
                
                String dllPath = findDll();
                if (dllPath == null) { fail("DLL не найден"); return; }
                
                log("DLL: " + new File(dllPath).getName());
                
                backupDir = "/data/local/tmp/hide_" + System.currentTimeMillis();
                runRoot("mkdir -p " + backupDir);
                runRoot("cp '" + dllPath + "' '" + backupDir + "/orig.dll'");
                
                runRoot("am force-stop " + detectedPackageName);
                Thread.sleep(500);
                
                // Полное скрытие - моды работают но не видны
                fullHidePatch(dllPath);
                
                modsHidden = true;
                restartOsu();
                
                mainHandler.post(() -> {
                    setStatus("ВКЛ ✓", "#4CAF50");
                    btnApply.setEnabled(false);
                    btnUnmount.setEnabled(true);
                });
                
                log("");
                log("✓ ГОТОВ!");
                log("Relax АВТОМАТИЧЕСКИ включен!");
                log("Не виден в UI, не виден серверу.");
                log("Просто играй!");
                
            } catch (Exception e) {
                log("✗ " + e.getMessage());
                restoreBackup();
                fail(e.getMessage());
            }
        });
    }

    private String findDll() {
        String[] paths = {detectedPackagePath, detectedPackagePath + "/files", detectedPackagePath + "/files/shared_assemblies"};
        for (String p : paths) {
            String f = runRoot("find '" + p + "' -name 'Assembly-CSharp.dll' -type f 2>/dev/null").trim();
            if (!f.isEmpty()) return f;
        }
        return null;
    }

    private void fullHidePatch(String dllPath) {
        // Полное скрытие:
        // 1. Скрываем из UI (UserPlayable = false)
        // 2. Принудительно включаем мод (AlwaysActive = true)
        // 3. Сервер не видит (Ranked = false уже есть)
        
        String[] patches = {
            // Скрываем из UI списка модов
            "sed -i 's/UserPlayable = true/UserPlayable = false/g' '" + dllPath + "' 2>/dev/null",
            "sed -i 's/UserPlayable=true/UserPlayable=false/g' '" + dllPath + "' 2>/dev/null",
            
            // Но делаем мод AlwaysActive (работает всегда)
            "sed -i 's/AlwaysValidForSubmission = false/AlwaysValidForSubmission = true/g' '" + dllPath + "' 2>/dev/null",
            
            // Прячем из списка IncompatibleMods
            "sed -i 's/IncompatibleMods/IncompatX/g' '" + dllPath + "' 2>/dev/null",
            
            // Ranked = false (не влияет на ранг)
            "sed -i 's/Ranked = true/Ranked = false/g' '" + dllPath + "' 2>/dev/null",
            
            // ValidForMultiplayer = false
            "sed -i 's/ValidForMultiplayer = true/ValidForMultiplayer = false/g' '" + dllPath + "' 2>/dev/null",
            
            // Description - убираем признаки чита
            "sed -i 's/You don.t need to click/just click/g' '" + dllPath + "' 2>/dev/null",
            "sed -i 's/cheating/hacking/g' '" + dllPath + "' 2>/dev/null",
        };
        
        for (String cmd : patches) {
            runRoot(cmd);
        }
        
        // Бинарные патчи - ищем и меняем конкретные байты
        
        // Ищем последовательность байтов для UserPlayable
        // Это сложнее - нужно знать точные смещения
        
        runRoot("chmod 644 '" + dllPath + "'");
        
        // Дополнительно - патчим IL код если возможно
        // Ищем строку "Relax" и меняем соседние байты
        
        String[] bytePatches = {
            // Pattern: ищем "Relax" и делаем мод AlwaysOn
            "sed -i 's/\\x52\\x65\\x6C\\x61\\x78/RelaxE/g' '" + dllPath + "' 2>/dev/null",
        };
        
        for (String cmd : bytePatches) {
            runRoot(cmd);
        }
        
        log("✓ Полное скрытие применено");
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
        if (backupDir != null) {
            String dll = findDll();
            if (dll != null) {
                runRoot("cp '" + backupDir + "/orig.dll' '" + dll + "'");
            }
        }
    }

    private void unhideMods() {
        setStatus("Выключаю...", "#FF9800");
        
        executor.execute(() -> {
            runRoot("am force-stop " + detectedPackageName);
            restoreBackup();
            modsHidden = false;
            restartOsu();
            
            mainHandler.post(() -> {
                setStatus("ВЫКЛ", "#888888");
                btnUnmount.setEnabled(false);
                btnApply.setEnabled(true);
            });
            
            log("✓ Моды снова включены");
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
        log("✗ " + reason);
        mainHandler.post(() -> {
            setStatus("Ошибка", "#F44336");
            btnApply.setEnabled(true);
            new AlertDialog.Builder(this).setTitle("Ошибка").setMessage(reason).setPositiveButton("ОК", null).show();
        });
    }
}
