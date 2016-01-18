package org.jetbrains.ktor.jetty

import org.eclipse.jetty.server.*
import org.eclipse.jetty.server.handler.*
import org.eclipse.jetty.server.session.*
import org.jetbrains.ktor.application.*
import org.jetbrains.ktor.host.*
import org.jetbrains.ktor.http.*
import org.jetbrains.ktor.servlet.*
import java.io.*
import javax.servlet.*
import javax.servlet.http.*

/**
 * [ApplicationHost] implementation for running standalone Jetty Host
 */
class JettyApplicationHost(override val hostConfig: ApplicationHostConfig,
                           val config: ApplicationConfig,
                           val applicationLifecycle: ApplicationLifecycle) : ApplicationHost {

    val application: Application get() = applicationLifecycle.application

    constructor(hostConfig: ApplicationHostConfig, config: ApplicationConfig)
    : this(hostConfig, config, ApplicationLoader(config))

    constructor(hostConfig: ApplicationHostConfig, config: ApplicationConfig, application: Application)
    : this(hostConfig, config, object : ApplicationLifecycle {
        override val application: Application = application
        override fun dispose() {
        }
    })

    var server: Server? = null
    val MULTI_PART_CONFIG = MultipartConfigElement(System.getProperty("java.io.tmpdir"));

    inner class Handler() : AbstractHandler() {

        override fun handle(target: String, baseRequest: Request, request: HttpServletRequest, response: HttpServletResponse) {
            response.characterEncoding = "UTF-8"
            try {
                val appRequest = ServletApplicationCall(application, request, response)
                val contentType = request.contentType
                if (contentType != null && ContentType.parse(contentType).match(ContentType.MultiPart.Any)) {
                    baseRequest.setAttribute(Request.__MULTIPART_CONFIG_ELEMENT, MULTI_PART_CONFIG)
                    // TODO someone reported auto-cleanup issues so we have to check it
                }

                val requestResult = application.handle(appRequest)
                when (requestResult) {
                    ApplicationCallResult.Handled -> baseRequest.isHandled = true
                    ApplicationCallResult.Unhandled -> baseRequest.isHandled = false
                    ApplicationCallResult.Asynchronous -> {
                        val asyncContext = baseRequest.startAsync()
                        appRequest.continueAsync(asyncContext)
                    }
                }
            } catch(ex: Throwable) {
                config.log.error("Application ${application.javaClass} cannot fulfill the request", ex);
            }
        }
    }

    public override fun start() {
        config.log.info("Starting server...")

        server = Server().apply {
            val httpConfig = HttpConfiguration().apply {
                sendServerVersion = false
            }
            val connectionFactory = HttpConnectionFactory(httpConfig)
            val connector = ServerConnector(this, connectionFactory).apply {
                host = hostConfig.host
                port = hostConfig.port
            }
            connectors = arrayOf(connector)
        }

        val sessionHandler = SessionHandler()
        val sessionManager = HashSessionManager()
        sessionManager.storeDirectory = File("tmp/sessions")
        sessionHandler.sessionManager = sessionManager
        sessionHandler.handler = Handler()
        server?.handler = sessionHandler

        server?.start()
        config.log.info("Server running.")
        server?.join()
        config.log.info("Server stopped.")
    }

    override fun stop() {
        if (server != null) {
            server?.stop()
            server = null
        }
    }
}

