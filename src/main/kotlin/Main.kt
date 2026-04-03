package cc.getportal.demo

import cc.getportal.PortalClient
import cc.getportal.PortalClientConfig
import cc.getportal.PollOptions
import cc.getportal.AsyncOperation
import cc.getportal.model.*
import io.javalin.Javalin
import io.javalin.http.HttpStatus
import io.javalin.http.staticfiles.Location
import io.javalin.websocket.WsContext
import org.slf4j.LoggerFactory
import com.google.gson.Gson
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

private val logger = LoggerFactory.getLogger("Bootstrap")
var recurringPaymentThread: ScheduledFuture<*>? = null
var javalinApp: Javalin? = null
var portalClient: PortalClient? = null

data class DaemonVersionInfo(val version: String, val git_commit: String)

private fun pollOpts(): PollOptions = PollOptions.defaults().intervalMs(1000).timeoutMs(300000)

fun main() {
    logger.info("Starting portal demo backend...")

    val restUrl = System.getenv("REST_URL")
    if (restUrl == null) {
        logger.error("missing REST_URL env variable")
        return
    }

    val token = System.getenv("REST_TOKEN")
    if (token == null) {
        logger.error("missing REST_TOKEN env variable")
        return
    }

    // build frontend
    if (System.getenv("DEV_MODE") == "true") {
        buildFrontend()
    }

    val dbPath = System.getenv("DB_PATH")
    if (dbPath == null) {
        logger.error("missing DB_PATH env variable")
        return
    }

    logger.info("Connecting to database...")
    DB.connect(dbPath, "data.db")

    logger.info("Connecting to Portal...")
    val client = PortalClient(
        PortalClientConfig.create(restUrl, token)
            .autoPolling(1000)
    )
    portalClient = client

    // Fetch sdk-daemon version
    val daemonVersion: DaemonVersionInfo = try {
        val v = client.version()
        DaemonVersionInfo(v.version, v.gitCommit)
    } catch (e: Exception) {
        logger.warn("Could not fetch daemon version: ${e.message}")
        DaemonVersionInfo("unknown", "unknown")
    }
    logger.info("sdk-daemon version: ${daemonVersion.version} (${daemonVersion.git_commit.take(7)})")

    // start web app after a few seconds
    logger.info("Starting webserver in a few seconds...")
    Thread.sleep(1000 * 5)
    startWebApp(client, daemonVersion)

    // TODO: listenClosedRecurringPayments is not supported in the REST SDK.
    // It was a WebSocket-specific continuous listener. Needs a webhook-based approach instead.
    // try {
    //     listenClosedRecurringPayments(client)
    // } catch (e: Exception) {
    //     logger.error("Error in listenClosedRecurringPayments", e)
    // }

    try {
        startRecurringPaymentThread(client)
    } catch (e: Exception) {
        logger.error("Error in startRecurringPaymentThread", e)
    }
}

