package com.boneink.picapica.network

import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Deliberately not a data class: credentials and security answers must not enter logs. */
class Registration(
    val account: String, val password: String, val name: String,
    val birthday: String, val gender: String,
    val questions: List<String>, val answers: List<String>,
) {
    fun validate() {
        validateBasics(account, password, name, birthday, gender)
        require(questions.size == 3 && answers.size == 3) { "请填写三组安全问答" }
        require(questions.all { it.trim().length in 2..100 } && answers.all { it.trim().length in 1..100 }) { "请完整填写三组安全问答" }
        require(questions.map { it.trim().lowercase() }.distinct().size == 3) { "请设置三个不同的安全问题" }
    }

    fun body(): JSONObject {
        validate()
        return JSONObject().put("email", account.trim()).put("password", password).put("name", name.trim())
            .put("birthday", birthday.replace('-', '/')).put("gender", gender).apply {
                repeat(3) { i -> put("question${i + 1}", questions[i].trim()); put("answer${i + 1}", answers[i].trim()) }
            }
    }

    companion object {
        fun validateBasics(account: String, password: String, name: String, birthday: String, gender: String) {
            require(account.trim().length in 2..64 && account.trim().none(Char::isWhitespace)) { "账号需为 2–64 个字符，不能包含空格" }
            require(password.length in 8..64) { "密码需为 8–64 个字符" }
            require(name.trim().length in 2..50) { "昵称需为 2–50 个字符" }
            val date = runCatching { LocalDate.parse(birthday.replace('/', '-'), DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()
            require(date != null && !date.isAfter(LocalDate.now()) && date.year >= 1900) { "请选择有效的生日" }
            require(gender in setOf("m", "f", "bot")) { "请选择性别" }
        }
    }
}
