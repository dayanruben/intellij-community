// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.core.script.k2.modules

import com.intellij.platform.workspace.jps.entities.SdkId
import com.intellij.platform.workspace.storage.*
import com.intellij.platform.workspace.storage.annotations.Default
import com.intellij.platform.workspace.storage.impl.containers.toMutableWorkspaceList
import com.intellij.platform.workspace.storage.url.VirtualFileUrl

@GeneratedCodeApiVersion(3)
interface ModifiableKotlinScriptEntity : ModifiableWorkspaceEntity<KotlinScriptEntity> {
  override var entitySource: EntitySource
  var virtualFileUrl: VirtualFileUrl
  var dependencies: MutableList<KotlinScriptLibraryEntityId>
  var configuration: ScriptCompilationConfigurationEntity?
  var sdkId: SdkId?
  var reports: MutableList<ScriptDiagnosticData>
}

internal object KotlinScriptEntityType : EntityType<KotlinScriptEntity, ModifiableKotlinScriptEntity>() {
  override val entityClass: Class<KotlinScriptEntity> get() = KotlinScriptEntity::class.java
  operator fun invoke(
    virtualFileUrl: VirtualFileUrl,
    dependencies: List<KotlinScriptLibraryEntityId>,
    entitySource: EntitySource,
    init: (ModifiableKotlinScriptEntity.() -> Unit)? = null,
  ): ModifiableKotlinScriptEntity {
    val builder = builder()
    builder.virtualFileUrl = virtualFileUrl
    builder.dependencies = dependencies.toMutableWorkspaceList()
    builder.entitySource = entitySource
    init?.invoke(builder)
    return builder
  }
}

fun MutableEntityStorage.modifyKotlinScriptEntity(
  entity: KotlinScriptEntity,
  modification: ModifiableKotlinScriptEntity.() -> Unit,
): KotlinScriptEntity = modifyEntity(ModifiableKotlinScriptEntity::class.java, entity, modification)

@JvmOverloads
@JvmName("createKotlinScriptEntity")
fun KotlinScriptEntity(
  virtualFileUrl: VirtualFileUrl,
  dependencies: List<KotlinScriptLibraryEntityId>,
  entitySource: EntitySource,
  init: (ModifiableKotlinScriptEntity.() -> Unit)? = null,
): ModifiableKotlinScriptEntity = KotlinScriptEntityType(virtualFileUrl, dependencies, entitySource, init)
