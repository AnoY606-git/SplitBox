// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.installer

/** Нулевой код завершения без явного подтверждения Package Manager не считается успехом. */
data class PmResult(val exitCode: Int, val output: String) {
    val success: Boolean get() = exitCode == 0 &&
        !Regex("(?im)\\b(?:Failure|Error|Exception)\\b").containsMatchIn(output) &&
        Regex("(?m)^Success(?:$|:|\\s)").containsMatchIn(output.trim())
    fun requireSuccess(): PmResult = apply {
        check(success) { "Команда Package Manager не завершилась успешно (код $exitCode): " + output.trim().ifEmpty { "ответ отсутствует" } }
    }
}
object PmProtocol {
    fun create(userId: Int, size: Long, downgrade: Boolean): String {
        require(userId >= 0 && size > 0) { "Некорректный пользователь Android или размер пакета" }
        return "pm install-create --user $userId -r${if (downgrade) " -d" else ""} -S $size"
    }
    fun write(sessionId: Int, index: Int, size: Long): String {
        require(sessionId > 0 && index >= 0 && size > 0) { "Некорректный идентификатор сессии, номер части или размер APK" }
        return "pm install-write -S $size $sessionId 'split_$index.apk' -"
    }
    fun commit(sessionId: Int): String { require(sessionId > 0) { "Некорректный идентификатор сессии установки" }; return "pm install-commit $sessionId" }
    fun abandon(sessionId: Int): String { require(sessionId > 0) { "Некорректный идентификатор сессии установки" }; return "pm install-abandon $sessionId" }
    fun sessionId(result: PmResult): Int {
        result.requireSuccess()
        val match = Regex("^Success: created install session \\[(\\d+)]$").matchEntire(result.output.trim())
            ?: error("Package Manager не вернул корректный идентификатор сессии: ${result.output.take(500)}")
        return match.groupValues[1].toIntOrNull()?.takeIf { it > 0 }
            ?: error("Некорректный идентификатор сессии")
    }
}
object ObbPolicy {
    fun destination(userId: Int, packageName: String, version: Long, basename: String): String {
        require(userId >= 0 && version >= 0) { "Некорректный пользователь Android или версия расширения OBB" }
        require(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+").matches(packageName)) { "Некорректное имя пакета для OBB" }
        val match = Regex("(?:main|patch)\\.([0-9]+)\\.${Regex.escape(packageName)}\\.obb").matchEntire(basename)
        require(match != null && match.groupValues[1].toLongOrNull() != null) {
            "Имя OBB должно содержать числовую версию расширения и точное имя пакета APK"
        }
        return "/storage/emulated/$userId/Android/obb/$packageName/$basename"
    }
    /** Все пути уже проверены destination(); пути из архива не попадают в командную оболочку. */
    fun copyCommand(userId: Int, packageName: String, version: Long, basename: String, size: Long): String {
        require(size > 0) { "Размер файла OBB должен быть положительным" }
        val path = destination(userId, packageName, version, basename)
        val dir = path.substringBeforeLast('/')
        val root = dir.substringBeforeLast('/')
        val temporary = "$dir/.splitbox-${java.util.UUID.randomUUID()}.tmp"
        // Отменённая или неполная передача не должна перезаписывать существующий файл расширения.
        return "trap \"rm -f '$temporary'\" EXIT; mkdir -p '$root' && [ ! -L '$dir' ] && mkdir -p '$dir' && " +
            "[ \"\$(readlink -f '$dir')\" = \"\$(readlink -f '$root')/$packageName\" ] && " +
            "[ ! -L '$path' ] && [ ! -d '$path' ] && ( set -C; cat > '$temporary' ) && " +
            "[ \"\$(wc -c < '$temporary')\" -eq $size ] && chmod 644 '$temporary' && mv -fT '$temporary' '$path' && echo Success"
    }
}