fun startRecurringPaymentThread(client: PortalClient) {
    val scheduler = Executors.newScheduledThreadPool(1)
    recurringPaymentThread = scheduler.scheduleAtFixedRate({
        val now = Instant.now()

        val subscriptions = DB.getDueSubscriptions(now)
        for (subscription in subscriptions) {

            val recentPayments = DB.getSubscriptionRecentPayments(subscription.user, subscription.data.portalSubscriptionId, limit = 3)
            if (recentPayments.size >= 3 && recentPayments.all { it.paid == null || !it.paid }) {
                logger.warn("Cancelling subscription {} due to 3 consecutive payment failures", subscription.data.portalSubscriptionId)
                DB.updateSubscriptionStatus(subscription.data.id, SubscriptionStatus.FAILED)

                try {
                    client.closeRecurringPayment(subscription.user, emptyList(), subscription.data.portalSubscriptionId)
                } catch (e: Exception) {
                    logger.warn("Error closing recurring payment: {}", e.message)
                }
                continue
            }

            // REQUESTING SUBSCRIPTION INVOICE
            val description = "Payment for subscription ${subscription.data.portalSubscriptionId}"

            var currency = Currency.MILLISATS
            if (subscription.data.currency != "Millisats") {
                currency = Currency.FIAT(subscription.data.currency)
            }
            val paymentId = DB.registerPayment(subscription.user, currency, subscription.data.amount, description, subscription.data.portalSubscriptionId)

            val req = SinglePaymentRequestContent(description, subscription.data.amount, currency, subscription.data.portalSubscriptionId, null, null)

            Thread {
                try {
                    val op = client.requestSinglePayment(subscription.user, emptyList(), req)
                    val result = client.pollUntilComplete(op, pollOpts())

                    when (result.status) {
                        "paid" -> {
                            DB.updatePaymentStatus(paymentId, paid = true)

                            try {
                                val nextOccurrence = client.calculateNextOccurrence(subscription.data.frequency, now.epochSecond)
                                if (nextOccurrence != null) {
                                    val nextAt = Instant.ofEpochSecond(nextOccurrence)
                                    DB.updateSubscriptionLastPayment(subscription.data.id, now, nextAt)
                                    logger.info("User paid invoice of subscription {}", subscription.data.portalSubscriptionId)
                                } else {
                                    logger.error("Error calculating next occurrence for subscription {}", subscription.data.portalSubscriptionId)
                                }
                            } catch (e: Exception) {
                                logger.error("Error calculating next occurrence for subscription {}", subscription.data.portalSubscriptionId)
                            }
                        }
                        "user_approved", "user_success" -> {}
                        else -> {
                            DB.updatePaymentStatus(paymentId, paid = false)
                            logger.info("User did not pay invoice of subscription {}", subscription.data.portalSubscriptionId)
                        }
                    }
                } catch (e: Exception) {
                    logger.info("Error requesting invoice of subscription {}, {}", subscription.data.portalSubscriptionId, e.message)
                }
            }.start()
        }
    }, 0, 1, TimeUnit.MINUTES)
}

