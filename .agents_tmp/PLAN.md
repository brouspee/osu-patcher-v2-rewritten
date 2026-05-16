# 1. OBJECTIVE
Переписать osu! patcher для корректной работы на Android 13-15 с настоящей верификацией патча, исправить проблему с blob патчингом (метод applyBlobPatch отсутствует), и добавить проверку загрузки библиотек в память процесса.

# 2. CONTEXT SUMMARY
Текущий код имеет несколько критических проблем:
- **Метод applyBlobPatch() отсутствует** - поэтому blob режим не работает и пишет "osu не найден"
- Успех пишется после выполнения shell команды mount, а не после реальной верификации загрузки
- Нет проверки /proc/<pid>/maps для подтверждения что DLL загружена
- Нет проверки architecture совместимости (arm64 vs armeabi-v7a)
- Нет валидации версии osu с патчем
- Mount namespace не обрабатывается корректно
- Blob патчинг не пересчитывает offsets и metadata
- gdb/dlinject inject методы сломаны на Android 13+

Файлы проекта:
- `/workspace/project/osu-patcher-v2-rewritten/app/src/main/java/com/osupatch/MainActivity.java` - основной код патчера

# 3. APPROACH OVERVIEW
Выбрана стратегия полного переписывания логики патчинга:
1. Добавить метод applyBlobPatch() для патчинга blob файлов
2. Реализовать верификацию через /proc/<pid>/maps после mount/inject
3. Добавить проверку architecture перед патчем
4. Добавить version matching (сравнивать версии osu и патча)
5. Реализовать bind mount + dlopen гибридный подход
6. Использовать Magisk/Hide для обхода SELinux
7. Переписать verification layer для реальной проверки

# 4. IMPLEMENTATION STEPS

## Шаг 1: Исправить структуру класса и дублирование методов
**Goal:** Убрать дублирование onDestroy и checkSystemCapabilities, исправить структуру
**Method:** 
- Удалить дублирующиеся определения методов из файла
- Добавить правильный порядок методов

## Шаг 2: Добавить缺失 applyBlobPatch() метод
**Goal:** Реализовать возможность патчинга blob файлов
**Method:**
- Добавить метод applyBlobPatch() который:
  1. Читает blob header, получает offsets и sizes всех entry
  2. Находит entry по имени DLL (из pickedFileName)
  3. Проверяет размер - не записывать если больше оригинального слота
  4. Пересчитывает metadata и checksums после замены
  5. Верифицирует blob после записи

## Шаг 3: Добавить полную проверку System Capabilities
**Goal:** Реально проверять selinux, root, ptrace, gdb availability
**Method:**
```java
private void checkSystemCapabilities() {
    // SELinux - проверять status и mode
    String selinuxStatus = runRoot("getenforce 2>/dev/null").trim();
    if (selinuxStatus.isEmpty()) {
        selinuxStatus = runRoot("cat /sys/fs/selinux/enforce 2>/dev/null").trim();
    }
    
    // ptrace_scope реальное значение
    String ptraceScope = runRoot("cat /proc/sys/kernel/yama/ptrace_scope 2>/dev/null").trim();
    
    // gdb и его возможность работать
    String gdbAvail = runRoot("gdb --version 2>/dev/null").trim();
    
    // Проверить /dev/null для ptrace
    String ptraceTest = runRoot("echo 123 | gdb -q -ex 'p 123' 2>&1").trim();
}
```

## Шаг 4: Добавить Architecture проверку
**Goal:** Не патчить если architecture не совпадает
**Method:**
```java
private boolean checkArchitectureMatch() {
    // Определить архитектуру выбранного файла
    String fileArch = detectFileArchitecture(pickedFile);
    
    // Определить архитектуру osu
    String osuArch = detectOsuArchitecture();
    
    // Проверить совместимость
    if (!isCompatible(fileArch, osuArch)) {
        log("✗ Architecture mismatch!");
        log("  File: " + fileArch + ", osu: " + osuArch);
        return false;
    }
    return true;
}
```

## Шаг 5: Добавить Version Matching
**Goal:** Не патчить если версии не совпадают
**Method:**
```java
private boolean checkVersionMatch() {
    // Читать version из metadata патча
    String patchVersion = getPatchVersion(pickedFile);
    
    // Получать версию osu из package
    String osuVersion = detectedVersion;
    
    // Сравнивать (поддерживать semver и диапазоны)
    if (!isVersionCompatible(patchVersion, osuVersion)) {
        log("✗ Version mismatch!");
        log("  Patch: " + patchVersion + ", osu: " + osuVersion);
        return false;
    }
    return true;
}
```

