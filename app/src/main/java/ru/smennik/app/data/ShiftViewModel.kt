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
import ru.smennik.app.domain.WorkPattern
import ru.smennik.app.domain.WorkShift
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.LocalDate

data class ShiftUiState(
    val shifts: List<WorkShift> = emptyList(),
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

    init { load() }

    fun reload() {
        _uiState.update { it.copy(isLoading = true, loadingError = null) }
        load()
    }

    private fun load() {
        viewModelScope.launch {
            try {
                val shifts = writeMutex.withLock { withContext(Dispatchers.IO) { store.all() } }
                _uiState.update { it.copy(shifts = shifts, isLoading = false, loadingError = null) }
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
                    store.all()
                }
            }
            _uiState.update { it.copy(shifts = shifts, loadingError = null) }
            Result.success(Unit)
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            _uiState.update { it.copy(isBusy = false) }
        }
    }

    suspend fun save(shift: WorkShift): Result<Unit> = mutate { store.save(shift) }
    suspend fun remove(date: LocalDate): Result<Unit> = mutate { store.remove(date) }

    suspend fun generate(
        anchor: LocalDate,
        pattern: WorkPattern,
        startMinutes: Int,
        endMinutes: Int,
        hourlyKopecks: Long
    ): Result<Unit> = mutate {
        store.fillPattern(anchor, pattern, startMinutes, endMinutes, hourlyKopecks)
    }

    suspend fun exportBackup(uri: Uri): Result<Unit> {
        _uiState.update { it.copy(isBusy = true) }
        return try {
            writeMutex.withLock {
                withContext(Dispatchers.IO) {
                    val bytes = ShiftBackup.encode(store.all())
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
                    ShiftBackup.decode(output.toByteArray())
                }
            }
            val updated = writeMutex.withLock {
                withContext(Dispatchers.IO) {
                    store.replaceAll(restored)
                    store.all()
                }
            }
            _uiState.update { it.copy(shifts = updated, loadingError = null) }
            Result.success(restored.size)
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
