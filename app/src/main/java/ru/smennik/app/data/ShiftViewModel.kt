package ru.smennik.app.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.smennik.app.domain.WorkPattern
import ru.smennik.app.domain.WorkShift
import java.time.LocalDate

class ShiftViewModel(application: Application) : AndroidViewModel(application) {
    private val store = ShiftStore(application.applicationContext)
    private val writeMutex = Mutex()
    private val _shifts = MutableStateFlow<List<WorkShift>>(emptyList())
    val shifts: StateFlow<List<WorkShift>> = _shifts
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    init { mutate { /* первоначальная загрузка */ } }

    private fun mutate(action: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            writeMutex.withLock {
                try {
                    action()
                    _shifts.value = store.all()
                    _error.value = null
                } catch (e: Exception) {
                    _error.value = "Не удалось сохранить данные: ${e.message ?: "неизвестная ошибка"}"
                }
            }
        }
    }

    fun save(shift: WorkShift) = mutate { store.save(shift) }
    fun remove(date: LocalDate) = mutate { store.remove(date) }
    fun generate(anchor: LocalDate, pattern: WorkPattern) = mutate {
        store.fillPattern(anchor, pattern, 480, 1200, 35_000L)
    }
    override fun onCleared() { store.close(); super.onCleared() }
}
