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
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolFilter
import com.google.adk.kt.tools.Toolset
import com.google.adk.kt.tools.mcp.McpToolException.McpToolLoadingException
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.spec.McpSchema.Tool as McpSchemaTool

/**
 * Connects to an MCP Server and exposes the server's MCP tools to an agent as ADK [BaseTool]s.
 *
 * `McpToolset` manages the lifecycle of the connection to a single MCP server and lazily fetches
 * the server's tool list on first use. The instance can then be passed directly to an `LlmAgent`'s
 * `toolsets`.
 *
 * Instances are created via [McpToolsetConfig.toToolset], for example:
 * ```
 * val toolset =
 *   McpToolset.McpToolsetConfig(
 *       stdioConnectionParams =
 *         McpConnectionParameters.Stdio(
 *           serverParameters =
 *             ServerParameters.builder("npx")
 *               .args("-y", "@modelcontextprotocol/server-filesystem")
 *               .build()
 *         ),
 *       toolFilter = ToolFilter.allowList("read_file", "list_directory"),
 *     )
 *     .toToolset()
 * ```
 *
 * The constructor is `internal`; user code should use [McpToolsetConfig.toToolset] instead.
 */
class McpToolset
internal constructor(
  private val mcpSessionManager: SessionManager,
  private val toolFilter: ToolFilter? = null,
  private val headerProvider: (suspend (ReadonlyContext) -> Map<String, String>)? = null,
  private val useMcpResources: Boolean = false,
  private val maxMcpResourceLength: Int = DEFAULT_MAX_MCP_RESOURCE_LENGTH,
) : Toolset {

  /**
   * Shared JVM/Android discovery and execution path. The JVM-specific resource helper methods
   * below remain while their richer return types are migrated through the same boundary.
   */
  private val sharedCore =
    McpToolsetCore(
      sessionManager = JvmMcpClientSessionManager(mcpSessionManager),
      toolFilter = toolFilter,
      headerProvider = headerProvider,
      useMcpResources = useMcpResources,
      maxMcpResourceLength = maxMcpResourceLength,
      onResourcesUnsupported = {
        logger.warn {
          "useMcpResources is enabled, but the MCP server did not report the \"resources\" " +
            "capability, so list_mcp_resources, load_mcp_resource, list_mcp_resource_templates " +
            "are not exposed to the agent."
        }
      },
      toolFactory = McpToolFactory { definition, invocation ->
        val tool =
          definition.platformTool as? McpSchemaTool
            ?: error("JVM MCP tool definition is missing its Java SDK tool.")
        McpTool(
          name = definition.name,
          description = definition.description,
          mcpSchemaTool = tool,
          invocation = invocation,
        )
      },
    )

  override suspend fun getTools(readonlyContext: ReadonlyContext?): List<BaseTool> =
    try {
      sharedCore.getTools(readonlyContext)
    } catch (error: IllegalArgumentException) {
      // This has historically been part of the JVM loading error contract.
      throw McpToolLoadingException("Invalid argument encountered during tool loading.", error)
    } catch (error: McpToolsetCoreException) {
      // Preserve the established JVM failure type while the mechanics live in shared code.
      throw McpToolLoadingException(LOAD_TOOLS_FAILURE_MESSAGE, error.cause ?: error)
    }

  /**
   * Lists a page of resources advertised by the MCP server.
   *
   * @param cursor An opaque pagination cursor from a previous [McpResourceListing.nextCursor], or
   *   `null` to fetch the first page.
   */
  internal suspend fun listResources(
    cursor: String? = null,
    readonlyContext: ReadonlyContext? = null,
  ): McpResourceListing =
    try {
      sharedCore.listResources(readonlyContext, cursor).toResourceListing()
    } catch (error: McpToolsetCoreException) {
      throw error.cause ?: error
    }

  /**
   * Fetches every resource advertised by the MCP server, following pagination cursors until the
   * server reports no further pages.
   *
   * This is the full-scan counterpart to the paged [listResources]: MCP keys resources by `uri`, so
   * resolving a [McpResourceInfo.name] means scanning the catalog, and names are not required to be
   * unique. It costs one round trip per page of the whole catalog.
   *
   * Kept `internal` like the rest of the resource surface for 1.0. Promoting any of it to `public`
   * later is purely additive.
   */
  internal suspend fun listAllResources(
    readonlyContext: ReadonlyContext? = null
  ): List<McpResourceInfo> =
    try {
      sharedCore.listAllResources(readonlyContext).map { it.toResourceInfo() }
    } catch (error: McpToolsetCoreException) {
      if (error.cause == null) {
        throw McpToolException.McpToolExecutionException(error.message.orEmpty(), error)
      }
      throw requireNotNull(error.cause)
    }

  /**
   * Lists a page of resource templates advertised by the MCP server.
   *
   * @param cursor An opaque pagination cursor from a previous
   *   [McpResourceTemplateListing.nextCursor], or `null` to fetch the first page.
   */
  internal suspend fun listResourceTemplates(
    cursor: String? = null,
    readonlyContext: ReadonlyContext? = null,
  ): McpResourceTemplateListing =
    try {
      sharedCore.listResourceTemplates(readonlyContext, cursor).toResourceTemplateListing()
    } catch (error: McpToolsetCoreException) {
      throw error.cause ?: error
    }

  /** Fetches and returns the contents of the resource with the given [uri]. */
  internal suspend fun readResource(
    uri: String,
    readonlyContext: ReadonlyContext? = null,
  ): List<McpResourceContent> =
    try {
      sharedCore.readResource(readonlyContext, uri).map { it.toResourceContent() }
    } catch (error: McpToolsetCoreException) {
      throw error.cause ?: error
    }

  override fun close() {
    sharedCore.close()
  }

  companion object {
    private const val LOAD_TOOLS_FAILURE_MESSAGE = "Failed to load tools."

    private val logger = LoggerFactory.getLogger(McpToolset::class)
  }

  /**
   * Configuration for an [McpToolset], used to construct one via [toToolset].
   *
   * Exactly one of [stdioConnectionParams], [sseConnectionParams], or
   * [streamableHttpConnectionParams] must be set; [toToolset] throws if zero or more than one are
   * provided.
   *
   * @property stdioConnectionParams Connection parameters for a local MCP server reached over stdio
   *   (e.g. one launched via `npx` or `python3`).
   * @property sseConnectionParams Connection parameters for an MCP server reached over SSE.
   * @property streamableHttpConnectionParams Connection parameters for an MCP server reached over
   *   the Streamable HTTP transport.
   * @property toolFilter Optional filter selecting which of the tools advertised by the server are
   *   exposed to the agent. Use [ToolFilter.AllowList] (or the [ToolFilter.allowList] helper) to
   *   keep tools by name, or [ToolFilter.Predicate] for context-aware selection that can consult
   *   the [ReadonlyContext]. When `null`, all tools advertised by the server are exposed. The
   *   resource tools added by [useMcpResources] are ADK's own and are not filtered.
   * @property useMcpResources When `true`, resource-related tools (`list_mcp_resources`,
   *   `list_mcp_resource_templates`, `load_mcp_resource`) are added to the toolset, granting the
   *   agent access to MCP resources exposed by the server. They are added only if the server
   *   reports the `resources` capability during the handshake; against a server that does not, they
   *   are omitted and a warning is logged, because the MCP client rejects the requests those tools
   *   would make. [toolFilter] does not apply to them: it selects among the tools the server
   *   advertises, so enabling this flag and filtering the server's tools are independent choices.
   *   Defaults to `false`.
   * @property maxMcpResourceLength Maximum length, in characters, of a single resource payload
   *   returned by `load_mcp_resource`. Longer payloads are truncated.
   */
  data class McpToolsetConfig(
    val stdioConnectionParams: McpConnectionParameters.Stdio? = null,
    val sseConnectionParams: McpConnectionParameters.Sse? = null,
    val streamableHttpConnectionParams: McpConnectionParameters.StreamableHttp? = null,
    val toolFilter: ToolFilter? = null,
    val useMcpResources: Boolean = false,
    val maxMcpResourceLength: Int = DEFAULT_MAX_MCP_RESOURCE_LENGTH,
  ) {
    /**
     * Creates an [McpToolset] from this configuration.
     *
     * @param headerProvider Optional suspending callback that, given a [ReadonlyContext], returns a
     *   map of HTTP headers to attach to each MCP session. Because it is a `suspend` function,
     *   headers or tokens can be minted asynchronously at request time (e.g. fetching an OAuth
     *   bearer token) without blocking a thread. When non-`null`, sessions are not cached across
     *   invocations so that headers can vary per-context (e.g. per-user authentication). When
     *   `null`, a single session is opened lazily and reused.
     * @param progressConsumers Callbacks invoked for every
     *   [McpSchema.ProgressNotification][io.modelcontextprotocol.spec.McpSchema.ProgressNotification]
     *   received from the MCP server during long-running tool executions.
     * @throws IllegalArgumentException if zero or more than one of [stdioConnectionParams],
     *   [sseConnectionParams], and [streamableHttpConnectionParams] is set.
     */
    fun toToolset(
      headerProvider: (suspend (ReadonlyContext) -> Map<String, String>)? = null,
      progressConsumers: List<(McpSchema.ProgressNotification) -> Unit> = emptyList(),
    ): McpToolset {
      val params =
        listOfNotNull(stdioConnectionParams, sseConnectionParams, streamableHttpConnectionParams)

      require(params.size == 1) {
        "Exactly one of stdioConnectionParams, sseConnectionParams or streamableHttpConnectionParams must be set"
      }

      val connectionParams = params.first()

      return McpToolset(
        McpSessionManager(connectionParams, progressConsumers = progressConsumers),
        toolFilter,
        headerProvider,
        useMcpResources,
        maxMcpResourceLength,
      )
    }

    /** Creates a McpToolset instance from the configuration with a specific SessionManager. */
    internal fun toToolset(
      sessionManager: SessionManager,
      headerProvider: (suspend (ReadonlyContext) -> Map<String, String>)? = null,
    ): McpToolset =
      McpToolset(sessionManager, toolFilter, headerProvider, useMcpResources, maxMcpResourceLength)
  }
}

