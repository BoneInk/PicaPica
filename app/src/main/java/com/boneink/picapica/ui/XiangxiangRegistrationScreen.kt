package com.boneink.picapica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.boneink.picapica.network.XiangxiangRegistration
import kotlinx.coroutines.delay

@Composable
internal fun XiangxiangRegistrationScreen(state: UiState, model: PicaViewModel) {
    var account by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var emailCode by remember { mutableStateOf("") }
    var captcha by remember { mutableStateOf("") }
    var validation by remember { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current
    LaunchedEffect(model.account.id) { model.loadCaptcha() }
    LaunchedEffect(state.captchaImage) { captcha = "" }
    LaunchedEffect(email) { emailCode = "" }
    val seconds by produceState(0L, state.registrationCodeRetryAt) {
        do {
            value = ((state.registrationCodeRetryAt - android.os.SystemClock.elapsedRealtime() + 999) / 1000).coerceAtLeast(0)
            if (value > 0) delay(1000)
        } while (value > 0)
    }
    AccountLayout("register-form") {
        Text("注册香香腐宅", style = MaterialTheme.typography.titleMedium)
        PicaPanel {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(account, { account = it.take(12) }, label = { Text("账号（4–12 位字母或数字）") }, singleLine = true,
                    enabled = !state.loading, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().testTag("register-account"))
                PasswordField(password, { password = it.take(128) }, "密码", "register-password", !state.loading)
                PasswordField(confirmation, { confirmation = it.take(128) }, "确认密码", "register-confirm", !state.loading)
                OutlinedTextField(email, { email = it.take(254) }, label = { Text("邮箱") }, singleLine = true, enabled = !state.loading && !state.registrationCodeSending,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().testTag("register-email"))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(emailCode, { emailCode = it.take(128) }, label = { Text("邮箱验证码") }, singleLine = true, enabled = !state.loading,
                        shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f).testTag("register-email-code"))
                    TextButton(onClick = { focus.clearFocus(); model.requestRegistrationCode(email) },
                        enabled = !state.loading && !state.registrationCodeSending && seconds == 0L && !state.captchaLoading && state.captchaImage != null,
                        modifier = Modifier.testTag("register-send-code")) {
                        Text(if (state.registrationCodeSending) "正在发送" else if (seconds > 0) "${seconds}秒后重试" else "获取验证码")
                    }
                }
                state.registrationCodeError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("register-email-error")) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(captcha, { captcha = it.take(16) }, label = { Text("图片验证码") }, singleLine = true, enabled = !state.loading && !state.captchaLoading,
                        shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f).testTag("register-captcha"))
                    TextButton(onClick = model::loadCaptcha, enabled = !state.loading && !state.captchaLoading, modifier = Modifier.testTag("register-refresh-captcha")) {
                        if (state.captchaLoading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        else if (state.captchaImage != null) AsyncImage(state.captchaImage, "刷新验证码", modifier = Modifier.size(100.dp, 46.dp))
                        else Text("加载验证码")
                    }
                }
                state.captchaError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                validation?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("register-validation")) }
                AccountError(state, "register-error")
                Button(onClick = {
                    val form = XiangxiangRegistration(account, password, confirmation, email, emailCode, captcha)
                    try { form.validate(); validation = null; focus.clearFocus(); model.registerXiangxiang(form) }
                    catch (e: IllegalArgumentException) { validation = e.message }
                }, enabled = !state.loading && !state.registrationCodeSending && !state.captchaLoading && state.captchaImage != null,
                    shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("register-submit")) {
                    if (state.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Text("创建账号")
                }
            }
        }
        TextButton(onClick = model::goBack, enabled = !state.loading, modifier = Modifier.align(Alignment.CenterHorizontally).testTag("register-login")) { Text("已有账号？去登录") }
    }
}
