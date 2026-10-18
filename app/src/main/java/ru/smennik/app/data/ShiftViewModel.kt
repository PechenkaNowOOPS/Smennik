package ru.smennik.app.data

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.smennik.app.domain.CustomCycle
import ru.smennik.app.domain.Workplace
import ru.smennik.app.domain.WorkSession
import ru.smennik.app.domain.WagePayment
import ru.smennik.app.domain.PaymentType
import ru.smennik.app.domain.WorkPattern
import ru.smennik.app.domain.WorkShift
import ru.smennik.app.reminders.ShiftReminders
import java.time.YearMonth
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.LocalDate

data class ShiftUiState(
    val shifts: List<WorkShift> = emptyList(),
    val sessions: List<WorkSession> = emptyList(),
    val payments: List<WagePayment> = emptyList(),
    val workplaces: List<Workplace> = emptyList(),
    val isLoading: Boolean = true,
    val isBusy: Boolean = false,
    val loadingError: String? = null
)

class ShiftViewModel(application: Application) : AndroidViewModel(application) {
    private val store = ShiftStore(application.applicationContext)
    private val resolver = application.contentResolver
    private val writeMutex = Mutex()
    private val _uiState = MutableStateFlow(ShiftUiState())
    val uiState: StateFlow<ShiftUiState> = _uiState.asStateFlow()

    private data class StoreSnapshot(
        val shifts: List<WorkShift>, val sessions: List<WorkSession>,
        val payments: List<WagePayment>, val workplaces: List<Workplace>
    )
    private fun snapshot() = StoreSnapshot(
        store.all(),store.allSessions(),store.allPayments(),store.workplaces()
    )

    init { load() }

    fun reload() {
        _uiState.update { it.copy(isLoading = true, loadingError = null) }
        load()
    }