## Шаг 6: Переписать applyPatch() с верификацией
**Goal:** Писать success только после реальной загрузки
**Method:**
```java
private void applyPatch() {
    // 1. Pre-patch checks
    if (!checkArchitectureMatch()) { fail(); return; }
    if (!checkVersionMatch()) { fail(); return; }
    if (!checkBlobSize()) { fail(); return; }
    
    // 2. Create backup
    createFullBackup();
    
    // 3. Apply patch based on targetType
    boolean patchApplied = false;
    switch (targetType) {
        case "blob": patchApplied = applyBlobPatch(); break;
        case "so": patchApplied = applySoPatch(); break;
        case "dll": patchApplied = applyDllPatch(); break;
    }
    
    if (!patchApplied) {
        rollback();
        fail();
        return;
    }
    
    // 4. Clear cache
    clearOsuCache();
    
    // 5. Restart osu
    restartOsu();
    
    // 6. REAL verification - wait for process and check maps
    if (!verifyPatchLoaded()) {
        log("✗ Patch NOT loaded - writing failure!");
        rollback();
        fail();
        return;
    }
    
    success(); // Only now
}
```

## Шаг 7: Реализовать verifyPatchLoaded()
**Goal:** Реально проверять что патч загружен в память процесса
**Method:**
```java
private boolean verifyPatchLoaded() {
    // Wait for osu to start (or use existing process)
    int pid = getOsuPid();
    if (pid <= 0) {
        log("⚠ osu not running, starting...");
        runRoot("monkey -c " + detectedPackageName);
        Thread.sleep(3000);
        pid = getOsuPid();
    }
    
    if (pid <= 0) {
        log("✗ Cannot get osu PID");
        return false;
    }
    
    // Read /proc/<pid>/maps
    String maps = runRoot("cat /proc/" + pid + "/maps 2>/dev/null");
    
    // Check for patched library path
    String patchedPath = getPatchedFilePath();
    if (maps.contains(patchedPath)) {
        log("✓ Patched DLL in memory: " + patchedPath);
        return true;
    }
    
    // For blob - check assembly DLL name
    String dllName = getDllNameFromPatch();
    if (maps.contains(dllName)) {
        log("✓ Patched assembly loaded: " + dllName);
        return true;
    }
    
    // Log what's actually loaded
    log("✗ Patched DLL NOT in memory!");
    log("  Looking for: " + patchedPath);
    log("  Maps contains: " + (maps.isEmpty() ? "empty" : "some files"));
    return false;
}
```

## Шаг 8: Исправить findTargetFiles() для blob
**Goal:** Корректно искать blob файлы во всех locations
**Method:**
```java
private void findTargetFiles() {
    // Split APKs - искать в lib/
    // Data directories - искать в /data/data/, /data/app/
    // Virtual space - проверять 所有 возможные locations
    
    // Искать blob с правильным именем:
    // 1. Проверить assemblies/*.blob
    // 2. Проверить assets/*.blob
    // 3. Проверить lib/*/assemblies.blob
}
```

## Шаг 9: Добавить проверку размера для blob
**Goal:** Не записывать если DLL больше оригинального слота
**Method:**
```java
private boolean checkBlobSize() {
    if (!targetType.equals("blob")) return true;
    
    long originalSize = getBlobEntryOriginalSize(blobFile, dllName);
    long newSize = pickedFile.length();
    
    if (newSize > originalSize) {
        log("✗ New DLL too large!");
        log("  Original: " + originalSize + " bytes");
        log("  New: " + newSize + " bytes");
        return false;
    }
    return true;
}
```

## Шаг 10: Переписать rollback
**Goal:** Полноценный откат с очисткой
**Method:**
```java
private void rollback() {
    log("═══ ROLLBACK ═══");
    
    // Unmount all
    runRoot("umount '" + patchedPath + "' 2>/dev/null");
    
    // Restore from backup
    restoreFromBackup();
    
    // Clear cache again
    clearOsuCache();
    
    log("✓ Rollback complete");
}
```

# 5. TESTING AND VALIDATION
Верификация должна включать:
1. **Pre-patch checks:**
   - ✓ SELinux status проверен
   - ✓ Root access подтвержден
   - ✓ ptrace scope получен
   - ✓ Architecture совпадает
   - ✓ Version совпадает
   - ✓ Size fits в blob slot

2. **Apply checks:**
   - ✓ Backup создан
   - ✓ Patch записан
   - ✓ Blob verified (magic + checksums)
   - ✓ Hash оригинального сохранен

3. **Post-patch verification - САМОЕ ВАЖНОЕ:**
   - ✓ osu запущен (или запущен)
   - ✓ PID получен
   - ✓ /proc/<pid>/maps читается
   - ✓ **Patched library PATH найден в maps** (main verification!)
   - ✓ DLL name найден в maps
   - ✓ osu не крашнулся

4. **Success condition:**
   - Метод `success()` вызывается ТОЛЬКО если verifyPatchLoaded() вернул true
   - Это гарантирует что игра реально использует patched assembly
