@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.boneink.picapica.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.boneink.picapica.network.Registration
import kotlinx.coroutines.*
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneOffset

@Composable
private fun AccountLayout(tag: String, scroll: ScrollState = rememberScrollState(), content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(scroll).testTag(tag), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
    }
}

@Composable
private fun AccountHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer,
            shadowElevation = PicaDepth.panel, border = panelBorder()) {
            Icon(Icons.Outlined.AutoStories, null, Modifier.padding(16.dp).size(28.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String, tag: String,
    enabled: Boolean = true, onDone: (() -> Unit)? = null) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, enabled = enabled,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = if (onDone != null) ImeAction.Done else ImeAction.Next),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        trailingIcon = { IconButton(onClick = { visible = !visible }) {
            Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (visible) "隐藏密码" else "显示密码")
        } }, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().testTag(tag))
}

@Composable
private fun AccountError(state: UiState, tag: String) {
    val clipboard = LocalClipboardManager.current
    var expanded by remember(state.error, state.errorDetails) { mutableStateOf(false) }
    state.error?.let { error ->
        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.testTag(tag))
                state.errorDetails?.let { details ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("$tag-toggle")) {
                            Text(if (expanded) "收起详情" else "错误详情", style = MaterialTheme.typography.bodySmall)
                            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(16.dp))
                        }
                        TextButton(onClick = { clipboard.setText(AnnotatedString("PicaPica ${com.boneink.picapica.BuildConfig.VERSION_NAME}\n$details")) }) { Text("复制错误信息", style = MaterialTheme.typography.bodySmall) }
                    }
                    if (expanded) Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.testTag("$tag-details"))
                }
            }
        }
    }
}

@Composable
private fun cooldown(state: UiState, model: PicaViewModel): Long {
    val remaining by produceState(model.repo.api.backoff.remainingSeconds(), state.error, state.loading) {
        do { value = model.repo.api.backoff.remainingSeconds(); if (value > 0) delay(1000) } while (value > 0)
    }
    return remaining
}

