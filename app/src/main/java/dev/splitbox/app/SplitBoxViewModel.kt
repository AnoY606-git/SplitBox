// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.splitbox.app.data.*
import dev.splitbox.app.installer.*
import dev.splitbox.app.model.*
import dev.splitbox.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rikka.shizuku.Shizuku
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SplitBoxViewModel(application: Application) : AndroidViewModel(application) {
    private val context: Application = application
    private val settingsRepository = SettingsRepository(context)
    private val historyRepository = HistoryRepository(context)
    private val stager = PackageStager(context)
    private val journal = InstallJournal.get(context)
    private val privilegedStore = FilePrivilegedOperationStore(File(context.filesDir, "privileged-operation.bin"))
    private val bootstrap = CompletableDeferred<Unit>()
    private var bootstrapSuccessful = false
    private val rootBackend = RootInstallBackend()
    private val shizukuBackend = ShizukuInstallBackend(context)
    private val registry = BackendRegistry(SystemInstallBackend(context, journal), rootBackend, shizukuBackend)
    private val mutableUi = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = mutableUi.asStateFlow()
    val settings: StateFlow<AppSettings> = settingsRepository.state
    val history: StateFlow<List<HistoryEntry>> = historyRepository.state
    private val mutableAccess = MutableStateFlow(AccessState())
    val access: StateFlow<AccessState> = mutableAccess.asStateFlow()
    private val mutableCache = MutableStateFlow(0L)
    val cacheBytes: StateFlow<Long> = mutableCache.asStateFlow()
    private val mutableNotice = MutableStateFlow<AppNotice?>(null)
    val notice: StateFlow<AppNotice?> = mutableNotice.asStateFlow()
    fun reportMessage(text: String, action: NoticeAction? = null) {
        val safe = text.replace(Regex("(?:content|file)://[^\\s]+"), "[локальный URI скрыт]").take(6000)
        mutableNotice.value = AppNotice(safe, action)
    }
    fun dismissNotice() { mutableNotice.value = null }
    private val mutableEvents = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = mutableEvents.asSharedFlow()
    private var operation: Job? = null
    private var accessJob: Job? = null
    private val lifecycleRecovery = LifecycleRecovery(viewModelScope)
    private var historyClearJob: Job? = null
    private val cleanupRetry = RetryablePersistenceAction { detail ->
        reportMessage("Очистка не завершена: $detail. Освободите место и повторите сохранение.", NoticeAction.RETRY_SAVE)
        val state = mutableUi.value
        state.prepared?.let { prepared ->
            checkpointOwner = prepared.workDir.absolutePath
            selectionWrites.submit(SelectionCheckpoint(prepared, state.selectedPaths, state.sha256, state.appLabel))
            if (state.phase == OperationPhase.READY) validateCurrentSelection()
        }
    }
    private val historyClearRetry = RetryablePersistenceAction { detail ->
        reportMessage("История не удалена: $detail. Освободите место и повторите сохранение.", NoticeAction.RETRY_SAVE)
    }
    private val privilegedMutex = Mutex()
    private val privilegedResults = PrivilegedResultPersistence(privilegedStore) { record, result ->
        val existing = history.value.firstOrNull { it.id == record.operationId && it.success != null }
        if (result != null || existing == null) historyRepository.add(privilegedHistory(record, result))
    }
    private var activeDirectory: File? = null
    private var currentSource: File? = null
    private val confirmationDelivery = ConfirmationDelivery()
    private val journalMutex = Mutex()
    private var restoredPermission = false
    private var systemResultHandled = false
    private data class SelectionCheckpoint(val prepared: PreparedPackage, val selected: Set<String>, val sha: String?, val label: String?)
    private val selectionWrites = RevisionedSaveQueue<SelectionCheckpoint>(viewModelScope) { value ->
        PreparedSnapshot.write(value.prepared, value.selected, value.sha, value.label)
    }
    private var checkpointOwner: String? = null
    private val selectionValidator = SelectionValidator(viewModelScope)
    private val receivedListener = Shizuku.OnBinderReceivedListener { refreshAccess() }
    private val deadListener = Shizuku.OnBinderDeadListener { refreshAccess() }
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { code, grant ->
        if (code == 901) {
            refreshAccess()
            message(if (grant == PackageManager.PERMISSION_GRANTED) "Доступ Shizuku разрешён" else "Доступ Shizuku отклонён")
        }
    }
    init {
        Shizuku.addBinderReceivedListenerSticky(receivedListener)
        Shizuku.addBinderDeadListener(deadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        refreshAccess()
        viewModelScope.launch {
            try {
                journal.awaitReady()
                restorePrivilegedOperation()
                bootstrapSuccessful = true
                bootstrap.complete(Unit)
            } catch (e: Exception) {
                bootstrap.completeExceptionally(e)
                message("Не удалось восстановить журнал: ${e.message}. Новая установка заблокирована, чтобы не потерять результат предыдущей.")
                mutableUi.value = UiState(OperationPhase.ERROR, "Журнал недоступен", "Освободите место и перезапустите SplitBox.")
            }
        }
        viewModelScope.launch {
            if (!awaitBootstrap()) return@launch
            journal.state.collect { stored ->
                if (stored != null) try { consumeJournal(stored) }
                catch (e: Exception) { message("Не удалось обработать результат Android: ${e.message}") }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { stager.rescan() }.onSuccess { mutableCache.value = it }
        }
        viewModelScope.launch {
            selectionWrites.state.collect { saved ->
                mutableUi.update { state ->
                    if (state.phase == OperationPhase.READY && state.prepared?.workDir?.absolutePath == checkpointOwner)
                        state.copy(savingSelection = saved.saving, selectionSaveError = saved.error)
                    else state
                }
            }
        }
        viewModelScope.launch {
            selectionValidator.state.collect { validation ->
                mutableUi.update { state ->
                    if (state.phase == OperationPhase.READY && state.selectedPaths == validation.selected)
                        state.copy(selectionValidationPending = validation.pending, selectionError = validation.error)
                    else state
                }
            }
        }
        viewModelScope.launch {
            journal.persistence.collect { value -> value.error?.let { reportMessage(it, NoticeAction.RETRY_SAVE) } }
        }
        viewModelScope.launch {
            if (!awaitBootstrap()) return@launch
            journal.retained.collect { records -> records.filter { it.state == "FINISHED" }.forEach {
                try { consumeRetained(it) } catch (e: Exception) { message("Не удалось сохранить поздний результат: ${e.message}") }
            } }
        }
        // Повторная доставка новому подписчику подавляется только после подтверждённого запуска Activity.
        viewModelScope.launch {
            mutableEvents.subscriptionCount.collect { count ->
                if (count == 0) confirmationDelivery.collectorStopped()
                else journal.read()?.takeIf { it.state == "CONFIRMATION" || (it.state == "UNKNOWN" && it.confirmation != null) }
                    ?.let { offerConfirmation(it) }
            }
        }
    }
    private fun busy(): Boolean = !bootstrapSuccessful || operation?.isActive == true || lifecycleRecovery.isActive || journal.read()?.blocksNewOperation == true
    private fun message(text: String) { reportMessage(text) }
    private fun device() = DeviceProfile(Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.toList(),
        context.resources.displayMetrics.densityDpi, context.resources.configuration.locales.let { locales ->
            (0 until locales.size()).map { locales[it].toLanguageTag() }
        })
    private suspend fun refreshCache() {
        mutableCache.value = withContext(Dispatchers.IO) {
            activeDirectory?.takeIf { it.isDirectory }?.let { stager.refreshDirectory(it) }
            stager.bytes()
        }
    }
    private fun validateCurrentSelection() {
        val state = mutableUi.value
        val prepared = state.prepared ?: return
        mutableUi.update { it.copy(selectionValidationPending = true, selectionError = null) }
        selectionValidator.submit(prepared.apks, state.selectedPaths)
    }
    fun retrySelectionSave() {
        if (operation?.isActive == true || lifecycleRecovery.isActive) return
        operation = viewModelScope.launch {
            mutableUi.update { it.copy(savingSelection = true, selectionSaveError = null) }
            selectionWrites.retry(); journal.retrySave()
            try {
                selectionWrites.flush(); journal.flush()
                retryCompletedPersistence()
                cleanupRetry.retry()
                historyClearJob?.join()
                historyClearRetry.retry()
                mutableUi.update { it.copy(savingSelection = false, selectionSaveError = null) }
                if (mutableUi.value.phase == OperationPhase.READY) validateCurrentSelection()
            } catch (e: Exception) {
                mutableUi.update { it.copy(savingSelection = false, selectionSaveError = e.message ?: "Не удалось сохранить состояние") }
                reportMessage("Не удалось завершить сохранение: ${e.message}", NoticeAction.RETRY_SAVE)
            }
        }
    }
    private suspend fun invalidateSelection() {
        checkpointOwner = null
        selectionValidator.clear()
        selectionWrites.invalidateAndAwait()
    }

    fun importUri(uri: Uri) {
        if (!bootstrapSuccessful) {
            lifecycleRecovery.deferImport(bootstrap::await, { importUri(uri) }) {
                message("Журнал установки недоступен: ${it.message}")
            }
            return
        }
        if (uri.scheme != "content" && uri.scheme != "file") { message("Поддерживаются только content/file URI"); return }
        val handoff = lifecycleRecovery.preemptForImport(operation?.isActive == true, journal.read())
        if (handoff == null) { message("Дождитесь завершения текущей операции или отмените её"); return }
        cleanupRetry.discard()
        journal.detachUnknown()
        journal.clearPreparedDirectory()
        journal.clearAwaitingPermission()
        restoredPermission = false
        val options = settings.value
        operation = viewModelScope.launch {
            var directory: File? = null
            try {
                // Старое чтение READY или permission continuation не может опубликоваться поверх нового URI.
                handoff.awaitPrevious()
                mutableUi.value = UiState(OperationPhase.COPYING, "Копирование файла", "Чтение выбранного URI", canCancel = true)
                invalidateSelection()
                archivePrivilegedRecord()
                journal.flush()
                val old = activeDirectory
                withContext(Dispatchers.IO) { old?.let { stager.delete(it) } }
                val name = withContext(Dispatchers.IO) { stager.name(uri) }
                val workDirectory = withContext(Dispatchers.IO) { stager.newDirectory() }
                directory = workDirectory
                activeDirectory = workDirectory
                val source = File(workDirectory, "source.bin")
                currentSource = source
                val job = currentCoroutineContext().job
                val sourceBytes = withContext(Dispatchers.IO) { stager.sourceSize(uri) }
                mutableUi.update { it.copy(sourceBytes = sourceBytes) }
                val copied = withContext(Dispatchers.IO) {
                    stager.copy(uri, source, options.maxExpandedGiB.toLong() * 1024 * 1024 * 1024,
                        { !job.isActive }, { bytes -> mutableUi.update { it.copy(copiedBytes = bytes,
                            detail = "Скопировано ${bytes / (1024 * 1024)} МиБ") } }, options.computeSha256)
                }
                mutableUi.update { it.copy(phase = OperationPhase.ANALYZING, title = "Проверка пакета", detail = name) }
                val prepared = withContext(Dispatchers.IO) {
                    ArchivePreparer().prepare(source, name, workDirectory, device(),
                        PrepareOptions(options.maxExpandedGiB.toLong() * 1024 * 1024 * 1024, options.autoSelectSplits),
                        { detail, progress -> mutableUi.update { it.copy(detail = detail, progress = progress) } }, { !job.isActive })
                }
                val checked = withContext(Dispatchers.IO) {
                    check(prepared.apks.size in 1..4096) { "Недопустимое количество APK" }
                    if (options.verifySignatures) {
                        mutableUi.update { it.copy(detail = "Криптографическая проверка подписей APK", progress = null) }
                        stager.verifySignatures(prepared.apks, Build.VERSION.SDK_INT) { !job.isActive }
                    }
                    if (options.verifySignatures) prepared else prepared.copy(warnings = prepared.warnings +
                        "Криптографическая проверка в SplitBox отключена. Android проверит подписи при установке.")
                }
                val sha = copied.sha256
                val label = withContext(Dispatchers.IO) { appLabel(checked) }
                val selected = checked.apks.filter { it.selected }.map { it.archivePath }.toSet()
                withContext(Dispatchers.IO) {
                    PreparedSnapshot.write(checked, selected, sha, label)
                    journal.savePreparedDirectory(checked.workDir.absolutePath)
                    journal.flush()
                }
                mutableUi.value = UiState(OperationPhase.READY, "Готово к установке", name,
                    prepared = checked, selectedPaths = selected, sha256 = sha, appLabel = label)
                checkpointOwner = checked.workDir.absolutePath
                validateCurrentSelection()
            } catch (e: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) { directory?.let {
                    journal.clearPreparedDirectory(it.absolutePath)
                    stager.delete(it)
                } }
                activeDirectory = null; currentSource = null
                mutableUi.value = UiState(title = "Операция отменена")
            } catch (e: Exception) {
                withContext(Dispatchers.IO) { directory?.let {
                    journal.clearPreparedDirectory(it.absolutePath)
                    stager.delete(it)
                } }
                activeDirectory = null; currentSource = null
                mutableUi.value = UiState(OperationPhase.ERROR, "Не удалось подготовить файл", e.message ?: "Неизвестная ошибка",
                    result = OperationResult(false, e.message ?: "Неизвестная ошибка"))
            } finally { withContext(NonCancellable) { refreshCache() } }
        }
    }
    private fun appLabel(prepared: PreparedPackage): String = runCatching {
        val base = prepared.apks.first { it.metadata.splitName == null }.file
        val info = context.packageManager.getPackageArchiveInfo(base.absolutePath, PackageManager.PackageInfoFlags.of(0))
        val app = requireNotNull(info?.applicationInfo) { "Не удалось прочитать сведения о приложении из APK" }.apply { sourceDir = base.absolutePath; publicSourceDir = base.absolutePath }
        context.packageManager.getApplicationLabel(app).toString()
    }.getOrDefault(prepared.metadata.packageName)

    fun toggleSplit(archivePath: String) {
        if (busy() || mutableUi.value.phase != OperationPhase.READY) return
        val state = mutableUi.value
        val prepared = state.prepared ?: return
        val entry = prepared.apks.firstOrNull { it.archivePath == archivePath } ?: return
        if (entry.locked) { message("Этот APK обязателен либо несовместим с устройством"); return }
        val selected = state.selectedPaths.toMutableSet().apply { if (!add(archivePath)) remove(archivePath) }
        mutableUi.update { it.copy(selectedPaths = selected, savingSelection = true,
            selectionSaveError = null, selectionValidationPending = true, selectionError = null) }
        checkpointOwner = prepared.workDir.absolutePath
        selectionWrites.submit(SelectionCheckpoint(prepared, selected, state.sha256, state.appLabel))
        validateCurrentSelection()
    }
    fun install() {
        if (busy()) { message("Установка уже выполняется"); return }
        val before = mutableUi.value
        val prepared = before.prepared ?: return
        if (before.selectionValidationPending || before.selectionError != null) {
            message(before.selectionError ?: "Дождитесь проверки выбранных APK"); return
        }
        cleanupRetry.discard()
        val options = settings.value
        systemResultHandled = false
        operation = viewModelScope.launch {
            var tracker: PrivilegedOperationTracker? = null
            var apkCommitted = false
            var backendStarted = false
            try {
                // Ошибка сохранения не позволяет покинуть экран с неподтверждённым выбором.
                mutableUi.update { it.copy(savingSelection = true) }
                selectionWrites.flush()
                mutableUi.update { it.copy(savingSelection = false) }
                journal.detachUnknown()
                archivePrivilegedRecord()
                journal.clearPreparedDirectory()
                if (options.backend == BackendMode.SYSTEM && !context.packageManager.canRequestPackageInstalls()) {
                    val source = currentSource ?: File(prepared.workDir, "source.bin")
                    journal.saveAwaitingPermission(source.absolutePath, prepared.sourceName, prepared.workDir.absolutePath)
                    journal.flush()
                    mutableUi.update { it.copy(phase = OperationPhase.WAITING_USER, title = "Требуется разрешение", canCancel = true,
                        detail = "Разрешите SplitBox устанавливать приложения и вернитесь назад") }
                    mutableEvents.emit(UiEvent.LaunchIntent(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))))
                    return@launch
                }
                journal.clearAwaitingPermission()
                journal.flush()
                mutableUi.update { it.copy(phase = OperationPhase.INSTALLING, title = "Установка приложения", progress = null,
                    result = null, canCancel = true, pendingPackageName = null) }
                val apks = prepared.apks.map { it.copy(selected = it.archivePath in before.selectedPaths) }
                val selected = apks.filter { it.selected }
                val job = currentCoroutineContext().job
                withContext(Dispatchers.IO) {
                    check(selected.size in 1..4096) { "Выберите корректный набор APK" }
                    validateSelection(apks)
                    selected.forEach { entry ->
                        check(entry.file.isFile && entry.file.length() == entry.size && entry.size > 0) { "Временный APK изменился или удалён. Выберите файл заново." }
                        check(entry.file.canonicalFile.toPath().startsWith(prepared.workDir.canonicalFile.toPath())) { "APK находится вне закрытого кэша приложения" }
                    }
                    if (options.verifySignatures) stager.verifySignatures(selected, Build.VERSION.SDK_INT) { !job.isActive }
                    if (options.backend != BackendMode.SYSTEM) {
                        tracker = PrivilegedOperationTracker(privilegedStore).also { it.begin(PrivilegedOperationRecord(
                            UUID.randomUUID().toString(), PrivilegedBackend.valueOf(options.backend.name), Process.myUid() / 100000,
                            prepared.metadata.packageName, prepared.metadata.versionName, prepared.metadata.versionCode,
                            before.appLabel ?: prepared.metadata.packageName, prepared.sourceName, System.currentTimeMillis(),
                            cleanAfter = options.cleanAfterInstall, saveHistory = options.saveHistory, workDir = prepared.workDir.absolutePath)) }
                    }
                }
                val request = InstallRequest(prepared, selected, options, before.appLabel ?: prepared.metadata.packageName,
                    Process.myUid() / 100000, onCommitted = {
                        apkCommitted = true
                        tracker?.committed()?.let(::message)
                    }, onCommitStarted = {
                        tracker?.commitStarted()
                        mutableUi.update { it.copy(canCancel = false) }
                    }, onSessionCreated = { tracker?.sessionCreated(it) })
                backendStarted = true
                when (val outcome = registry.select(options.backend).install(request) { detail, progress ->
                    mutableUi.update { it.copy(detail = detail, progress = progress) }
                }) {
                    is BackendOutcome.Finished -> withContext(NonCancellable) {
                        val record = tracker?.current
                        if (record != null) completePrivileged(record, outcome.result, prepared)
                        else finish(outcome.result, prepared, options, before.appLabel ?: prepared.metadata.packageName, UUID.randomUUID().toString())
                    }
                    is BackendOutcome.SystemPending -> journal.read()?.let { consumeJournal(it) }
                }
            } catch (e: UncertainInstallException) {
                withContext(NonCancellable) {
                    val record = tracker?.current
                    if (record != null) showPrivilegedUnknown(record, e.message ?: "Результат установки не подтверждён", prepared)
                    else message(e.message ?: "Результат установки не подтверждён")
                }
            } catch (e: Exception) {
                withContext(NonCancellable) {
                    if (!backendStarted && tracker?.current == null && journal.read() == null &&
                        (mutableUi.value.phase == OperationPhase.READY || mutableUi.value.phase == OperationPhase.WAITING_USER)) {
                        val restored = runCatching {
                            restoreReadyCheckpoint(
                                isOwned = { !backendStarted && tracker?.current == null && journal.read() == null &&
                                    mutableUi.value.prepared === prepared && mutableUi.value.selectedPaths == before.selectedPaths &&
                                    checkpointOwner == prepared.workDir.absolutePath },
                                restorePointer = {
                                    journal.savePreparedDirectory(prepared.workDir.absolutePath)
                                    journal.clearAwaitingPermission()
                                }, flushSelection = selectionWrites::flush, flushJournal = journal::flush)
                        }
                        val detail = listOfNotNull(e.message, restored.exceptionOrNull()?.message).distinct().joinToString(". ")
                        mutableUi.update { it.copy(phase = OperationPhase.READY, savingSelection = false,
                            selectionSaveError = "Не удалось сохранить состояние: $detail", canCancel = false) }
                        reportMessage("Установка не начата.\n$detail", NoticeAction.RETRY_SAVE)
                    } else if (journal.read() == null && !systemResultHandled) {
                        val result = if (apkCommitted || tracker?.hasCommitProof == true)
                            OperationResult(true, "Установка APK подтверждена", prepared.metadata.packageName,
                                "Завершение дополнительных действий не подтверждено. При необходимости экспортируйте OBB.")
                        else OperationResult(false, if (e is CancellationException) "Установка отменена" else e.message ?: "Ошибка установки", prepared.metadata.packageName)
                        val record = tracker?.current
                        if (record != null) completePrivileged(record, result, prepared)
                        else finish(result, prepared, options, before.appLabel ?: prepared.metadata.packageName, UUID.randomUUID().toString())
                    }
                }
            } finally { withContext(NonCancellable) { refreshCache() } }
        }
    }
    private suspend fun awaitBootstrap(): Boolean = try { bootstrap.await(); true }
        catch (_: Exception) { false }
    private fun privilegedHistory(record: PrivilegedOperationRecord, result: OperationResult?) = HistoryEntry(
        record.operationId, record.createdAt, record.appLabel, record.packageName, record.versionName,
        record.sourceName, BackendMode.valueOf(record.backend.name), result?.success,
        listOfNotNull(result?.message ?: record.message ?: "Результат установки не подтверждён", result?.obbWarning).joinToString("\n"))
    private suspend fun archivePrivilegedRecord() = privilegedMutex.withLock {
        withContext(Dispatchers.IO) { privilegedResults.archive() }
    }
    private suspend fun retryCompletedPersistence() {
        retryFinishedResults(journal::read, { journal.retained.value }, ::consumeJournal, ::consumeRetained) {
            reportMessage("Не удалось сохранить результат Android: ${it.message}", NoticeAction.RETRY_SAVE)
        }
        val pending = withContext(Dispatchers.IO) { privilegedResults.pending() }
        pending.forEach { record ->
            val result = (record.recoveryDecision() as PrivilegedRecoveryDecision.Known).result
            val prepared = mutableUi.value.prepared?.takeIf { it.workDir.absolutePath == record.workDir }
            completePrivileged(record, result, prepared)
        }
    }
    private suspend fun restorePrivilegedOperation() {
        val record = withContext(Dispatchers.IO) { privilegedStore.read() } ?: return
        if (journal.read() != null) { archivePrivilegedRecord(); return }
        val prepared = withContext(Dispatchers.IO) {
            record.workDir?.let { runCatching { PreparedSnapshot.read(File(it), stager.root) }.getOrNull() }
        }
        prepared?.let(::applyRestored)
        // История может успеть записаться, даже если завершающая запись операции или очистка не удались.
        val historyResult = history.value.firstOrNull { it.id == record.operationId && it.success != null }
        val decision = historyResult?.let { PrivilegedRecoveryDecision.Known(OperationResult(it.success == true, it.message, it.packageName)) }
            ?: record.recoveryDecision()
        when (decision) {
            is PrivilegedRecoveryDecision.Known -> completePrivileged(record, decision.result, prepared?.prepared)
            PrivilegedRecoveryDecision.Unknown -> showPrivilegedUnknown(record,
                record.message ?: "Работа ${record.backend.name} была прервана. Окончательный результат не подтверждён. Проверьте приложение перед повторной установкой.", prepared?.prepared)
        }
    }
    private suspend fun showPrivilegedUnknown(record: PrivilegedOperationRecord, detail: String, prepared: PreparedPackage?) {
        val unknown = record.copy(phase = PrivilegedOperationPhase.UNKNOWN, success = null, message = detail)
        withContext(Dispatchers.IO) {
            runCatching { privilegedStore.write(unknown) }.onFailure { message("Не удалось сохранить неопределённый результат: ${it.message}") }
            if (record.saveHistory) runCatching { historyRepository.add(privilegedHistory(unknown, null)) }
                .onFailure { message("Не удалось сохранить историю: ${it.message}") }
        }
        mutableUi.update { it.copy(phase = OperationPhase.UNKNOWN, title = "Результат не подтверждён", detail = detail,
            prepared = prepared, result = null, progress = null, canCancel = false, pendingPackageName = record.packageName) }
    }
    private suspend fun completePrivileged(record: PrivilegedOperationRecord, result: OperationResult, prepared: PreparedPackage?) = privilegedMutex.withLock {
        val finalRecord = privilegedResults.remember(record, result)
        val persisted = withContext(Dispatchers.IO) { runCatching { privilegedResults.persist(finalRecord) } }
        persisted.exceptionOrNull()?.let {
            reportMessage("Результат установки известен, но сохранение не завершено: ${it.message}", NoticeAction.RETRY_SAVE)
        }
        val options = settings.value.copy(backend = BackendMode.valueOf(record.backend.name), cleanAfterInstall = record.cleanAfter, saveHistory = false)
        val finished = finish(result, prepared, options, record.appLabel, record.operationId, cleanupDirectory = record.workDir)
        if (persisted.isSuccess && finished) withContext(Dispatchers.IO) {
            runCatching { privilegedResults.clear(record.operationId) }.onFailure {
                reportMessage("Не удалось завершить запись операции: ${it.message}", NoticeAction.RETRY_SAVE)
            }
        }
    }
    private suspend fun finish(result: OperationResult, prepared: PreparedPackage?, options: AppSettings,
        label: String, id: String, stored: PendingInstall? = null, cleanupDirectory: String? = null): Boolean {
        journal.clearPreparedDirectory()
        val persistence = runCatching { withContext(Dispatchers.IO) {
            journal.flush()
            if (options.saveHistory) historyRepository.add(HistoryEntry(id, System.currentTimeMillis(), label,
                result.packageName ?: prepared?.metadata?.packageName.orEmpty(), prepared?.metadata?.versionName ?: stored?.versionName.orEmpty(),
                prepared?.sourceName ?: stored?.sourceName.orEmpty(), options.backend, result.success,
                listOfNotNull(result.message, result.obbWarning).joinToString("\n")))
            if (options.cleanAfterInstall) {
                val directory = prepared?.workDir ?: (stored?.workDir ?: cleanupDirectory)?.let(::File)
                if (directory != null) {
                    val keepObb = result.success && result.obbWarning != null && prepared?.obbFiles?.isNotEmpty() == true
                    if (keepObb) {
                        val keep = prepared!!.obbFiles.map { it.file.canonicalPath }.toSet()
                        directory.walkBottomUp().forEach { file ->
                            if (file.isFile && file.canonicalPath !in keep) file.delete()
                            else if (file.isDirectory && file != directory && file.listFiles()?.isEmpty() == true) file.delete()
                        }
                    } else stager.delete(directory)
                }
            }
        } }
        persistence.exceptionOrNull()?.let { reportMessage("Результат установки известен, но сохранение или очистка не завершены: ${it.message}", NoticeAction.RETRY_SAVE) }
        val retained = if (persistence.isFailure || !options.cleanAfterInstall || (result.success && result.obbWarning != null && prepared?.obbFiles?.isNotEmpty() == true)) prepared else null
        mutableUi.update { it.copy(phase = if (result.success) OperationPhase.SUCCESS else OperationPhase.ERROR,
            title = if (result.success) "Приложение установлено" else "Установка не выполнена", detail = result.message,
            progress = null, prepared = retained, result = result, canCancel = false, pendingPackageName = null) }
        if (retained == null) { activeDirectory = null; currentSource = null }
        refreshCache()
        return persistence.isSuccess
    }
    private suspend fun consumeJournal(value: PendingInstall) = journalMutex.withLock {
        val current = journal.read() ?: return@withLock
        if (!journal.isCurrent(value)) return@withLock
        if (mutableUi.value.prepared == null) {
            val restored = withContext(Dispatchers.IO) { runCatching { PreparedSnapshot.read(File(current.workDir), stager.root) }.getOrNull() }
            // В состоянии UNKNOWN во время ввода-вывода возможен новый импорт; старый снимок после него не применяем.
            if (!journal.isCurrent(current)) return@withLock
            restored?.let { applyRestored(it) }
        }
        if (current.state == "FINISHED") {
            systemResultHandled = true
            val options = settings.value.copy(backend = BackendMode.SYSTEM, cleanAfterInstall = current.cleanAfter, saveHistory = current.saveHistory)
            val result = OperationResult(current.success == true, current.message, current.packageName, current.obbWarning.takeIf { current.success == true })
            if (finish(result, mutableUi.value.prepared, options, current.appLabel, current.historyId, current)) {
                if (journal.isCurrent(current)) {
                    journal.consume(current.sessionId)
                    journal.flush()
                }
            }
        } else if (current.state == "UNKNOWN") {
            mutableUi.update { it.copy(phase = OperationPhase.UNKNOWN, title = "Результат не подтверждён", detail = current.message,
                result = null, progress = null, canCancel = false, pendingPackageName = current.packageName) }
        } else {
            mutableUi.update { it.copy(phase = if (current.state == "WRITING") OperationPhase.INSTALLING else OperationPhase.WAITING_USER,
                canCancel = current.state == "WRITING", title = if (current.state == "WRITING") "Запись APK" else "Ожидание Android",
                detail = current.message.ifBlank { "Android обрабатывает сессию ${current.sessionId}" }, progress = null) }
            if (current.state == "CONFIRMATION") offerConfirmation(current)
        }
    }
    private fun applyRestored(value: PreparedSnapshot.Restored) {
        activeDirectory = value.prepared.workDir
        currentSource = File(value.prepared.workDir, "source.bin")
        mutableUi.value = UiState(OperationPhase.READY, "Подготовленный пакет восстановлен", value.prepared.sourceName,
            prepared = value.prepared, selectedPaths = value.selected, sha256 = value.sha256, appLabel = value.label)
        checkpointOwner = value.prepared.workDir.absolutePath
        validateCurrentSelection()
    }
    private suspend fun consumeRetained(value: PendingInstall) = journalMutex.withLock {
        val record = journal.find(value.sessionId)?.takeIf { it == value && it.state == "FINISHED" } ?: return@withLock
        if (journal.read()?.sessionId == record.sessionId) return@withLock
        withContext(Dispatchers.IO) {
            if (record.saveHistory) historyRepository.add(HistoryEntry(record.historyId, System.currentTimeMillis(), record.appLabel,
                record.packageName, record.versionName, record.sourceName, BackendMode.SYSTEM, record.success == true,
                listOfNotNull(record.message, record.obbWarning.takeIf { record.success == true }).joinToString("\n")))
            if (record.cleanAfter && activeDirectory?.absolutePath != record.workDir) {
                stager.delete(File(record.workDir))
            }
        }
        if (journal.find(record.sessionId) != record || journal.read()?.sessionId == record.sessionId) return@withLock
        journal.consume(record.sessionId)
        journal.flush()
        message("Поздний результат Android для ${record.appLabel}: ${record.message}")
    }
    private suspend fun offerConfirmation(value: PendingInstall) {
        if (mutableEvents.subscriptionCount.value == 0 || !journal.isCurrent(value) || !journal.isLaunchCurrent(value.confirmationToken)) return
        val raw = value.confirmation ?: return
        val intent = runCatching { InstallJournal.decodeIntent(raw) }.getOrElse {
            if (journal.isCurrent(value)) journal.markLaunchFailed(value.sessionId)
            return
        }
        try { journal.flush() }
        catch (e: Exception) { message("Подтверждение Android не сохранено: ${e.message}. Освободите место и проверьте статус снова."); return }
        if (mutableEvents.subscriptionCount.value == 0 || !journal.isCurrent(value) ||
            !journal.isLaunchCurrent(value.confirmationToken) || !confirmationDelivery.offer(value.confirmationToken)) return
        mutableEvents.emit(UiEvent.LaunchIntent(intent, value.confirmationToken))
    }
    /** UI проверяет актуальность перед startActivity, чтобы отбросить устаревшее подтверждение из очереди. */
    fun isIntentLaunchCurrent(token: String): Boolean = journal.isLaunchCurrent(token)
    /** UI вызывает сразу после успешного startActivity либо передаёт false при исключении запуска. */
    fun acknowledgeIntentLaunch(token: String, launched: Boolean) {
        if (!isIntentLaunchCurrent(token)) return
        val record = journal.read()?.takeIf { it.confirmationToken == token && it.state != "FINISHED" } ?: return
        confirmationDelivery.acknowledge(token, launched)
        if (!launched) journal.markLaunchFailed(record.sessionId)
        else if (record.state == "UNKNOWN") journal.write(record.copy(state = "CONFIRMATION", message = "Подтвердите установку в системном окне"))
    }
    private suspend fun restoreReadyPackage() {
        fun idleForRestore(): Boolean = mutableUi.value.phase == OperationPhase.IDLE && operation?.isActive != true &&
            journal.read() == null && journal.awaitingPermission() == null
        if (!idleForRestore()) return
        val directory = journal.preparedDirectory() ?: return
        val restored = withContext(Dispatchers.IO) {
            runCatching { PreparedSnapshot.read(File(directory), stager.root) }.getOrNull()
        }
        // Новый импорт, сброс или установка могли заменить или очистить указатель во время ввода-вывода.
        if (journal.preparedDirectory() != directory || !idleForRestore()) return
        if (restored == null) {
            journal.clearPreparedDirectory(directory)
            message("Готовый пакет больше не доступен в кэше. Выберите файл заново.")
            return
        }
        applyRestored(restored)
    }
    /** Пояснение неопределённого привилегированного итога открывается только по действию пользователя. */
    fun checkStatus() {
        if (journal.read() == null && mutableUi.value.phase == OperationPhase.UNKNOWN) {
            message("Автоматического подтверждения результата Root/Shizuku нет. Откройте приложение и проверьте его версию. Повторная установка возможна только по вашему явному действию.")
        } else onHostResumed()
    }
    fun onHostResumed() {
        refreshAccess()
        if (lifecycleRecovery.isActive || operation?.isActive == true) return
        lifecycleRecovery.start {
            if (!awaitBootstrap() || operation?.isActive == true) return@start
            try {
                journal.retrySave()
                journal.flush()
                retryCompletedPersistence()
                val stored = journal.read()
                if (stored != null) {
                    recoverSystemSession(stored, { journal.isCurrent(stored) && operation?.isActive != true },
                        readSealed = { withContext(Dispatchers.IO) { context.packageManager.packageInstaller.getSessionInfo(stored.sessionId)?.isSealed } },
                        abandon = { withContext(Dispatchers.IO) {
                            if (journal.isCurrent(stored)) context.packageManager.packageInstaller.abandonSession(stored.sessionId)
                        } },
                        write = journal::write, missing = { journal.markMissing(stored.sessionId) },
                        confirm = { offerConfirmation(stored) })
                    return@start
                }
                val resume = journal.awaitingPermission()
                if (resume == null) {
                    restoreReadyPackage()
                    return@start
                }
                if (operation?.isActive == true) return@start
                if (settings.value.backend != BackendMode.SYSTEM) { journal.returnPermissionToReady(resume.third); journal.flush(); restoreReadyPackage(); return@start }
                if (mutableUi.value.prepared == null && !restoredPermission) {
                    restoredPermission = true
                    val restored = withContext(Dispatchers.IO) { runCatching { PreparedSnapshot.read(File(resume.third), stager.root) }.getOrNull() }
                    if (journal.awaitingPermission() != resume || journal.read() != null || operation?.isActive == true) return@start
                    if (restored == null) {
                        journal.clearAwaitingPermission()
                        mutableUi.value = UiState(OperationPhase.ERROR, "Временный пакет недоступен", "Выберите файл заново. Доступ к исходному URI мог истечь.")
                        return@start
                    }
                    applyRestored(restored)
                }
                val owner = mutableUi.value.prepared ?: return@start
                val selected = mutableUi.value.selectedPaths
                val revision = selectionValidator.state.value.revision
                fun isFresh(): Boolean = journal.awaitingPermission() == resume && journal.read() == null &&
                    operation?.isActive != true && settings.value.backend == BackendMode.SYSTEM &&
                    mutableUi.value.prepared === owner && owner.workDir.absolutePath == resume.third &&
                    mutableUi.value.selectedPaths == selected && checkpointOwner == resume.third
                if (!selectionValidator.awaitValid(revision, ::isFresh)) return@start
                // Публикация StateFlow и UI-подписчик могут выполняться в разном порядке.
                mutableUi.update { it.copy(selectionValidationPending = false, selectionError = null) }
                if (context.packageManager.canRequestPackageInstalls()) {
                    lifecycleRecovery.release()
                    install()
                } else mutableUi.update { it.copy(phase = OperationPhase.READY, title = "Требуется разрешение", canCancel = false,
                    detail = "Установка из этого источника ещё не разрешена. Нажмите «Установить», чтобы открыть разрешение снова.") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { reportMessage("Не удалось восстановить состояние: ${e.message}", NoticeAction.RETRY_SAVE) }
        }
    }
    fun cancelOperation() {
        if (!mutableUi.value.canCancel) { message("Передача установки Android уже началась. Дождитесь результата."); return }
        if (cancelObbExport(mutableUi.value, operation)) return
        val stored = journal.read()
        if (stored != null && stored.state != "FINISHED") {
            viewModelScope.launch {
                val error = withContext(Dispatchers.IO) { runCatching { context.packageManager.packageInstaller.abandonSession(stored.sessionId) }.exceptionOrNull() }
                if (error == null) {
                    val latest = journal.read()
                    if (latest?.sessionId == stored.sessionId && latest.state != "FINISHED") journal.write(latest.copy(state = "FINISHED", success = false, message = "Установка отменена"))
                } else message("Android уже обрабатывает установку: ${error.message}")
            }
        } else if (operation?.isActive == true) operation?.cancel()
        else {
            journal.awaitingPermission()?.let { journal.returnPermissionToReady(it.third) }
            mutableUi.update { it.copy(phase = if (it.prepared == null) OperationPhase.IDLE else OperationPhase.READY, title = "Операция отменена", canCancel = false) }
        }
    }
    fun reset() {
        if (busy()) { cancelOperation(); return }
        val old = activeDirectory
        operation = viewModelScope.launch {
            cleanupRetry.run {
                invalidateSelection()
                archivePrivilegedRecord()
                journal.detachUnknown()
                journal.clearPreparedDirectory()
                journal.clearAwaitingPermission()
                journal.flush()
                withContext(Dispatchers.IO) { old?.let { stager.delete(it) } }
                activeDirectory = null; currentSource = null; restoredPermission = false
                mutableUi.value = UiState()
                refreshCache()
            }
        }
    }
    fun updateSettings(value: AppSettings) {
        if (journal.isReady && value.backend != settings.value.backend)
            journal.awaitingPermission()?.let { journal.returnPermissionToReady(it.third) }
        runCatching { settingsRepository.update(value) }.onFailure { message(it.message ?: "Некорректная настройка") }
    }
    fun refreshAccess() {
        val running = shizukuBackend.isRunning()
        val granted = shizukuBackend.isGranted()
        mutableAccess.update { it.copy(shizukuRunning = running, shizukuGranted = granted,
            shizukuStatus = when { granted -> "Shizuku подключён, доступ разрешён"; running -> "Shizuku работает, требуется разрешение"; else -> "Shizuku не подключён" }) }
    }
    fun requestRootAccess() {
        if (accessJob?.isActive == true || busy()) return
        mutableAccess.update { it.copy(rootStatus = "Ожидание менеджера Root…") }
        accessJob = viewModelScope.launch {
            val granted = runCatching { rootBackend.requestAccess() }.getOrDefault(false)
            mutableAccess.update { it.copy(rootGranted = granted, rootStatus = if (granted) "Root подтверждён явной проверкой" else "Root недоступен или запрос отклонён") }
        }
    }
    fun requestShizukuAccess() { runCatching { shizukuBackend.requestPermission() }.onFailure { message(it.message ?: "Shizuku недоступен") } }
    fun clearHistory() {
        if (historyClearJob?.isActive == true) return
        historyClearJob = viewModelScope.launch {
            historyClearRetry.run { withContext(Dispatchers.IO) { historyRepository.clear() } }
        }
    }
    fun clearCache() {
        if (busy()) { message("Очистка недоступна во время операции"); return }
        operation = viewModelScope.launch {
            cleanupRetry.run {
                invalidateSelection()
                archivePrivilegedRecord()
                journal.detachUnknown()
                journal.clearPreparedDirectory()
                journal.clearAwaitingPermission()
                journal.flush()
                withContext(Dispatchers.IO) { stager.clearExcept(emptySet()) }
                activeDirectory = null; currentSource = null; restoredPermission = false
                mutableUi.value = UiState(title = "Временные файлы удалены")
                refreshCache()
            }
        }
    }
    fun exportObb(destination: Uri) {
        if (busy()) { message("Дождитесь завершения операции"); return }
        cleanupRetry.discard()
        val before = mutableUi.value
        val prepared = before.prepared ?: return
        if (prepared.obbFiles.isEmpty()) { message("В пакете нет OBB"); return }
        operation = viewModelScope.launch {
            try {
                runObbExport(before, mutableUi, { checkpointOwner }, { selectionWrites.state.value }, { selectionValidator.state.value }) {
                    val job = currentCoroutineContext().job
                    withContext(Dispatchers.IO) {
                        val names = HashSet<String>()
                        val output = context.contentResolver.openOutputStream(destination, "wt") ?: throw IOException("Не удалось открыть место экспорта")
                        ZipOutputStream(output).use { zip ->
                            prepared.obbFiles.forEach { entry ->
                                val name = entry.archivePath.substringAfterLast('/')
                                ObbPolicy.destination(Process.myUid() / 100000, prepared.metadata.packageName, prepared.metadata.versionCode, name)
                                check(names.add(name)) { "Повторяющееся имя OBB" }
                                check(entry.file.isFile && entry.file.length() == entry.size) { "OBB уже удалён из кэша. Выберите исходный архив заново." }
                                zip.putNextEntry(ZipEntry("${prepared.metadata.packageName}/$name"))
                                entry.file.inputStream().use { input ->
                                    val buffer = ByteArray(65536)
                                    while (true) {
                                        job.ensureActive()
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        zip.write(buffer, 0, count)
                                    }
                                }
                                zip.closeEntry()
                            }
                        }
                    }
                }
                message("OBB экспортированы в ZIP")
            } catch (e: Exception) {
                message("Экспорт не завершён: ${e.message ?: "отменён"}. Неполный ZIP можно удалить в выбранной папке.")
            } finally { withContext(NonCancellable) { refreshCache() } }
        }
    }
    fun openInstalledApp(packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) message("У приложения нет доступного экрана запуска")
        else viewModelScope.launch { mutableEvents.emit(UiEvent.LaunchIntent(intent)) }
    }
    override fun onCleared() {
        operation?.cancel(); accessJob?.cancel(); lifecycleRecovery.cancel(); historyClearJob?.cancel()
        Shizuku.removeBinderReceivedListener(receivedListener)
        Shizuku.removeBinderDeadListener(deadListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        super.onCleared()
    }
}
