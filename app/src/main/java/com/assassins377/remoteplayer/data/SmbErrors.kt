package com.assassins377.remoteplayer.data

import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.transport.TransportException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

enum class SmbErrorKind(val retryable: Boolean, val message: String) {
    AUTH(false, "Неверный логин или пароль"),
    ACCESS_DENIED(false, "Нет доступа к папке или файлу"),
    NOT_FOUND(false, "Папка, файл или общий ресурс не найдены"),
    HOST_UNKNOWN(false, "Сервер не найден (проверьте адрес)"),
    UNREACHABLE(true, "Сервер недоступен (выключен или другая сеть)"),
    TIMEOUT(true, "Превышено время ожидания ответа сервера"),
    NETWORK(true, "Ошибка сети, соединение прервано"),
    UNKNOWN(false, "Неизвестная ошибка"),
}

class SmbError(val kind: SmbErrorKind, cause: Throwable?) :
    IOException(kind.message + (cause?.message?.let { ": $it" } ?: ""), cause)

object SmbErrors {
    fun classify(t: Throwable): SmbErrorKind {
        var e: Throwable? = t
        val seen = HashSet<Throwable>()
        while (e != null && seen.add(e)) {
            classifyOne(e)?.let { return it }
            e = e.cause
        }
        return SmbErrorKind.UNKNOWN
    }

    fun classifyStatus(status: NtStatus): SmbErrorKind? = when (status) {
        NtStatus.STATUS_LOGON_FAILURE, NtStatus.STATUS_ACCOUNT_DISABLED,
        NtStatus.STATUS_PASSWORD_EXPIRED -> SmbErrorKind.AUTH
        NtStatus.STATUS_ACCESS_DENIED -> SmbErrorKind.ACCESS_DENIED
        NtStatus.STATUS_BAD_NETWORK_NAME, NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
        NtStatus.STATUS_OBJECT_PATH_NOT_FOUND, NtStatus.STATUS_NOT_FOUND -> SmbErrorKind.NOT_FOUND
        NtStatus.STATUS_NETWORK_NAME_DELETED, NtStatus.STATUS_CONNECTION_DISCONNECTED,
        NtStatus.STATUS_USER_SESSION_DELETED, NtStatus.STATUS_NETWORK_SESSION_EXPIRED -> SmbErrorKind.NETWORK
        else -> null
    }

    private fun classifyOne(e: Throwable): SmbErrorKind? = when (e) {
        is SmbError -> e.kind
        is SMBApiException -> classifyStatus(e.status) ?: SmbErrorKind.UNKNOWN
        is UnknownHostException -> SmbErrorKind.HOST_UNKNOWN
        is SocketTimeoutException, is TimeoutException -> SmbErrorKind.TIMEOUT
        is ConnectException, is NoRouteToHostException -> SmbErrorKind.UNREACHABLE
        is TransportException -> SmbErrorKind.NETWORK
        else -> null
    }

    fun wrap(t: Throwable): SmbError = t as? SmbError ?: SmbError(classify(t), t)

    /** Whether the pooled session should be dropped after this error. */
    fun invalidatesSession(kind: SmbErrorKind) = kind == SmbErrorKind.NETWORK || kind == SmbErrorKind.TIMEOUT ||
        kind == SmbErrorKind.UNREACHABLE || kind == SmbErrorKind.AUTH
}
