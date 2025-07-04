package com.intellij.workspaceModel.test.api.impl

import com.intellij.platform.workspace.storage.ConnectionId
import com.intellij.platform.workspace.storage.EntitySource
import com.intellij.platform.workspace.storage.GeneratedCodeApiVersion
import com.intellij.platform.workspace.storage.GeneratedCodeImplVersion
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.WorkspaceEntity
import com.intellij.platform.workspace.storage.WorkspaceEntityInternalApi
import com.intellij.platform.workspace.storage.impl.ModifiableWorkspaceEntityBase
import com.intellij.platform.workspace.storage.impl.WorkspaceEntityBase
import com.intellij.platform.workspace.storage.impl.WorkspaceEntityData
import com.intellij.platform.workspace.storage.impl.containers.MutableWorkspaceList
import com.intellij.platform.workspace.storage.impl.containers.toMutableWorkspaceList
import com.intellij.platform.workspace.storage.instrumentation.EntityStorageInstrumentation
import com.intellij.platform.workspace.storage.instrumentation.EntityStorageInstrumentationApi
import com.intellij.platform.workspace.storage.metadata.model.EntityMetadata
import com.intellij.platform.workspace.storage.url.VirtualFileUrl
import com.intellij.workspaceModel.test.api.DataClassWithUrl
import com.intellij.workspaceModel.test.api.EntityWithUrls

@GeneratedCodeApiVersion(3)
@GeneratedCodeImplVersion(7)
@OptIn(WorkspaceEntityInternalApi::class)
internal class EntityWithUrlsImpl(private val dataSource: EntityWithUrlsData) : EntityWithUrls, WorkspaceEntityBase(dataSource) {

  private companion object {


    private val connections = listOf<ConnectionId>(
    )

  }

  override val simpleUrl: VirtualFileUrl
    get() {
      readField("simpleUrl")
      return dataSource.simpleUrl
    }

  override val nullableUrl: VirtualFileUrl?
    get() {
      readField("nullableUrl")
      return dataSource.nullableUrl
    }

  override val listOfUrls: List<VirtualFileUrl>
    get() {
      readField("listOfUrls")
      return dataSource.listOfUrls
    }

  override val dataClassWithUrl: DataClassWithUrl
    get() {
      readField("dataClassWithUrl")
      return dataSource.dataClassWithUrl
    }

  override val entitySource: EntitySource
    get() {
      readField("entitySource")
      return dataSource.entitySource
    }

  override fun connectionIdList(): List<ConnectionId> {
    return connections
  }


