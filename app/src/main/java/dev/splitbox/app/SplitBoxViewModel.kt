// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app

import dev.splitbox.core.message
import dev.splitbox.core.nestedMessage

import android.app.Application
import android.app.LocaleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.net.toUri
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
        val safe = MessageEnvelope.sanitize(text) { plain ->
            plain.replace(Regex("(?:content|file)://[^\\s]+"), "[URI]").take(6000)
        }
        mutableNotice.value = AppNotice(safe, action)
    }
    fun refreshLanguage() { settingsRepository.refreshLanguage() }
    fun updateLanguage(language: AppLanguage) {
        settingsRepository.update(settings.value.copy(language = language))
    }
    fun dismissNotice() { mutableNotice.value = null }
    private val mutableEvents = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = mutableEvents.asSharedFlow()
    private var operation: Job? = null
    private val lifecycleRecovery = LifecycleRecovery(viewModelScope)
    private var historyClearJob: Job? = null
    private val cleanupRetry = RetryablePersistenceAction { detail ->
        reportMessage(message("split_box_view_model_cleanup_was_not_completed_free_up_space", "Очистка не завершена: %1\$s. Освободите место и повторите сохранение.", nestedMessage(detail)), NoticeAction.RETRY_SAVE)
        val state = mutableUi.value
        state.prepared?.let { prepared ->
            checkpointOwner = prepared.workDir.absolutePath
            selectionWrites.submit(SelectionCheckpoint(prepared, state.selectedPaths, state.sha256, state.appLabel))
            if (state.phase == OperationPhase.READY) validateCurrentSelection()
        }
    }
    private val historyClearRetry = RetryablePersistenceAction { detail ->
        reportMessage(message("split_box_view_model_history_was_not_deleted_free_up_space", "История не удалена: %1\$s. Освободите место и повторите сохранение.", nestedMessage(detail)), NoticeAction.RETRY_SAVE)
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
    private val accessCoordinator by lazy {
        AccessCoordinator(viewModelScope, rootBackend::requestAccess, {
            val running = shizukuBackend.isRunning()
            val granted = running && shizukuBackend.isGranted()
            ShizukuAccess(running, granted, running && !granted &&
                runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false))
        }, shizukuBackend::requestPermission,
            { !bootstrapSuccessful || operation?.isActive == true || journal.read()?.blocksNewOperation == true },
            android.os.SystemClock::elapsedRealtime, ::publishAccess)
    }
    private val receivedListener = Shizuku.OnBinderReceivedListener { viewModelScope.launch { accessCoordinator.binderChanged() } }
    private val deadListener = Shizuku.OnBinderDeadListener { viewModelScope.launch { accessCoordinator.binderDied() } }
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { code, grant ->
        viewModelScope.launch { accessCoordinator.permissionResult(code, grant == PackageManager.PERMISSION_GRANTED) }
    }
    init {
        settingsRepository.sizeMigrationNotice?.let(::reportMessage)
        Shizuku.addBinderReceivedListenerSticky(receivedListener)
        Shizuku.addBinderDeadListener(deadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        accessCoordinator.select(settings.value.backend)
        viewModelScope.launch {
            try {
                journal.awaitReady()
                restorePrivilegedOperation()
                bootstrapSuccessful = true
                bootstrap.complete(Unit)
                accessCoordinator.resumed()
            } catch (e: Exception) {
                bootstrap.completeExceptionally(e)
                message(message("split_box_view_model_could_not_restore_the_log_a_new", "Не удалось восстановить журнал: %1\$s. Новая установка заблокирована, чтобы не потерять результат предыдущей.", nestedMessage(e.message)))
                mutableUi.value = UiState(OperationPhase.ERROR, message("split_box_view_model_log_unavailable", "Журнал недоступен"), message("split_box_view_model_free_up_space_and_restart_splitbox", "Освободите место и перезапустите SplitBox."))
            }
        }
        viewModelScope.launch {
            if (!awaitBootstrap()) return@launch
            journal.state.collect { stored ->
                if (stored != null) try { consumeJournal(stored) }
                catch (e: Exception) { message(message("split_box_view_model_could_not_process_the_android_result", "Не удалось обработать результат Android: %1\$s", nestedMessage(e.message))) }
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
                try { consumeRetained(it) } catch (e: Exception) { message(message("split_box_view_model_could_not_save_the_late_result", "Не удалось сохранить поздний результат: %1\$s", nestedMessage(e.message))) }
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
        context.resources.displayMetrics.densityDpi, context.getSystemService(LocaleManager::class.java).systemLocales.let { locales ->
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
                mutableUi.update { it.copy(savingSelection = false, selectionSaveError = e.message ?: message("split_box_view_model_could_not_save_state", "Не удалось сохранить состояние")) }
                reportMessage(message("split_box_view_model_could_not_finish_saving", "Не удалось завершить сохранение: %1\$s", nestedMessage(e.message)), NoticeAction.RETRY_SAVE)
            }
        }
    }
    private suspend fun invalidateSelection() {
        checkpointOwner = null
        selectionValidator.clear()
        selectionWrites.invalidateAndAwait()
    }

    fun importUri(uri: Uri) {
        if (uri.scheme != "content" && uri.scheme != "file") { message(message("split_box_view_model_only_content_file_uris_are_supported", "Поддерживаются только content/file URI")); return }
        startImport(listOf(uri))
    }

    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val normalized = try { ImportSelection.normalize(uris.map(Uri::toString)) }
        catch (e: IllegalArgumentException) { message(e.message ?: message("split_box_view_model_invalid_file_selection", "Некорректный выбор файлов")); return }
        if (normalized.size == 1) importUri(normalized.single().toUri())
        else startImport(normalized.map(Uri::parse))
    }

    private fun startImport(uris: List<Uri>) {
        if (!bootstrapSuccessful) {
            lifecycleRecovery.deferImport(bootstrap::await, { startImport(uris) }) {
                message(message("split_box_view_model_installation_log_unavailable", "Журнал установки недоступен: %1\$s", nestedMessage(it.message)))
            }
            return
        }
        val handoff = lifecycleRecovery.preemptForImport(operation?.isActive == true, journal.read())
        if (handoff == null) { message(message("split_box_view_model_wait_for_the_current_operation_to_finish", "Дождитесь завершения текущей операции или отмените её")); return }
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
                mutableUi.value = UiState(OperationPhase.COPYING, message("split_box_view_model_copying_file", "Копирование файла"), message("split_box_view_model_reading_selected_uri", "Чтение выбранного URI"), canCancel = true)
                invalidateSelection()
                archivePrivilegedRecord()
                journal.flush()
                val old = activeDirectory
                withContext(Dispatchers.IO) { old?.let { stager.delete(it) } }
                val workDirectory = withContext(Dispatchers.IO) { stager.newDirectory() }
                directory = workDirectory
                activeDirectory = workDirectory
                currentSource = null
                val job = currentCoroutineContext().job
                val imported = withContext(Dispatchers.IO) {
                    PackageImporter(stager).prepare(uris, workDirectory, device(),
                        PrepareOptions(PackageSizePolicy.maxBytes(options.maxExpandedGiB), options.autoSelectSplits),
                        options.computeSha256, { !job.isActive },
                        { total, bytes -> mutableUi.update { it.copy(sourceBytes = total, copiedBytes = bytes,
                            progress = total?.takeIf { it > 0 }?.let { (bytes.toDouble() / it).coerceIn(0.0, 1.0).toFloat() },
                            detail = message("split_box_view_model_copied_mib", "Скопировано %1\$s МиБ", bytes / (1024 * 1024))) } },
                        { detail, progress -> mutableUi.update { it.copy(phase = OperationPhase.ANALYZING,
                            title = message("split_box_view_model_checking_package", "Проверка пакета"), detail = detail, progress = progress) } })
                }
                currentSource = imported.source
                val prepared = imported.prepared
                val name = prepared.sourceName
                val checked = withContext(Dispatchers.IO) {
                    check(prepared.apks.size in 1..4096) { message("split_box_view_model_invalid_number_of_apks", "Недопустимое количество APK") }
                    if (options.verifySignatures) {
                        mutableUi.update { it.copy(detail = message("split_box_view_model_cryptographically_verifying_apk_signatures", "Криптографическая проверка подписей APK"), progress = null) }
                        stager.verifySignatures(prepared.apks, Build.VERSION.SDK_INT) { !job.isActive }
                    }
                    if (options.verifySignatures) prepared else prepared.copy(warnings = prepared.warnings +
                        message("split_box_view_model_cryptographic_verification_in_splitbox_is_disabled_andro", "Криптографическая проверка в SplitBox отключена. Android проверит подписи при установке."))
                }
                val sha = imported.sha256
                val label = withContext(Dispatchers.IO) { appLabel(checked) }
                val selected = checked.apks.filter { it.selected }.map { it.archivePath }.toSet()
                withContext(Dispatchers.IO) {
                    PreparedSnapshot.write(checked, selected, sha, label)
                    journal.savePreparedDirectory(checked.workDir.absolutePath)
                    journal.flush()
                }
                mutableUi.value = UiState(OperationPhase.READY, message("split_box_view_model_ready_to_install", "Готово к установке"), name,
                    prepared = checked, selectedPaths = selected, sha256 = sha, appLabel = label)
                checkpointOwner = checked.workDir.absolutePath
                validateCurrentSelection()
            } catch (e: Exception) {
                val recovered = recoverFailedImport(e) { directory?.let {
                    journal.clearPreparedDirectory(it.absolutePath)
                    stager.delete(it)
                } }
                activeDirectory = null; currentSource = null
                mutableUi.value = recovered
            } finally { withContext(NonCancellable) { refreshCache() } }
        }
    }
    private fun appLabel(prepared: PreparedPackage): String = runCatching {
        val base = prepared.apks.first { it.metadata.splitName == null }.file
        val info = context.packageManager.getPackageArchiveInfo(base.absolutePath, PackageManager.PackageInfoFlags.of(0))
        val app = requireNotNull(info?.applicationInfo) { message("split_box_view_model_could_not_read_app_information_from_the", "Не удалось прочитать сведения о приложении из APK") }.apply { sourceDir = base.absolutePath; publicSourceDir = base.absolutePath }
        context.packageManager.getApplicationLabel(app).toString()
    }.getOrDefault(prepared.metadata.packageName)

    fun toggleSplit(archivePath: String) {
        if (busy() || mutableUi.value.phase != OperationPhase.READY) return
        val state = mutableUi.value
        val prepared = state.prepared ?: return
        val entry = prepared.apks.firstOrNull { it.archivePath == archivePath } ?: return
        if (entry.locked) { message(message("split_box_view_model_this_apk_is_required_or_incompatible_with", "Этот APK обязателен либо несовместим с устройством")); return }
        val selected = state.selectedPaths.toMutableSet().apply { if (!add(archivePath)) remove(archivePath) }
        mutableUi.update { it.copy(selectedPaths = selected, savingSelection = true,
            selectionSaveError = null, selectionValidationPending = true, selectionError = null) }
        checkpointOwner = prepared.workDir.absolutePath
        selectionWrites.submit(SelectionCheckpoint(prepared, selected, state.sha256, state.appLabel))
        validateCurrentSelection()
    }
    fun install() {
        if (busy()) { message(message("split_box_view_model_installation_is_already_in_progress", "Установка уже выполняется")); return }
        val before = mutableUi.value
        val prepared = before.prepared ?: return
        if (before.selectionValidationPending || before.selectionError != null) {
            message(before.selectionError ?: message("split_box_view_model_wait_for_the_selected_apks_to_be", "Дождитесь проверки выбранных APK")); return
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
                    val source = currentSource ?: prepared.apks.single { it.metadata.splitName == null }.file
                    journal.saveAwaitingPermission(source.absolutePath, prepared.sourceName, prepared.workDir.absolutePath)
                    journal.flush()
                    mutableUi.update { it.copy(phase = OperationPhase.WAITING_USER, title = message("split_box_view_model_permission_required", "Требуется разрешение"), canCancel = true,
                        detail = message("split_box_view_model_allow_splitbox_to_install_apps_then_return", "Разрешите SplitBox устанавливать приложения и вернитесь назад")) }
                    mutableEvents.emit(UiEvent.LaunchIntent(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))))
                    return@launch
                }
                journal.clearAwaitingPermission()
                journal.flush()
                mutableUi.update { it.copy(phase = OperationPhase.INSTALLING, title = message("split_box_view_model_installing_app", "Установка приложения"), progress = null,
                    result = null, canCancel = true, pendingPackageName = null) }
                val apks = prepared.apks.map { it.copy(selected = it.archivePath in before.selectedPaths) }
                val selected = apks.filter { it.selected }
                val job = currentCoroutineContext().job
                withContext(Dispatchers.IO) {
                    check(selected.size in 1..4096) { message("split_box_view_model_select_a_valid_set_of_apks", "Выберите корректный набор APK") }
                    validateSelection(apks)
                    selected.forEach { entry ->
                        check(entry.file.isFile && entry.file.length() == entry.size && entry.size > 0) { message("split_box_view_model_the_temporary_apk_has_changed_or_been", "Временный APK изменился или удалён. Выберите файл заново.") }
                        check(entry.file.canonicalFile.toPath().startsWith(prepared.workDir.canonicalFile.toPath())) { message("split_box_view_model_the_apk_is_outside_the_app_s", "APK находится вне закрытого кэша приложения") }
                    }
                    // Исходник и распакованные данные уже занимают кэш; сессии Android нужна ещё одна копия APK.
                    val sessionBytes = selected.fold(0L) { sum, entry -> Math.addExact(sum, entry.size) }
                    PackageSizePolicy.requireSpace(sessionBytes, prepared.workDir.usableSpace)
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
                    }, onSessionCreated = { tracker?.sessionCreated(it) }, onObbCopyStarted = { mutableUi.update { it.copy(title = message("split_box_view_model_transferring_obb", "Перенос OBB"), canCancel = true) } })
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
                    if (record != null) showPrivilegedUnknown(record, e.message ?: message("split_box_view_model_installation_result_not_confirmed", "Результат установки не подтверждён"), prepared)
                    else message(e.message ?: message("split_box_view_model_installation_result_not_confirmed", "Результат установки не подтверждён"))
                }
            } catch (e: Exception) {
                withContext(NonCancellable) {
                    if (e is InsufficientStorageException && !backendStarted && tracker?.current == null && journal.read() == null) {
                        val recovered = recoverStoragePreflight(before, e,
                            isOwned = { !backendStarted && tracker?.current == null && journal.read() == null &&
                                mutableUi.value.prepared === prepared && mutableUi.value.selectedPaths == before.selectedPaths &&
                                checkpointOwner == prepared.workDir.absolutePath },
                            restorePointer = {
                                journal.savePreparedDirectory(prepared.workDir.absolutePath)
                                journal.clearAwaitingPermission()
                            }, flushSelection = selectionWrites::flush, flushJournal = journal::flush)
                        if (recovered != null) {
                            mutableUi.value = recovered
                            reportMessage(recovered.detail, if (recovered.selectionSaveError != null) NoticeAction.RETRY_SAVE else null)
                        }
                    } else if (!backendStarted && tracker?.current == null && journal.read() == null &&
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
                            selectionSaveError = message("split_box_view_model_could_not_save_state_2", "Не удалось сохранить состояние: %1\$s", nestedMessage(detail)), canCancel = false) }
                        reportMessage(message("split_box_view_model_installation_did_not_start", "Установка не начата.\n%1\$s", nestedMessage(detail)), NoticeAction.RETRY_SAVE)
                    } else if (journal.read() == null && !systemResultHandled) {
                        val result = if (apkCommitted || tracker?.hasCommitProof == true)
                            OperationResult(true, message("split_box_view_model_apk_installation_confirmed", "Установка APK подтверждена"), prepared.metadata.packageName,
                                message("split_box_view_model_completion_of_additional_actions_has_not_been", "Завершение дополнительных действий не подтверждено. При необходимости экспортируйте OBB."))
                        else OperationResult(false, if (e is CancellationException) message("split_box_view_model_installation_canceled", "Установка отменена") else e.message ?: message("split_box_view_model_installation_error", "Ошибка установки"), prepared.metadata.packageName)
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
        listOfNotNull(result?.message ?: record.message ?: message("split_box_view_model_installation_result_not_confirmed", "Результат установки не подтверждён"), result?.obbWarning).joinToString("\n"))
    private suspend fun archivePrivilegedRecord() = privilegedMutex.withLock {
        withContext(Dispatchers.IO) { privilegedResults.archive() }
    }
    private suspend fun retryCompletedPersistence() {
        retryFinishedResults(journal::read, { journal.retained.value }, ::consumeJournal, ::consumeRetained) {
            reportMessage(message("split_box_view_model_could_not_save_the_android_result", "Не удалось сохранить результат Android: %1\$s", nestedMessage(it.message)), NoticeAction.RETRY_SAVE)
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
                record.message ?: message("split_box_view_model_the_operation_was_interrupted_the_final_result", "Работа %1\$s была прервана. Окончательный результат не подтверждён. Проверьте приложение перед повторной установкой.", record.backend.name), prepared?.prepared)
        }
    }
    private suspend fun showPrivilegedUnknown(record: PrivilegedOperationRecord, detail: String, prepared: PreparedPackage?) {
        val unknown = record.copy(phase = PrivilegedOperationPhase.UNKNOWN, success = null, message = detail)
        withContext(Dispatchers.IO) {
            runCatching { privilegedStore.write(unknown) }.onFailure { message(message("split_box_view_model_could_not_save_the_uncertain_result", "Не удалось сохранить неопределённый результат: %1\$s", nestedMessage(it.message))) }
            if (record.saveHistory) runCatching { historyRepository.add(privilegedHistory(unknown, null)) }
                .onFailure { message(message("split_box_view_model_could_not_save_history", "Не удалось сохранить историю: %1\$s", nestedMessage(it.message))) }
        }
        mutableUi.update { it.copy(phase = OperationPhase.UNKNOWN, title = message("split_box_view_model_result_not_confirmed", "Результат не подтверждён"), detail = detail,
            prepared = prepared, result = null, progress = null, canCancel = false, pendingPackageName = record.packageName) }
    }
    private suspend fun completePrivileged(record: PrivilegedOperationRecord, result: OperationResult, prepared: PreparedPackage?) = privilegedMutex.withLock {
        val finalRecord = privilegedResults.remember(record, result)
        val persisted = withContext(Dispatchers.IO) { runCatching { privilegedResults.persist(finalRecord) } }
        persisted.exceptionOrNull()?.let {
            reportMessage(message("split_box_view_model_the_installation_result_is_known_but_saving", "Результат установки известен, но сохранение не завершено: %1\$s", nestedMessage(it.message)), NoticeAction.RETRY_SAVE)
        }
        val options = settings.value.copy(backend = BackendMode.valueOf(record.backend.name), cleanAfterInstall = record.cleanAfter, saveHistory = false)
        val finished = finish(result, prepared, options, record.appLabel, record.operationId, cleanupDirectory = record.workDir)
        if (persisted.isSuccess && finished) withContext(Dispatchers.IO) {
            runCatching { privilegedResults.clear(record.operationId) }.onFailure {
                reportMessage(message("split_box_view_model_could_not_finish_writing_the_operation_record", "Не удалось завершить запись операции: %1\$s", nestedMessage(it.message)), NoticeAction.RETRY_SAVE)
            }
        }
    }
    private suspend fun finish(result: OperationResult, prepared: PreparedPackage?, options: AppSettings,
        label: String, id: String, stored: PendingInstall? = null, cleanupDirectory: String? = null): Boolean {
        var keepForObb = false
        val persistence = runCatching { withContext(Dispatchers.IO) {
            if (options.saveHistory) historyRepository.add(HistoryEntry(id, System.currentTimeMillis(), label,
                result.packageName ?: prepared?.metadata?.packageName.orEmpty(), prepared?.metadata?.versionName ?: stored?.versionName.orEmpty(),
                prepared?.sourceName ?: stored?.sourceName.orEmpty(), options.backend, result.success,
                listOfNotNull(result.message, result.obbWarning).joinToString("\n")))
            keepForObb = finishPreparedStorage(result, prepared, (stored?.workDir ?: cleanupDirectory)?.let(::File),
                options.cleanAfterInstall, journal::savePreparedDirectory, { journal.clearPreparedDirectory() }, journal::flush, stager::delete)
        } }
        persistence.exceptionOrNull()?.let { reportMessage(message("split_box_view_model_the_installation_result_is_known_but_saving_2", "Результат установки известен, но сохранение или очистка не завершены: %1\$s", nestedMessage(it.message)), NoticeAction.RETRY_SAVE) }
        val retained = if (persistence.isFailure || !options.cleanAfterInstall || keepForObb) prepared else null
        val displayedResult = if (keepForObb) result.copy(obbWarning = result.obbWarning +
            message("split_box_view_model_the_package_containing_obb_files_has_been", " Пакет с OBB сохранён для экспорта после перезапуска. Очистите кэш после экспорта.")) else result
        mutableUi.update { it.copy(phase = if (result.success) OperationPhase.SUCCESS else OperationPhase.ERROR,
            title = if (result.success) message("split_box_view_model_app_installed", "Приложение установлено") else message("split_box_view_model_installation_failed", "Установка не выполнена"), detail = result.message,
            progress = null, prepared = retained, result = displayedResult, canCancel = false, pendingPackageName = null) }
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
            mutableUi.update { it.copy(phase = OperationPhase.UNKNOWN, title = message("split_box_view_model_result_not_confirmed", "Результат не подтверждён"), detail = current.message,
                result = null, progress = null, canCancel = false, pendingPackageName = current.packageName) }
        } else {
            mutableUi.update { it.copy(phase = if (current.state == "WRITING") OperationPhase.INSTALLING else OperationPhase.WAITING_USER,
                canCancel = current.state == "WRITING", title = if (current.state == "WRITING") message("split_box_view_model_writing_apk", "Запись APK") else message("split_box_view_model_waiting_for_android", "Ожидание Android"),
                detail = current.message.ifBlank { message("split_box_view_model_android_is_processing_session", "Android обрабатывает сессию %1\$s", current.sessionId) }, progress = null) }
            if (current.state == "CONFIRMATION") offerConfirmation(current)
        }
    }
    private fun applyRestored(value: PreparedSnapshot.Restored) {
        activeDirectory = value.prepared.workDir
        currentSource = null
        mutableUi.value = UiState(OperationPhase.READY, message("split_box_view_model_prepared_package_restored", "Подготовленный пакет восстановлен"), value.prepared.sourceName,
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
        message(message("split_box_view_model_late_android_result_for", "Поздний результат Android для %1\$s: %2\$s", record.appLabel, nestedMessage(record.message)))
    }
    private suspend fun offerConfirmation(value: PendingInstall) {
        if (mutableEvents.subscriptionCount.value == 0 || !journal.isCurrent(value) || !journal.isLaunchCurrent(value.confirmationToken)) return
        val raw = value.confirmation ?: return
        val intent = runCatching { InstallJournal.decodeIntent(raw) }.getOrElse {
            if (journal.isCurrent(value)) journal.markLaunchFailed(value.sessionId)
            return
        }
        try { journal.flush() }
        catch (e: Exception) { message(message("split_box_view_model_android_confirmation_was_not_saved_free_up", "Подтверждение Android не сохранено: %1\$s. Освободите место и проверьте статус снова.", nestedMessage(e.message))); return }
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
        else if (record.state == "UNKNOWN") journal.write(record.copy(state = "CONFIRMATION", message = message("split_box_view_model_confirm_installation_in_the_system_dialog", "Подтвердите установку в системном окне")))
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
            message(message("split_box_view_model_the_prepared_package_is_no_longer_available", "Готовый пакет больше не доступен в кэше. Выберите файл заново."))
            return
        }
        applyRestored(restored)
    }
    /** Пояснение неопределённого привилегированного итога открывается только по действию пользователя. */
    fun checkStatus() {
        if (journal.read() == null && mutableUi.value.phase == OperationPhase.UNKNOWN) {
            message(message("split_box_view_model_root_shizuku_results_cannot_be_confirmed_automatically", "Автоматического подтверждения результата Root/Shizuku нет. Откройте приложение и проверьте его версию. Повторная установка возможна только по вашему явному действию."))
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
                        mutableUi.value = UiState(OperationPhase.ERROR, message("split_box_view_model_temporary_package_unavailable", "Временный пакет недоступен"), message("split_box_view_model_select_the_file_again_access_to_the", "Выберите файл заново. Доступ к исходному URI мог истечь."))
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
                } else mutableUi.update { it.copy(phase = OperationPhase.READY, title = message("split_box_view_model_permission_required", "Требуется разрешение"), canCancel = false,
                    detail = message("split_box_view_model_installation_from_this_source_is_not_yet", "Установка из этого источника ещё не разрешена. Нажмите «Установить», чтобы открыть разрешение снова.")) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { reportMessage(message("split_box_view_model_could_not_restore_state", "Не удалось восстановить состояние: %1\$s", nestedMessage(e.message)), NoticeAction.RETRY_SAVE) }
        }
    }
    fun cancelOperation() {
        if (!mutableUi.value.canCancel) { message(message("split_box_view_model_installation_has_already_been_handed_over_to", "Передача установки Android уже началась. Дождитесь результата.")); return }
        if (cancelObbExport(mutableUi.value, operation)) return
        val stored = journal.read()
        if (stored != null && stored.state != "FINISHED") {
            viewModelScope.launch {
                val error = withContext(Dispatchers.IO) { runCatching { context.packageManager.packageInstaller.abandonSession(stored.sessionId) }.exceptionOrNull() }
                if (error == null) {
                    val latest = journal.read()
                    if (latest?.sessionId == stored.sessionId && latest.state != "FINISHED") journal.write(latest.copy(state = "FINISHED", success = false, message = message("split_box_view_model_installation_canceled", "Установка отменена")))
                } else message(message("split_box_view_model_android_is_already_processing_the_installation", "Android уже обрабатывает установку: %1\$s", nestedMessage(error.message)))
            }
        } else if (operation?.isActive == true) operation?.cancel()
        else {
            journal.awaitingPermission()?.let { journal.returnPermissionToReady(it.third) }
            mutableUi.update { it.copy(phase = if (it.prepared == null) OperationPhase.IDLE else OperationPhase.READY, title = message("split_box_view_model_operation_canceled", "Операция отменена"), canCancel = false) }
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
        runCatching { settingsRepository.update(value) }
            .onSuccess { accessCoordinator.select(value.backend) }
            .onFailure { message(it.message ?: message("split_box_view_model_invalid_setting", "Некорректная настройка")) }
    }
    fun refreshAccess() = accessCoordinator.resumed()
    fun requestRootAccess() { if (settings.value.backend == BackendMode.ROOT) accessCoordinator.retry() }
    fun requestShizukuAccess() { if (settings.value.backend == BackendMode.SHIZUKU) accessCoordinator.retry() }
    private fun publishAccess(snapshot: AccessSnapshot) {
        val root = when (snapshot.root) {
            RootAccessStatus.UNCHECKED -> "unchecked" to "Доступ Root не проверен"
            RootAccessStatus.CHECKING -> "checking" to "Проверка Root…"
            RootAccessStatus.GRANTED -> "granted" to "Доступ Root разрешён"
            RootAccessStatus.DENIED -> "denied" to "Root недоступен или запрос отклонён"
        }
        val shizuku = when (snapshot.shizuku) {
            ShizukuAccessStatus.STOPPED -> "stopped" to "Shizuku не запущен"
            ShizukuAccessStatus.NEEDS_PERMISSION -> "needs_permission" to "Shizuku работает, требуется разрешение"
            ShizukuAccessStatus.REQUESTING -> "requesting" to "Ожидание разрешения Shizuku…"
            ShizukuAccessStatus.GRANTED -> "granted" to "Доступ Shizuku разрешён"
            ShizukuAccessStatus.DENIED -> "denied" to "Доступ Shizuku отклонён. Запросите его повторно."
            ShizukuAccessStatus.PERMANENTLY_DENIED -> "permanently_denied" to "Доступ заблокирован. Разрешите его в Shizuku."
        }
        mutableAccess.value = AccessState(message("access_root_${root.first}", root.second),
            snapshot.root == RootAccessStatus.GRANTED, snapshot.shizuku != ShizukuAccessStatus.STOPPED,
            snapshot.shizuku == ShizukuAccessStatus.GRANTED, message("access_shizuku_${shizuku.first}", shizuku.second))
    }
    fun clearHistory() {
        if (historyClearJob?.isActive == true) return
        historyClearJob = viewModelScope.launch {
            historyClearRetry.run { withContext(Dispatchers.IO) { historyRepository.clear() } }
        }
    }
    fun clearCache() {
        if (busy()) { message(message("split_box_view_model_cleanup_is_unavailable_during_an_operation", "Очистка недоступна во время операции")); return }
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
                mutableUi.value = UiState(title = message("split_box_view_model_temporary_files_deleted", "Временные файлы удалены"))
                refreshCache()
            }
        }
    }
    fun exportObb(destination: Uri) {
        if (busy()) { message(message("split_box_view_model_wait_for_the_operation_to_finish", "Дождитесь завершения операции")); return }
        cleanupRetry.discard()
        val before = mutableUi.value
        val prepared = before.prepared ?: return
        if (prepared.obbFiles.isEmpty()) { message(message("split_box_view_model_the_package_contains_no_obb_files", "В пакете нет OBB")); return }
        operation = viewModelScope.launch {
            try {
                runObbExport(before, mutableUi, { checkpointOwner }, { selectionWrites.state.value }, { selectionValidator.state.value }) {
                    val job = currentCoroutineContext().job
                    withContext(Dispatchers.IO) {
                        val names = HashSet<String>()
                        val output = context.contentResolver.openOutputStream(destination, "wt") ?: throw IOException(message("split_box_view_model_could_not_open_the_export_location", "Не удалось открыть место экспорта"))
                        val totalBytes = prepared.obbFiles.fold(0L) { sum, entry -> Math.addExact(sum, entry.size) }
                        var exported = 0L
                        val reporting = TransferProgress(totalBytes) { bytes, fraction -> mutableUi.update {
                            it.copy(detail = message("split_box_view_model_exported_mib", "Экспортировано %1\$s МиБ", bytes / 1048576), progress = fraction, sourceBytes = totalBytes, copiedBytes = bytes)
                        } }
                        ZipOutputStream(output).use { zip ->
                            prepared.obbFiles.forEach { entry ->
                                val name = entry.archivePath.substringAfterLast('/')
                                ObbPolicy.destination(Process.myUid() / 100000, prepared.metadata.packageName, prepared.metadata.versionCode, name)
                                check(names.add(name)) { message("split_box_view_model_duplicate_obb_name", "Повторяющееся имя OBB") }
                                check(entry.file.isFile && entry.file.length() == entry.size) { message("split_box_view_model_the_obb_file_has_already_been_removed", "OBB уже удалён из кэша. Выберите исходный архив заново.") }
                                zip.putNextEntry(ZipEntry("${prepared.metadata.packageName}/$name"))
                                entry.file.inputStream().use { input ->
                                    val buffer = ByteArray(65536)
                                    while (true) {
                                        job.ensureActive()
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        zip.write(buffer, 0, count)
                                        exported = Math.addExact(exported, count.toLong())
                                        reporting.report(exported)
                                    }
                                }
                                zip.closeEntry()
                            }
                        }
                    }
                }
                message(message("split_box_view_model_obb_files_exported_to_zip", "OBB экспортированы в ZIP"))
            } catch (e: Exception) {
                message(message("split_box_view_model_export_was_not_completed_you_can_delete", "Экспорт не завершён: %1\$s. Неполный ZIP можно удалить в выбранной папке.", nestedMessage(e.message ?: message("split_box_view_model_canceled", "отменён"))))
            } finally { withContext(NonCancellable) { refreshCache() } }
        }
    }
    fun openInstalledApp(packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) message(message("split_box_view_model_the_app_has_no_launchable_screen", "У приложения нет доступного экрана запуска"))
        else viewModelScope.launch { mutableEvents.emit(UiEvent.LaunchIntent(intent)) }
    }
    override fun onCleared() {
        operation?.cancel(); lifecycleRecovery.cancel(); historyClearJob?.cancel()
        Shizuku.removeBinderReceivedListener(receivedListener)
        Shizuku.removeBinderDeadListener(deadListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        super.onCleared()
    }
}
