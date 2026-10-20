// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.data

import dev.splitbox.core.message
import dev.splitbox.core.nestedMessage

import dev.splitbox.app.model.OperationResult
import java.io.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

enum class PrivilegedBackend { ROOT, SHIZUKU }
enum class PrivilegedOperationPhase { CREATING, WRITING, COMMITTING, COMMITTED, FINISHED, UNKNOWN }

data class PrivilegedOperationRecord(
    val operationId: String,
    val backend: PrivilegedBackend,
    val userId: Int,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val appLabel: String,
    val sourceName: String,
    val createdAt: Long,
    val sessionId: Int? = null,
    val phase: PrivilegedOperationPhase = PrivilegedOperationPhase.CREATING,
    val success: Boolean? = null,
    val message: String? = null,
    val obbWarning: String? = null,
    val cleanAfter: Boolean = true,
    val saveHistory: Boolean = true,
    val workDir: String? = null
) {
    /** Восстановление использует только записанные доказательства; команды и повтор commit запрещены. */
    fun recoveryDecision(): PrivilegedRecoveryDecision = when (phase) {
        PrivilegedOperationPhase.COMMITTED -> PrivilegedRecoveryDecision.Known(OperationResult(true,
            message ?: message("privileged_operation_store_apk_installation_confirmed_by_package_manager", "Установка APK подтверждена Package Manager"), packageName,
            obbWarning ?: message("privileged_operation_store_apk_installed_completion_of_additional_actions_has", "APK установлен; завершение дополнительных действий не подтверждено.")))
        PrivilegedOperationPhase.FINISHED -> if (success != null) PrivilegedRecoveryDecision.Known(
            OperationResult(success, message ?: if (success) message("split_box_view_model_app_installed", "Приложение установлено") else message("privileged_operation_store_installation_failed", "Установка завершилась ошибкой"), packageName, obbWarning))
            else PrivilegedRecoveryDecision.Unknown
        else -> PrivilegedRecoveryDecision.Unknown
    }
}

sealed interface PrivilegedRecoveryDecision {
    data class Known(val result: OperationResult) : PrivilegedRecoveryDecision
    data object Unknown : PrivilegedRecoveryDecision
}

/** Все методы выполняют дисковый ввод-вывод; вызывающая сторона обязана использовать IO. */
interface PrivilegedOperationStore {
    @Throws(IOException::class) fun read(): PrivilegedOperationRecord?
    @Throws(IOException::class) fun write(record: PrivilegedOperationRecord)
    @Throws(IOException::class) fun clear(operationId: String)
}

/** Один ограниченный снимок: синхронизация файла перед атомарной заменой сохраняет предыдущую запись при сбое. */
class FilePrivilegedOperationStore(private val file: File) : PrivilegedOperationStore {
    @Synchronized override fun read(): PrivilegedOperationRecord? {
        if (!file.exists()) return null
        try {
            if (file.length() !in (HEADER_BYTES + DIGEST_BYTES).toLong()..MAX_FILE_BYTES.toLong()) throw IOException(message("privileged_operation_store_invalid_privileged_operation_record_size", "Недопустимый размер записи привилегированной операции"))
            return file.inputStream().buffered().use { input ->
                val data = DataInputStream(input)
                if (data.readInt() != MAGIC || data.readInt() != VERSION) throw IOException(message("privileged_operation_store_unknown_privileged_operation_record_format", "Неизвестный формат записи привилегированной операции"))
                val length = data.readInt()
                if (length !in 1..MAX_PAYLOAD_BYTES) throw IOException(message("privileged_operation_store_invalid_operation_data_size", "Недопустимый размер данных операции"))
                val payload = ByteArray(length).also(data::readFully)
                val digest = ByteArray(DIGEST_BYTES).also(data::readFully)
                if (data.read() != -1 || !MessageDigest.isEqual(digest, sha256(payload))) throw IOException(message("privileged_operation_store_corrupt_privileged_operation_record", "Повреждена запись привилегированной операции"))
                decode(payload).also(::validate)
            }
        } catch (e: IOException) { throw e }
        catch (e: Exception) { throw IOException(message("privileged_operation_store_corrupt_privileged_operation_record", "Повреждена запись привилегированной операции"), e) }
    }

