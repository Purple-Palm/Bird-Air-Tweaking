package io.github.purplepalm.birdair.phone.ui.logs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.purplepalm.birdair.phone.GhConfig
import io.github.purplepalm.birdair.phone.GitHubApi
import io.github.purplepalm.birdair.phone.LogStore
import io.github.purplepalm.birdair.phone.ScooterRepo
import io.github.purplepalm.birdair.phone.ui.Msg
import kotlinx.coroutines.launch

/**
 * The GitHub screen's body and footer (a stub hosting the old settings; P2 builds it). [onBack] leaves it; the shell
 * draws its back bar and handles Back.
 */
@Composable
fun GitHubScreen(@Suppress("UNUSED_PARAMETER") onBack: () -> Unit) {
    val gh by ScooterRepo.gh.collectAsStateWithLifecycle()
    val hasToken by ScooterRepo.hasToken.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 20.dp)) {
        Msg("Куда приложение кладёт логи. Репозиторий публичный: логи уходят обезличенными (имя и id самоката → scooter).")
        GhSettings(gh, hasToken)
    }
}

@Composable
private fun GhSettings(gh: GhConfig, hasToken: Boolean) {
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var err by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Field("Владелец (owner)", gh.owner) { v -> ScooterRepo.updateGh { it.copy(owner = v.trim()) } }
        Field("Репозиторий", gh.repo) { v -> ScooterRepo.updateGh { it.copy(repo = v.trim()) } }
        Field("Ветка", gh.branch) { v -> ScooterRepo.updateGh { it.copy(branch = v.trim()) } }
        Field("Папка", gh.folder) { v -> ScooterRepo.updateGh { it.copy(folder = v.trim()) } }
        OutlinedTextField(
            value = token, onValueChange = { token = it }, singleLine = true,
            label = { Text(if (hasToken) "Токен сохранён — вставь новый, чтобы заменить" else "Fine-grained токен (Contents: Read and write)") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        Msg("Токен хранится только на телефоне и нигде не показывается.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val clean = GitHubApi.cleanToken(token)
                if (clean.isEmpty()) { msg = "Вставь токен."; err = true; return@OutlinedButton }
                if (!GitHubApi.tokenOk(clean)) { msg = "В токене есть буквы не из латиницы или лишние знаки — скопируй его заново."; err = true; return@OutlinedButton }
                ScooterRepo.setToken(clean); token = ""; msg = "Токен сохранён в приложении."; err = false
            }, modifier = Modifier.weight(1f).height(48.dp)) { Text("Сохранить токен") }
            OutlinedButton(onClick = {
                msg = "Проверяю…"; err = false
                scope.launch { val r = LogStore.check(); msg = r; err = !r.startsWith("Доступ есть") }
            }, modifier = Modifier.weight(1f).height(48.dp)) { Text("Проверить доступ") }
        }
        if (hasToken) TextButton(onClick = { ScooterRepo.setToken(""); msg = "Токен удалён из приложения."; err = false }, modifier = Modifier.height(48.dp)) { Text("Забыть токен") }
        Msg(msg, err)
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    var v by rememberSaveable(label) { mutableStateOf(value) }
    OutlinedTextField(
        value = v, onValueChange = { v = it; onChange(it) }, singleLine = true, label = { Text(label) },
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth(),
    )
}