@Composable
internal fun LoginScreen(state: UiState, model: PicaViewModel) {
    var account by rememberSaveable(state.registeredAccount) { mutableStateOf(state.registeredAccount.orEmpty()) }
    var password by remember { mutableStateOf("") }
    val remaining = cooldown(state, model)
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val canSubmit = !state.loading && remaining == 0L && account.isNotBlank() && password.isNotEmpty()
    val submit = { if (canSubmit) { focus.clearFocus(); keyboard?.hide(); model.login(account, password) } }
    AccountLayout("login-form") {
        AccountHeading("欢迎回来", "登录 PicACG，继续你的阅读。")
        PicaPanel {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                OutlinedTextField(account, { account = it }, label = { Text("账号（登录 ID）") }, singleLine = true, enabled = !state.loading,
                    leadingIcon = { Icon(Icons.Outlined.PersonOutline, null, Modifier.size(20.dp)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().testTag("login-account"))
                PasswordField(password, { password = it }, "密码", "login-password", !state.loading, submit)
                Button(submit, enabled = canSubmit, shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().height(50.dp).testTag("login-submit")) {
                    if (state.loading) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    else Text(if (remaining > 0) "请等待 $remaining 秒" else "登录")
                }
                AccountError(state, "login-error")
                HorizontalDivider(thickness = .5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Text("还没有账号？", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { password = ""; model.navigate(Screen.REGISTER) }, enabled = !state.loading, modifier = Modifier.testTag("open-register")) { Text("注册账号") }
                }
            }
        }
        ConnectionSelector(model)
        TextButton(onClick = { password = ""; model.tab(Screen.LIBRARY) }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("浏览本地书架") }
    }
}

@Composable
internal fun RegisterScreen(state: UiState, model: PicaViewModel) {
    var step by remember { mutableIntStateOf(0) }
    var account by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var birthday by rememberSaveable { mutableStateOf("") }
    var choosingBirthday by rememberSaveable { mutableStateOf(false) }
    var gender by rememberSaveable { mutableStateOf("") }
    val questions = remember { mutableStateListOf("", "", "") }
    val answers = remember { mutableStateListOf("", "", "") }
    var validation by remember { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val remaining = cooldown(state, model)
    LaunchedEffect(step) { scroll.scrollTo(0) }
    BackHandler(step == 1 && !state.loading) { step = 0; validation = null }
    AccountLayout("register-form", scroll) {
        AccountHeading("创建账号", if (step == 0) "设置你的 PicACG 账号与个人资料。" else "安全问答用于找回账号，请妥善保存。")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("账号资料", "安全问答").forEachIndexed { i, title ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(progress = { if (i <= step) 1f else 0f }, modifier = Modifier.fillMaxWidth(), trackColor = MaterialTheme.colorScheme.outlineVariant)
                    Text("${i + 1}  $title", style = MaterialTheme.typography.labelMedium, color = if (i == step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        PicaPanel {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (step == 0) {
                    OutlinedTextField(account, { account = it.take(64) }, label = { Text("账号（登录 ID）") }, supportingText = { Text("2–64 个字符，用于登录") },
                        singleLine = true, enabled = !state.loading, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().testTag("register-account"))
                    OutlinedTextField(name, { name = it.take(50) }, label = { Text("昵称") }, singleLine = true, enabled = !state.loading,
                        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().testTag("register-name"))
                    PasswordField(password, { password = it.take(64) }, "密码（至少 8 位）", "register-password", !state.loading)
                    PasswordField(confirmation, { confirmation = it.take(64) }, "确认密码", "register-confirm", !state.loading)
                    OutlinedTextField(birthday, { birthday = it.take(10) }, label = { Text("生日") }, placeholder = { Text("YYYY-MM-DD") },
                        singleLine = true, enabled = !state.loading, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        trailingIcon = { IconButton(onClick = {
                            focus.clearFocus(); keyboard?.hide()
                            choosingBirthday = true
                        }, enabled = !state.loading) { Icon(Icons.Outlined.CalendarMonth, "选择生日") } }, shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().testTag("register-birthday"))
                    Text("性别", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("m" to "男", "f" to "女", "bot" to "机器人").forEach { (code, label) ->
                            QuietChip(label, gender == code, Modifier.testTag("register-gender-$code")) { gender = code }
                        }
                    }
                } else repeat(3) { index ->
                    Text("安全问题 ${index + 1}", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(questions[index], { questions[index] = it.take(100) }, label = { Text("自定义问题") }, enabled = !state.loading,
                        singleLine = true, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().testTag("register-question-${index + 1}"))
                    OutlinedTextField(answers[index], { answers[index] = it.take(100) }, label = { Text("答案") }, enabled = !state.loading,
                        singleLine = true, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().testTag("register-answer-${index + 1}"))
                    if (index < 2) HorizontalDivider(thickness = .5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }
                validation?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("register-validation")) }
                Button(onClick = {
                    focus.clearFocus(); keyboard?.hide()
                    validation = runCatching {
                        Registration.validateBasics(account, password, name, birthday, gender)
                        require(password == confirmation) { "两次输入的密码不一致" }
                        if (step == 0) step = 1
                        else {
                            val form = Registration(account, password, name, birthday, gender, questions.toList(), answers.toList())
                            form.validate(); model.register(form)
                        }
                    }.exceptionOrNull()?.message
                }, enabled = !state.loading && remaining == 0L, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().height(50.dp).testTag("register-submit")) {
                    if (state.loading) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    else Text(if (remaining > 0) "请等待 $remaining 秒" else if (step == 0) "下一步" else "创建账号")
                }
                if (step == 1) AccountError(state, "register-error")
                if (step == 1) TextButton(onClick = { step = 0; validation = null }, enabled = !state.loading, modifier = Modifier.fillMaxWidth().testTag("register-previous")) { Text("上一步") }
            }
        }
        TextButton(onClick = model::goBack, enabled = !state.loading, modifier = Modifier.align(Alignment.CenterHorizontally).testTag("register-login")) { Text("已有账号？去登录") }
    }
    if (choosingBirthday) BirthdayPicker(birthday, { choosingBirthday = false }) { birthday = it; choosingBirthday = false }
}

@Composable
private fun BirthdayPicker(current: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    val today = remember { LocalDate.now() }
    val initial = remember(current) { runCatching { LocalDate.parse(current) }.getOrDefault(today.minusYears(18)).coerceIn(LocalDate.of(1900, 1, 1), today) }
    val dates = remember(today) { object : SelectableDates {
        override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    } }
    val picker = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        yearRange = 1900..today.year, selectableDates = dates)
    DatePickerDialog(onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { picker.selectedDateMillis?.let { onSelect(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) } },
            enabled = picker.selectedDateMillis != null, modifier = Modifier.testTag("birthday-confirm")) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }) {
        DatePicker(picker, title = { Text("选择生日", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 24.dp, top = 16.dp)) })
    }
}