fun startWebApp(client: PortalClient, daemonVersion: DaemonVersionInfo) {
    val app = Javalin.create { config ->
        run {
            config.bundledPlugins.enableCors { cors ->
                cors.addRule {
                    it.anyHost()
                }
            }

            config.spaRoot.addFile("/", "/static/index.html")
            config.staticFiles.add { staticFiles ->
                staticFiles.hostedPath = "/"
                staticFiles.directory = "/static"
                staticFiles.location = Location.CLASSPATH
            }
        }
    }
    javalinApp = app
        .get("/healthcheck") { ctx -> ctx.status(HttpStatus.OK).result("OK") }
        .start(7070)

    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info("Shutdown hook: closing resources...")
        try {
            recurringPaymentThread?.cancel(true)
            portalClient?.close()
            DB.disconnect()
            app.stop()
        } catch (e: Exception) {
            logger.warn("Error during shutdown", e)
        }
        logger.info("Shutdown complete.")
    })

    app.exception(Exception::class.java) { e, ctx ->
        logger.error("Server unexpected error", e)
    }

    app.wsException(Exception::class.java) { e, ctx ->
        logger.error("Server ws unexpected error", e)
    }

    app.ws("ws", { ws ->
        ws.onConnect { ctx ->
            logger.debug("New connection ${ctx.host()}")
            ctx.sendSuccess("DaemonVersion", mapOf(
                "version" to daemonVersion.version,
                "git_commit" to daemonVersion.git_commit.take(7)
            ))
        }
        ws.onClose { ctx ->
        }

        ws.onMessage { ctx ->
            if (ctx.message() == "PING") {
                ctx.send("PONG")
                return@onMessage
            }

            val command = ctx.message().split(",")
            val cmd = command.first()
            when (cmd) {
                "GenerateQRCode" -> {
                    var staticToken: String? = command.getOrNull(1)
                    if (staticToken.isNullOrEmpty()) {
                        staticToken = null
                    }
                    generateQR(client, ctx, staticToken)
                }
                "LoginWithNip05" -> {
                    if (command.size < 2) {
                        ctx.sendErr("Malformed message: LoginWithNip05 requires nip05 address")
                        return@onMessage
                    }
                    val nip05Address = command[1]
                    if (nip05Address.isEmpty()) {
                        ctx.sendErr("Nip05 address can not be empty")
                        return@onMessage
                    }

                    Thread {
                        try {
                            val nip05Profile = client.fetchNip05Profile(nip05Address)
                            sendAuthRequest(client, ctx, pub = nip05Profile.public_key())
                        } catch (e: Exception) {
                            ctx.sendErr(e.message ?: "Error fetching NIP-05 profile")
                        }
                    }.start()
                }
                "RequestPaymentsHistory" -> {
                    if (command.size < 2) {
                        ctx.sendErr("Malformed message: RequestPaymentsHistory requires session token")
                        return@onMessage
                    }
                    val sessionToken = command[1]
                    val userState = DB.getUserByToken(sessionToken)
                    if (userState == null) {
                        ctx.sendErr("Not authenticated")
                        return@onMessage
                    }
                    ctx.sendSuccess("PaymentsHistory", mapOf("history" to DB.getPaymentsHistory(userState.key)))
                }
                "RequestSubscriptionsHistory" -> {
                    if (command.size < 2) {
                        ctx.sendErr("Malformed message: RequestSubscriptionsHistory requires session token")
                        return@onMessage
                    }
                    val sessionToken = command[1]
                    val userState = DB.getUserByToken(sessionToken)
                    if (userState == null) {
                        ctx.sendErr("Not authenticated")
                        return@onMessage
                    }
                    ctx.sendSuccess("SubscriptionsHistory", mapOf("history" to DB.getSubscriptionsHistory(userState.key)))
                }
                "CashuMintAndSend" -> {
                    if (command.size < 7) {
                        ctx.sendErr("Malformed message: CashuMintAndSend requires sessionToken,mintUrl,authToken,unit,amount,description")
                        return@onMessage
                    }
                    val sessionToken = command[1]
                    val userState = DB.getUserByToken(sessionToken)
                    if (userState == null) {
                        ctx.sendErr("Not authenticated")
                        return@onMessage
                    }

                    val mintUrl = command[2]
                    val staticAuthToken = command[3]
                    val unit = command[4]
                    val amount = command[5].toLongOrNull()
                    if (amount == null) {
                        ctx.sendErr("Amount not a valid number")
                        return@onMessage
                    }
                    val description = command[6]

                    Thread {
                        try {
                            val cashuToken = client.mintCashu(mintUrl, unit, amount, staticAuthToken, description)
                            client.sendCashuDirect(userState.key, emptyList(), cashuToken)
                            ctx.sendSuccess("CashuSent", mapOf<String, Any>())
                        } catch (e: Exception) {
                            ctx.sendErr(e.message ?: "Error minting/sending cashu")
                        }
                    }.start()
                }
                "BurnToken" -> {
                    if (command.size < 6) {
                        ctx.sendErr("Malformed message: BurnToken requires sessionToken,mintUrl,authToken,unit,amount")
                        return@onMessage
                    }
                    val sessionToken = command[1]
                    val userState = DB.getUserByToken(sessionToken)
                    if (userState == null) {
                        ctx.sendErr("Not authenticated")
                        return@onMessage
                    }

                    val mintUrl = command[2]
                    val staticAuthToken = command[3]
                    val unit = command[4]
                    val amount = command[5].toLongOrNull()
                    if (amount == null) {
                        ctx.sendErr("Amount not a valid number")
                        return@onMessage
                    }

                    Thread {
                        try {
                            val op = client.requestCashu(userState.key, emptyList(), mintUrl, unit, amount)
                            val result = client.pollUntilComplete(op, pollOpts())

                            when (result.status()) {
                                CashuResponseStatus.Status.SUCCESS -> {
                                    val burnAmount = client.burnCashu(mintUrl, unit, result.token(), staticAuthToken)
                                    ctx.sendSuccess("BurnToken", mapOf("amount" to burnAmount))
                                }
                                CashuResponseStatus.Status.INSUFFICIENT_FUNDS -> {
                                    ctx.sendErr("Insufficient cashu tokens")
                                }
                                CashuResponseStatus.Status.REJECTED -> {
                                    ctx.sendErr("Rejected cashu token request")
                                }
                            }
                        } catch (e: Exception) {
                            ctx.sendErr(e.message ?: "Error burning token")
                        }
                    }.start()
                }
                "RequestSinglePayment" -> {
                    if (command.size < 5) {
                        ctx.sendErr("Malformed message: RequestSinglePayment requires sessionToken,currency,amount,description")
                        return@onMessage
                    }
                    val sessionToken = command[1]
                    val userState = DB.getUserByToken(sessionToken)
                    if (userState == null) {
                        ctx.sendErr("Not authenticated")
                        return@onMessage
                    }

                    val currencyStr = command[2]
                    var currency = Currency.MILLISATS
                    if (currencyStr != "Millisats") {
                        currency = Currency.FIAT(currencyStr)
                    }

                    val amount = command[3].toLongOrNull()
                    if (amount == null) {
                        ctx.sendErr("Amount not a valid integer")
                        return@onMessage
                    }

                    val description = command[4]

                    val paymentId = DB.registerPayment(userState.key, currency, amount, description, portalSubscriptionId = null)
                    val req = SinglePaymentRequestContent(description, amount, currency, null, null, null)

                    Thread {
                        try {
                            val op = client.requestSinglePayment(userState.key, emptyList(), req)

                            ctx.sendSuccess("PaymentsHistory", mapOf("history" to DB.getPaymentsHistory(userState.key)))
                            ctx.sendSuccess("RequestSinglePayment", mapOf<String, Any>())

                            // Wait for terminal result via polling
                            val result = client.pollUntilComplete(op, pollOpts())

                            when (result.status) {
                                "paid" -> {
                                    DB.updatePaymentStatus(paymentId, paid = true)
                                    ctx.sendSuccess("PaymentsHistory", mapOf("history" to DB.getPaymentsHistory(userState.key)))
                                }
                                "user_approved", "user_success" -> {}
                                else -> {
                                    DB.updatePaymentStatus(paymentId, paid = false)
                                    ctx.sendSuccess("PaymentsHistory", mapOf("history" to DB.getPaymentsHistory(userState.key)))
                                }
                            }
                        } catch (e: Exception) {
                            DB.updatePaymentStatus(paymentId, paid = false)
                            ctx.sendErr(e.message ?: "Error requesting payment")
                        }
                    }.start()
                }
                "GetSubscriptionPayments" -> {
                    if (command.size < 3) {
                        ctx.sendErr("Malformed message: GetSubscriptionPayments requires sessionToken,subscriptionId")
                        return@onMessage
                    }
                    val sessionToken = command[1]
                    val userState = DB.getUserByToken(sessionToken)
                    if (userState == null) {
                        ctx.sendErr("Not authenticated")
                        return@onMessage
                    }
                    val subscription = command[2]
                    ctx.sendSuccess("SubscriptionPayments", mapOf("history" to DB.getSubscriptionAllPayments(userState.key, subscription)))
                }
                "RequestRecurringPayment" -> {
                    if (command.size < 6) {
                        ctx.sendErr("Malformed message: RequestRecurringPayment requires sessionToken,currency,amount,description,frequency")
                        return@onMessage
                    }
                    val sessionToken = command[1]
                    val userState = DB.getUserByToken(sessionToken)
                    if (userState == null) {
                        ctx.sendErr("Not authenticated")
                        return@onMessage
                    }

                    val currencyStr = command[2]
                    var currency = Currency.MILLISATS
                    if (currencyStr != "Millisats") {
                        currency = Currency.FIAT(currencyStr)
                    }

                    val amount = command[3].toLongOrNull()
                    if (amount == null) {
                        ctx.sendErr("Amount not a valid integer")
                        return@onMessage
                    }

                    val description = command[4]
                    val frequency = command[5]

                    val now = Instant.now()

                    val req = RecurringPaymentRequestContent(
                        description,
                        amount,
                        currency,
                        null, // auth token
                        RecurrenceInfo(null, frequency, null, now.epochSecond),
                        Instant.now().plusSeconds(3600).epochSecond,
                    )

                    Thread {
                        try {
                            val op = client.requestRecurringPayment(userState.key, emptyList(), req)
                            val result = client.pollUntilComplete(op, pollOpts())

                            when (result.status.status) {
                                "confirmed" -> {
                                    DB.registerSubscription(
                                        userState.key,
                                        currency,
                                        amount,
                                        frequency,
                                        description,
                                        nextPaymentAt = now,
                                        portalSubscriptionId = result.status.subscriptionId
                                    )
                                    ctx.sendSuccess("SubscriptionsHistory", mapOf("history" to DB.getSubscriptionsHistory(userState.key)))
                                }
                                "rejected" -> {
                                    ctx.sendSuccess("SubscriptionsHistory", mapOf("history" to DB.getSubscriptionsHistory(userState.key)))
                                }
                            }
                        } catch (e: Exception) {
                            ctx.sendErr(e.message ?: "Error requesting recurring payment")
                        }
                    }.start()
                }
            }
            logger.debug("OnMessage ${ctx.message()}")
        }
    })
}