  internal class Builder(result: EntityWithUrlsData?) : ModifiableWorkspaceEntityBase<EntityWithUrls, EntityWithUrlsData>(result),
                                                        EntityWithUrls.Builder {
    internal constructor() : this(EntityWithUrlsData())

    override fun applyToBuilder(builder: MutableEntityStorage) {
      if (this.diff != null) {
        if (existsInBuilder(builder)) {
          this.diff = builder
          return
        }
        else {
          error("Entity EntityWithUrls is already created in a different builder")
        }
      }

      this.diff = builder
      addToBuilder()
      this.id = getEntityData().createEntityId()
      // After adding entity data to the builder, we need to unbind it and move the control over entity data to builder
      // Builder may switch to snapshot at any moment and lock entity data to modification
      this.currentEntityData = null

      index(this, "simpleUrl", this.simpleUrl)
      index(this, "nullableUrl", this.nullableUrl)
      index(this, "listOfUrls", this.listOfUrls)
      // Process linked entities that are connected without a builder
      processLinkedEntities(builder)
      checkInitialization() // TODO uncomment and check failed tests
    }

    private fun checkInitialization() {
      val _diff = diff
      if (!getEntityData().isEntitySourceInitialized()) {
        error("Field WorkspaceEntity#entitySource should be initialized")
      }
      if (!getEntityData().isSimpleUrlInitialized()) {
        error("Field EntityWithUrls#simpleUrl should be initialized")
      }
      if (!getEntityData().isListOfUrlsInitialized()) {
        error("Field EntityWithUrls#listOfUrls should be initialized")
      }
      if (!getEntityData().isDataClassWithUrlInitialized()) {
        error("Field EntityWithUrls#dataClassWithUrl should be initialized")
      }
    }

    override fun connectionIdList(): List<ConnectionId> {
      return connections
    }

    override fun afterModification() {
      val collection_listOfUrls = getEntityData().listOfUrls
      if (collection_listOfUrls is MutableWorkspaceList<*>) {
        collection_listOfUrls.cleanModificationUpdateAction()
      }
    }

    // Relabeling code, move information from dataSource to this builder
    override fun relabel(dataSource: WorkspaceEntity, parents: Set<WorkspaceEntity>?) {
      dataSource as EntityWithUrls
      if (this.entitySource != dataSource.entitySource) this.entitySource = dataSource.entitySource
      if (this.simpleUrl != dataSource.simpleUrl) this.simpleUrl = dataSource.simpleUrl
      if (this.nullableUrl != dataSource?.nullableUrl) this.nullableUrl = dataSource.nullableUrl
      if (this.listOfUrls != dataSource.listOfUrls) this.listOfUrls = dataSource.listOfUrls.toMutableList()
      if (this.dataClassWithUrl != dataSource.dataClassWithUrl) this.dataClassWithUrl = dataSource.dataClassWithUrl
      updateChildToParentReferences(parents)
    }


    override var entitySource: EntitySource
      get() = getEntityData().entitySource
      set(value) {
        checkModificationAllowed()
        getEntityData(true).entitySource = value
        changedProperty.add("entitySource")

      }

    override var simpleUrl: VirtualFileUrl
      get() = getEntityData().simpleUrl
      set(value) {
        checkModificationAllowed()
        getEntityData(true).simpleUrl = value
        changedProperty.add("simpleUrl")
        val _diff = diff
        if (_diff != null) index(this, "simpleUrl", value)
      }

    override var nullableUrl: VirtualFileUrl?
      get() = getEntityData().nullableUrl
      set(value) {
        checkModificationAllowed()
        getEntityData(true).nullableUrl = value
        changedProperty.add("nullableUrl")
        val _diff = diff
        if (_diff != null) index(this, "nullableUrl", value)
      }

    private val listOfUrlsUpdater: (value: List<VirtualFileUrl>) -> Unit = { value ->
      val _diff = diff
      if (_diff != null) index(this, "listOfUrls", value)
      changedProperty.add("listOfUrls")
    }
    override var listOfUrls: MutableList<VirtualFileUrl>
      get() {
        val collection_listOfUrls = getEntityData().listOfUrls
        if (collection_listOfUrls !is MutableWorkspaceList) return collection_listOfUrls
        if (diff == null || modifiable.get()) {
          collection_listOfUrls.setModificationUpdateAction(listOfUrlsUpdater)
        }
        else {
          collection_listOfUrls.cleanModificationUpdateAction()
        }
        return collection_listOfUrls
      }
      set(value) {
        checkModificationAllowed()
        getEntityData(true).listOfUrls = value
        listOfUrlsUpdater.invoke(value)
      }

    override var dataClassWithUrl: DataClassWithUrl
      get() = getEntityData().dataClassWithUrl
      set(value) {
        checkModificationAllowed()
        getEntityData(true).dataClassWithUrl = value
        changedProperty.add("dataClassWithUrl")

      }

    override fun getEntityClass(): Class<EntityWithUrls> = EntityWithUrls::class.java
  }
}

@OptIn(WorkspaceEntityInternalApi::class)
internal class EntityWithUrlsData : WorkspaceEntityData<EntityWithUrls>() {
  lateinit var simpleUrl: VirtualFileUrl
  var nullableUrl: VirtualFileUrl? = null
  lateinit var listOfUrls: MutableList<VirtualFileUrl>
  lateinit var dataClassWithUrl: DataClassWithUrl