// The SDK records are plain Jackson bindings with no non-null validation, so a server may omit
// any field. The non-null properties below default to "" rather than letting a Kotlin
// platform-type assignment throw NPE from inside the mapper.

private fun McpClientResource.toResourceInfo(): McpResourceInfo =
  McpResourceInfo(
    name = name,
    uri = uri,
    title = title,
    description = description,
    mimeType = mimeType,
    size = size,
    annotations = annotations?.toAnnotations(),
    meta = meta,
  )

private fun McpClientResourcePage.toResourceListing(): McpResourceListing =
  McpResourceListing(resources.map { it.toResourceInfo() }, nextCursor)

private fun McpClientResourceTemplate.toResourceTemplateInfo(): McpResourceTemplateInfo =
  McpResourceTemplateInfo(
    name = name,
    uriTemplate = uriTemplate,
    title = title,
    description = description,
    mimeType = mimeType,
    annotations = annotations?.toAnnotations(),
    meta = meta,
  )

private fun McpClientResourceTemplatePage.toResourceTemplateListing(): McpResourceTemplateListing =
  McpResourceTemplateListing(resourceTemplates.map { it.toResourceTemplateInfo() }, nextCursor)

private fun McpClientAnnotations.toAnnotations(): McpAnnotations =
  McpAnnotations(
    // `audience` is optional in the schema: annotations may carry only a priority.
    audience = audience.map(::McpRole),
    priority = priority,
    lastModified = lastModified,
  )

// No else branch below: McpSchema.ResourceContents is a sealed interface permitting exactly the
// two subtypes handled here, so the compiler proves the `when` exhaustive. An SDK upgrade that
// adds a third subtype turns that proof into a compile error here, which is the signal we want.
private fun McpClientResourceContent.toResourceContent(): McpResourceContent =
  when (this) {
    is McpClientResourceContent.Text ->
      McpResourceContent.Text(
        uri = uri,
        mimeType = mimeType,
        text = text,
        meta = meta,
      )
    is McpClientResourceContent.Blob ->
      McpResourceContent.Blob(
        uri = uri,
        mimeType = mimeType,
        blobBase64 = blobBase64,
        meta = meta,
      )
  }