fun generateQR(client: PortalClient, ctx: WsContext, staticToken: String?) {
    Thread {
        try {
            val op = client.newKeyHandshakeUrl(staticToken, false)
            // The streamId for key handshake is the handshake URL
            ctx.sendSuccess("KeyHandshakeUrlRequest", mapOf("url" to op.streamId()))

            // Wait for handshake to complete (user scans QR)
            val result = client.pollUntilComplete(op, pollOpts())
            val pub = result.mainKey
            sendAuthRequest(client, ctx, pub)
        } catch (e: Exception) {
            ctx.sendErr(e.message ?: "Error generating QR")
        }
    }.start()
}

fun sendAuthRequest(client: PortalClient, ctx: WsContext, pub: String) {
    ctx.sendSuccess("PendingAuthRequest", mapOf<String, Any>())

    Thread {
        try {
            val op = client.authenticateKey(pub, emptyList())
            val result = client.pollUntilComplete(op, pollOpts())

            if (result.status.status == "declined") {
                ctx.sendErr("Authentication failed. Reason: '${result.status.reason}'")
                ctx.sendSuccess("CancelledAuthRequest", mapOf<String, Any>())
                return@Thread
            }

            val profile = client.fetchProfile(pub)

            val sessionToken = UUID.randomUUID().toString()
            val sessionState = UserSession(pub, profile)
            DB.insertUserToken(sessionToken, sessionState)
            ctx.sendSuccess("AuthenticateKeyRequest", mapOf("sessionToken" to sessionToken, "state" to sessionState))
        } catch (e: Exception) {
            ctx.sendErr(e.message ?: "Authentication error")
            ctx.sendSuccess("CancelledAuthRequest", mapOf<String, Any>())
        }
    }.start()
}

fun buildFrontend() {
    logger.info("DEV_MODE=true → building frontend...")

    val processBuilder = ProcessBuilder()
        .directory(java.io.File("/home/unldenis/IdeaProjects/portal-demo/frontend"))
        .command("npm", "run", "build")

    val environment = processBuilder.environment()
    environment["VITE_BACKEND_API_WS"] = "ws://localhost:7070/ws"

    val process = processBuilder.start()
    val exitCode = process.waitFor()
    if (exitCode != 0) {
        logger.error("❌ Frontend build failed (exit code $exitCode)")
        return
    }

    val distDir = java.nio.file.Paths.get("frontend", "dist")
    val staticDir = java.nio.file.Paths.get("/home/unldenis/IdeaProjects/portal-demo", "src", "main", "resources", "static")

    if (java.nio.file.Files.exists(staticDir)) {
        staticDir.toFile().deleteRecursively()
    }
    java.nio.file.Files.createDirectories(staticDir)

    distDir.toFile().copyRecursively(staticDir.toFile(), overwrite = true)

    logger.info("✅ Frontend build copied to resources/static")
}
