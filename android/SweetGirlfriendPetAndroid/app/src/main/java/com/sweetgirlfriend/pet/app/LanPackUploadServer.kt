package com.sweetgirlfriend.pet.app

import android.content.Context
import com.sweetgirlfriend.pet.content.ContentPackInstaller
import com.sweetgirlfriend.pet.content.PackInstallResult
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

class LanPackUploadServer(
    context: Context,
    private val onInstallResult: (PackInstallResult) -> Unit,
) {
    data class Endpoint(
        /** Compatibility links containing the long random token. */
        val urls: List<String>,
        /** Short addresses intended for manual entry on the computer. */
        val browserUrls: List<String>,
        val pairingCode: String,
        val port: Int,
        val expiresAtMillis: Long,
        val diagnostics: List<String>,
    )

    private val applicationContext = context.applicationContext
    private val installer = ContentPackInstaller(applicationContext)
    private val acceptExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "petpack-lan-accept").apply { isDaemon = true }
    }
    private val clientExecutor = LanClientExecutorPolicy.create { runnable ->
        Thread(runnable, "petpack-lan-client").apply { isDaemon = true }
    }
    private val token = ByteArray(24).also(SecureRandom()::nextBytes).let {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it)
    }
    private val pairingCode = LanPairingPolicy.generateCode()
    private val pairingAttempts = LanPairingAttemptLimiter()
    private var serverSocket: ServerSocket? = null
    private var expiresAtMillis: Long = 0L
    @Volatile
    private var advertisedCandidates: List<LanNetworkDiscovery.Candidate> = emptyList()
    private val activeClients = ConcurrentHashMap.newKeySet<Socket>()

    @Volatile
    private var running = false

    fun start(): Endpoint {
        check(!running) { "局域网上传服务已经运行" }
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), 0), 16)
            soTimeout = 1_000
        }
        val network = LanNetworkDiscovery.inspect(applicationContext)
        advertisedCandidates = network.candidates
        serverSocket = socket
        running = true
        expiresAtMillis = System.currentTimeMillis() + SESSION_DURATION_MS
        acceptExecutor.execute { acceptLoop(socket) }
        val browserUrls = network.candidates.map {
            LanPairingPolicy.browserUrl(checkNotNull(it.address.hostAddress), socket.localPort)
        }
        val urls = network.candidates.map {
            LanPairingPolicy.fullUrl(checkNotNull(it.address.hostAddress), socket.localPort, token)
        }
        return Endpoint(
            urls = urls,
            browserUrls = browserUrls,
            pairingCode = pairingCode,
            port = socket.localPort,
            expiresAtMillis = expiresAtMillis,
            diagnostics = network.diagnostics,
        )
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        advertisedCandidates = emptyList()
        activeClients.forEach { runCatching { it.close() } }
        activeClients.clear()
        acceptExecutor.shutdownNow()
        clientExecutor.shutdownNow()
    }

    fun isRunning(): Boolean = running

    fun refreshEndpoint(): Endpoint {
        check(running) { "局域网上传服务未运行" }
        val socket = checkNotNull(serverSocket) { "局域网上传监听端口不可用" }
        val network = LanNetworkDiscovery.inspect(applicationContext)
        advertisedCandidates = network.candidates
        return Endpoint(
            browserUrls = network.candidates.map {
                LanPairingPolicy.browserUrl(checkNotNull(it.address.hostAddress), socket.localPort)
            },
            urls = network.candidates.map {
                LanPairingPolicy.fullUrl(checkNotNull(it.address.hostAddress), socket.localPort, token)
            },
            pairingCode = pairingCode,
            port = socket.localPort,
            expiresAtMillis = expiresAtMillis,
            diagnostics = network.diagnostics,
        )
    }

    private fun acceptLoop(socket: ServerSocket) {
        try {
            while (running && System.currentTimeMillis() < expiresAtMillis) {
                try {
                    val client = socket.accept()
                    client.soTimeout = CLIENT_TIMEOUT_MS
                    activeClients += client
                    try {
                        clientExecutor.execute {
                            try {
                                handleClient(client)
                            } catch (_: Exception) {
                                // A stalled or malformed peer must only end its own connection. A
                                // SocketTimeoutException escaping this executor is process-fatal on
                                // Android because it reaches the app's uncaught-exception handler.
                            } finally {
                                activeClients -= client
                                runCatching { client.close() }
                            }
                        }
                    } catch (_: RejectedExecutionException) {
                        activeClients -= client
                        respond(client, 503, "text/plain; charset=utf-8", "上传服务正忙，请稍后重试。")
                        runCatching { client.close() }
                    }
                } catch (_: SocketTimeoutException) {
                    // Periodically wake to enforce session expiry.
                }
            }
        } catch (_: Throwable) {
            // Closing the server socket is the normal stop path.
        } finally {
            running = false
            runCatching { socket.close() }
            activeClients.forEach { runCatching { it.close() } }
            activeClients.clear()
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            if (!isLocalPeer(client.inetAddress)) {
                respond(client, 403, "text/plain; charset=utf-8", "只允许局域网设备访问。")
                return
            }
            val input = BufferedInputStream(client.getInputStream())
            if (LanPairingPolicy.isExpired(System.currentTimeMillis(), expiresAtMillis)) {
                respond(client, 410, "text/plain; charset=utf-8", "上传会话已到期，请在手机上重新开启。")
                return
            }
            val requestLine = readAsciiLine(input, MAX_HEADER_LINE) ?: return
            val requestParts = requestLine.split(' ')
            if (requestParts.size < 2) {
                respond(client, 400, "text/plain; charset=utf-8", "请求格式错误。")
                return
            }
            val method = requestParts[0]
            val target = requestParts[1]
            val path = target.substringBefore('?')
            val headers = linkedMapOf<String, String>()
            val headerBudget = LanHttpHeaderBudget(
                maxLines = MAX_HEADERS,
                maxBytes = MAX_HEADER_BYTES,
                initialBytes = requestLine.length + 2,
            )
            while (true) {
                val line = readAsciiLine(input, MAX_HEADER_LINE) ?: return
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator <= 0) {
                    respond(client, 400, "text/plain; charset=utf-8", "请求头格式错误。")
                    return
                }
                val name = line.substring(0, separator).trim().lowercase()
                when (headerBudget.record(name, line.length + 2)) {
                    LanHeaderDecision.ACCEPT ->
                        headers[name] = line.substring(separator + 1).trim()

                    LanHeaderDecision.DUPLICATE -> {
                        respond(client, 400, "text/plain; charset=utf-8", "不支持重复请求头。")
                        return
                    }

                    LanHeaderDecision.TOO_MANY, LanHeaderDecision.TOO_LARGE -> {
                        respond(client, 431, "text/plain; charset=utf-8", "请求头过多或过大。")
                        return
                    }
                }
            }
            val authenticated = validCredential(target, headers)
            when {
                method == "GET" && path == "/" && authenticated ->
                    respond(client, 200, "text/html; charset=utf-8", uploadPage(LanPairingPolicy.queryValue(target, "token")))

                method == "GET" && path == "/" -> {
                    val invalidLongLink = LanPairingPolicy.queryValue(target, "token") != null
                    respond(
                        client,
                        if (invalidLongLink) 403 else 200,
                        "text/html; charset=utf-8",
                        pairingPage(if (invalidLongLink) "完整链接已失效，请改用手机当前显示的配对码。" else null),
                    )
                }

                method == "POST" && path == "/pair" -> receivePairing(client, input, headers)

                !authenticated && method == "POST" && path == "/upload" ->
                    respond(client, 403, "application/json; charset=utf-8", json(false, "配对已失效，请刷新电脑页面并重新输入配对码。"))

                !authenticated -> respond(client, 403, "text/html; charset=utf-8", deniedPage())

                method == "GET" && path == "/health" ->
                    respond(client, 200, "application/json; charset=utf-8", "{\"ok\":true}")

                method == "POST" && path == "/upload" ->
                    receiveUpload(client, input, headers)

                else -> respond(client, 404, "text/plain; charset=utf-8", "未找到页面。")
            }
        }
    }

    private fun receivePairing(
        client: Socket,
        input: BufferedInputStream,
        headers: Map<String, String>,
    ) {
        val peer = client.inetAddress.hostAddress ?: "unknown"
        val now = System.currentTimeMillis()
        val initialDecision = pairingAttempts.check(peer, now)
        if (!initialDecision.allowed) {
            val seconds = ((initialDecision.retryAfterMillis + 999L) / 1_000L).coerceAtLeast(1L)
            respond(
                client,
                429,
                "text/html; charset=utf-8",
                pairingPage("尝试次数过多，请 $seconds 秒后再试。"),
                mapOf("Retry-After" to seconds.toString()),
            )
            return
        }
        val contentLength = headers["content-length"]?.toLongOrNull() ?: -1L
        if (contentLength !in 1..MAX_PAIR_BODY_BYTES) {
            respond(client, 400, "text/html; charset=utf-8", pairingPage("配对请求格式不正确。"))
            return
        }
        if (!headers["content-type"].orEmpty().startsWith("application/x-www-form-urlencoded")) {
            respond(client, 415, "text/html; charset=utf-8", pairingPage("浏览器提交格式不受支持。"))
            return
        }
        val bytes = ByteArray(contentLength.toInt())
        var offset = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            if (count <= 0) {
                respond(client, 400, "text/html; charset=utf-8", pairingPage("配对请求提前断开。"))
                return
            }
            offset += count
        }
        val submitted = LanPairingPolicy.formValue(bytes.toString(StandardCharsets.UTF_8), "code")
        if (!LanPairingPolicy.codeMatches(submitted, pairingCode)) {
            val failureDecision = pairingAttempts.recordFailure(peer, now)
            if (!failureDecision.allowed) {
                val seconds = ((failureDecision.retryAfterMillis + 999L) / 1_000L).coerceAtLeast(1L)
                respond(
                    client,
                    429,
                    "text/html; charset=utf-8",
                    pairingPage("配对码不正确，尝试次数过多，请 $seconds 秒后再试。"),
                    mapOf("Retry-After" to seconds.toString()),
                )
            } else {
                respond(client, 403, "text/html; charset=utf-8", pairingPage("配对码不正确，请查看手机当前显示的 6 位数字。"))
            }
            return
        }
        pairingAttempts.recordSuccess(peer)
        val maxAgeSeconds = ((expiresAtMillis - now).coerceAtLeast(1L) / 1_000L).coerceAtLeast(1L)
        respond(
            client,
            303,
            "text/plain; charset=utf-8",
            "配对成功，正在打开上传页面。",
            mapOf(
                "Location" to "/",
                "Set-Cookie" to "${LanPairingPolicy.COOKIE_NAME}=$token; Path=/; Max-Age=$maxAgeSeconds; HttpOnly; SameSite=Strict",
            ),
        )
    }

    private fun receiveUpload(
        client: Socket,
        input: BufferedInputStream,
        headers: Map<String, String>,
    ) {
        val contentLength = headers["content-length"]?.toLongOrNull() ?: -1L
        if (contentLength !in 1..ContentPackInstaller.MAX_ARCHIVE_BYTES) {
            respond(client, 413, "application/json; charset=utf-8", json(false, "资源包为空或超过 96 MB。"))
            return
        }
        if (!headers["content-type"].orEmpty().startsWith("application/octet-stream")) {
            respond(client, 415, "application/json; charset=utf-8", json(false, "请上传 .petpack 文件。"))
            return
        }
        val upload = File(applicationContext.cacheDir, "lan-${UUID.randomUUID()}.petpack")
        try {
            upload.outputStream().buffered().use { destination ->
                var remaining = contentLength
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (remaining > 0) {
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    require(count > 0) { "上传连接提前断开" }
                    destination.write(buffer, 0, count)
                    remaining -= count
                }
            }
            val result = installer.install(upload)
            onInstallResult(result)
            when (result) {
                is PackInstallResult.Success -> respond(
                    client,
                    200,
                    "application/json; charset=utf-8",
                    json(
                        true,
                        if (result.unchanged) {
                            "${result.name} v${result.version} 内容相同，无需重复安装"
                        } else {
                            "已安装 ${result.name} v${result.version}"
                        },
                    ),
                )

                is PackInstallResult.Failure -> respond(
                    client,
                    422,
                    "application/json; charset=utf-8",
                    json(false, result.message),
                )
            }
        } catch (error: Throwable) {
            respond(client, 400, "application/json; charset=utf-8", json(false, error.message ?: "上传失败"))
        } finally {
            upload.delete()
        }
    }

    private fun validCredential(target: String, headers: Map<String, String>): Boolean =
        LanPairingPolicy.credentialMatches(target, headers["cookie"], token)

    private fun respond(
        socket: Socket,
        status: Int,
        contentType: String,
        body: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        runCatching {
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            val reason = when (status) {
                200 -> "OK"
                303 -> "See Other"
                400 -> "Bad Request"
                403 -> "Forbidden"
                404 -> "Not Found"
                410 -> "Gone"
                413 -> "Payload Too Large"
                415 -> "Unsupported Media Type"
                422 -> "Unprocessable Content"
                429 -> "Too Many Requests"
                431 -> "Request Header Fields Too Large"
                503 -> "Service Unavailable"
                else -> "Error"
            }
            BufferedOutputStream(socket.getOutputStream()).use { output ->
                output.write("HTTP/1.1 $status $reason\r\n".toByteArray(StandardCharsets.US_ASCII))
                output.write("Content-Type: $contentType\r\n".toByteArray(StandardCharsets.US_ASCII))
                output.write("Content-Length: ${bytes.size}\r\n".toByteArray(StandardCharsets.US_ASCII))
                extraHeaders.forEach { (name, value) ->
                    output.write("$name: $value\r\n".toByteArray(StandardCharsets.US_ASCII))
                }
                output.write(
                    ("Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\n" +
                        "Referrer-Policy: no-referrer\r\n" +
                        "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'\r\n" +
                        "Connection: close\r\n\r\n")
                        .toByteArray(StandardCharsets.US_ASCII),
                )
                output.write(bytes)
            }
        }
    }

    private fun readAsciiLine(input: BufferedInputStream, maxLength: Int): String? {
        val bytes = ArrayList<Byte>()
        while (bytes.size <= maxLength) {
            val value = input.read()
            if (value < 0) return null
            if (value == '\n'.code) {
                if (bytes.lastOrNull() == '\r'.code.toByte()) bytes.removeAt(bytes.lastIndex)
                return bytes.toByteArray().toString(StandardCharsets.US_ASCII)
            }
            bytes.add(value.toByte())
        }
        return null
    }

    private fun isLocalPeer(address: InetAddress): Boolean {
        if (LanAddressPolicy.isLanScoped(address)) return true
        val ipv4 = address as? Inet4Address ?: return false
        return advertisedCandidates.any { candidate ->
            LanAddressPolicy.sameSubnet(ipv4, candidate.address, candidate.prefixLength)
        }
    }

    private fun json(ok: Boolean, message: String): String = JSONObject()
        .put("ok", ok)
        .put("message", LocalPackScanPolicy.safeUiText(message, 500))
        .toString()

    private fun deniedPage(): String = """
        <!doctype html><meta charset="utf-8"><title>访问已拒绝</title>
        <style>body{font:16px system-ui;background:#fff7fa;color:#4c303b;padding:40px}</style>
        <h2>访问链接无效</h2><p>请返回电脑地址首页并输入手机当前显示的 6 位配对码。上传会话关闭后，旧地址和配对状态立即失效。</p>
    """.trimIndent()

    private fun pairingPage(error: String?): String = """
        <!doctype html>
        <html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>SweetPet 局域网配对</title>
        <style>
        body{font:16px system-ui;margin:0;background:#fff7fa;color:#4c303b}.box{max-width:520px;margin:10vh auto;padding:28px;background:white;border:1px solid #f3dce5;border-radius:24px;box-shadow:0 12px 40px #6b324018}h1{margin-top:0}.hint{color:#8d707b}.error{padding:12px;background:#fff0eb;color:#a33218;border-radius:12px}input{box-sizing:border-box;width:100%;padding:15px;text-align:center;font-size:26px;letter-spacing:.3em;border:2px solid #e6b7c8;border-radius:14px}button{width:100%;margin-top:14px;padding:14px;border:0;border-radius:14px;background:#c9577d;color:white;font-size:17px}
        </style></head><body><main class="box"><h1>连接手机桌宠</h1>
        <p class="hint">在手机“局域网上传”中查看 6 位配对码。配对和上传服务会在 15 分钟后关闭。</p>
        ${if (error == null) "" else "<p class=\"error\">${html(error)}</p>"}
        <form method="post" action="/pair"><input name="code" inputmode="numeric" autocomplete="one-time-code" maxlength="8" pattern="[0-9 -]{6,8}" autofocus required aria-label="6 位配对码"><button type="submit">配对并打开上传页</button></form>
        </main></body></html>
    """.trimIndent()

    private fun uploadPage(authToken: String?): String = """
        <!doctype html>
        <html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>SweetPet 资源包上传</title>
        <style>
        body{font:16px system-ui;margin:0;background:#fff7fa;color:#4c303b}.box{max-width:620px;margin:8vh auto;padding:28px;background:white;border:1px solid #f3dce5;border-radius:24px;box-shadow:0 12px 40px #6b324018}
        h1{margin-top:0}.drop{display:block;padding:36px 18px;text-align:center;background:#fff0f5;border:2px dashed #cb587f;border-radius:18px;cursor:pointer}button{width:100%;margin-top:18px;padding:14px;border:0;border-radius:14px;background:#c9577d;color:#fff;font-size:17px}button:disabled{opacity:.5}small{color:#8d707b}#status{min-height:24px;margin-top:16px;white-space:pre-wrap}
        </style></head><body><main class="box"><h1>上传桌宠资源包</h1>
        <p>选择由 SweetPet Pack Protocol v2 工具生成的 <code>.petpack</code> 文件。</p>
        <label class="drop"><input id="file" type="file" accept=".petpack,.zip" hidden><span id="name">点击选择文件（最大 96 MB）</span></label>
        <button id="send" disabled>上传并安装</button><div id="status"></div>
        <small>资源包只能包含声明式数据与媒体文件。应用会验证协议、路径、大小、扩展点和 SHA-256。</small></main>
        <script>
        const token=${if (authToken == null) "null" else jsString(authToken)}, file=document.querySelector('#file'), send=document.querySelector('#send'), name=document.querySelector('#name'), status=document.querySelector('#status');
        file.onchange=()=>{const f=file.files[0];name.textContent=f?f.name:'点击选择文件';send.disabled=!f};
        send.onclick=async()=>{const f=file.files[0];if(!f)return;send.disabled=true;status.textContent='正在上传并校验，请不要关闭页面…';
          try{const target=token?'/upload?token='+encodeURIComponent(token):'/upload';const r=await fetch(target,{method:'POST',headers:{'Content-Type':'application/octet-stream','X-Filename':f.name},body:f});const j=await r.json();status.textContent=j.message;if(!j.ok)throw new Error(j.message)}
          catch(e){status.textContent='失败：'+e.message}finally{send.disabled=false}}
        </script></body></html>
    """.trimIndent()

    private fun jsString(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    private fun html(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    companion object {
        private const val SESSION_DURATION_MS = 15 * 60_000L
        private const val CLIENT_TIMEOUT_MS = 30_000
        private const val MAX_HEADER_LINE = 8 * 1024
        private const val MAX_HEADERS = 64
        private const val MAX_HEADER_BYTES = 32 * 1024
        private const val MAX_PAIR_BODY_BYTES = 128L
    }
}
