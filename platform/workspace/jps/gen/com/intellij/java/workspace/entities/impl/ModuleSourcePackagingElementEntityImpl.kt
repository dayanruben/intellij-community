// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.java.workspace.entities.impl

import com.intellij.java.workspace.entities.CompositePackagingElementEntity
import com.intellij.java.workspace.entities.ModuleSourcePackagingElementEntity
import com.intellij.java.workspace.entities.PackagingElementEntity
import com.intellij.openapi.util.NlsSafe
import com.intellij.platform.workspace.jps.entities.LibraryId
import com.intellij.platform.workspace.jps.entities.ModuleId
import com.intellij.platform.workspace.storage.*
import com.intellij.platform.workspace.storage.annotations.Abstract
import com.intellij.platform.workspace.storage.annotations.Parent
import com.intellij.platform.workspace.storage.impl.EntityLink
import com.intellij.platform.workspace.storage.impl.ModifiableWorkspaceEntityBase
import com.intellij.platform.workspace.storage.impl.SoftLinkable
import com.intellij.platform.workspace.storage.impl.WorkspaceEntityBase
import com.intellij.platform.workspace.storage.impl.WorkspaceEntityData
import com.intellij.platform.workspace.storage.impl.containers.toMutableWorkspaceList
import com.intellij.platform.workspace.storage.impl.extractOneToAbstractManyParent
import com.intellij.platform.workspace.storage.impl.indices.WorkspaceMutableIndex
import com.intellij.platform.workspace.storage.impl.updateOneToAbstractManyParentOfChild
import com.intellij.platform.workspace.storage.instrumentation.EntityStorageInstrumentation
import com.intellij.platform.workspace.storage.instrumentation.EntityStorageInstrumentationApi
import com.intellij.platform.workspace.storage.instrumentation.MutableEntityStorageInstrumentation
import com.intellij.platform.workspace.storage.metadata.model.EntityMetadata
import com.intellij.platform.workspace.storage.url.VirtualFileUrl
import org.jetbrains.annotations.NonNls

