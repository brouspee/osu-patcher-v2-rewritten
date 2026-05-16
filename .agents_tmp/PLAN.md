# 1. OBJECTIVE
Переписать osu! patcher для корректной работы на Android 13-15, исправить метод injectLegitCheat() на реальную инжекцию C++ библиотеки хуков, добавить верификацию через /proc/pid/maps, и реализовать полноценную систему патчинга с UI настройками чита.

# 2. CONTEXT SUMMARY
Текущий код имеет критические проблемы:
- **injectLegitCheat() содержит псевдо-код** - Frida скрипты которые не выполнятся
- **Метод applyBlobPatch() отсутствует** - blob режим не работает и пишет "osu не найден"
- Успех пишется после shell команды mount, а не после верификации загрузки
- Нет проверки /proc/pid/maps для подтверждения что DLL загружена
- Нет проверки architecture совместимости
- Нет валидации версии osu с патчем
- Mount namespace не обрабатывается корректно
- gdb/dlinject inject методы сломаны на Android 13+
- Нет UI для настроек чита

Файлы проекта:
- `/workspace/project/osu-patcher-v2-rewritten/app/src/main/java/com/osupatch/MainActivity.java` - основной код патчера

# 3. APPROACH OVERVIEW
Выбрана стратегия полного переписывания по 7 фазам:

**Фаза 1 - Ядро инжекции (критично):**
1.1 Заменить псевдо-код в injectLegitCheat() на реальную dlopen инжекцию
1.2 Создать libcheat.cpp с реальными хуками
1.3 Добавить паттерн-скан для osu!lazer функций

**Фаза 2 - Mount система:**
2.1 Поддержка overlayfs (Android 10+)
2.2 Автоматический remount rw
2.3 Проверка успешности mount через inode

**Фаза 3 - Обход защит:**
3.1 Детект обфускации
3.2 Обход ptrace_scope
3.3 Обход SELinux для инжекции

**Фаза 4 - Версии osu!:**
4.1 Автоопределение версии и смещений
4.2 Поддержка обновлений через интернет

**Фаза 5 - UI настройки:**
5.1 SeekBar для AIM_ASSIST_STRENGTH
5.2 Switch для AUTO_TAP
5.3 Сохранение настроек в SharedPreferences

**Фаза 6 - Обработка ошибок:**
6.1 Полный лог с таймстемпом
6.2 Проверка каждого шага с откатом

# 4. IMPLEMENTATION STEPS

## Шаг 1: Исправить структуру класса и дублирование методов
**Goal:** Убрать дублирование onDestroy и checkSystemCapabilities, исправить структуру
**Method:** 
- Удалить дублирующиеся определения методов
- Добавить правильный порядок методов

## Шаг 2: Переписать injectLegitCheat() на реальную инжекцию
**Goal:** Заменить псевдо-код на реальную dlopen инжекцию
**Method:**
```java
private void injectLegitCheat() {
    log("═══ INJECT CHEAT ═══");
    
    // 1. Извлечь библиотеку из assets
    String soPath = getCacheDir() + "/libcheat.so";
    extractCheatSo(soPath);
    
    // 2. Получить PID osu
    int pid = getOsuPid();
    if (pid <= 0) {
        log("⚠ osu not running, starting...");
        runRoot("monkey -c " + detectedPackageName);
        Thread.sleep(3000);
        pid = getOsuPid();
    }
    
    if (pid <= 0) {
        log("✗ Cannot get osu PID");
        return;
    }
    
    // 3. Проверить SELinux и permissiveness
    if (!isSelinuxPermissive()) {
        log("⚠ SELinux enforcing - trying to bypass...");
        setSelinuxPermissive();
    }
    
    // 4. Реальная инжекция через remote dlopen
    boolean injected = injectViaDlopen(pid, soPath);
    
    if (injected) {
        // 5. Верифицировать загрузку
        if (verifyLibraryLoaded(pid, soPath)) {
            log("✓ Cheat library loaded!");
            applyCheatSettings(pid);
        } else {
            log("✗ Library NOT loaded in memory!");
        }
    }
}

private boolean injectViaDlopen(int pid, String soPath) {
    // Метод 1: через remote thread
    String cmd1 = "su -c 'nsenter -t " + pid + " -m -- /system/bin/linker64 --library-path /data/app/" + 
                detectedPackageName + "/lib/arm64 " + soPath + "'";
    
    // Метод 2: через Magisk spawn
    String cmd2 = "magisk su -c 'DEXP=/data/data/" + detectedPackageName + 
                  "; cp " + soPath + " $DEXP/lib/libcheat.so; " +
                  "$DEXP/lib/libil2cpp.so --load $DEXP/lib/libcheat.so'";
    
    // Метод 3: через Zygisk module
    String cmd3 = createZygiskModule(soPath);
    
    return runRoot(cmd1).contains("loaded") || runRoot(cmd2).contains("loaded");
}
```

