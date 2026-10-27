package com.boneink.picapica.network

import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** HTTP 400 is used for many unrelated PicACG errors; classify the actual body first. */
object ApiFailure {
    fun parse(http: Int, body: JSONObject, signingIn: Boolean, retryAfter: String? = null, now: Long = System.currentTimeMillis()): PicaException {
        val code = body.optInt("code", http)
        val error = body.optString("error").takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,32}")) }
        val text = body.optString("message").trim().lowercase(Locale.ROOT)
        val kind: PicaException.Kind
        val message: String
        var delay: Long? = null
        when {
            http == 429 || code == 429 || error == "1023" || "too many requests" in text || "rate limit" in text -> {
                kind = PicaException.Kind.RATE_LIMIT
                delay = retryDelay(retryAfter, now)
                message = "哔咔服务请求过多，请等待后再试（${error ?: http}）"
            }
            listOf("invalid signature", "invalid api key", "invalid nonce", "invalid timestamp", "signature verification", "unsupported version", "outdated version").any { it in text } -> {
                kind = PicaException.Kind.PROTOCOL
                message = if ("timestamp" in text) "请求时间校验失败，请检查手机日期和时间" else "客户端请求校验失败，请更新 PicaPica 后重试（${error ?: http}）"
            }
            signingIn && listOf("invalid email or password", "invalid username or password", "invalid credentials", "bad credentials", "wrong password", "invalid password", "incorrect password").any { it in text } -> {
                kind = PicaException.Kind.CREDENTIALS
                message = "账号或密码不正确，请确认使用原版哔咔的登录 ID"
            }
            !signingIn && (http == 401 || code == 401) -> {
                kind = PicaException.Kind.AUTH
                message = "登录已失效，请重新登录"
            }
            else -> {
                kind = PicaException.Kind.SERVICE
                message = "${if (signingIn) "登录服务" else "服务"}返回错误（HTTP $http${error?.let { " / $it" } ?: ""}），请查看错误详情"
            }
        }
        return PicaException(kind, message, http, code, error, delay)
    }

    private fun retryDelay(value: String?, now: Long): Long {
        value?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let { return it.coerceAtLeast(1) }
        val date = value?.let { runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull() }
        return if (date != null) ((date - now).coerceAtLeast(0) / 1000 + 1) else 60
    }
}

/** No automatic login retry. A server cooldown also survives an app/process restart. */
class ApiBackoff(
    private val now: () -> Long = System::currentTimeMillis,
    load: () -> Long = { 0L },
    private val save: (Long) -> Unit = {},
) {
    private val until = AtomicLong(load().coerceAtLeast(0))
    fun remainingSeconds(): Long {
        val delta = (until.get() - now()).coerceAtLeast(0)
        return delta / 1000 + if (delta % 1000 > 0) 1 else 0
    }
    @Synchronized fun blockFor(seconds: Long) {
        val duration = seconds.coerceIn(1, Long.MAX_VALUE / 1000) * 1000
        val instant = now()
        val deadline = if (Long.MAX_VALUE - instant < duration) Long.MAX_VALUE else instant + duration
        val value = maxOf(until.get(), deadline)
        until.set(value)
        save(value)
    }
    fun checkAllowed() {
        val remaining = remainingSeconds()
        if (remaining > 0) throw PicaException(PicaException.Kind.RATE_LIMIT, "服务仍在等待期，请稍后重试", retryAfterSeconds = remaining)
    }
}
