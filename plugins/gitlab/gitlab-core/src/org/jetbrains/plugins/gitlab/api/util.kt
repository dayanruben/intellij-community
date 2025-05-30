// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.gitlab.api

import com.intellij.collaboration.util.resolveRelative
import com.intellij.collaboration.util.withQuery
import org.jetbrains.plugins.gitlab.util.GitLabProjectPath
import java.net.URI
import java.net.URLEncoder

val GitLabProjectPath.apiId: String
  get() = URLEncoder.encode("$owner/$name", Charsets.UTF_8)

val GitLabProjectCoordinates.restApiUri: URI
  get() = serverPath.restApiUri
    .resolveRelative("projects/")
    .resolveRelative(projectPath.apiId + "/")

internal fun URI.withParams(params: Map<String, String>): URI =
  withQuery(params.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, Charsets.UTF_8)}" })