## Шаг 3: Создать C++ библиотеку хуков (libcheat.cpp)
**Goal:** Реальная библиотека с хуками для osu!lazer
**Method:**
- Создать jni/cheat/libcheat.cpp с реальными хуками
- Добавить паттерн-скан для поиска функций
- Добавить вычисление ближайшего HitObject
- Добавить Android.mk для сборки

```cpp
#include <jni.h>
#include <substrate.h>
#include <android/log.h>

// Настройки чита
float AIM_ASSIST_STRENGTH = 0.85f;
bool AUTO_TAP = false;
float AUTO_TAP_PERCENT = 100.0f;

// Оригинальные функции
void (*original_HandleTouch)(void* instance, void* touchEvent);

// Хук HandleTouch
void new_HandleTouch(void* instance, void* touchEvent) {
    // Чтение координат
    float* x = (float*)((char*)touchEvent + 0x8);
    float* y = (float*)((char*)touchEvent + 0xC);
    
    // Поиск ближайшего круга
    float targetX, targetY;
    if (findNearestHitObject(*x, *y, &targetX, &targetY)) {
        // AIM ASSIST - магнит
        *x = *x + (targetX - *x) * AIM_ASSIST_STRENGTH;
        *y = *y + (targetY - *y) * AIM_ASSIST_STRENGTH;
        
        // Тремор
        *x += (rand() % 4 - 2);
        *y += (rand() % 4 - 2);
    }
    
    // AUTO TAP
    if (AUTO_TAP && shouldAutoTap(*x, *y)) {
        // auto tap логика
    }
    
    original_HandleTouch(instance, touchEvent);
}

// JNI_OnLoad - инициализация хуков
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    // Паттерн-скан для поиска osu!Game::HandleTouch
    void* handleTouch = findPattern("FF 15 ?? ?? ?? ?? 48 8B F8 48 85 C0");
    if (handleTouch) {
        MSHookFunction(handleTouch, (void*)new_HandleTouch, (void**)&original_HandleTouch);
        __android_log_print(ANDROID_LOG_INFO, "cheat", "Hooked HandleTouch at %p", handleTouch);
    }
    return JNI_VERSION_1_6;
}
```

## Шаг 4: Добавить паттерн-скан для osu!lazer
**Goal:** Находить адреса функций в памяти процесса
**Method:**
```java
private long findFunctionAddress(String pattern, int pid) {
    // Читать /proc/pid/maps
    String maps = runRoot("cat /proc/" + pid + "/maps");
    
    // Для каждого loaded .so:
    // - Просканировать memory на предмет сигнатуры
    // - Вернуть адрес если найдено
    
    // Паттерны для разных версий osu!
    String[] patterns = {
        "FF 15 ?? ?? ?? ?? 48 8B F8",  // HandleTouch v1
        "48 8B ?? ?? ?? ?? ?? 00 48",   // HandleTouch v2  
        "48 83 EC ?? 48 89 ?? ?? 48"   // HandleTouch v3
    };
}
```

## Шаг 5: Улучшить mount систему
**Goal:** Поддержка overlayfs и проверка через inode
**Method:**
```java
private boolean mountOverlay(String src, String dst) {
    log("═══ OVERLAY MOUNT ═══");
    
    // Android 10+ использует overlayfs
    String overlayDir = getCacheDir() + "/overlay";
    runRoot("mkdir -p " + overlayDir + "/upper " + overlayDir + "/work");
    
    String cmd = "mount -t overlay overlay -o lowerdir=" + dst + 
                 ",upperdir=" + overlayDir + "/upper" + 
                 ",workdir=" + overlayDir + "/work " + dst;
    
    return runRoot(cmd).isEmpty();
}

private boolean isMounted(String path) {
    // Проверка через inode - если inode изменился, значит mount удался
    String inode = runRoot("stat -c %i " + path).trim();
    String origInode = getOriginalInode(path);
    return !inode.equals(origInode);
}
```

## Шаг 6: Добавить обход защит
**Goal:** Обходить SELinux, ptrace_scope, обфускацию
**Method:**
```java
private void setSelinuxPermissive() {
    // Magisk policy bypass
    runRoot("magiskpolicy --live 'allow * * * *'");
    runRoot("supolicy --live");
    runRoot("setenforce 0 2>/dev/null");
}

private void disablePtraceScope() {
    runRoot("echo 0 > /proc/sys/kernel/yama/ptrace_scope");
}

private void detectObfuscation() {
    String symbols = runRoot("nm -D " + libPath + " | wc -l");
    if (Integer.parseInt(symbols) < 100) {
        log("⚠ Обфускация обнаружена!");
    }
}
```

## Шаг 7: Поддержка разных версий osu!
**Goal:** Автоопределение версии и загрузка правильных offsets
**Method:**
```java
private class OsuVersionOffsets {
    String version;
    long handleTouchOffset;
    long updateOffset;
    long getHitObjectsOffset;
}

private OsuVersionOffsets detectAndLoadOffsets() {
    // 1. Получить versionName из PackageInfo
    String version = detectedVersion;
    
    // 2. Попробовать локальные offsets
    OsuVersionOffsets local = loadLocalOffsets(version);
    if (local != null) return local;
    
    // 3. Скачать с сервера
    return downloadOffsets(version);
}

private OsuVersionOffsets downloadOffsets(String version) {
    String url = "https://your-server.com/osu/offsets/" + version + ".json";
    // Download и парсинг
}
```

