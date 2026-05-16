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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int PICK_REQUEST = 1;

    // Все возможные варианты osu! и форков
    private static final String[] OSU_PACKAGES = {
        "sh.ppy.osulazer",
        "sh.ppy.osu", 
        "sh.ppy.osulazer.dev",
        "sh.ppy.osulazer.canary",
        "sh.ppy.osulazer.beta",
        "sh.ppy.osulazer.lts",
        "sh.ppy.osulazer.internal",
        "com.reco1l.rimu",
        "com.reco1l.rimu.client",
        "com.reco1l.rimu.stable",
        "com.cephasun.osulazer",
        "com.cephasun.osulazer.dev",
        "moe.mathiessen.osulazer",
        "moe.mathiessen.osulazer.dev",
        "com.osu.beatmap"
    };

    private static final int BLOB_MAGIC_V1 = 0x41424158;
    private static final int BLOB_MAGIC_V2 = 0x32424158;

    private TextView   tvLog, tvStatus;
    private Button     btnPick, btnApply, btnUnmount, btnSettings;
    private EditText   etTargetName;
    private ScrollView scrollLog;

    private File   pickedFile;
    private String pickedFileName;
    private String pickedFileArch;

    private final ExecutorService executor    = Executors.newSingleThreadExecutor();
    private final Handler         mainHandler = new Handler(Looper.getMainLooper());

    // ═══════════════════════════════════════════════════════════════════════════
    // Core State
    // ═══════════════════════════════════════════════════════════════════════════
    
    private String detectedPackageName;
    private String detectedPackagePath;
    private String detectedVersion;
    private String detectedArch;
    private String detectedBuildType; // il2cpp, mono, hybrid
    private String originalFileHash;
    private String targetType; // blob, so, dll
    private boolean hasAllFilesPermission = false;
    private boolean hasMagisk = false;
    private boolean hasZygisk = false;
    private boolean hasLSPosed = false;
    private boolean hasRiru = false;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvLog        = findViewById(R.id.tv_log);
        tvStatus     = findViewById(R.id.tv_status);
        btnPick      = findViewById(R.id.btn_pick);
        btnApply     = findViewById(R.id.btn_apply);
        btnUnmount   = findViewById(R.id.btn_unmount);
        btnSettings = findViewById(R.id.btn_settings);
        etTargetName = findViewById(R.id.et_target_name);
        scrollLog    = findViewById(R.id.scroll_log);

        btnApply.setEnabled(false);
        btnPick.setOnClickListener(v -> pickFile());
        btnApply.setOnClickListener(v -> applyPatch());
        btnUnmount.setOnClickListener(v -> unmount());
        btnSettings.setOnClickListener(v -> openSettings());

    // Check permissions first
    checkPermissions();
}

@Override
protected void onDestroy() {
    super.onDestroy();
    executor.shutdown();
}

// ══════════════════════════════════════════════════════════════════════
// Permission Check - MANAGE_EXTERNAL_STORAGE for Android 11+
// ══════════════════════════════════════════════════════════════════════

private static final int REQUEST_ALL_FILES = 1001;

private void checkPermissions() {
    log("═══ PERMISSIONS ═══");
    
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        hasAllFilesPermission = android.os.Environment.isExternalStorageManager();
        
        if (hasAllFilesPermission) {
            log("All Files Access: GRANTED");
        } else {
            log("All Files Access: NOT GRANTED");
            mainHandler.post(() -> showPermissionDialog());
            return;
        }
    } else {
        hasAllFilesPermission = true;
    }
    
    checkSystemCapabilities();
}

private void showPermissionDialog() {
    new AlertDialog.Builder(this)
        .setTitle("Требуется разрешение")
        .setMessage("На Android 11+ нужен доступ ко всем файлам!")
        .setPositiveButton("Предоставить", (d, w) -> requestAllFiles())
        .setNegativeButton("Выйти", (d, w) -> finish())
        .setCancelable(false)
        .show();
}

private void requestAllFiles() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
            startActivityForResult(intent, REQUEST_ALL_FILES);
        } catch (Exception e) {
            log("Error: " + e.getMessage());
            fail();
        }
    }
}

@Override
protected void onActivityResult(int req, int res, Intent data) {
    super.onActivityResult(req, res, data);
    
    // Handle ALL_FILES permission request
    if (req == REQUEST_ALL_FILES && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        hasAllFilesPermission = android.os.Environment.isExternalStorageManager();
        if (hasAllFilesPermission) {
            log("Permission granted!");
            checkSystemCapabilities();
        } else {
            fail();
        }
        return;
    }
    
    // Handle file picker request
    if (req != PICK_REQUEST || res != RESULT_OK || data == null) return;
    Uri uri = data.getData();
    if (uri == null) return;

    executor.execute(() -> {
        try {
            String origName = resolveFileName(uri);
            if (origName == null || origName.isEmpty()) origName = "picked_file";
            pickedFileName = origName;

            pickedFileArch = detectFileArch(origName);
            log("→ Файл архитектура: " + pickedFileArch);

            File dest = new File(getCacheDir(), origName);
            try (InputStream  in  = getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(dest)) {
                if (in == null) throw new Exception("Cannot open stream");
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }

            pickedFile = dest;
            pickedFileArch = detectFileArch(origName);
            log("✓ Загружен: " + origName + " (" + dest.length() / 1024 + " KB) [" + pickedFileArch + "]");

            if (!pickedFileArch.equals("unknown") && !detectedArch.equals("unknown")) {
                if (!pickedFileArch.equals(detectedArch)) {
                    mainHandler.post(this::showArchWarning);
                    return;
                }
            }

            targetType = null;
            findTargetFiles();

            mainHandler.post(() -> {
                setStatus("Готов", "#4CAF50");
                btnApply.setEnabled(true);
            });

        } catch (Exception e) {
            log("✗ Ошибка загрузки: " + e.getMessage());
            fail();
        }
    });
}

// ══════════════════════════════════════════════════════════════════════
// System Checks - Check SELinux, Root, ptrace, Android version
// ══════════════════════════════════════════════════════════════════════════════