    private fun load() {
        viewModelScope.launch {
            try {
                val snapshot = writeMutex.withLock {
                    withContext(Dispatchers.IO) { snapshot() }
                }
                _uiState.update {
                    it.copy(shifts = snapshot.shifts, sessions = snapshot.sessions,
                        payments = snapshot.payments, workplaces = snapshot.workplaces,
                        isLoading = false, loadingError = null)
                }
                withContext(Dispatchers.IO) {
                    runCatching {
                        ShiftReminders.sync(getApplication<Application>().applicationContext, snapshot.shifts)
                    }
                }
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, loadingError = "Не удалось загрузить график: ${error.message ?: "ошибка базы данных"}")
                }
            }
        }
    }

    private suspend fun mutate(action: () -> Unit): Result<Unit> {
        _uiState.update { it.copy(isBusy = true) }
        return try {
            val shifts = writeMutex.withLock {
                withContext(Dispatchers.IO) {
                    action()
                    snapshot()
                }
            }
            _uiState.update { it.copy(shifts = shifts.shifts, sessions = shifts.sessions,
                payments = shifts.payments, workplaces = shifts.workplaces,
                loadingError = null) }
            // Do not undo an already persisted user operation if Android disallows alarms.
            withContext(Dispatchers.IO) {
                runCatching {
                    ShiftReminders.sync(getApplication<Application>().applicationContext, shifts.shifts)
                }
            }
            Result.success(Unit)
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            _uiState.update { it.copy(isBusy = false) }
        }
    }

    suspend fun startWork(date: LocalDate, hourlyKopecks: Long, workplaceId: Long = 1): Result<Unit> =
        mutate { store.startSession(date, hourlyKopecks, System.currentTimeMillis(), workplaceId) }

    suspend fun togglePause(id: Long): Result<Unit> =
        mutate { store.togglePause(id, System.currentTimeMillis()) }

    suspend fun finishWork(id: Long): Result<Unit> =
        mutate { store.finishSession(id, System.currentTimeMillis()) }

    suspend fun addPayment(date: LocalDate, amount: Long, type: PaymentType, workplaceId: Long = 1): Result<Unit> =
        mutate { store.addPayment(date, amount, type, workplaceId) }

    suspend fun removePayment(id: Long): Result<Unit> =
        mutate { store.removePayment(id) }

    suspend fun editFinishedSession(
        id: Long, startMs: Long, endMs: Long, breakMinutes: Int
    ): Result<Unit> = mutate {
        store.editFinishedSession(id, startMs, endMs, breakMinutes)
    }

    suspend fun deleteFinishedSession(id: Long): Result<Unit> =
        mutate { store.deleteFinishedSession(id) }

    suspend fun addWorkplace(name: String, hourlyKopecks: Long): Result<Unit> =
        mutate { store.addWorkplace(name, hourlyKopecks) }

    suspend fun updatePayRules(id: Long, nightPercent: Int, overtimePercent: Int,
                               overtimeAfterMinutes: Int): Result<Unit> =
        mutate { store.updatePayRules(id, nightPercent, overtimePercent, overtimeAfterMinutes) }

    suspend fun save(shift: WorkShift): Result<Unit> = mutate { store.save(shift) }
    suspend fun remove(date: LocalDate, workplaceId: Long = 1): Result<Unit> =
        mutate { store.remove(date, workplaceId) }

    suspend fun generate(
        anchor: LocalDate,
        pattern: WorkPattern,
        startMinutes: Int,
        endMinutes: Int,
        hourlyKopecks: Long,
        replaceExisting: Boolean, workplaceId: Long = 1
    ): Result<Unit> = mutate {
        store.fillPattern(anchor, pattern, startMinutes, endMinutes, hourlyKopecks, replaceExisting, workplaceId)
    }

    suspend fun generateCustom(
        anchor: LocalDate, mask: String, startMinutes: Int, endMinutes: Int,
        hourlyKopecks: Long, replaceExisting: Boolean, workplaceId: Long
    ): Result<Unit> = mutate {
        store.fillCustomPattern(anchor, CustomCycle(mask), startMinutes, endMinutes,
            hourlyKopecks, replaceExisting, workplaceId)
    }

    suspend fun exportMonthlyCsv(
        uri: Uri, workplaceId: Long, month: YearMonth
    ): Result<Unit> {
        _uiState.update { it.copy(isBusy = true) }
        return try {
            writeMutex.withLock {
                withContext(Dispatchers.IO) {
                    val workplace = store.workplaces().firstOrNull { it.id == workplaceId }
                        ?: error("Место работы не найдено.")
                    val csv = MonthlyCsv.generate(
                        workplace,
                        month,
                        store.allSessions().filter { it.workplaceId == workplaceId },
                        store.allPayments().filter { it.workplaceId == workplaceId },
                        System.currentTimeMillis()
                    )
                    val output = resolver.openOutputStream(uri, "w")
                        ?: throw IOException("Не удалось открыть CSV-файл.")
                    output.use { it.write(byteArrayOf(0xEF.toByte(),0xBB.toByte(),0xBF.toByte()))
                        it.write(csv.toByteArray(Charsets.UTF_8)) }
                }
            }
            Result.success(Unit)
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            _uiState.update { it.copy(isBusy = false) }
        }
    }

    suspend fun exportBackup(uri: Uri): Result<Unit> {
        _uiState.update { it.copy(isBusy = true) }
        return try {
            writeMutex.withLock {
                withContext(Dispatchers.IO) {
                    val bytes = ShiftBackup.encode(store.all(), store.allSessions(), store.allPayments(),store.workplaces())
                    val output = resolver.openOutputStream(uri, "w")
                        ?: throw IOException("Не удалось открыть выбранный файл для записи.")
                    output.use { it.write(bytes) }
                }
            }
            Result.success(Unit)
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            _uiState.update { it.copy(isBusy = false) }
        }
    }

    suspend fun importBackup(uri: Uri): Result<Int> {
        _uiState.update { it.copy(isBusy = true) }
        return try {
            val restored = withContext(Dispatchers.IO) {
                val input = resolver.openInputStream(uri)
                    ?: throw IOException("Не удалось открыть выбранный файл.")
                input.use { stream ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > ShiftBackup.MAX_BYTES) {
                            throw IOException("Файл больше 5 МБ и не может быть импортирован.")
                        }
                        output.write(buffer, 0, count)
                    }
                    ShiftBackup.decodeSnapshot(output.toByteArray())
                }
            }
            val updated = writeMutex.withLock {
                withContext(Dispatchers.IO) {
                    store.replaceAllSnapshot(restored)
                    snapshot()
                }
            }
            _uiState.update { it.copy(shifts = updated.shifts, sessions = updated.sessions,
                payments = updated.payments, workplaces = updated.workplaces,
                loadingError = null) }
            withContext(Dispatchers.IO) {
                runCatching {
                    ShiftReminders.sync(getApplication<Application>().applicationContext, updated.shifts)
                }
            }
            Result.success(restored.shifts.size + restored.sessions.size + restored.payments.size)
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            _uiState.update { it.copy(isBusy = false) }
        }
    }

    override fun onCleared() {
        store.close()
        super.onCleared()
    }
}
