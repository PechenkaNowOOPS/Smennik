package ru.smennik.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import ru.smennik.app.data.ShiftViewModel
import ru.smennik.app.ui.SmennikApp

class MainActivity : ComponentActivity() {
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
                onExportBackup = model::exportBackup,
                onImportBackup = model::importBackup
            )
        }
    }
}