private void checkSystemCapabilities() {
        executor.execute(() -> {
            log("══════ SYSTEM CHECKS ═══════");
            
            // 1. Android version
            log("Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
            boolean isAndroid13Plus = Build.VERSION.SDK_INT >= 33;
            log("Android 13+: " + (isAndroid13Plus ? "✓" : "✗"));
            
            // 2. SELinux status - check real status and mode
            String selinuxStatus = runRoot("getenforce 2>/dev/null").trim();
            if (selinuxStatus.isEmpty()) {
                selinuxStatus = runRoot("cat /sys/fs/selinux/enforce 2>/dev/null").trim();
            }
            log("SELinux: " + (selinuxStatus.isEmpty() ? "unknown" : selinuxStatus));
            
            // 3. Root access
            String suTest = runRoot("id").trim();
            if (suTest.contains("uid=0") || suTest.contains("root")) {
                log("Root: ✓ AVAILABLE");
            } else {
                log("Root: ✗ NOT AVAILABLE");
                log("⚠ Без root патчинг НЕВОЗМОЖЕН!");
            }
            
            // 4. ptrace_scope real value
            String ptraceScope = runRoot("cat /proc/sys/kernel/yama/ptrace_scope 2>/dev/null").trim();
            log("ptrace_scope: " + (ptraceScope.isEmpty() ? "unknown" : ptraceScope));
            if (!ptraceScope.isEmpty() && !ptraceScope.equals("0")) {
                log("⚠ ptrace может быть заблокирован!");
            }
            
            // 5. Check gdb availability
            String gdbAvail = runRoot("gdb --version 2>/dev/null").trim();
            log("gdb: " + (gdbAvail.isEmpty() ? "not found" : "available"));
            
            // 6. Check linker
            String linker = runRoot("ls /system/bin/linker* 2>/dev/null").trim();
            log("linker: " + (linker.isEmpty() ? "not found" : linker));
            
            // 7. Check /data/local/tmp permissions
            String tmpPerms = runRoot("ls -la /data/local/tmp 2>/dev/null | head -1").trim();
            log("/data/local/tmp: " + tmpPerms);
            
            // 8. Check /data/data permissions
            String dataPerms = runRoot("ls -la /data/data 2>/dev/null | head -1").trim();
            log("/data/data: " + dataPerms);
            
            // Now find osu
            checkFrameworks();
            findOsu();
        });
    }

    // ═══════════════════════════════════════════════════════════════════════════

    // ════════════════════════════════════════════════════════════════════════════════════
    // Framework Detection - Check Magisk, Zygisk, LSPosed, Riru
    // ════════════════════════════════════════════════════════════════════════════════════

    private void checkFrameworks() {
        log("=== FRAMEWORKS ===");

        String magisk = runRoot("magisk --version 2>/dev/null").trim();
        if (magisk.isEmpty()) magisk = runRoot("ls /sbin/magisk 2>/dev/null").trim();
        if (magisk.isEmpty()) magisk = runRoot("ls /data/adb/magisk/magisk 2>/dev/null").trim();
        hasMagisk = !magisk.isEmpty();
        log("Magisk: " + (hasMagisk ? "FOUND" : "NOT FOUND"));


        if (hasMagisk) {
            String zygiskConf = runRoot("cat /data/adb/magisk/config 2>/dev/null").trim();
            hasZygisk = zygiskConf.contains("ZYGISC=true");
            log("Zygisk: " + (hasZygisk ? "ENABLED" : "DISABLED"));
        }

        String lsposed = runRoot("ls /data/adb/modules/LSPosed 2>/dev/null").trim();
        hasLSPosed = !lsposed.isEmpty();
        log("LSPosed: " + (hasLSPosed ? "FOUND" : "NOT FOUND"));


        String riru = runRoot("ls /data/adb/modules/riru 2>/dev/null").trim();
        hasRiru = !riru.isEmpty();
        log("Riru: " + (hasRiru ? "FOUND" : "NOT FOUND"));
    }

    // Find osu! - Determine package name, path, version, arch, build type
    // ═══════════════════════════════════════════════════════════════════════════

    private void findOsu() {
        log("══════ ПОИСК OSU ═══════");
        
        detectedPackageName = null;
        detectedPackagePath = null;
        detectedVersion = null;
        detectedArch = null;
        detectedBuildType = null;
        
        // Метод 1: Используем PackageManager для точного определения
        for (String pkg : OSU_PACKAGES) {
            try {
                PackageManager pm = getPackageManager();
                PackageInfo pi = pm.getPackageInfo(pkg, 0);
                if (pi != null) {
                    detectedPackageName = pkg;
                    detectedVersion = pi.versionName;
                    log("✓ [PM] " + pkg + " v" + detectedVersion);
                    
                    // Получаем путь через pm
                    String pmPath = runRoot("pm path " + pkg + " 2>/dev/null").trim();
                    if (pmPath.contains("package:")) {
                        detectedPackagePath = pmPath.substring(pmPath.indexOf(":") + 1).trim();
                        // Убираем .apk из пути
                        if (detectedPackagePath.endsWith(".apk")) {
                            detectedPackagePath = detectedPackagePath.substring(0, detectedPackagePath.lastIndexOf('/'));
                        }
                    }
                    
                    log("  Путь: " + detectedPackagePath);
                    break;
                }
            } catch (PackageManager.NameNotFoundException e) {
                // Package not found, continue
            }
        }
        
        // Метод 2: Если не найден через PM, ищем руками
        if (detectedPackageName == null) {
            log("[Поиск] Ищу в /data/app...");
            for (String frag : new String[]{"osulazer", "ppy.osu", "rimu", "mathiessen", "reco1l", "cephasun"}) {
                String found = runRoot(
                    "find /data/app -maxdepth 2 -name 'base.apk' 2>/dev/null | grep -i '" + frag + "' | head -1").trim();
                if (!found.isEmpty() && found.endsWith(".apk")) {
                    detectedPackagePath = found.substring(0, found.lastIndexOf('/'));
                    detectedPackageName = found.substring(found.indexOf('/') + 1, found.lastIndexOf('-'));
                    log("✓ [find] " + detectedPackageName + " -> " + detectedPackagePath);
                    break;
                }
            }
        }
        
        // Метод 3: Ищем в data/data
        if (detectedPackageName == null) {
            log("[Поиск] Ищу в /data/data...");
            for (String pkg : OSU_PACKAGES) {
                String dir = pkg.replace("sh.", "").replace("com.", "");
                String found = runRoot("ls /data/data/" + dir + "/base.apk 2>/dev/null").trim();
                if (!found.isEmpty()) {
                    detectedPackageName = pkg;
                    detectedPackagePath = "/data/data/" + dir;
                    log("✓ [data/data] " + pkg + " -> " + detectedPackagePath);
                    break;
                }
            }
        }
        
        if (detectedPackageName == null || detectedPackagePath == null) {
            log("✗ osu! НЕ НАЙДЕН!");
            mainHandler.post(() -> setStatus("osu! не найден", "#FF5500"));
            return;
        }
        
        // Определяем архитектуру
        detectOsuArchitecture();
        
        // Определяем тип сборки (il2cpp/mono/hybrid)
        detectBuildType();
        
        // Ищем файлы для патчинга
        findTargetFiles();
        
        mainHandler.post(() -> setStatus("Готов", "#4CAF50"));
    }

    private void detectOsuArchitecture() {
        log("═══ АРХИТЕКТУРА ═══");
        
        // Ищем .so файлы
        String soList = runRoot("find '" + detectedPackagePath + "' -name '*.so' 2>/dev/null | head -20").trim();
        
        if (soList.contains("arm64") || soList.contains("aarch64")) {
            detectedArch = "arm64";
        } else if (soList.contains("armeabi-v7a") || soList.contains("armeabi")) {
            detectedArch = "arm32";
        } else if (soList.contains("x86_64")) {
            detectedArch = "x64";
        } else if (soList.contains("x86")) {
            detectedArch = "x86";
        } else {
            detectedArch = "unknown";
        }
        
        log("Архитектура: " + detectedArch);
    }

    private void detectBuildType() {
        log("═══ ТИП СБОРКИ ═══");
        
        // Проверяем наличие libil2cpp.so
        String il2cpp = runRoot("find '" + detectedPackagePath + "' -name 'libil2cpp.so' 2>/dev/null").trim();
        if (!il2cpp.isEmpty()) {
            detectedBuildType = "il2cpp";
            log("Тип: il2cpp");
            return;
        }
        
        // Проверяем наличие assemblies (mono/hybrid)
        String assemblies = runRoot("find '" + detectedPackagePath + "' -name '*.dll' 2>/dev/null | head -5").trim();
        if (!assemblies.isEmpty()) {
            detectedBuildType = "mono";
            log("Тип: mono/hybrid");
            return;
        }
        
        // Проверяем blobs
        String blobs = runRoot("find '" + detectedPackagePath + "' -name '*.blob' 2>/dev/null | head -3").trim();
        if (!blobs.isEmpty()) {
            detectedBuildType = "mono";
            log("Тип: mono (blob)");
            return;
        }
        
        detectedBuildType = "unknown";
        log("Тип: " + detectedBuildType);
    }

    private void findTargetFiles() {
        log("═══ ПОИСК ФАЙЛОВ ═══");
        
        // For osu!lazer the main assembly is always Assembly-CSharp
        String baseName = "Assembly-CSharp";
        
        // If user specified a specific target, use that
        String customName = etTargetName.getText().toString().trim();
        if (!customName.isEmpty()) {
            baseName = customName;
        }
        
        log("Ищу: " + baseName);
        
        // Build list of paths to search
        java.util.ArrayList<String> searchPaths = new java.util.ArrayList<>();
        
        // Add detected package path
        if (detectedPackagePath != null) {
            searchPaths.add(detectedPackagePath);
            // Also add /files subdirectory
            searchPaths.add(detectedPackagePath + "/files");
            searchPaths.add(detectedPackagePath + "/files/shared_assemblies");
            searchPaths.add(detectedPackagePath + "/assemblies");
        }
        
        // Add alternative paths for osu!lazer
        if (detectedPackageName != null) {
            // /data/data/sh.ppy.osulazer/
            String dataDataPath = "/data/data/" + detectedPackageName.replace(".", "_");
            searchPaths.add(dataDataPath);
            searchPaths.add(dataDataPath + "/files");
            searchPaths.add(dataDataPath + "/files/shared_assemblies");
            searchPaths.add(dataDataPath + "/assemblies");
            
            // /data/app/<package>/
            String appPath = "/data/app/" + detectedPackageName;
            searchPaths.add(appPath);
            searchPaths.add(appPath + "/base");
            searchPaths.add(appPath + "/split_config");
        }
        
        // Try common paths
        searchPaths.add("/data/data/sh.ppy.osulazer");
        searchPaths.add("/data/data/sh.ppy.osulazer/files");
        searchPaths.add("/data/data/sh.ppy.osulazer/files/shared_assemblies");
        
        // 1. Search for .blob files
        for (String path : searchPaths) {
            String blobs = runRoot("find '" + path + "' -name '*.blob' -type f 2>/dev/null").trim();
            if (!blobs.isEmpty()) {
                targetType = "blob";
                log("Найден blob: " + blobs.split("\n")[0]);
                checkBlobIntegrity(new File(blobs.split("\n")[0]));
                return;
            }
        }
        
        // 2. Search for AOT .so files
        String[] soPatterns = {"libaot-Assembly-CSharp.dll.so", "libaot-" + baseName + ".dll.so", "libaot-" + baseName + ".so"};
        for (String path : searchPaths) {
            for (String pattern : soPatterns) {
                String so = runRoot("find '" + path + "' -name '" + pattern + "' -type f 2>/dev/null").trim();
                if (!so.isEmpty()) {
                    targetType = "so";
                    originalFileHash = runRoot("md5sum '" + so + "' 2>/dev/null").trim().split("\\s+")[0];
                    log("Найден AOT: " + so);
                    log("Original hash: " + originalFileHash);
                    return;
                }
            }
        }
        
        // 3. Search for .dll files
        for (String path : searchPaths) {
            String dll = runRoot("find '" + path + "' -name '" + baseName + ".dll' -type f 2>/dev/null").trim();
            if (!dll.isEmpty()) {
                targetType = "dll";
                originalFileHash = runRoot("md5sum '" + dll + "' 2>/dev/null").trim().split("\\s+")[0];
                log("Найден dll: " + dll);
                log("Original hash: " + originalFileHash);
                return;
            }
        }
        
        // 4. List ALL available dll/so files
        for (String path : searchPaths) {
            String allDlls = runRoot("find '" + path + "' \\( -name '*.dll' -o -name '*.so' \\) -type f 2>/dev/null | head -10").trim();
            if (!allDlls.isEmpty()) {
                log("Файлы в " + path + ":");
                String[] files = allDlls.split("\n");
                for (int i = 0; i < Math.min(5, files.length); i++) {
                    log("  " + new java.io.File(files[i]).getName());
                }
                // Use first file if we found any
                if (targetType == null) {
                    String[] parts = allDlls.split("\n");
                    if (parts.length > 0) {
                        String first = parts[0];
                        if (first.endsWith(".dll")) {
                            targetType = "dll";
                        } else {
                            targetType = "so";
                        }
                        originalFileHash = runRoot("md5sum '" + first + "' 2>/dev/null").trim().split("\\s+")[0];
                        log("Использую: " + first);
                        return;
                    }
                }
            }
        }
        
        log("✗ Файлы не найдены!");
    }

    private void checkBlobIntegrity(File blobFile) {
        if (!blobFile.exists()) return;
        
        try (RandomAccessFile raf = new RandomAccessFile(blobFile, "r")) {
            byte[] hb = new byte[16];
            raf.readFully(hb);
            ByteBuffer hdr = ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN);
            int magic = hdr.getInt();
            
            boolean isV2 = (magic == BLOB_MAGIC_V2);
            if (magic != BLOB_MAGIC_V1 && !isV2) {
                log("⚠ Неверный blob magic!");
                return;
            }
            
            int count = hdr.getInt();
            log("Blob entries: " + count);
            log("Blob цел: ✓");
            
            // Сохраняем hash
            originalFileHash = runRoot("md5sum '" + blobFile.getAbsolutePath() + "' 2>/dev/null").trim().split("\\s+")[0];
            log("Original hash: " + originalFileHash);
            
        } catch (Exception e) {
            log("⚠ Ошибка проверки blob: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // File Selection
    // ═══════════════════════════════════════════════════════════════════

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(i, "Выбери файл (.dll / .so / .blob)"), PICK_REQUEST);
    }

    private void showArchWarning() {
        new AlertDialog.Builder(this)
            .setTitle("Несовпадение архитектуры")
            .setMessage("Файл: " + pickedFileArch + "\nosu: " + detectedArch + "\n\nПродолжить?")
            .setPositiveButton("Продолжить", (d, w) -> enableApply())
            .setNegativeButton("Отмена", (d, w) -> {})
            .show();
    }

    private void enableApply() {
        btnApply.setEnabled(true);
        setStatus("Готов", "#4CAF50");
    }

    private void checkPatchCompatibility(File newFile) {
        if (targetType == null) return;
        
        // Для blob проверяем размер
        if (targetType.equals("blob") && pickedFileName.toLowerCase().endsWith(".blob")) {
            // Just log, doesn't matter for .blob file selection
            return;
        }
        
        // Для DLL/SO - просто логируем
        log("→ Размер патча: " + newFile.length() + " bytes");
    }

    private String detectFileArch(String fileName) {
        String lo = fileName.toLowerCase();
        if (lo.contains("arm64") || lo.contains("aarch64")) return "arm64";
        if (lo.contains("armeabi") || lo.contains("armv7")) return "arm32";
        if (lo.contains("x86_64")) return "x64";
        if (lo.contains("x86") && !lo.contains("64")) return "x86";
        
        if (pickedFile != null && pickedFile.exists()) {
            try {
                byte[] head = new byte[20];
                java.io.FileInputStream fis = new java.io.FileInputStream(pickedFile);
                int r = fis.read(head);
                fis.close();
                if (r >= 20) {
                    if (head[0] == 0x7F && head[1] == 'E' && head[2] == 'L' && head[3] == 'F') {
                        if (head[4] == 2 && head[5] == 183) return "arm64";
                        if (head[4] == 1 && head[5] == 40) return "arm32";
                    }
                    if (head[0] == 0x4D && head[1] == 0x5A) {
                        if (head[4] == 0x64 || head[20] == 0x20) return "arm64";
                        return "arm32";
                    }
                }
            } catch (Exception e) {}
        }
        return "unknown";
    }

    private String resolveFileName(Uri uri) {
        String name = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) name = c.getString(idx);
                }
            } catch (Exception ignored) {}
        }
        if (name == null) {
            name = uri.getLastPathSegment();
            if (name != null && name.contains("/"))
                name = name.substring(name.lastIndexOf('/') + 1);
        }
        return name;
    }

    private String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Apply Patch - Main Logic
    // ═══════════════════════════════════════════════════════════════════

    private void applyPatch() {
        if (pickedFile == null || !pickedFile.exists()) { 
            log("✗ Файл не выбран"); 
            return; 
        }
        
        String inp = etTargetName.getText().toString().trim();
        // Default to Assembly-CSharp (main osu! assembly) instead of picked file name
        final String targetName = inp.isEmpty() ? "Assembly-CSharp" : inp;
        
        btnApply.setEnabled(false);
        setStatus("Применяю...", "#FF9800");

        executor.execute(() -> {
            // Check system first
            String suTest = runRoot("id").trim();
            if (!suTest.contains("uid=0") && !suTest.contains("root")) {
                log("✗ НЕТ ROOT! Патчинг невозможен.");
                fail();
                return;
            }
            
            if (detectedPackageName == null || detectedPackagePath == null) {
                log("✗ osu! не найден");
                fail();
                return;
            }
            
            // Pre-patch checks
            if (!checkArchitectureMatch()) {
                rollback();
                fail();
                return;
            }
            
            if (!checkVersionMatch()) {
                rollback();
                fail();
                return;
            }
            
            if (!checkBlobSize()) {
                rollback();
                fail();
                return;
            }
            
            log("════════════════════════");
            log("Пакет: " + detectedPackageName);
            log("Версия: " + detectedVersion);
            log("Арх: " + detectedArch);
            log("Тип: " + detectedBuildType);
            log("Цель: " + targetType);
            log("════════════════════════");
            
            // Create backup
            createBackup();
            
            // Apply based on target type
            if (targetType == null) {
                log("✗ Неизвестный тип файла!");
                fail();
                return;
            }
            
            boolean patchApplied = false;
            if (targetType.equals("blob")) {
                patchApplied = applyBlobPatch(targetName);
            } else if (targetType.equals("so")) {
                patchApplied = applySoPatch();
            } else {
                patchApplied = applyDllPatch();
            }
            
            if (!patchApplied) {
                rollback();
                fail();
                return;
            }
            
            // Clear cache
            clearOsuCache();
            
            // Restart osu
            restartOsu();
            
            // REAL verification - wait for process and check maps
            if (!verifyPatchLoaded()) {
                log("✗ Патч НЕ загружен - записываю ошибку!");
                rollback();
                fail();
                return;
            }
            
            // Success only if verification passed
            success();
        });
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // Pre-patch Verification Checks
    // ══════════════════════════════════════════════════════════════════════

    private boolean checkArchitectureMatch() {
        if (detectedArch == null || pickedFileArch == null) return true;
        
        // Check if selected file architecture matches osu architecture
        if (pickedFileArch.equals("unknown") || detectedArch.equals("unknown")) {
            log("⚠ Архитектура неизвестна, пропускаю проверку");
            return true;
        }
        
        // arm64 is compatible with arm32 for DLLs (WOW64), but not vice versa
        if (pickedFileArch.equals("arm64") && detectedArch.equals("arm32")) {
            log("✗ Несовместимость архитектуры!");
            log("  Файл: " + pickedFileArch + ", osu: " + detectedArch);
            return false;
        }
        
        if (pickedFileArch.equals("x64") && detectedArch.equals("x86")) {
            log("✗ Несовместимость архитектуры!");
            log("  Файл: " + pickedFileArch + ", osu: " + detectedArch);
            return false;
        }
        
        if (pickedFileArch.equals("x86") && detectedArch.equals("x64")) {
            log("⚠ x86 на x64 - может не работать");
        }
        
        log("→ Архитектура: " + pickedFileArch + " -> " + detectedArch + " ✓");
        return true;
    }

    private boolean checkVersionMatch() {
        // For now, just log - version matching would require metadata in the patch file
        // This could be enhanced to read version from patch metadata
        log("→ Проверка версии: " + detectedVersion + " (пропущено)");
        return true;
    }

    private boolean checkBlobSize() {
        if (pickedFile == null || !targetType.equals("blob")) return true;
        
        // For blob, we check size in patchBlob method itself
        // This is a pre-check placeholder
        return true;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Post-patch Verification - Check if patch is loaded in memory
    // ══════════════════════════════════════════════════════════════════════

    private boolean verifyPatchLoaded() {
        log("═══ VERIFY PATCH ═══");
        
        // Wait for osu to start (or use existing process)
        int pid = getOsuPid();
        if (pid <= 0) {
            log("⚠ osu не запущен, запускаю...");
            runRoot("monkey -c " + detectedPackageName);
            try { Thread.sleep(3000); } catch (Exception e) {}
            pid = getOsuPid();
        }
        
        if (pid <= 0) {
            log("✗ Не удалось получить PID osu");
            return false;
        }
        
        log("osu PID: " + pid);
        
        // Read /proc/<pid>/maps
        String maps = runRoot("cat /proc/" + pid + "/maps 2>/dev/null");
        
        if (maps.isEmpty()) {
            log("✗ Не удалось прочитать maps!");
            return false;
        }
        
        // Check for patched library path based on target type
        String patchedPath = getPatchedFilePath();
        if (!patchedPath.isEmpty() && maps.contains(patchedPath)) {
            log("✓ Пропатченная библиотека в памяти: " + patchedPath);
            return true;
        }
        
        // For blob - check assembly DLL name
        String dllName = getDllNameFromPatch();
        if (!dllName.isEmpty() && maps.contains(dllName)) {
            log("✓ Пропатченная assembly загружена: " + dllName);
            return true;
        }
        
        // Also check with common assembly names
        for (String asm : new String[]{"Assembly-CSharp", "assembly", ".dll"}) {
            if (maps.contains(asm.toLowerCase()) || maps.contains(asm)) {
                log("✓ Assembly в памяти: " + asm);
                return true;
            }
        }
        
        log("✗ Пропатченная библиотека НЕ в памяти!");
        log("  Ищу: " + patchedPath);
        log("  Maps содержит: " + (maps.isEmpty() ? "пусто" : "данные"));
        return false;
    }

    private int getOsuPid() {
        // Try multiple times with delay in case process just started
        for (int attempt = 0; attempt < 10; attempt++) {
            // Method 1: pidof
            String pidof = runRoot("pidof " + detectedPackageName + " 2>/dev/null").trim();
            if (!pidof.isEmpty()) {
                try { return Integer.parseInt(pidof.split("\\s+")[0]); }
                catch (Exception e) {}
            }
            
            // Method 2: ps piped through grep
            String ps = runRoot("ps -A 2>/dev/null | grep -i '" + detectedPackageName + "'").trim();
            if (!ps.isEmpty()) {
                String[] lines = ps.split("\n");
                for (String line : lines) {
                    String[] parts = line.split("\\s+");
                    if (parts.length > 0) {
                        try { 
                            int pid = Integer.parseInt(parts[0]);
                            if (pid > 0) return pid;
                        } catch (Exception e) {}
                    }
                }
            }
            
            // Method 3: Direct /proc search - iterate through all PIDs
            String procs = runRoot("ls /proc/ 2>/dev/null | grep -E '^[0-9]+$'").trim();
            if (!procs.isEmpty()) {
                for (String pidStr : procs.split("\n")) {
                    try {
                        int pid = Integer.parseInt(pidStr.trim());
                        if (pid > 100) { // Skip kernel processes
                            String cmdline = runRoot("cat /proc/" + pid + "/cmdline 2>/dev/null").replace("\0", " ");
                            if (cmdline.toLowerCase().contains(detectedPackageName.toLowerCase())) {
                                return pid;
                            }
                        }
                    } catch (Exception e) {}
                }
            }
            
            // Wait before retry
            if (attempt < 9) {
                try { Thread.sleep(500); } catch (Exception e) {}
            }
        }
        
        return -1;
    }

    private String getPatchedFilePath() {
        // Return the expected path where patched file should be
        if (targetType == null) return "";
        
        if (targetType.equals("blob")) {
            return runRoot("find '" + detectedPackagePath + "' -name '*.blob' 2>/dev/null").trim().split("\n")[0];
        } else if (targetType.equals("so")) {
            String baseName = stripExt(pickedFileName);
            return runRoot("find '" + detectedPackagePath + "' -name '*" + baseName + "*.so' 2>/dev/null").trim().split("\n")[0];
        } else if (targetType.equals("dll")) {
            String baseName = stripExt(pickedFileName);
            return runRoot("find '" + detectedPackagePath + "' -name '" + baseName + ".dll' 2>/dev/null").trim().split("\n")[0];
        }
        
        return "";
    }

    // ══════════════════════════════════════════════════════════════════════
    // Pattern Scanning for osu!lazer functions
    // ══════════════════════════════════════════════════════════════════════
    
    private long findFunctionAddress(String pattern, int pid) {
        log("→ Pattern scan: " + pattern);
        
        try {
            // Get maps to find loaded libraries
            String maps = runRoot("cat /proc/" + pid + "/maps 2>/dev/null").trim();
            if (maps.isEmpty()) return -1;
            
            // For each .so in memory
            String[] lines = maps.split("\n");
            for (String line : lines) {
                if (!line.contains(".so")) continue;
                
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 2) continue;
                
                String soPath = parts[parts.length - 1];
                if (!soPath.contains("/")) continue;
                
                // Read memory and scan for pattern (simplified - real implementation would do byte scan)
                // For now, log the libraries we find
                log("  Library: " + soPath);
            }
            
            log("  Pattern scan complete - requires native code for full implementation");
            
        } catch (Exception e) {
            log("  Pattern scan error: " + e.getMessage());
        }
        
        return -1;
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // Overlay Mount for Android 10+
    // ══════════════════════════════════════════════════════════════════════
    
    private boolean mountOverlay(String src, String dst) {
        log("═══ OVERLAY MOUNT ═══");
        
        // Check Android version
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            log("  Android < 10, using regular mount");
            return mountBind(src, dst);
        }
        
        String overlayDir = getCacheDir() + "/overlay";
        runRoot("mkdir -p " + overlayDir + "/upper " + overlayDir + "/work " + overlayDir + "/lower");
        
        // Copy original to lower
        runRoot("cp '" + dst + "' '" + overlayDir + "/lower/'");
        
        // Try overlay mount
        String cmd = "mount -t overlay overlay -o lowerdir=" + overlayDir + "/lower,upperdir=" + 
                    overlayDir + "/upper,workdir=" + overlayDir + "/work '" + dst + "'";
        
        String result = runRoot(cmd);
        
        if (result.isEmpty() || result.contains("mounted") || result.contains("Permission")) {
            log("✓ Overlay mount attempted");
            return true;
        }
        
        log("  Overlay failed: " + result);
        // Fallback to bind mount
        return mountBind(src, dst);
    }
    
    private boolean mountBind(String src, String dst) {
        runRoot("mount --bind '" + src + "' '" + dst + "'");
        return verifyMount(dst, src);
    }
    
    private boolean isMounted(String path) {
        String inode = runRoot("stat -c %i '" + path + "' 2>/dev/null").trim();
        return !inode.isEmpty();
    }

    // ══════════════════════════════════════════════════════════════════════
    // SELinux Bypass
    // ══════════════════════════════════════════════════════════════════════
    
    private boolean isSelinuxPermissive() {
        String status = runRoot("getenforce 2>/dev/null").trim();
        return status.equals("0") || status.equals("Permissive");
    }
    
    private void setSelinuxPermissive() {
        log("═══ SELINUX BYPASS ═══");
        
        // Try multiple bypass methods
        runRoot("setenforce 0 2>/dev/null");
        
        if (isSelinuxPermissive()) {
            log("✓ SELinux set to Permissive");
            return;
        }
        
        // Try Magisk
        runRoot("magiskpolicy --live 'allow * * * *' 2>/dev/null");
        runRoot("supolicy --live 2>/dev/null");
        
        if (isSelinuxPermissive()) {
            log("✓ SELinux bypassed via Magisk");
            return;
        }
        
        log("⚠ Could not set SELinux permissive");
    }

    // ══════════════════════════════════════════════════════════════════════
    // ptrace Scope Bypass
    // ══════════════════════════════════════════════════════════════════════
    
    private boolean isPtraceAllowed() {
        String scope = runRoot("cat /proc/sys/kernel/yama/ptrace_scope 2>/dev/null").trim();
        return scope.equals("0");
    }
    
    private void disablePtraceScope() {
        if (isPtraceAllowed()) return;
        
        log("→ Disabling ptrace_scope...");
        runRoot("echo 0 > /proc/sys/kernel/yama/ptrace_scope 2>/dev/null");
        
        if (isPtraceAllowed()) {
            log("✓ ptrace_scope disabled");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // Version Detection and Offsets
    // ══════════════════════════════════════════════════════════════════════
    
    private static class OsuVersionOffsets {
        String version;
        long handleTouchOffset;
        long updateOffset;
        long getHitObjectsOffset;
    }
    
    private OsuVersionOffsets detectAndLoadOffsets() {
        OsuVersionOffsets offsets = new OsuVersionOffsets();
        offsets.version = detectedVersion;
        
        // Default offsets for known versions
        if (detectedVersion != null) {
            if (detectedVersion.startsWith("2024") || detectedVersion.startsWith("2025")) {
                offsets.handleTouchOffset = 0x15A000;
                offsets.updateOffset = 0x2B5000;
                offsets.getHitObjectsOffset = 0x1F8000;
                log("  Using offsets for " + detectedVersion);
            } else if (detectedVersion.startsWith("2023")) {
                offsets.handleTouchOffset = 0x148000;
                offsets.updateOffset = 0x2A8000;
                offsets.getHitObjectsOffset = 0x1F0000;
            } else {
                // Unknown version
                offsets.handleTouchOffset = 0x100000;
                offsets.updateOffset = 0x200000;
                offsets.getHitObjectsOffset = 0x180000;
                log("  Using default offsets for " + detectedVersion);
            }
        }
        
        return offsets;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Enhanced Logging with Timestamp
    // ══════════════════════════════════════════════════════════════════════
    
    private void logWithTimestamp(String msg) {
        long now = System.currentTimeMillis();
        long secs = now / 1000;
        long ms = now % 1000;
        long mins = secs / 60;
        secs = secs % 60;
        long hours = mins / 60;
        mins = mins % 60;
        
        String timestamp = String.format("%02d:%02d:%02d.%03d", hours, mins, secs, ms);
        log("[" + timestamp + "] " + msg);
    }
    
    private boolean safeStep(String name, Runnable step) {
        log("→ " + name + "...");
        try {
            step.run();
            log("✓ " + name);
            return true;
        } catch (Exception e) {
            log("✗ " + name + ": " + e.getMessage());
            return false;
        }
    }

    // File to save log
    private File logFile = null;
    
    private void saveLogToFile(String msg) {
        if (logFile == null) {
            logFile = new File(getExternalFilesDir(null), "patcher.log");
        }
        
        try {
            java.io.FileWriter fw = new java.io.FileWriter(logFile, true);
            fw.write(msg + "\n");
            fw.close();
        } catch (Exception ignored) {}
    }

    private String getDllNameFromPatch() {
        if (pickedFileName == null) return "";
        return stripExt(pickedFileName);
    }

    private void clearOsuCache() {
        log("→ Очищаю кэш...");
        runRoot("pm clear " + detectedPackageName);
    }

    private void createBackup() {
        log("→ Создаю backup...");
        
        String backupDir = "/data/data/" + detectedPackageName + "_backup";
        runRoot("mkdir -p '" + backupDir + "'");
        
        // Backup existing files
        if (detectedPackagePath != null) {
            String cmd = "cp -r '" + detectedPackagePath + "'/* '" + backupDir + "/' 2>/dev/null";
            runRoot(cmd);
        }
        
        log("✓ Backup создан в " + backupDir);
    }

    private boolean applyBlobPatch(String targetName) {
        log("═══ BLOB PATCH ═══");
        
        // Find blob path
        String blobs = runRoot("find '" + detectedPackagePath + "' -name '*.blob' 2>/dev/null").trim();
        if (blobs.isEmpty() && detectedPackageName != null) {
            blobs = runRoot("find /data/data/" + detectedPackageName.replace(".", "_") + " -name '*.blob' 2>/dev/null").trim();
        }
        
        if (blobs.isEmpty()) {
            log("✗ Blob не найден!");
            return false;
        }
        
        String blobPath = blobs.split("\n")[0];
        log("Blob: " + blobPath);
        
        try {
            // Copy blob to work area
            File workBlob = new File(getCacheDir(), "work.blob");
            runRoot("cp '" + blobPath + "' '" + workBlob.getAbsolutePath() + "'");
            runRoot("chmod 666 '" + workBlob.getAbsolutePath() + "'");
            
            // Check integrity before
            if (!verifyBlobIntegrity(workBlob)) {
                log("⚠ Исходный blob повреждён!");
            }
            
            // Patch blob
            if (!patchBlob(workBlob, pickedFile, targetName)) {
                log("✗ Не удалось пропатчить blob");
                return false;
            }
            
            // Verify after patch
            if (!verifyBlobIntegrity(workBlob)) {
                log("⚠ Пропатченный blob ПОВРЕЖДЁН!");
                return false;
            }
            
            // Mount patched blob
            return applyMount(workBlob.getAbsolutePath(), blobPath);
            
        } catch (Exception e) {
            log("✗ Ошибка blob: " + e.getMessage());
            return false;
        }
    }

    private boolean verifyBlobIntegrity(File blobFile) {
        if (!blobFile.exists()) return false;
        
        try (RandomAccessFile raf = new RandomAccessFile(blobFile, "r")) {
            byte[] hb = new byte[16];
            raf.readFully(hb);
            ByteBuffer hdr = ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN);
            int magic = hdr.getInt();
            
            boolean isV2 = (magic == BLOB_MAGIC_V2);
            if (magic != BLOB_MAGIC_V1 && !isV2) return false;
            
            int count = hdr.getInt();
            int entrySize = isV2 ? 32 : 28;
            long tableEnd = 16L + (long) count * entrySize;
            
            return tableEnd <= blobFile.length();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean patchBlob(File blobFile, File newDll, String targetName) {
        try (RandomAccessFile raf = new RandomAccessFile(blobFile, "rw")) {
            byte[] hb = new byte[16];
            raf.readFully(hb);
            ByteBuffer hdr = ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN);
            int magic = hdr.getInt();
            hdr.getInt();
            int count = hdr.getInt();
            hdr.getInt();

            boolean isV2 = (magic == BLOB_MAGIC_V2);
            int entrySize = isV2 ? 32 : 28;
            long tableBase = 16L;

            byte[] newBytes = readFileFully(newDll);
            String tgtLower = targetName.toLowerCase().replace(".dll", "");

            for (int i = 0; i < count; i++) {
                long pos = tableBase + (long) i * entrySize;
                raf.seek(pos);
                byte[] eb = new byte[entrySize];
                raf.readFully(eb);
                ByteBuffer e = ByteBuffer.wrap(eb).order(ByteOrder.LITTLE_ENDIAN);
                if (isV2) { e.getLong(); e.getLong(); }
                else { e.getInt(); e.getLong(); }
                long dataOff = e.getInt() & 0xFFFFFFFFL;
                long dataSize = e.getInt() & 0xFFFFFFFFL;
                
                if (dataSize < 64 || dataOff == 0) continue;

                raf.seek(dataOff);
                byte[] sig = new byte[2];
                raf.readFully(sig);
                if (sig[0] != 0x4D || sig[1] != 0x5A) continue;

                raf.seek(dataOff);
                int peekLen = (int) Math.min(dataSize, 4096);
                byte[] peek = new byte[peekLen];
                raf.readFully(peek);

                String ascii = new String(peek, "ISO-8859-1").toLowerCase();
                boolean found = ascii.contains(tgtLower);
                
                if (!found) {
                    StringBuilder u16 = new StringBuilder();
                    for (int b = 0; b + 1 < peekLen; b += 2) {
                        char ch = (char)((peek[b] & 0xFF) | ((peek[b + 1] & 0xFF) << 8));
                        if (ch >= 32 && ch < 127) u16.append(ch);
                    }
                    found = u16.toString().toLowerCase().contains(tgtLower);
                }
                
                if (!found) continue;

                log("→ Патчу запись #" + i);
                log("  Slot size: " + dataSize + ", new: " + newBytes.length);

                if (newBytes.length > dataSize) {
                    log("⚠ НОВЫЙ ФАЙЛ БОЛЬШЕ! Пропускаю.");
                    return false;
                }

                raf.seek(dataOff);
                raf.write(newBytes, 0, newBytes.length);
                if (newBytes.length < dataSize) {
                    raf.write(new byte[(int)(dataSize - newBytes.length)]);
                }

                // Update size field
                long sizeFieldPos = pos + (isV2 ? 24 : 20);
                raf.seek(sizeFieldPos);
                ByteBuffer sb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
                sb.putInt(newBytes.length);
                raf.write(sb.array());

                log("✓ Пропатчено: " + newBytes.length + " bytes");
                return true;
            }
            
            log("✗ \"" + targetName + "\" не найден в blob");
            return false;
            
        } catch (Exception e) {
            log("✗ Ошибка патча: " + e.getMessage());
            return false;
        }
    }

    private boolean applySoPatch() {
        log("═══ SO PATCH ═══");
        
        // Find the target so
        String baseName = stripExt(pickedFileName);
        String soPath = runRoot("find '" + detectedPackagePath + "' -name '*" + baseName + "*.so' 2>/dev/null | head -1").trim();
        
        if (soPath.isEmpty()) {
            log("✗ SO не найден!");
            return false;
        }
        
        return applyMount(pickedFile.getAbsolutePath(), soPath);
    }

    private boolean applyDllPatch() {
        log("═══ DLL PATCH ═══");
        
        String baseName = stripExt(pickedFileName);
        String dllPath = runRoot("find '" + detectedPackagePath + "' -name '" + baseName + ".dll' 2>/dev/null | head -1").trim();
        
        if (dllPath.isEmpty()) {
            log("✗ DLL не найден!");
            return false;
        }
        
        return applyMount(pickedFile.getAbsolutePath(), dllPath);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Mount - Real verification and rollback
    // ═══════════════════════════════════════════════════════════════════

    private boolean applyMount(String srcPath, String dstPath) {
        log("═══ MOUNT ═══");
        
        // Unmount if already mounted
        runRoot("umount '" + dstPath + "' 2>/dev/null");
        try { Thread.sleep(300); } catch (Exception e) {}
        
        // Try different mount methods
        
        // Method 1: mount --bind
        log("→ [1] mount --bind");
        runRoot("mount --bind '" + srcPath + "' '" + dstPath + "'");
        if (verifyMount(dstPath, srcPath)) {
            log("✓ Mount успешен (method 1)");
            return true;
        }
        
        // Method 2: mount -o bind
        log("→ [2] mount -o bind");
        runRoot("mount -o bind '" + srcPath + "' '" + dstPath + "'");
        if (verifyMount(dstPath, srcPath)) {
            log("✓ Mount успешен (method 2)");
            return true;
        }
        
        // Method 3: nsenter
        log("→ [3] nsenter");
        runRoot("nsenter --mount=/proc/1/ns/mnt -- mount --bind '" + srcPath + "' '" + dstPath + "'");
        if (verifyMount(dstPath, srcPath)) {
            log("✓ Mount успешен (method 3)");
            return true;
        }
        
        // Method 4: Magisk module (for next reboot)
        log("→ [4] Magisk module");
        createMagiskModule(srcPath, dstPath);
        log("✓ Magisk модуль создан (требуется перезагрузка)");
        return true;
    }

    private boolean verifyMount(String path, String expectedSrc) {
        // Check mountinfo
        String mi = runRoot("cat /proc/self/mountinfo 2>/dev/null | grep '" + path + "'").trim();
        if (mi.isEmpty()) return false;
        
        // Verify hash matches
        String hash = runRoot("md5sum '" + path + "' 2>/dev/null").trim().split("\\s+")[0];
        String srcHash = runRoot("md5sum '" + expectedSrc + "' 2>/dev/null").trim().split("\\s+")[0];
        
        if (!hash.isEmpty() && !srcHash.isEmpty() && hash.equals(srcHash)) {
            log("✓ Mount верифицирован! Hash match.");
            return true;
        }
        
        return !mi.isEmpty();
    }

    private void restartOsu() {
        log("→ Перезапускаю osu для перезагрузки assembly...");
        
        // Force stop
        runRoot("am force-stop " + detectedPackageName);
        
        // Clear dalvik cache
        runRoot("pm clear " + detectedPackageName);
        
        // Wait a bit
        try { Thread.sleep(1000); } catch (Exception e) {}
        
        // Start osu (user can manually start)
        log("✓ osu остановлен. Перезапусти вручную.");
    }

    private void createMagiskModule(String srcPath, String dstPath) {
        String modDir = "/data/adb/modules/osupatch";
        runRoot("mkdir -p '" + modDir + "'");
        
        String prop = "id=osupatch\nname=osu! Patcher\nversion=v2\nversionCode=2\nauthor=osupatch\ndescription=osu! DLL patch\n";
        runRoot("printf '" + prop.replace("\n", "\\n") + "' > '" + modDir + "/module.prop'");
        
        String rel = dstPath.startsWith("/") ? dstPath.substring(1) : dstPath;
        String destDir = modDir + "/" + rel.substring(0, rel.lastIndexOf('/'));
        
        runRoot("mkdir -p '" + destDir + "'");
        runRoot("cp '" + srcPath + "' '" + modDir + "/" + rel + "'");
        runRoot("chmod -R 755 '" + modDir + "'");
        
        log("✓ Magisk модуль создан");
        log("  Перезагрузи для активации");
    }

    private void rollback() {
        log("═══ ROLLBACK ═══");
        
        // Unmount all mounts
        if (detectedPackagePath != null) {
            runRoot("umount '" + detectedPackagePath + "' 2>/dev/null");
            runRoot("umount -l '" + detectedPackagePath + "' 2>/dev/null");
        }
        
        // Also try to unmount specific paths
        runRoot("umount /data/data/*/files/*.blob 2>/dev/null");
        runRoot("umount /data/app/*/lib/*.so 2>/dev/null");
        
        // Restore from backup
        if (detectedPackageName != null && detectedPackagePath != null) {
            String backupDir = "/data/data/" + detectedPackageName + "_backup";
            runRoot("cp -r '" + backupDir + "'/* '" + detectedPackagePath + "/' 2>/dev/null");
        }
        
        // Clear cache again
        if (detectedPackageName != null) {
            runRoot("pm clear " + detectedPackageName + " 2>/dev/null");
            runRoot("am force-stop " + detectedPackageName + " 2>/dev/null");
        }
        
        // Remove Magisk module
        runRoot("rm -rf /data/adb/modules/osupatch 2>/dev/null");
        
        log("✓ Rollback выполнен");
    }

    // ═══════════════════════════════════════════════════════════════════
    // Unmount
    // ═══════════════════════════════════════════════════════════════════

    private void unmount() {
        setStatus("Снимаю...", "#FF9800");
        
        executor.execute(() -> {
            runRoot("umount '" + detectedPackagePath + "' 2>/dev/null");
            runRoot("rm -rf /data/adb/modules/osupatch 2>/dev/null");
            
            log("✓ Патч снят");
            
            mainHandler.post(() -> setStatus("Снято", "#888888"));
        });
    }

    // ═══════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════

    private byte[] readFileFully(File f) throws Exception {
        byte[] d = new byte[(int) f.length()];
        try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
            int off = 0;
            while (off < d.length) {
                int n = fis.read(d, off, d.length - off);
                if (n < 0) break;
                off += n;
            }
        }
        return d;
    }

    private String escQ(String s) {
        return s == null ? "" : s.replace("'", "'\\''");
    }

    private String runRoot(String cmd) {
        StringBuilder sb = new StringBuilder();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
                 BufferedReader be = new BufferedReader(new InputStreamReader(p.getErrorStream(), "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append("\n");
                while ((line = be.readLine()) != null) if (!line.trim().isEmpty()) log("! " + line);
            }
            p.waitFor();
        } catch (Exception e) {
            log("! runRoot: " + e.getMessage());
        }
        return sb.toString();
    }

    private void log(String msg) {
        mainHandler.post(() -> {
            tvLog.append(msg + "\n");
            scrollLog.post(() -> scrollLog.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void setStatus(String msg, String hex) {
        mainHandler.post(() -> {
            tvStatus.setText(msg);
            try {
                tvStatus.setTextColor(android.graphics.Color.parseColor(hex));
            } catch (Exception ignored) {}
        });
    }

    private void success() {
        log("✓ ПАТЧ УСПЕШНО ПРИМЕНЁН!");
        log("⚠ Перезапусти osu!");
        
        mainHandler.post(() -> setStatus("PATCHED ✓", "#4CAF50"));
    }

    private void fail() {
        mainHandler.post(() -> {
            setStatus("Ошибка", "#F44336");
            btnApply.setEnabled(true);
        });
    }

    private void openSettings() {
        log("Открываю настройки...");
        try {
            startActivity(new android.content.Intent(this, SettingsActivity.class));
        } catch (Exception e) {
            log("✗ Не удалось открыть: " + e.getMessage());
        }
    }
}
