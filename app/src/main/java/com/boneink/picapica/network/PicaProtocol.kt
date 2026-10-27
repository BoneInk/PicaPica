package com.boneink.picapica.network

import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Protocol compatibility based on niuhuan/pica-go (MIT); see third_party. */
object PicaProtocol {
    const val API_KEY = "C69BAF41DA5ABD1FFEDC6D2FEA56B"
    private const val SIGNING_KEY = "~d}\$Q7\$eIni=V)9\\RK/P.RM4;9[7|@/CA}b~OW!3?EV`:<>M7pddUBL5n|0/*Cn"

    fun signature(pathAndQuery: String, timestamp: String, nonce: String, method: String): String {
        val message = (pathAndQuery.removePrefix("/") + timestamp + nonce + method + API_KEY)
            .lowercase(Locale.ROOT)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(SIGNING_KEY.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
