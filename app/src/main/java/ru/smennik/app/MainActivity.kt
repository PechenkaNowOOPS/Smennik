package ru.smennik.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import ru.smennik.app.data.ShiftViewModel
import ru.smennik.app.ui.SmennikApp
import androidx.lifecycle.ViewModelProvider

class MainActivity : ComponentActivity() {
    // Actions triggered from the notification mutate the same SQLite DB outside Compose.
    // Refresh on returning to the activity to display the authoritative timer state.
    override fun onResume() {
        super.onResume()
        ViewModelProvider(this)[ShiftViewModel::class.java].reload()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val model: ShiftViewModel = viewModel()
            val state by model.uiState.collectAsState()
            SmennikApp(
                state = state,
                onRetryLoad = model::reload,
                onSave = model::save,
                onRemove = model::remove,
                onPattern = model::generate,
                onCustomPattern = model::generateCustom,
                onBulkPlan = model::applyBulkPlan,
                onBulkMove = model::movePlannedShifts,
                onAddWorkplace = model::addWorkplace,
                onUpdatePayRules = model::updatePayRules,
                onUpdateMonthlyGoal = model::updateMonthlyGoal,
                onExportBackup = model::exportBackup,
                onImportBackup = model::importBackup,
                onStartWork = model::startWork,
                onTogglePause = model::togglePause,
                onFinishWork = model::finishWork,
                onPayment = model::addPayment,
                onRemovePayment = model::removePayment,
                onEditFinishedSession = model::editFinishedSession,
                onAddHistoricalSession = model::addHistoricalSession,
                onEditDetailedSession = model::editDetailedSession,
                onDeleteFinishedSession = model::deleteFinishedSession,
                onExportCsv = model::exportMonthlyCsv
            )
        }
    }
}
