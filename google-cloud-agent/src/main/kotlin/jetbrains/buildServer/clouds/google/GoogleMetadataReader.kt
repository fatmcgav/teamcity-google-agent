/*
 * Copyright 2000-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jetbrains.buildServer.clouds.google

import com.google.gson.Gson
import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.agent.AgentLifeCycleAdapter
import jetbrains.buildServer.agent.AgentLifeCycleListener
import jetbrains.buildServer.agent.BuildAgent
import jetbrains.buildServer.agent.BuildAgentConfigurationEx
import jetbrains.buildServer.clouds.CloudInstanceUserData
import jetbrains.buildServer.util.EventDispatcher
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.HttpClients
import org.apache.http.util.EntityUtils
import java.net.UnknownHostException

class GoogleMetadataReader(events: EventDispatcher<AgentLifeCycleListener>,
                           private val configuration: BuildAgentConfigurationEx,
                           private val idleShutdown: IdleShutdown) {

    init {
        LOG.info("Google plugin initializing...")

        events.addListener(object : AgentLifeCycleAdapter() {
            override fun afterAgentConfigurationLoaded(agent: BuildAgent) {
                fetchConfiguration()
            }
        })
    }

    private fun fetchConfiguration() {
        createHttpClient().use {
            val response = try {
                it.execute(HttpGet(METADATA_URL).apply {
                    addHeader("Metadata-Flavor", "Google")
                })
            } catch (e: UnknownHostException) {
                LOG.info("Google Compute integration is not available: could not access $METADATA_URL")
                return@use
            } catch (e: Exception) {
                LOG.infoAndDebugDetails("Google Compute integration is not available: Failed to connect to $METADATA_URL: ${e.message}", e)
                return@use
            }

            val statusCode = response.statusLine.statusCode
            if (statusCode == 200) {
                updateConfiguration(EntityUtils.toString(response.entity))
            } else {
                LOG.info("Google Compute integration is not available: Failed to connect to $METADATA_URL: HTTP $statusCode")
            }
        }
    }

    private fun updateConfiguration(json: String) {
        val metadata = deserializeMetadata(json)
        if (metadata == null) {
            LOG.info("Google Compute integration is not available: Invalid instance metadata")
            LOG.debug(json)
            return
        }

        val data = CloudInstanceUserData.deserialize(metadata.attributes?.teamcityData ?: "")
        if (data == null) {
            LOG.info("Google Compute integration is not available: No TeamCity metadata")
            LOG.debug(json)
            return
        }

        LOG.info("Google Compute integration is available, will register agent \"${metadata.name}\" on server URL \"${data.serverAddress}\"")
        configuration.name = metadata.name
        configuration.serverUrl = data.serverAddress

        metadata.networkInterfaces.firstOrNull()?.let {
            it.accessConfigs.firstOrNull()?.let {
                LOG.info("Setting external IP address: ${it.externalIp}")
                configuration.addAlternativeAgentAddress(it.externalIp)
            }
        }

        configuration.addConfigurationParameter(GoogleAgentProperties.INSTANCE_NAME, metadata.name)
        data.customAgentConfigurationParameters.entries.forEach { it ->
            configuration.addConfigurationParameter(it.key, it.value)
            LOG.info("Added config parameter: ${it.key} => ${it.value}")
        }

        data.idleTimeout?.let {
            idleShutdown.setIdleTime(it)
        }
    }

    private fun createHttpClient(): CloseableHttpClient {
        val requestConfig = RequestConfig.custom().setSocketTimeout(TIMEOUT).setConnectTimeout(TIMEOUT).build()
        return HttpClients.custom().useSystemProperties().setDefaultRequestConfig(requestConfig).build()
    }

    data class Metadata(
            val attributes: MetadataAttributes?,
            val name: String,
            val networkInterfaces: List<NetworkInterface>
    )

    data class MetadataAttributes(
            val teamcityData: String?
    )

    data class NetworkInterface(
            val accessConfigs: List<AccessConfig>
    )

    data class AccessConfig(
            val externalIp: String
    )

    companion object {
        private val LOG = Logger.getInstance(GoogleMetadataReader::class.java.name)
        private val METADATA_URL = "http://metadata.google.internal/computeMetadata/v1/instance/?recursive=true"
        private val TIMEOUT = 10000
        private val GSON = Gson()

        fun deserializeMetadata(json: String) = try {
            GSON.fromJson<Metadata>(json, Metadata::class.java)
        } catch (e: Exception) {
            LOG.debug("Failed to deserialize JSON data ${e.message}", e)
            null
        }
    }
}
