/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.kt.tools.mcp

import com.google.adk.kt.agents.ReadonlyContext
import com.google.adk.kt.tools.Toolset

/** Android adapter: the public common config is backed by the Kotlin MCP SDK transport. */
internal actual fun createPlatformMcpToolset(
  config: McpToolsetConfig,
  headerProvider: (suspend (ReadonlyContext) -> Map<String, String>)?,
  progressConsumers: List<(McpProgressUpdate) -> Unit>,
): Toolset =
  AndroidMcpToolset(
    serverUrl = config.streamableHttpTransport.url,
    headers = config.streamableHttpTransport.headers,
    toolFilter = config.toolFilter,
    timeouts =
      AndroidMcpTimeouts(
        connect = config.streamableHttpTransport.connectTimeout,
        request = config.streamableHttpTransport.requestTimeout,
        socket = config.streamableHttpTransport.requestTimeout,
      ),
    useMcpResources = config.useMcpResources,
    maxMcpResourceLength = config.maxMcpResourceLength,
    progressConsumers = progressConsumers,
    headerProvider = headerProvider,
    allowInsecureHttp = config.streamableHttpTransport.allowInsecureHttp,
  )

private val McpToolsetConfig.streamableHttpTransport: McpTransportConfig.StreamableHttp
  get() =
    when (val configuredTransport = transport) {
      is McpTransportConfig.StreamableHttp -> configuredTransport
    }