@Composable
internal fun EditProfileScreen(state: UiState, model: PicaViewModel) {
    val user = state.selfProfile ?: return
    var slogan by rememberSaveable(user.id) { mutableStateOf(user.slogan) }
    var avatar by remember(user.id) { mutableStateOf<ByteArray?>(null) }
    var decoding by remember { mutableStateOf(false) }
    var imageError by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dirty = slogan.trim() != user.slogan || avatar != null
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            decoding = true; imageError = null
            try { avatar = withContext(Dispatchers.IO) { prepareAvatar(context, uri) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { imageError = "图片无法读取，请选择其他图片" }
            finally { decoding = false }
        }
    }
    BackHandler(dirty && !state.loading) { discard = true }
    AccountLayout("edit-profile-form") {
        PicaPanel {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box {
                    if (avatar == null) UserAvatar(user, Modifier.size(88.dp))
                    else AsyncImage(avatar, "待保存的头像", contentScale = ContentScale.Crop, modifier = Modifier.size(88.dp).clip(CircleShape).testTag("profile-avatar-preview"))
                    Surface(Modifier.align(Alignment.BottomEnd), shape = CircleShape, color = MaterialTheme.colorScheme.primary, shadowElevation = PicaDepth.panel) {
                        Icon(Icons.Outlined.PhotoCamera, null, Modifier.padding(6.dp).size(16.dp), tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
                TextButton(onClick = { picker.launch("image/*") }, enabled = !state.loading && !decoding, modifier = Modifier.testTag("profile-pick-avatar")) { Text(if (decoding) "正在处理图片" else "更换头像") }
                Text("图片将居中裁剪为方形", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                imageError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        }
        PicaPanel {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("个人资料", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("昵称", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text(user.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End, modifier = Modifier.weight(2f))
                }
                HorizontalDivider(thickness = .5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                OutlinedTextField(slogan, { slogan = it.take(100) }, label = { Text("个性签名") }, placeholder = { Text("写下你的阅读心情") },
                    minLines = 3, maxLines = 5, enabled = !state.loading, supportingText = { Text("${slogan.length} / 100") },
                    shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().testTag("profile-slogan"))
                Text("目前支持修改头像和个性签名。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Button(onClick = { focus.clearFocus(); keyboard?.hide(); model.saveProfile(slogan, avatar) }, enabled = dirty && !state.loading && !decoding,
            modifier = Modifier.fillMaxWidth().height(50.dp).testTag("profile-save"), shape = MaterialTheme.shapes.medium) {
            if (state.loading) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp) else Text("保存修改")
        }
        AccountError(state, "profile-save-error")
        TextButton(onClick = { if (dirty) discard = true else model.goBack() }, enabled = !state.loading, modifier = Modifier.align(Alignment.CenterHorizontally).testTag("profile-edit-cancel")) { Text("取消") }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("放弃本次修改？") }, text = { Text("尚未保存的头像和签名会被放弃。") },
        confirmButton = { TextButton(onClick = { discard = false; model.goBack() }, modifier = Modifier.testTag("profile-discard")) { Text("放弃修改") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } })
}