@GeneratedCodeApiVersion(3)
@GeneratedCodeImplVersion(7)
@OptIn(WorkspaceEntityInternalApi::class)
internal class ModuleSourcePackagingElementEntityImpl(private val dataSource: ModuleSourcePackagingElementEntityData) :
  ModuleSourcePackagingElementEntity, WorkspaceEntityBase(dataSource) {

  private companion object {
    internal val PARENTENTITY_CONNECTION_ID: ConnectionId = ConnectionId.create(
      CompositePackagingElementEntity::class.java, PackagingElementEntity::class.java, ConnectionId.ConnectionType.ONE_TO_ABSTRACT_MANY,
      true
    )

    private val connections = listOf<ConnectionId>(
      PARENTENTITY_CONNECTION_ID,
    )

  }

  override val parentEntity: CompositePackagingElementEntity?
    get() = snapshot.extractOneToAbstractManyParent(PARENTENTITY_CONNECTION_ID, this)

  override val module: ModuleId?
    get() {
      readField("module")
      return dataSource.module
    }

  override val entitySource: EntitySource
    get() {
      readField("entitySource")
      return dataSource.entitySource
    }

  override fun connectionIdList(): List<ConnectionId> {
    return connections
  }


  internal class Builder(result: ModuleSourcePackagingElementEntityData?) :
    ModifiableWorkspaceEntityBase<ModuleSourcePackagingElementEntity, ModuleSourcePackagingElementEntityData>(result),
    ModuleSourcePackagingElementEntity.Builder {
    internal constructor() : this(ModuleSourcePackagingElementEntityData())

    override fun applyToBuilder(builder: MutableEntityStorage) {
      if (this.diff != null) {
        if (existsInBuilder(builder)) {
          this.diff = builder
          return
        }
        else {
          error("Entity ModuleSourcePackagingElementEntity is already created in a different builder")
        }
      }

      this.diff = builder
      addToBuilder()
      this.id = getEntityData().createEntityId()
      // After adding entity data to the builder, we need to unbind it and move the control over entity data to builder
      // Builder may switch to snapshot at any moment and lock entity data to modification
      this.currentEntityData = null

      // Process linked entities that are connected without a builder
      processLinkedEntities(builder)
      checkInitialization() // TODO uncomment and check failed tests
    }

    private fun checkInitialization() {
      val _diff = diff
      if (!getEntityData().isEntitySourceInitialized()) {
        error("Field WorkspaceEntity#entitySource should be initialized")
      }
    }

    override fun connectionIdList(): List<ConnectionId> {
      return connections
    }

    // Relabeling code, move information from dataSource to this builder
    override fun relabel(dataSource: WorkspaceEntity, parents: Set<WorkspaceEntity>?) {
      dataSource as ModuleSourcePackagingElementEntity
      if (this.entitySource != dataSource.entitySource) this.entitySource = dataSource.entitySource
      if (this.module != dataSource?.module) this.module = dataSource.module
      updateChildToParentReferences(parents)
    }


    override var entitySource: EntitySource
      get() = getEntityData().entitySource
      set(value) {
        checkModificationAllowed()
        getEntityData(true).entitySource = value
        changedProperty.add("entitySource")

      }

    override var parentEntity: CompositePackagingElementEntity.Builder<out CompositePackagingElementEntity>?
      get() {
        val _diff = diff
        return if (_diff != null) {
          @OptIn(EntityStorageInstrumentationApi::class)
          ((_diff as MutableEntityStorageInstrumentation).getParentBuilder(
            PARENTENTITY_CONNECTION_ID, this
          ) as? CompositePackagingElementEntity.Builder<out CompositePackagingElementEntity>)
          ?: (this.entityLinks[EntityLink(
            false, PARENTENTITY_CONNECTION_ID
          )] as? CompositePackagingElementEntity.Builder<out CompositePackagingElementEntity>)
        }
        else {
          this.entityLinks[EntityLink(
            false, PARENTENTITY_CONNECTION_ID
          )] as? CompositePackagingElementEntity.Builder<out CompositePackagingElementEntity>
        }
      }
      set(value) {
        checkModificationAllowed()
        val _diff = diff
        if (_diff != null && value is ModifiableWorkspaceEntityBase<*, *> && value.diff == null) {
          // Setting backref of the list
          if (value is ModifiableWorkspaceEntityBase<*, *>) {
            val data = (value.entityLinks[EntityLink(true, PARENTENTITY_CONNECTION_ID)] as? List<Any> ?: emptyList()) + this
            value.entityLinks[EntityLink(true, PARENTENTITY_CONNECTION_ID)] = data
          }
          // else you're attaching a new entity to an existing entity that is not modifiable
          _diff.addEntity(value as ModifiableWorkspaceEntityBase<WorkspaceEntity, *>)
        }
        if (_diff != null && (value !is ModifiableWorkspaceEntityBase<*, *> || value.diff != null)) {
          _diff.updateOneToAbstractManyParentOfChild(PARENTENTITY_CONNECTION_ID, this, value)
        }
        else {
          // Setting backref of the list
          if (value is ModifiableWorkspaceEntityBase<*, *>) {
            val data = (value.entityLinks[EntityLink(true, PARENTENTITY_CONNECTION_ID)] as? List<Any> ?: emptyList()) + this
            value.entityLinks[EntityLink(true, PARENTENTITY_CONNECTION_ID)] = data
          }
          // else you're attaching a new entity to an existing entity that is not modifiable

          this.entityLinks[EntityLink(false, PARENTENTITY_CONNECTION_ID)] = value
        }
        changedProperty.add("parentEntity")
      }

    override var module: ModuleId?
      get() = getEntityData().module
      set(value) {
        checkModificationAllowed()
        getEntityData(true).module = value
        changedProperty.add("module")

      }

    override fun getEntityClass(): Class<ModuleSourcePackagingElementEntity> = ModuleSourcePackagingElementEntity::class.java
  }
}

@OptIn(WorkspaceEntityInternalApi::class)
internal class ModuleSourcePackagingElementEntityData : WorkspaceEntityData<ModuleSourcePackagingElementEntity>(), SoftLinkable {
  var module: ModuleId? = null