## Шаг 8: Добавить UI настройки чита
**Goal:** Добавить settings Activity с SeekBar и Switch
**Method:**
- Создать activity_settings.xml:
```xml
<SeekBar android:id="@+id/sb_aim_assist"
    android:max="100" android:progress="85"/>

<Switch android:id="@+id/sw_auto_tap"/>

<SeekBar android:id="@+id/sb_auto_tap_percent"
    android:max="100" android:progress="100"/>

<Button android:id="@+id/btn_test"/>
```

- Добавить сохранение в SharedPreferences:
```java
private SharedPreferences prefs;

prefs = getSharedPreferences("cheat_config", MODE_PRIVATE);
float aimAssist = prefs.getFloat("aim_assist", 0.85f);
boolean autoTap = prefs.getBoolean("auto_tap", false);
```

## Шаг 9: Добавить метод applyBlobPatch()
**Goal:** Реализовать патчинг blob файлов
**Method:**
```java
private boolean applyBlobPatch() {
    log("═══ BLOB PATCH ═══");
    
    // 1. Найти blob файл
    String blobPath = findBlobFile();
    if (blobPath == null) {
        log("✗ Blob not found!");
        return false;
    }
    
    // 2. Прочитать header, получить offsets
    BlobHeader header = readBlobHeader(blobPath);
    
    // 3. Найти entry для DLL
    int entryIndex = findBlobEntry(blobPath, dllName);
    if (entryIndex < 0) {
        log("✗ DLL entry not found in blob!");
        return false;
    }
    
    // 4. Проверить размер
    long originalSize = header.entries[entryIndex].size;
    long newSize = pickedFile.length();
    
    if (newSize > originalSize) {
        log("✗ New DLL too large!");
        return false;
    }
    
    // 5. Заменить данные
    boolean success = replaceBlobEntry(blobPath, entryIndex, pickedFile);
    
    // 6. Верифицировать
    if (success && verifyBlob(blobPath)) {
        log("✓ Blob patched!");
        return true;
    }
    
    return false;
}
```

## Шаг 10: Реализовать verifyPatchLoaded() через /proc/pid/maps
**Goal:** Реально проверять что патч загружен в память процесса
**Method:**
```java
private boolean verifyPatchLoaded(int pid, String patchPath) {
    // Читать maps
    String maps = runRoot("cat /proc/" + pid + "/maps").trim();
    
    // Проверить путь к patched library
    if (maps.contains(patchPath)) {
        log("✓ Patched library in memory: " + patchPath);
        return true;
    }
    
    // Для blob - проверить DLL name
    String dllName = new File(patchPath).getName();
    if (maps.contains(dllName)) {
        log("✓ Patched assembly loaded: " + dllName);
        return true;
    }
    
    log("✗ Patch NOT loaded in memory!");
    log("  Looking for: " + patchPath);
    return false;
}
```

## Шаг 11: Обработка ошибок и отладка
**Goal:** Полный лог с таймстемпом и откат при ошибках
**Method:**
```java
private void logWithTimestamp(String msg) {
    SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");
    String timestamp = sdf.format(new Date());
    log("[" + timestamp + "] " + msg);
    
    // Сохранить в файл
    try {
        File logFile = new File("/sdcard/osu_injector.log");
        FileWriter fw = new FileWriter(logFile, true);
        fw.write("[" + timestamp + "] " + msg + "\n");
        fw.close();
    } catch (Exception ignored) {}
}

private boolean safeStep(String name, Runnable step) {
    try {
        step.run();
        logWithTimestamp("✓ " + name);
        return true;
    } catch (Exception e) {
        logWithTimestamp("✗ " + name + ": " + e.getMessage());
        rollback();
        return false;
    }
}
```

# 5. TESTING AND VALIDATION
Верификация должна включать:

**Pre-patch checks:**
- ✓ SELinux status проверен
- ✓ Root access подтвержден
- ✓ ptrace scope получен
- ✓ Architecture совпадает
- ✓ Version совпадает
- ✓ Size fits в blob slot

**Apply checks:**
- ✓ Backup создан
- ✓ Patch записан
- ✓ Blob verified (magic + checksums)
- ✓ Hash оригинального сохранен
- ✓ Library извлечена из assets

**Post-patch verification:**
- ✓ osu запущен
- ✓ PID получен
- ✓ /proc/pid/maps читается
- ✓ **Patched library PATH найден в maps**
- ✓ DLL name найден в maps
- ✓ Cheat library загружена (dlopen verification)
- ✓ osu не крашнулся

**Success condition:**
- Метод success() вызывается ТОЛЬКО если verifyPatchLoaded() вернул true