  internal fun isSimpleUrlInitialized(): Boolean = ::simpleUrl.isInitialized
  internal fun isListOfUrlsInitialized(): Boolean = ::listOfUrls.isInitialized
  internal fun isDataClassWithUrlInitialized(): Boolean = ::dataClassWithUrl.isInitialized

  override fun wrapAsModifiable(diff: MutableEntityStorage): WorkspaceEntity.Builder<EntityWithUrls> {
    val modifiable = EntityWithUrlsImpl.Builder(null)
    modifiable.diff = diff
    modifiable.id = createEntityId()
    return modifiable
  }

  @OptIn(EntityStorageInstrumentationApi::class)
  override fun createEntity(snapshot: EntityStorageInstrumentation): EntityWithUrls {
    val entityId = createEntityId()
    return snapshot.initializeEntity(entityId) {
      val entity = EntityWithUrlsImpl(this)
      entity.snapshot = snapshot
      entity.id = entityId
      entity
    }
  }

  override fun getMetadata(): EntityMetadata {
    return MetadataStorageImpl.getMetadataByTypeFqn("com.intellij.workspaceModel.test.api.EntityWithUrls") as EntityMetadata
  }

  override fun clone(): EntityWithUrlsData {
    val clonedEntity = super.clone()
    clonedEntity as EntityWithUrlsData
    clonedEntity.listOfUrls = clonedEntity.listOfUrls.toMutableWorkspaceList()
    return clonedEntity
  }

  override fun getEntityInterface(): Class<out WorkspaceEntity> {
    return EntityWithUrls::class.java
  }

  override fun createDetachedEntity(parents: List<WorkspaceEntity.Builder<*>>): WorkspaceEntity.Builder<*> {
    return EntityWithUrls(simpleUrl, listOfUrls, dataClassWithUrl, entitySource) {
      this.nullableUrl = this@EntityWithUrlsData.nullableUrl
    }
  }

  override fun getRequiredParents(): List<Class<out WorkspaceEntity>> {
    val res = mutableListOf<Class<out WorkspaceEntity>>()
    return res
  }

  override fun equals(other: Any?): Boolean {
    if (other == null) return false
    if (this.javaClass != other.javaClass) return false

    other as EntityWithUrlsData

    if (this.entitySource != other.entitySource) return false
    if (this.simpleUrl != other.simpleUrl) return false
    if (this.nullableUrl != other.nullableUrl) return false
    if (this.listOfUrls != other.listOfUrls) return false
    if (this.dataClassWithUrl != other.dataClassWithUrl) return false
    return true
  }

  override fun equalsIgnoringEntitySource(other: Any?): Boolean {
    if (other == null) return false
    if (this.javaClass != other.javaClass) return false

    other as EntityWithUrlsData

    if (this.simpleUrl != other.simpleUrl) return false
    if (this.nullableUrl != other.nullableUrl) return false
    if (this.listOfUrls != other.listOfUrls) return false
    if (this.dataClassWithUrl != other.dataClassWithUrl) return false
    return true
  }

  override fun hashCode(): Int {
    var result = entitySource.hashCode()
    result = 31 * result + simpleUrl.hashCode()
    result = 31 * result + nullableUrl.hashCode()
    result = 31 * result + listOfUrls.hashCode()
    result = 31 * result + dataClassWithUrl.hashCode()
    return result
  }

  override fun hashCodeIgnoringEntitySource(): Int {
    var result = javaClass.hashCode()
    result = 31 * result + simpleUrl.hashCode()
    result = 31 * result + nullableUrl.hashCode()
    result = 31 * result + listOfUrls.hashCode()
    result = 31 * result + dataClassWithUrl.hashCode()
    return result
  }
}