  override fun getLinks(): Set<SymbolicEntityId<*>> {
    val result = HashSet<SymbolicEntityId<*>>()
    val optionalLink_module = module
    if (optionalLink_module != null) {
      result.add(optionalLink_module)
    }
    return result
  }

  override fun index(index: WorkspaceMutableIndex<SymbolicEntityId<*>>) {
    val optionalLink_module = module
    if (optionalLink_module != null) {
      index.index(this, optionalLink_module)
    }
  }

  override fun updateLinksIndex(prev: Set<SymbolicEntityId<*>>, index: WorkspaceMutableIndex<SymbolicEntityId<*>>) {
    // TODO verify logic
    val mutablePreviousSet = HashSet(prev)
    val optionalLink_module = module
    if (optionalLink_module != null) {
      val removedItem_optionalLink_module = mutablePreviousSet.remove(optionalLink_module)
      if (!removedItem_optionalLink_module) {
        index.index(this, optionalLink_module)
      }
    }
    for (removed in mutablePreviousSet) {
      index.remove(this, removed)
    }
  }

  override fun updateLink(oldLink: SymbolicEntityId<*>, newLink: SymbolicEntityId<*>): Boolean {
    var changed = false
    var module_data_optional = if (module != null) {
      val module___data = if (module!! == oldLink) {
        changed = true
        newLink as ModuleId
      }
      else {
        null
      }
      module___data
    }
    else {
      null
    }
    if (module_data_optional != null) {
      module = module_data_optional
    }
    return changed
  }

  override fun wrapAsModifiable(diff: MutableEntityStorage): WorkspaceEntity.Builder<ModuleSourcePackagingElementEntity> {
    val modifiable = ModuleSourcePackagingElementEntityImpl.Builder(null)
    modifiable.diff = diff
    modifiable.id = createEntityId()
    return modifiable
  }

  @OptIn(EntityStorageInstrumentationApi::class)
  override fun createEntity(snapshot: EntityStorageInstrumentation): ModuleSourcePackagingElementEntity {
    val entityId = createEntityId()
    return snapshot.initializeEntity(entityId) {
      val entity = ModuleSourcePackagingElementEntityImpl(this)
      entity.snapshot = snapshot
      entity.id = entityId
      entity
    }
  }

  override fun getMetadata(): EntityMetadata {
    return MetadataStorageImpl.getMetadataByTypeFqn(
      "com.intellij.java.workspace.entities.ModuleSourcePackagingElementEntity"
    ) as EntityMetadata
  }

  override fun getEntityInterface(): Class<out WorkspaceEntity> {
    return ModuleSourcePackagingElementEntity::class.java
  }

  override fun createDetachedEntity(parents: List<WorkspaceEntity.Builder<*>>): WorkspaceEntity.Builder<*> {
    return ModuleSourcePackagingElementEntity(entitySource) {
      this.module = this@ModuleSourcePackagingElementEntityData.module
      this.parentEntity =
        parents.filterIsInstance<CompositePackagingElementEntity.Builder<out CompositePackagingElementEntity>>().singleOrNull()
    }
  }

  override fun getRequiredParents(): List<Class<out WorkspaceEntity>> {
    val res = mutableListOf<Class<out WorkspaceEntity>>()
    return res
  }

  override fun equals(other: Any?): Boolean {
    if (other == null) return false
    if (this.javaClass != other.javaClass) return false

    other as ModuleSourcePackagingElementEntityData

    if (this.entitySource != other.entitySource) return false
    if (this.module != other.module) return false
    return true
  }

  override fun equalsIgnoringEntitySource(other: Any?): Boolean {
    if (other == null) return false
    if (this.javaClass != other.javaClass) return false

    other as ModuleSourcePackagingElementEntityData

    if (this.module != other.module) return false
    return true
  }

  override fun hashCode(): Int {
    var result = entitySource.hashCode()
    result = 31 * result + module.hashCode()
    return result
  }

  override fun hashCodeIgnoringEntitySource(): Int {
    var result = javaClass.hashCode()
    result = 31 * result + module.hashCode()
    return result
  }
}