    @Synchronized override fun write(record: PrivilegedOperationRecord) {
        val payload = try { validate(record); encode(record) }
        catch (e: IOException) { throw e }
        catch (e: Exception) { throw IOException(message("privileged_operation_store_invalid_privileged_operation_record", "Некорректная запись привилегированной операции"), e) }
        val parent = file.absoluteFile.parentFile ?: throw IOException(message("privileged_operation_store_the_operation_record_has_no_directory", "У записи операции нет каталога"))
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException(message("privileged_operation_store_could_not_create_the_operation_record_directory", "Не удалось создать каталог записи операции"))
        val temporary = File(parent, ".${file.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                val data = DataOutputStream(output)
                data.writeInt(MAGIC)
                data.writeInt(VERSION)
                data.writeInt(payload.size)
                data.write(payload)
                data.write(sha256(payload))
                data.flush()
                output.fd.sync()
            }
            // Временный файл находится в том же каталоге; неатомарного fallback нет.
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    @Synchronized override fun clear(operationId: String) {
        if (read()?.operationId == operationId && !file.delete()) throw IOException(message("privileged_operation_store_could_not_delete_the_privileged_operation_record", "Не удалось удалить запись привилегированной операции"))
    }

    private fun validate(record: PrivilegedOperationRecord) {
        require(UUID.fromString(record.operationId).toString() == record.operationId) { message("privileged_operation_store_invalid_operation_uuid", "Некорректный UUID операции") }
        require(record.userId >= 0 && record.versionCode >= 0 && record.createdAt >= 0) { message("privileged_operation_store_invalid_operation_data", "Некорректные данные операции") }
        require(record.sessionId == null || record.sessionId > 0) { message("privileged_operation_store_invalid_operation_session", "Некорректная сессия операции") }
        require(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+").matches(record.packageName)) { message("privileged_operation_store_invalid_operation_package", "Некорректный пакет операции") }
        require(record.phase != PrivilegedOperationPhase.FINISHED || record.success != null) { message("privileged_operation_store_no_confirmed_operation_result", "Нет подтверждённого результата операции") }
        require(record.phase != PrivilegedOperationPhase.COMMITTED || record.success != false) { message("privileged_operation_store_conflicting_commit_result", "Противоречивый результат commit") }
        require(record.success == null || record.phase in setOf(PrivilegedOperationPhase.COMMITTED, PrivilegedOperationPhase.FINISHED)) { message("privileged_operation_store_the_result_was_recorded_before_the_operation", "Результат записан до подтверждения операции") }
    }

    private fun encode(record: PrivilegedOperationRecord): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { data ->
            data.string(record.operationId); data.string(record.backend.name); data.writeInt(record.userId)
            data.string(record.packageName); data.string(record.versionName); data.writeLong(record.versionCode)
            data.string(record.appLabel); data.string(record.sourceName); data.writeLong(record.createdAt)
            data.writeInt(record.sessionId ?: -1); data.string(record.phase.name)
            data.writeByte(when (record.success) { null -> 0; true -> 1; false -> 2 })
            data.nullableString(record.message); data.nullableString(record.obbWarning)
            data.writeBoolean(record.cleanAfter); data.writeBoolean(record.saveHistory); data.nullableString(record.workDir)
        }
        if (buffer.size() > MAX_PAYLOAD_BYTES) throw IOException(message("privileged_operation_store_operation_record_size_limit_exceeded", "Превышен предел размера записи операции"))
        return buffer.toByteArray()
    }

    private fun decode(payload: ByteArray): PrivilegedOperationRecord = DataInputStream(ByteArrayInputStream(payload)).use { data ->
        val operationId = data.string(); val backend = PrivilegedBackend.valueOf(data.string()); val userId = data.readInt()
        val packageName = data.string(); val versionName = data.string(); val versionCode = data.readLong()
        val appLabel = data.string(); val sourceName = data.string(); val createdAt = data.readLong()
        val sessionId = data.readInt().let { if (it == -1) null else it }; val phase = PrivilegedOperationPhase.valueOf(data.string())
        val success = when (data.readUnsignedByte()) { 0 -> null; 1 -> true; 2 -> false; else -> throw IOException(message("privileged_operation_store_invalid_operation_result", "Некорректный результат операции")) }
        val record = PrivilegedOperationRecord(operationId, backend, userId, packageName, versionName, versionCode, appLabel,
            sourceName, createdAt, sessionId, phase, success, data.nullableString(), data.nullableString(), data.boolean(), data.boolean(), data.nullableString())
        if (data.read() != -1) throw IOException(message("privileged_operation_store_extra_data_in_the_operation_record", "Лишние данные в записи операции"))
        record
    }

    private fun DataOutputStream.string(value: String) {
        if (value.length > MAX_STRING_BYTES) throw IOException(message("privileged_operation_store_operation_field_length_limit_exceeded", "Превышен предел длины поля операции"))
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_STRING_BYTES) throw IOException(message("privileged_operation_store_operation_field_length_limit_exceeded", "Превышен предел длины поля операции"))
        writeInt(bytes.size); write(bytes)
    }
    private fun DataOutputStream.nullableString(value: String?) { writeBoolean(value != null); if (value != null) string(value) }
    private fun DataInputStream.string(): String {
        val length = readInt()
        if (length !in 0..MAX_STRING_BYTES || length > available()) throw IOException(message("privileged_operation_store_invalid_operation_field_length", "Некорректная длина поля операции"))
        val bytes = ByteArray(length).also(::readFully)
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }
    private fun DataInputStream.nullableString(): String? = if (boolean()) string() else null
    private fun DataInputStream.boolean(): Boolean = when (readUnsignedByte()) {
        0 -> false; 1 -> true; else -> throw IOException(message("privileged_operation_store_invalid_operation_field_flag", "Некорректный признак поля операции"))
    }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private companion object {
        const val MAGIC = 0x5350424f
        const val VERSION = 1
        const val HEADER_BYTES = 12
        const val DIGEST_BYTES = 32
        const val MAX_STRING_BYTES = 8192
        const val MAX_PAYLOAD_BYTES = 65536
        const val MAX_FILE_BYTES = HEADER_BYTES + MAX_PAYLOAD_BYTES + DIGEST_BYTES
    }
}
