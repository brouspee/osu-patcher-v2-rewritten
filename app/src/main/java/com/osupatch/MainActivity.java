package com.osupatch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
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

    // Все пути где может быть osu!
    private static final String[] SEARCH_ROOTS = {
        "/data/app",
        "/data/app-private",
        "/mnt/asec",
        "/data/data",
        "/data/user/0",
        "/sdcard/Android/data",
        "/storage/emulated/0/Android/data",
        "/data/user_de/0",
        "/apex",
        "/data/app-ephemeral",
        "/mnt/expand",
        "/data/local"
    };

    private TextView   tvLog, tvStatus;
    private Button     btnPick, btnApply, btnUnmount;
    private EditText   etTargetName;
    private ScrollView scrollLog;

    private File   pickedFile;
    private String pickedFileName;
    private String pickedFileArch;

    private final ExecutorService executor    = Executors.newSingleThreadExecutor();
    private final Handler         mainHandler = new Handler(Looper.getMainLooper());

    // Framework detection
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
        etTargetName = findViewById(R.id.et_target_name);
        scrollLog    = findViewById(R.id.scroll_log);

        btnApply.setEnabled(false);
        btnPick.setOnClickListener(v -> pickFile());
        btnApply.setOnClickListener(v -> applyPatch());
        btnUnmount.setOnClickListener(v -> unmount());

        checkRoot();
        checkCurrentStatus();
        checkFrameworks();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(i, "Выбери файл (.dll / .so / .blob)"), PICK_REQUEST);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK_REQUEST || res != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;

        executor.execute(() -> {
            try {
                String origName = resolveFileName(uri);
                if (origName == null || origName.isEmpty()) origName = "picked_file";
                pickedFileName = origName;

                pickedFileArch = detectFileArch(origName);
                log("-> Файл архитектура: " + pickedFileArch);

                File dest = new File(getCacheDir(), origName);
                try (InputStream  in  = getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(dest)) {
                    if (in == null) throw new Exception("Cannot open stream");
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }

                if (origName.toLowerCase().endsWith(".cs")) {
                    log("-> C# файл: " + origName);
                    File compiled = compileCSharp(dest, origName);
                    if (compiled != null) {
                        pickedFile     = compiled;
                        pickedFileName = compiled.getName();
                        pickedFileArch = detectFileArch(pickedFileName);
                        log("✓ Скомпилировано: " + pickedFileName + " (" + compiled.length() / 1024 + " KB) [" + pickedFileArch + "]");
                    } else {
                        final File fd  = dest;
                        final String fn = origName;
                        mainHandler.post(() -> showCsWarning(fd, fn));
                        return;
                    }
                } else {
                    pickedFile = dest;
                    log("✓ Загружен: " + origName + " (" + dest.length() / 1024 + " KB) [" + pickedFileArch + "]");
                }

                String baseName = stripExt(pickedFileName);
                mainHandler.post(() -> {
                    if (etTargetName.getText().toString().trim().isEmpty())
                        etTargetName.setText(baseName);
                    btnApply.setEnabled(true);
                    setStatus("Готов — нажми Apply", "#4CAF50");
                });

            } catch (Exception e) {
                log("✗ Ошибка загрузки: " + e.getMessage());
            }
        });
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
                        if (head[4] == 2 && head[5] == 62) return "x64";
                        if (head[4] == 1 && head[5] == 3) return "x86";
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

    private File compileCSharp(File csFile, String origName) {
        String mcsPath = runRoot("which mcs 2>/dev/null").trim();
        if (mcsPath.isEmpty())
            mcsPath = runRoot("ls /data/data/com.termux/files/usr/bin/mcs 2>/dev/null").trim();
        if (mcsPath.isEmpty()) {
            log("! mcs не найден. Установи Mono: pkg install mono");
            return null;
        }
        log("-> Компилятор: " + mcsPath);
        String dllName = stripExt(origName) + ".dll";
        File   outDll  = new File(getCacheDir(), dllName);
        String refs    = "";
        PatchTarget pt = findPatchTarget();
        if (pt != null && !pt.isBlobMode && pt.mountTarget != null) {
            String dir = pt.mountTarget.substring(0, pt.mountTarget.lastIndexOf('/'));
            refs = " -lib:'" + escQ(dir) + "'";
        }
        String cmd = "\"" + mcsPath + "\" -target:library" + refs
                + " -out:'" + escQ(outDll.getAbsolutePath()) + "'"
                + " '" + escQ(csFile.getAbsolutePath()) + "' 2>&1";
        String result = runRoot(cmd);
        if (!result.trim().isEmpty()) log("-> Компилятор:\n" + result.trim());
        
        if (outDll.exists() && outDll.length() > 0) {
            pickedFileArch = detectFileArch(outDll.getName());
            log("->DLL архитектура: " + pickedFileArch);
        }
        
        return (outDll.exists() && outDll.length() > 0) ? outDll : null;
    }

    private void showCsWarning(File csFile, String origName) {
        new AlertDialog.Builder(this)
            .setTitle("Mono не установлен")
            .setMessage("Для компиляции .cs нужен Mono:\n  pkg install mono\n\nВшить как есть?")
            .setPositiveButton("Вшить как есть", (d, w) -> {
                pickedFile = csFile;
                pickedFileArch = detectFileArch(csFile.getName());
                if (etTargetName.getText().toString().trim().isEmpty())
                    etTargetName.setText(stripExt(origName));
                btnApply.setEnabled(true);
                setStatus("Готов (без компиляции)", "#FF9800");
                log("⚠ Будет вшито без компиляции [" + pickedFileArch + "]");
            })
            .setNegativeButton("Отмена", null)
            .show();
    }

    private void checkRoot() {
        executor.execute(() -> {
            String suTest = runRoot("id").trim();
            if (suTest.contains("uid=0") || suTest.contains("root")) {
                log("✓ Root подтверждён: " + suTest);
            } else {
                log("✗ Root недоступен или ограничен");
                mainHandler.post(() -> setStatus("ROOT REQUIRED", "#F44336"));
            }
        });
    }

    private void checkCurrentStatus() {
        executor.execute(() -> {
            PatchTarget pt = findPatchTarget();
            if (pt == null) {
                mainHandler.post(() -> setStatus("osu! не найден", "#FF5500"));
                return;
            }
            
            boolean mounted = checkMountReal(pt.mountTarget);
            if (mounted) {
                log("● Патч АКТИВЕН: " + pt.mountTarget);
                mainHandler.post(() -> setStatus("PATCHED ✓", "#4CAF50"));
            } else {
                log("○ Не пропатчен [" + (pt.isBlobMode ? "BLOB" : "DLL/SO") + "] pkg=" + pt.packageName);
                mainHandler.post(() -> setStatus("Не пропатчен", "#888888"));
            }
        });
    }

    // ════════════════════════════════════════════════════════════════════════════
    // Framework Detection - Check Magisk, Zygisk, LSPosed, Riru
    // ════════════════════════════════════════════════════════════════════════════

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

    private void applyPatch() {
        if (pickedFile == null || !pickedFile.exists()) { log("✗ Файл не выбран"); return; }
        String inp = etTargetName.getText().toString().trim();
        final String targetName = inp.isEmpty() ? stripExt(pickedFileName) : inp;
        btnApply.setEnabled(false);
        setStatus("Применяю...", "#FF9800");

        executor.execute(() -> {
            PatchTarget pt = findPatchTarget();
            if (pt == null) { 
                log("✗ osu! не найден - проверь что игра установлена"); 
                fail(); 
                return; 
            }

            if (!pt.isBlobMode && pickedFileArch != null && !pickedFileArch.equals("unknown")) {
                String osuArch = pt.targetArch != null ? pt.targetArch : detectOsuArch(pt);
                if (osuArch != null && !osuArch.equals("unknown")) {
                    if (!pickedFileArch.equals(osuArch)) {
                        log("⚠ АРХИТЕКТУРА НЕ СОВПАДАЕТ!");
                        log("   Файл: " + pickedFileArch + ", osu!: " + osuArch);
                        mainHandler.post(() -> {
                            new AlertDialog.Builder(this)
                                .setTitle("Несовпадение архитектуры")
                                .setMessage("Файл: " + pickedFileArch + "\nosu!: " + osuArch + "\n\nПродолжить?")
                                .setPositiveButton("Продолжить", (d, w) -> doApplyPatch(pt, targetName))
                                .setNegativeButton("Отмена", (d, w) -> {
                                    log("✗ Отменено пользователем");
                                    fail(); 
                                })
                                .show();
                        });
                        return;
                    }
                    log("✓ Архитектура совпадает: " + pickedFileArch);
                }
            }

            doApplyPatch(pt, targetName);
        });
    }
    
    private void doApplyPatch(PatchTarget pt, String targetName) {
        log("─────────────────────────");
        log("Пакет: " + pt.packageName);
        log("Режим: " + (pt.isBlobMode ? "BLOB" : "DLL/SO"));
        log("Цель:  " + pt.mountTarget);
        log(" arch: " + (pt.targetArch != null ? pt.targetArch : "unknown"));
        log("─────────────────────────");

        if (isMounted(pt.mountTarget)) {
            runRoot("umount '" + escQ(pt.mountTarget) + "' 2>/dev/null");
            log("-> Старый mount снят");
            try { Thread.sleep(500); } catch (Exception e) {}
        }

        boolean isBlob = pickedFileName != null && pickedFileName.toLowerCase().endsWith(".blob");
        if (isBlob || !pt.isBlobMode) {
            applyDirectMount(pt);
        } else {
            applyBlobPatch(pt, targetName);
        }
    }

    private void applyDirectMount(PatchTarget pt) {
        String tempDir = "/data/data/" + pt.packageName;
        runRoot("mkdir -p '" + tempDir + "'");
        String src = tempDir + "/" + pickedFileName;
        runRoot("cp '" + escQ(pickedFile.getAbsolutePath()) + "' '" + escQ(src) + "'");
        runRoot("chmod 644 '" + escQ(src) + "'");
        
        if (!runRoot("ls -la '" + escQ(src) + "'").contains(String.valueOf(pickedFile.length()))) {
            log("⚠ Файл не скопировался, пробую /data/local/tmp");
            src = "/data/local/tmp/" + pickedFileName;
            runRoot("cp '" + escQ(pickedFile.getAbsolutePath()) + "' '" + escQ(src) + "'");
            runRoot("chmod 644 '" + escQ(src) + "'");
        }
        
        String dst = "'" + escQ(pt.mountTarget) + "'";

        log("-> [1] mount --bind...");
        runRoot("mount --bind '" + escQ(src) + "' " + dst);
        if (verifyMount(pt, src)) { success(pt, src); return; }

        log("-> [2] mount -o bind...");
        runRoot("mount -o bind '" + escQ(src) + "' " + dst);
        if (verifyMount(pt, src)) { success(pt, src); return; }

        log("-> [3] nsenter mount (init)...");
        runRoot("nsenter --mount=/proc/1/ns/mnt -- mount --bind '" + escQ(src) + "' " + dst);
        if (verifyMount(pt, src)) { success(pt, src); return; }

        log("-> [4] nsenter mount (root)...");
        runRoot("nsenter --mount=/proc/$(pgrep -f '^/system/bin/init$' 2>/dev/null | head -1)/ns/mnt -- mount --bind '" + escQ(src) + "' " + dst);
        if (verifyMount(pt, src)) { success(pt, src); return; }

        log("-> [5] Magisk модуль...");
        String modDir = "/data/adb/modules/osupatch";
        runRoot("mkdir -p '" + modDir + "'");
        String prop = "id=osupatch\\nname=osu! Patcher\\nversion=v2\\nversionCode=2\\nauthor=osupatch\\ndescription=osu! DLL patch\\n";
        runRoot("printf '" + prop + "' > '" + modDir + "/module.prop'");
        String rel = pt.mountTarget.startsWith("/") ? pt.mountTarget.substring(1) : pt.mountTarget;
        String destDir = modDir + "/" + rel.substring(0, rel.lastIndexOf('/'));
        runRoot("mkdir -p '" + escQ(destDir) + "'");
        runRoot("cp '" + escQ(src) + "' '" + escQ(modDir + "/" + rel) + "'");
        runRoot("chmod -R 755 '" + modDir + "'");
        log("✓ Magisk модуль — перезагрузи");

        log("-> [6] Runtime inject...");
        if (tryRuntimeInject(pt, src)) return;

        log("✗ НИЧЕГО НЕ СРАБОТАЛО!");
        log("  1. mount заблокирован на Android 13+");
        log("  2. nsenter может быть недоступен");
        log("  3. osu уже запущен и закешировал DLL");
        log("  Решение: Закрой osu полностью и попробуй снова");
        fail();
    }

    private boolean verifyMount(PatchTarget pt, String srcFile) {
        if (checkMountReal(pt.mountTarget)) {
            return true;
        }
        
        if (pt.packageName != null) {
            String mountedHash = runRoot("md5sum '" + escQ(pt.mountTarget) + "' 2>/dev/null").trim().split("\\s+")[0];
            String srcHash = runRoot("md5sum '" + escQ(srcFile) + "' 2>/dev/null").trim().split("\\s+")[0];
            if (!mountedHash.isEmpty() && !srcHash.isEmpty() && mountedHash.equals(srcHash)) {
                log("✓ Hash совпал — mount работает");
                return true;
            }
            log("! Hash НЕ совпал!");
            log("   mounted: " + mountedHash);
            log("   source:  " + srcHash);
        }
        
        return false;
    }

    private boolean checkMountReal(String path) {
        if (path == null || path.isEmpty()) return false;
        
        String mi = runRoot("cat /proc/self/mountinfo 2>/dev/null | grep '" + escQ(path) + "'").trim();
        if (!mi.isEmpty()) return true;
        
        String m = runRoot("grep ' " + escQ(path) + " ' /proc/mounts 2>/dev/null").trim();
        if (!m.isEmpty()) return true;
        
        String mg = runRoot("mount 2>/dev/null | grep '" + escQ(path) + "'").trim();
        return !mg.isEmpty();
    }

    private void applyBlobPatch(PatchTarget pt, String targetName) {
        try {
            log("-> Копирую blob...");
            File workBlob = new File(getCacheDir(), "work.blob");
            runRoot("cp '" + escQ(pt.blobPath) + "' '" + escQ(workBlob.getAbsolutePath()) + "'");
            runRoot("chmod 666 '" + escQ(workBlob.getAbsolutePath()) + "'");

            if (!verifyBlobIntegrity(workBlob)) {
                log("⚠ Исходный blob повреждён!");
            }

            if (!patchAssemblyBlob(workBlob, pickedFile, targetName)) {
                log("✗ \"" + targetName + "\" не найдена в blob");
                log("  Уточни имя в поле выше (без .dll)");
                fail(); return;
            }

            if (!verifyBlobIntegrity(workBlob)) {
                log("⚠ Пропатченный blob ПОВРЕЖДЁН! osu проигнорирует.");
            }

            String tempDir = "/data/data/" + pt.packageName;
            runRoot("mkdir -p '" + tempDir + "'");
            String tmpBlob = tempDir + "/patched.blob";
            runRoot("cp '" + escQ(workBlob.getAbsolutePath()) + "' '" + escQ(tmpBlob) + "'");
            runRoot("chmod 644 '" + escQ(tmpBlob) + "'");
            String dst = "'" + escQ(pt.mountTarget) + "'";

            log("-> [B1] mount --bind blob...");
            runRoot("mount --bind '" + escQ(tmpBlob) + "' " + dst);
            if (verifyMount(pt, tmpBlob)) { success(pt, tmpBlob); return; }

            log("-> [B2] mount -o bind blob...");
            runRoot("mount -o bind '" + escQ(tmpBlob) + "' " + dst);
            if (verifyMount(pt, tmpBlob)) { success(pt, tmpBlob); return; }

            log("-> [B3] nsenter blob...");
            runRoot("nsenter --mount=/proc/1/ns/mnt -- mount --bind '" + escQ(tmpBlob) + "' " + dst);
            if (verifyMount(pt, tmpBlob)) { success(pt, tmpBlob); return; }

            log("✗ Blob mount не удался");
            fail();
        } catch (Exception e) {
            log("✗ Ошибка blob: " + e.getMessage());
            fail();
        }
    }

    private boolean verifyBlobIntegrity(File blobFile) {
        if (blobFile == null || !blobFile.exists()) return false;
        
        try (RandomAccessFile raf = new RandomAccessFile(blobFile, "r")) {
            byte[] hb = new byte[16];
            raf.readFully(hb);
            ByteBuffer hdr = ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN);
            int magic = hdr.getInt();
            hdr.getInt();
            int count = hdr.getInt();
            hdr.getInt();
            
            boolean isV2 = (magic == BLOB_MAGIC_V2);
            if (magic != BLOB_MAGIC_V1 && !isV2) {
                log("! Неверный blob magic");
                return false;
            }
            
            int entrySize = isV2 ? 32 : 28;
            long tableEnd = 16L + (long) count * entrySize;
            if (tableEnd > blobFile.length()) {
                log("! Таблица выходит за пределы файла");
                return false;
            }
            
            log("✓ Blob цел: " + count + " записей");
            return true;
        } catch (Exception e) {
            log("! Ошибка проверки blob: " + e.getMessage());
            return false;
        }
    }

    private boolean patchAssemblyBlob(File blobFile, File newDll, String targetName) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(blobFile, "rw")) {
            byte[] hb = new byte[16];
            raf.readFully(hb);
            ByteBuffer hdr = ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN);
            int magic = hdr.getInt();
            hdr.getInt();
            int count = hdr.getInt();
            hdr.getInt();

            log("-> Blob magic: 0x" + Long.toHexString(magic & 0xFFFFFFFFL));
            log("-> Записей: " + count);

            boolean isV2 = (magic == BLOB_MAGIC_V2);
            if (magic != BLOB_MAGIC_V1 && !isV2) {
                log("! Неверный blob magic — не Assembly Store");
                return false;
            }

            int entrySize = isV2 ? 32 : 28;
            int dataOffsetField = isV2 ? 16 : 12;
            long tableBase = 16L;

            List<long[]> entries = new ArrayList<>();
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
                entries.add(new long[]{pos + dataOffsetField, dataOff, dataSize});
            }

            byte[] newBytes = readFileFully(newDll);
            String tgtLower = targetName.toLowerCase().replace(".dll", "");

            for (int i = 0; i < entries.size(); i++) {
                long sizeFieldAbs = entries.get(i)[0] + 4;
                long dataOff = entries.get(i)[1];
                long dataSize = entries.get(i)[2];
                if (dataSize < 64 || dataOff == 0) continue;

                raf.seek(dataOff);
                byte[] sig = new byte[2];
                raf.readFully(sig);
                if (sig[0] != 0x4D || sig[1] != 0x5A) continue;

                raf.seek(dataOff);
                int peekLen = (int) Math.min(dataSize, 8192);
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

                log("-> Запись #" + i + " (off=" + dataOff + " slot=" + dataSize + ")");
                log("   новый файл: " + newBytes.length + " bytes");

                if (newBytes.length > dataSize) {
                    log("⚠ ОПАСНО! новый файл БОЛЬШЕ слота!");
                    log("   osu скорее всего проигнорирует патч");
                }

                raf.seek(dataOff);
                int writeLen = (int) Math.min(newBytes.length, dataSize);
                raf.write(newBytes, 0, writeLen);
                if (newBytes.length < dataSize) {
                    raf.write(new byte[(int)(dataSize - newBytes.length)]);
                }

                raf.seek(sizeFieldAbs);
                ByteBuffer sb2 = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
                sb2.putInt(writeLen);
                raf.write(sb2.array());
                
                log("✓ Запись #" + i + " пропатчена (" + writeLen + " bytes)");
                return true;
            }
            log("✗ \"" + targetName + "\" не найдена в blob");
            return false;
        }
    }

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

    private boolean tryRuntimeInject(PatchTarget pt, String soPath) {
        if (!soPath.toLowerCase().endsWith(".so")) {
            log("! Runtime inject: нужен .so файл");
            return false;
        }
        
        log("-> Ищу PID osu!...");
        String pid = findOsuPid(pt.packageName);
        if (pid.isEmpty()) {
            log("! osu! не запущен — запусти игру først!");
            return false;
        }
        
        String osuArch = getProcessArch(pid);
        if (!osuArch.equals("unknown") && !pickedFileArch.equals("unknown")) {
            if (!osuArch.equals(pickedFileArch)) {
                log("⚠ Архитектура НЕ СОВПАДАЕТ: so=" + pickedFileArch + ", osu=" + osuArch);
            }
        }

        String injectOut = tryDlopenInject(pid, soPath);
        
        if (verifySoLoaded(pid, soPath)) {
            log("✓ Runtime inject успешен!");
            log("⚠ Перезапусти osu! для применения патча!");
            mainHandler.post(() -> setStatus("INJECTED ✓", "#4CAF50"));
            return true;
        }
        
        log("! Подтверждения загрузки нет — SELinux заблокировал");
        log("  " + injectOut);
        return false;
    }

    private String findOsuPid(String packageName) {
        String pid = "";
        
        if (packageName != null) {
            pid = runRoot("pidof " + packageName + " 2>/dev/null").trim().split("\\s+")[0];
        }
        if (!pid.isEmpty()) return pid;
        
        for (String pkg : OSU_PACKAGES) {
            pid = runRoot("pidof " + pkg + " 2>/dev/null").trim().split("\\s+")[0];
            if (!pid.isEmpty()) return pid;
        }
        
        String ps = runRoot("ps -A -o PID,NAME 2>/dev/null | grep -iE 'osulazer|ppy.osu|rimu'").trim();
        if (!ps.isEmpty()) {
            for (String line : ps.split("\n")) {
                if (!line.contains("grep")) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 1) {
                        pid = parts[0];
                        break;
                    }
                }
            }
        }
        
        return pid != null ? pid : "";
    }

    private String getProcessArch(String pid) {
        if (pid == null || pid.isEmpty()) return "unknown";
        String status = runRoot("cat /proc/" + pid + "/status 2>/dev/null").trim();
        if (status.contains("arm64") || status.contains("aarch64")) return "arm64";
        return "unknown";
    }

    private String tryDlopenInject(String pid, String soPath) {
        String script = 
            "#!/system/bin/sh\n" +
            "PID=" + pid + "\n" +
            "SO='" + soPath + "'\n" +
            "echo \"Trying: LD_PRELOAD=$SO kill -0 $PID\"\n" +
            "LD_PRELOAD=$SO kill -0 $PID 2>&1\n" +
            "echo \"dlopen typically blocked on Android 13+\"\n";
        
        File f = new File(getCacheDir(), "inject.sh");
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(script.getBytes("UTF-8"));
        } catch (Exception e) {
            return "";
        }
        runRoot("chmod +x '" + escQ(f.getAbsolutePath()) + "'");
        return runRoot("sh '" + escQ(f.getAbsolutePath()) + "'");
    }

    private boolean verifySoLoaded(String pid, String soPath) {
        if (pid == null || pid.isEmpty() || soPath == null) return false;
        
        String maps = runRoot("grep '" + escQ(soPath) + "' /proc/" + pid + "/maps 2>/dev/null").trim();
        if (!maps.isEmpty()) {
            log("✓ SO найден в памяти процесса");
            return true;
        }
        
        return false;
    }

    private void unmount() {
        setStatus("Снимаю...", "#FF9800");
        executor.execute(() -> {
            PatchTarget pt = findPatchTarget();
            if (pt == null) { log("✗ osu! не найден"); return; }
            
            boolean mounted = checkMountReal(pt.mountTarget);
            if (!mounted) {
                log("○ Патч не активен");
                mainHandler.post(() -> setStatus("Не пропатчен", "#888888"));
            } else {
                for (int i = 0; i < 3; i++) {
                    runRoot("umount '" + escQ(pt.mountTarget) + "' 2>/dev/null");
                    if (!checkMountReal(pt.mountTarget)) break;
                    try { Thread.sleep(300); } catch (Exception e) {}
                }
                
                if (!checkMountReal(pt.mountTarget)) {
                    log("✓ Патч снят. Перезапусти osu!");
                    mainHandler.post(() -> setStatus("Снято", "#888888"));
                } else {
                    log("✗ Не удалось снять — перезагрузи");
                    mainHandler.post(() -> setStatus("Ошибка", "#F44336"));
                }
            }
            runRoot("rm -rf /data/adb/modules/osupatch 2>/dev/null");
            log("-> Magisk модуль удалён");
        });
    }

    static class PatchTarget {
        String mountTarget;
        String blobPath;
        boolean isBlobMode;
        String packageName;
        String apkDir;
        String targetArch;
    }

    private String detectOsuArch(PatchTarget pt) {
        if (pt == null || pt.apkDir == null) return "unknown";
        
        String so = runRoot("find '" + escQ(pt.apkDir) + "' -name '*.so' 2>/dev/null | head -10").trim();
        if (so.contains("arm64")) return "arm64";
        if (so.contains("armeabi-v7a") || so.contains("armeabi")) return "arm32";
        
        return "unknown";
    }

    private PatchTarget findPatchTarget() {
        String apkDir = null;
        String detectedPkg = null;

        // [1] pm path
        log("[Поиск 1] pm path...");
        for (String pkg : OSU_PACKAGES) {
            String out = runRoot("pm path " + pkg + " 2>/dev/null").trim();
            String path = firstApkPath(out);
            if (!path.isEmpty()) {
                apkDir = dirOf(path);
                detectedPkg = pkg;
                log("✓ [1] " + pkg + " -> " + apkDir);
                break;
            }
        }

        // [2] pm list packages -3
        if (apkDir == null) {
            log("[Поиск 2] pm list packages -3...");
            String all = runRoot("pm list packages -3 2>/dev/null");
            for (String line : all.split("\n")) {
                line = line.trim();
                if (!line.startsWith("package:")) continue;
                for (String p : OSU_PACKAGES) {
                    if (line.toLowerCase().contains(p.toLowerCase())) {
                        String out = runRoot("pm path " + p + " 2>/dev/null").trim();
                        String path = firstApkPath(out);
                        if (!path.isEmpty()) {
                            apkDir = dirOf(path);
                            detectedPkg = p;
                            log("✓ [2] " + p + " -> " + apkDir);
                            break;
                        }
                    }
                }
                if (apkDir != null) break;
            }
        }

        // [3] find /data/app
        if (apkDir == null) {
            log("[Поиск 3] find /data/app...");
            String[] frags = {"osulazer", "ppy.osu", "ppy", "rimu", "osu", "mathiessen", "reco1l", "beatmap", "cephasun"};
            for (String frag : frags) {
                String found = runRoot(
                    "find /data/app -maxdepth 3 -name 'base.apk' 2>/dev/null | grep -i '" + frag + "' | head -3").trim();
                if (!found.isEmpty()) {
                    apkDir = dirOf(found.split("\n")[0].trim());
                    detectedPkg = "sh.ppy." + frag;
                    log("✓ [3] " + frag + " -> " + apkDir);
                    break;
                }
            }
        }

        // [4] find по SEARCH_ROOTS
        if (apkDir == null) {
            log("[Поиск 4] find SEARCH_ROOTS...");
            for (String root : SEARCH_ROOTS) {
                String found = runRoot(
                    "find " + root + " -maxdepth 5 -name '*.apk' 2>/dev/null | grep -iE 'osu|ppy|rimu' | head -3").trim();
                if (!found.isEmpty()) {
                    apkDir = dirOf(found.split("\n")[0].trim());
                    detectedPkg = "osu! (" + root + ")";
                    log("✓ [4] -> " + apkDir);
                    break;
                }
            }
        }

        // [5] dumpsys
        if (apkDir == null) {
            log("[Поиск 5] dumpsys...");
            for (String pkg : OSU_PACKAGES) {
                String dump = runRoot("dumpsys package " + pkg + " 2>/dev/null | grep codePath | head -3").trim();
                for (String ln : dump.split("\n")) {
                    int eq = ln.indexOf('=');
                    if (eq >= 0) {
                        String p = ln.substring(eq + 1).trim();
                        if (!p.isEmpty()) {
                            apkDir = p;
                            detectedPkg = pkg;
                            log("✓ [5] " + pkg + " -> " + apkDir);
                            break;
                        }
                    }
                }
                if (apkDir != null) break;
            }
        }

        // [6] /proc/mounts
        if (apkDir == null) {
            log("[Поиск 6] /proc/mounts...");
            String mounts = runRoot("grep -iE 'osu|ppy|rimu' /proc/mounts 2>/dev/null | head -5").trim();
            for (String ln : mounts.split("\n")) {
                String[] p = ln.split("\\s+");
                if (p.length > 1 && (p[1].contains("apk") || p[1].contains("oat"))) {
                    apkDir = dirOf(p[1]);
                    detectedPkg = "osu! (mounts)";
                    log("✓ [6] -> " + apkDir);
                    break;
                }
            }
        }

        // [7] find .so
        if (apkDir == null) {
            log("[Поиск 7] so...");
            String found = runRoot("find /data -maxdepth 10 -name 'libosu*.so' 2>/dev/null | head -3").trim();
            if (found.isEmpty()) {
                found = runRoot("find /data -maxdepth 10 -name 'libil2cpp.so' 2>/dev/null | grep -iE 'osu|ppy' | head -3").trim();
            }
            if (!found.isEmpty()) {
                apkDir = dirOf(found.split("\n")[0].trim());
                detectedPkg = "osu! (so)";
                log("✓ [7] -> " + apkDir);
            }
        }

        // [8] ls /data/data/
        if (apkDir == null) {
            log("[Поиск 8] ls /data/data...");
            String dirs = runRoot("ls /data/data/ 2>/dev/null").trim();
            for (String dir : dirs.split("\n")) {
                for (String p : OSU_PACKAGES) {
                    if (dir.replace("sh.", "").replace("com.", "").contains(p.replace("sh.", "").replace("com.", ""))) {
                        String apks = runRoot("ls /data/data/" + dir + "/base.apk 2>/dev/null").trim();
                        if (!apks.isEmpty()) {
                            apkDir = "/data/data/" + dir;
                            detectedPkg = dir;
                            log("✓ [8] " + dir);
                            break;
                        }
                    }
                }
                if (apkDir != null) break;
            }
        }

        if (apkDir == null) {
            log("✗ osu! не найден ВСЕМИ методами!");
            log("-> Покажи содержимое /data/app:");
            log(runRoot("ls /data/app/ 2>/dev/null").trim());
            return null;
        }

        PatchTarget pt = new PatchTarget();
        pt.packageName = detectedPkg != null ? detectedPkg : "unknown";
        pt.apkDir = apkDir;
        
        pt.targetArch = detectOsuArch(pt);
        log("-> osu! архитектура: " + pt.targetArch);

        String baseName = (pickedFileName != null && !pickedFileName.isEmpty())
                ? stripExt(pickedFileName) : "Assembly-CSharp";

        // Приоритет 1: AOT .so
        log("-> Ищу AOT .so: " + baseName);
        for (String nm : new String[]{
                "libaot-" + baseName + ".dll.so",
                "libaot-" + baseName + ".so",
                baseName + ".dll.so"}) {
            String f = runRoot("find '" + escQ(apkDir) + "' -name '" + nm + "' 2>/dev/null").trim();
            if (f.isEmpty()) {
                f = runRoot("find '" + escQ(dirOf(apkDir)) + "' -name '" + nm + "' 2>/dev/null | head -5").trim();
            }
            if (!f.isEmpty()) {
                String best = pickBestArch(f);
                pt.mountTarget = best;
                pt.isBlobMode = false;
                log("-> Цель AOT SO: " + best);
                return pt;
            }
        }

        // Приоритет 2: blob - ВО ВСЕХ папках
        log("-> Ищу blob ВО ВСЕХ папках...");
        String blobs = runRoot("find '" + escQ(apkDir) + "' -name '*.blob' 2>/dev/null").trim();
        if (blobs.isEmpty()) {
            blobs = runRoot("find '" + escQ(dirOf(apkDir)) + "' -name '*.blob' 2>/dev/null | head -10").trim();
        }
        if (blobs.isEmpty() && detectedPkg != null) {
            blobs = runRoot("find /data/data/" + detectedPkg + " -name '*.blob' 2>/dev/null | head -5").trim();
        }
        if (blobs.isEmpty()) {
            blobs = runRoot("find '" + escQ(apkDir) + "' -name '*assemblies*.blob' 2>/dev/null").trim();
        }
        if (blobs.isEmpty()) {
            blobs = runRoot("find /data -maxdepth 8 -name '*.blob' 2>/dev/null | grep -iE 'osu|assemblies' | head -10").trim();
        }
        
        if (!blobs.isEmpty()) {
            String best = pickBestBlob(blobs);
            pt.blobPath = best;
            pt.mountTarget = best;
            pt.isBlobMode = true;
            log("-> Цель BLOB: " + best);
            return pt;
        }

        // Приоритет 3: .dll
        log("-> Ищу .dll: " + baseName + ".dll");
        String f = runRoot("find '" + escQ(apkDir) + "' -name '" + baseName + ".dll' 2>/dev/null | head -5").trim();
        if (f.isEmpty()) {
            f = runRoot("find '" + escQ(dirOf(apkDir)) + "' -name '" + baseName + ".dll' 2>/dev/null | head -5").trim();
        }
        if (!f.isEmpty()) {
            pt.mountTarget = f.split("\n")[0].trim();
            pt.isBlobMode = false;
            log("-> Цель DLL: " + pt.mountTarget);
            return pt;
        }

        // Приоритет 4: fuzzy
        String q = baseName != null ? baseName.toLowerCase() : "game";
        f = runRoot("find '" + escQ(apkDir) + "' -name '*.so' 2>/dev/null | grep -i '" + q + "' | head -3").trim();
        if (!f.isEmpty()) {
            pt.mountTarget = pickBestArch(f);
            pt.isBlobMode = false;
            log("-> Цель SO (fuzzy): " + pt.mountTarget);
            return pt;
        }

        // Показываем что есть в папке
        log("✗ НО! Файлы в apkDir:");
        String all = runRoot("find '" + escQ(apkDir) + "' -type f 2>/dev/null | head -40").trim();
        log(all.isEmpty() ? "(пусто)" : all);
        
        // Последняя попытка
        log("-> Ищу любые .so...");
        f = runRoot("find '" + escQ(apkDir) + "' -name '*.so' 2>/dev/null | head -20").trim();
        if (!f.isEmpty()) {
            pt.mountTarget = pickBestArch(f);
            pt.isBlobMode = false;
            log("-> Цель - первый .so: " + pt.mountTarget);
            return pt;
        }
        
        return null;
    }

    private String pickBestArch(String multiline) {
        String[] lines = multiline.split("\n");
        for (String arch : new String[]{"arm64-v8a", "arm64", "armeabi-v7a", "arm", "x86_64", "x86"}) {
            for (String ln : lines) {
                if (ln.trim().contains(arch)) return ln.trim();
            }
        }
        return lines[0].trim();
    }

    private String pickBestBlob(String multiline) {
        String[] lines = multiline.split("\n");
        for (String ln : lines) {
            ln = ln.trim();
            if (ln.contains("arm64") && ln.contains("assemblies")) return ln;
        }
        for (String ln : lines) {
            ln = ln.trim();
            if (ln.contains("arm64")) return ln;
        }
        for (String ln : lines) {
            ln = ln.trim();
            if (ln.contains("assemblies")) return ln;
        }
        return lines[0].trim();
    }

    private String firstApkPath(String pmOut) {
        if (pmOut == null || pmOut.isEmpty()) return "";
        for (String line : pmOut.split("\n")) {
            line = line.trim();
            if (!line.startsWith("package:")) continue;
            String p = line.substring("package:").length().trim();
            if (p.contains("base")) return p;
        }
        return "";
    }

    private String dirOf(String path) {
        if (path == null || path.isEmpty()) return path;
        int idx = path.lastIndexOf('/');
        return idx > 0 ? path.substring(0, idx) : path;
    }

    private boolean isMounted(String path) {
        return checkMountReal(path);
    }

    private void success(PatchTarget pt, String srcFile) {
        log("✓ Патч применён!");
        log("  источник: " + srcFile);
        log("  цель: " + pt.mountTarget);
        log("⚠ Перезапусти osu! для загрузки патча");
        
        String hash = runRoot("md5sum '" + escQ(pt.mountTarget) + "' 2>/dev/null").trim();
        if (!hash.isEmpty()) {
            log("  hash: " + hash.split("\\s+")[0]);
        }
        
        mainHandler.post(() -> setStatus("PATCHED ✓", "#4CAF50"));
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

    private void fail() {
        mainHandler.post(() -> {
            setStatus("Ошибка", "#F44336");
            btnApply.setEnabled(true);
        });
    }
}