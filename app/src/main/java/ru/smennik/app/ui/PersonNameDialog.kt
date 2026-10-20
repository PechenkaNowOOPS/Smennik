package ru.smennik.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.launch

/** Local person profile editor. No registration, accounts or network. */
@Composable
fun PersonNameDialog(
    title: String,
    initialName: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: suspend (String) -> Result<Unit>
) {
    var name by remember(title,initialName) { mutableStateOf(initialName) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope=rememberCoroutineScope()
    AlertDialog(
        onDismissRequest={ if(!saving) onDismiss() },
        title={ Text(title) },
        text={
            Column {
                Text("У каждого человека — отдельные работы, календарь и заработок.")
                OutlinedTextField(
                    value=name,
                    onValueChange={ if(it.length<=60) name=it },
                    label={ Text("Имя или псевдоним") },
                    singleLine=true,modifier=Modifier.fillMaxWidth(),
                    keyboardOptions=KeyboardOptions(imeAction=ImeAction.Done)
                )
                if(error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error)
            }
        },
        confirmButton={
            Button(enabled=!busy && !saving && name.isNotBlank(),onClick={
                saving=true
                scope.launch {
                    onSave(name.trim()).fold(
                        onSuccess={onDismiss()},
                        onFailure={error=it.message ?: "Не удалось сохранить";saving=false}
                    )
                }
            }) { Text(if(saving)"Сохранение…" else "Сохранить") }
        },
        dismissButton={TextButton(enabled=!saving,onClick=onDismiss){Text("Отмена")}}
    )
}
