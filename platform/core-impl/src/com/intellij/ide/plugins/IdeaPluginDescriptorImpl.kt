// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.plugins

import com.intellij.AbstractBundle
import com.intellij.DynamicBundle
import com.intellij.core.CoreBundle
import com.intellij.ide.plugins.ModuleLoadingRule.Companion.fromElementValue
import com.intellij.idea.AppMode
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.ExtensionDescriptor
import com.intellij.openapi.extensions.LoadingOrder
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.extensions.impl.ExtensionPointImpl
import com.intellij.openapi.util.NlsSafe
import com.intellij.platform.plugins.parser.impl.PluginDescriptorBuilder
import com.intellij.platform.plugins.parser.impl.RawPluginDescriptor
import com.intellij.platform.plugins.parser.impl.elements.*
import com.intellij.util.Java11Shim
import com.intellij.util.PlatformUtils
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey
import org.jetbrains.annotations.VisibleForTesting
import java.io.IOException
import java.nio.file.Path
import java.time.ZoneOffset
import java.util.*

private val LOG: Logger
  get() = PluginManagerCore.logger

@ApiStatus.Internal
class IdeaPluginDescriptorImpl private constructor(
  raw: RawPluginDescriptor,
  pluginPath: Path,
  isBundled: Boolean,
  id: PluginId?,
  moduleName: String?,
  moduleLoadingRule: ModuleLoadingRule? = null,
  useCoreClassLoader: Boolean = false,
  isIndependentFromCoreClassLoader: Boolean = false,
  descriptorPath: String? = null
) : IdeaPluginDescriptorEx {

  constructor(
    raw: RawPluginDescriptor,
    pluginPath: Path,
    isBundled: Boolean,
    id: PluginId?,
    moduleName: String?,
    moduleLoadingRule: ModuleLoadingRule? = null,
    useCoreClassLoader: Boolean = false,
  ) : this(
    raw = raw,
    pluginPath = pluginPath,
    isBundled = isBundled,
    id = id,
    moduleName = moduleName,
    moduleLoadingRule = moduleLoadingRule,
    useCoreClassLoader = useCoreClassLoader,
    isIndependentFromCoreClassLoader = false,
    descriptorPath = null)

  init {
    if (moduleName != null) {
      require(moduleLoadingRule != null) { "'moduleLoadingRule' parameter must be specified when creating a module descriptor, but it is missing for '$moduleName'" }
    }
  }

  private val id: PluginId = id ?: PluginId.getId(raw.id ?: raw.name ?: throw RuntimeException("Neither id nor name are specified"))
  private val name: String = raw.name ?: id?.idString ?: raw.id!! // if it throws, it throws on `id` above

  override val moduleName: String? = moduleName
  override val moduleLoadingRule: ModuleLoadingRule? = moduleLoadingRule

  private val version: String? = raw.version
  private val sinceBuild: String? = raw.sinceBuild
  @Suppress("DEPRECATION")
  private val untilBuild: String? = UntilBuildDeprecation.nullizeIfTargets243OrLater(raw.untilBuild, raw.name ?: raw.id)

  private val productCode: String? = raw.productCode
  private val releaseDate: Date? = raw.releaseDate?.let { Date.from(it.atStartOfDay(ZoneOffset.UTC).toInstant()) }
  private val releaseVersion: Int = raw.releaseVersion
  private val isLicenseOptional: Boolean = raw.isLicenseOptional

  private val rawDescription: @NlsSafe String? = raw.description
  private val category: String? = raw.category
  private val changeNotes: String? = raw.changeNotes

  private val vendor: String? = raw.vendor
  private val vendorEmail: String? = raw.vendorEmail
  private val vendorUrl: String? = raw.vendorUrl
  private val url: String? = raw.url

  private val dependenciesV1: List<PluginDependencyImpl> = raw.depends
    .let(::fixDepends)
    .let(::convertDepends)
  override val incompatiblePlugins: List<PluginId> = raw.incompatibleWith.map(PluginId::getId)
  override val pluginAliases: List<PluginId> = raw.pluginAliases.map(PluginId::getId)
    .let(::addCorePluginAliases)

  val appContainerDescriptor: ContainerDescriptor = raw.appElementsContainer.convert()
  val projectContainerDescriptor: ContainerDescriptor = raw.projectElementsContainer.convert()
  val moduleContainerDescriptor: ContainerDescriptor = raw.moduleElementsContainer.convert()

  override val miscExtensions: Map<String, List<ExtensionDescriptor>> = raw.miscExtensions
    .let(::convertExtensions)
    .let(::sortExtensions)

  private val resourceBundleBaseName: String? = raw.resourceBundleBaseName
    .also { warnIfResourceBundleIsDefinedForCorePlugin(it) }

  override val actions: List<ActionElement> = raw.actions

  val content: PluginContentDescriptor =
    raw.contentModules.takeIf { it.isNotEmpty() }?.let { PluginContentDescriptor(convertContentModules(it)) }
    ?: PluginContentDescriptor.EMPTY
  override val dependenciesV2: ModuleDependenciesDescriptor = raw.dependencies.let(::convertDependencies)
  val packagePrefix: String? = raw.`package`

  private val isRestartRequired: Boolean = raw.isRestartRequired
  private val isImplementationDetail: Boolean = raw.isImplementationDetail
  private val isBundled: Boolean = isBundled
  private val isBundledUpdateAllowed: Boolean = raw.isBundledUpdateAllowed

  val isUseIdeaClassLoader: Boolean = raw.isUseIdeaClassLoader
  override val isIndependentFromCoreClassLoader: Boolean = isIndependentFromCoreClassLoader
  override val useCoreClassLoader: Boolean = useCoreClassLoader

  private val pluginPath: Path = pluginPath
  private val descriptorPath: String? = descriptorPath

  private var isEnabled = true

  @Transient
  var jarFiles: List<Path>? = null
  private var _pluginClassLoader: ClassLoader? = null

  var isDeleted: Boolean = false

  internal var isIncomplete: PluginLoadingError? = null

  @Volatile
  private var loadedDescriptionText: @Nls String? = null

  override fun getPluginId(): PluginId = id

  override fun getName(): String {
    PluginCardOverrides.getNameOverride(id)?.let {
      return it
    }
    return name
  }

  override fun getVersion(): String? = version
  override fun getSinceBuild(): String? = sinceBuild
  override fun getUntilBuild(): String? = untilBuild

  override fun getProductCode(): String? = productCode
  override fun getReleaseDate(): Date? = releaseDate
  override fun getReleaseVersion(): Int = releaseVersion
  override fun isLicenseOptional(): Boolean = isLicenseOptional

  override fun getChangeNotes(): String? = changeNotes
  override fun getCategory(): String? = category

  override fun getDisplayCategory(): @Nls String? = getCategory()?.let {
    val key = "plugin.category.${category?.replace(' ', '.')}"
    CoreBundle.messageOrNull(key) ?: fromPluginBundle(key, getCategory())
  }

  override fun getDescription(): @Nls String? {
    var result = loadedDescriptionText
    if (result != null) {
      return result
    }
    PluginCardOverrides.getDescriptionOverride(id)?.let {
      loadedDescriptionText = it
      return it
    }
    result = fromPluginBundle("plugin.$id.description", rawDescription)
    loadedDescriptionText = result
    return result
  }

  override fun getVendor(): String? = vendor
  override fun getVendorEmail(): String? = vendorEmail
  override fun getVendorUrl(): String? = vendorUrl
  override fun getUrl(): String? = url

  override fun isBundled(): Boolean = isBundled
  override fun allowBundledUpdate(): Boolean = isBundledUpdateAllowed
  override fun isImplementationDetail(): Boolean = isImplementationDetail
  override fun isRequireRestart(): Boolean = isRestartRequired

  /**
   * aka `<depends>` elements from the plugin.xml
   *
   * Note that it's different from [dependenciesV2]
   */
  override fun getDependencies(): List<PluginDependency> = dependenciesV1

  override fun getResourceBundleBaseName(): String? = resourceBundleBaseName

  override fun getPluginPath(): Path = pluginPath
  override fun getDescriptorPath(): String? = descriptorPath

  override fun getPluginClassLoader(): ClassLoader? = _pluginClassLoader

  @ApiStatus.Internal
  fun setPluginClassLoader(classLoader: ClassLoader?) {
    _pluginClassLoader = classLoader
  }

  override fun isEnabled(): Boolean = isEnabled

  override fun setEnabled(enabled: Boolean) {
    isEnabled = enabled
  }

  override fun equals(other: Any?): Boolean {
    return this === other || other is IdeaPluginDescriptorImpl && id == other.id && descriptorPath == other.descriptorPath
  }

  override fun hashCode(): Int = 31 * id.hashCode() + (descriptorPath?.hashCode() ?: 0)

  override fun toString(): String =
    "PluginDescriptor(name=$name, id=$id, " +
    (if (moduleName == null) "" else "moduleName=$moduleName, ") +
    "descriptorPath=${descriptorPath ?: "plugin.xml"}, " +
    "path=${PluginUtils.pluginPathToUserString(pluginPath)}, version=$version, package=$packagePrefix, isBundled=$isBundled)"

  internal fun createSub(
    subBuilder: PluginDescriptorBuilder,
    descriptorPath: String,
    context: DescriptorListLoadingContext,
    module: PluginContentDescriptor.ModuleItem?,
  ): IdeaPluginDescriptorImpl {
    subBuilder.name = name
    subBuilder.vendor = vendor
    if (subBuilder.version != null && subBuilder.version != version) {
      LOG.warn("Sub descriptor version redefinition for plugin $id. Original value: ${subBuilder.version}, inherited value: ${version ?: context.defaultVersion}")
    }
    subBuilder.version = version ?: context.defaultVersion
    if (module == null) { // resource bundle is inherited for v1 sub-descriptors only
      if (subBuilder.resourceBundleBaseName == null) {
        subBuilder.resourceBundleBaseName = resourceBundleBaseName
      } else {
        if (subBuilder.resourceBundleBaseName != resourceBundleBaseName && resourceBundleBaseName != null) {
          LOG.warn("Resource bundle redefinition for plugin $id. Parent value: $resourceBundleBaseName, new value: ${subBuilder.resourceBundleBaseName}")
        }
      }
    }
    val raw = subBuilder.build()
    val result = IdeaPluginDescriptorImpl(
      raw = raw,
      pluginPath = pluginPath,
      isBundled = isBundled,
      id = id,
      moduleName = module?.name,
      moduleLoadingRule = module?.loadingRule,
      useCoreClassLoader = useCoreClassLoader,
      isIndependentFromCoreClassLoader = raw.isIndependentFromCoreClassLoader,
      descriptorPath = descriptorPath)
    context.debugData?.recordDescriptorPath(descriptor = result, rawPluginDescriptor = raw, path = descriptorPath)
    return result
  }

  internal fun initialize(context: DescriptorListLoadingContext, pathResolver: PathResolver, dataLoader: DataLoader) {
    if (context.isPluginDisabled(id)) {
      markAsIncomplete(disabledDependency = null, shortMessage = null)
    }
    else {
      checkCompatibility(context)
      if (isIncomplete != null) {
        return
      }

      for (pluginDependency in dependenciesV2.plugins) {
        if (context.isPluginDisabled(pluginDependency.id)) {
          markAsIncomplete(pluginDependency.id, shortMessage = "plugin.loading.error.short.depends.on.disabled.plugin")
        }
      }
    }

    if (isIncomplete == null && moduleName == null) {
      initializeV1Dependencies(context, pathResolver, dataLoader)
    }
  }

  private fun initializeV1Dependencies(context: DescriptorListLoadingContext,
                                       pathResolver: PathResolver,
                                       dataLoader: DataLoader) {
    var visitedFiles: MutableList<String>? = null
    for (dependency in dependenciesV1) {
      // context.isPluginIncomplete must be not checked here as another version of a plugin may be supplied later from another source
      if (context.isPluginDisabled(dependency.pluginId)) {
        if (!dependency.isOptional && isIncomplete == null) {
          markAsIncomplete(dependency.pluginId, "plugin.loading.error.short.depends.on.disabled.plugin")
          // TODO: why we don't just return immediately? or at least continue?
        }
      }

      // because of https://youtrack.jetbrains.com/issue/IDEA-206274, configFile maybe not only for optional dependencies
      val configFile = dependency.configFile ?: continue
      if (pathResolver.isFlat && context.checkOptionalConfigShortName(configFile, this)) {
        continue
      }

      if (isKotlinPlugin(dependency.pluginId) && isIncompatibleWithKotlinPlugin(this)) {
        LOG.warn("Plugin ${this} depends on Kotlin plugin via `${configFile}` " +
                 "but the plugin is not compatible with the Kotlin plugin in the  ${if (isKotlinPluginK1Mode()) "K1" else "K2"} mode. " +
                 "So, the `${configFile}` was not loaded")
        continue
      }

      var resolveError: Exception? = null
      val raw: PluginDescriptorBuilder? = try {
        pathResolver.resolvePath(context, dataLoader, configFile)
      }
      catch (e: IOException) {
        resolveError = e
        null
      }

      if (raw == null) {
        val message = "Plugin $this misses optional descriptor $configFile"
        if (context.isMissingSubDescriptorIgnored) {
          LOG.info(message)
          if (resolveError != null) {
            LOG.debug(resolveError)
          }
        }
        else {
          throw RuntimeException(message, resolveError)
        }
        continue
      }

      if (visitedFiles == null) {
        visitedFiles = context.visitedFiles
      }

      checkCycle(this, configFile, visitedFiles)

      visitedFiles.add(configFile)
      val subDescriptor = createSub(raw, configFile, context, module = null)
      if (subDescriptor.isIncomplete == null) {
        subDescriptor.initializeV1Dependencies(context, pathResolver, dataLoader)
      }
      dependency.setSubDescriptor(subDescriptor)
      visitedFiles.clear() // TODO: shouldn't it be removeLast instead?
    }
  }

  private fun checkCompatibility(context: DescriptorListLoadingContext) {
    fun markAsIncompatible(error: PluginLoadingError) {
      if (isIncomplete != null) {
        return
      }
      isIncomplete = error
      isEnabled = false
    }

    if (isPluginWhichDependsOnKotlinPluginAndItsIncompatibleWithIt(this)) {
      // disable plugins which are incompatible with the Kotlin Plugin K1/K2 Modes KTIJ-24797, KTIJ-30474
      val mode = if (isKotlinPluginK1Mode()) CoreBundle.message("plugin.loading.error.k1.mode") else CoreBundle.message("plugin.loading.error.k2.mode")
      markAsIncompatible(PluginLoadingError(
        plugin = this,
        detailedMessageSupplier = { CoreBundle.message("plugin.loading.error.long.kotlin.incompatible", getName(), mode) },
        shortMessageSupplier = { CoreBundle.message("plugin.loading.error.short.kotlin.incompatible", mode) },
        isNotifyUser = false,
      ))
      return
    }

    if (isBundled) {
      return
    }

    if (AppMode.isDisableNonBundledPlugins()) {
      markAsIncompatible(PluginLoadingError(
        plugin = this,
        detailedMessageSupplier = { CoreBundle.message("plugin.loading.error.long.custom.plugin.loading.disabled", getName()) },
        shortMessageSupplier = { CoreBundle.message("plugin.loading.error.short.custom.plugin.loading.disabled") },
        isNotifyUser = false
      ))
      return
    }

    PluginManagerCore.checkBuildNumberCompatibility(this, context.productBuildNumber())?.let {
      markAsIncompatible(it)
      return
    }

    // "Show broken plugins in Settings | Plugins so that users can uninstall them and resolve 'Plugin Error' (IDEA-232675)"
    if (context.isBroken(this)) {
      markAsIncompatible(PluginLoadingError(
        plugin = this,
        detailedMessageSupplier = { CoreBundle.message("plugin.loading.error.long.marked.as.broken", name, version) },
        shortMessageSupplier = { CoreBundle.message("plugin.loading.error.short.marked.as.broken") }
      ))
    }
  }

  private fun markAsIncomplete(disabledDependency: PluginId?, @PropertyKey(resourceBundle = CoreBundle.BUNDLE) shortMessage: String?) {
    if (isIncomplete != null) {
      return
    }

    if (shortMessage == null) {
      isIncomplete = PluginLoadingError(plugin = this, detailedMessageSupplier = null, shortMessageSupplier = PluginLoadingError.DISABLED)
    }
    else {
      isIncomplete = PluginLoadingError(
        plugin = this,
        detailedMessageSupplier = null,
        shortMessageSupplier = { CoreBundle.message(shortMessage, disabledDependency!!) },
        isNotifyUser = false,
        disabledDependency)
    }

    isEnabled = false
  }

  @ApiStatus.Internal
  fun registerExtensions(nameToPoint: Map<String, ExtensionPointImpl<*>>, containerDescriptor: ContainerDescriptor, listenerCallbacks: MutableList<in Runnable>?) {
    if (!containerDescriptor.extensions.isEmpty()) {
      for ((name, descriptors) in containerDescriptor.extensions) {
        nameToPoint[name]?.registerExtensions(descriptors, pluginDescriptor = this, listenerCallbacks)
      }
      return
    }

    val map = miscExtensions
    if (map.isEmpty()) {
      return
    }

    // app container: in most cases will be only app-level extensions - to reduce map copying, assume that all extensions are app-level and then filter out
    // project container: rest of extensions will be mostly project level
    // module container: just use rest, area will not register unrelated extension anyway as no registered point

    if (containerDescriptor === appContainerDescriptor) {
      val registeredCount = doRegisterExtensions(map, nameToPoint, listenerCallbacks)
      containerDescriptor.distinctExtensionPointCount = registeredCount

      if (registeredCount == map.size) {
        projectContainerDescriptor.extensions = Java11Shim.INSTANCE.mapOf()
        moduleContainerDescriptor.extensions = Java11Shim.INSTANCE.mapOf()
      }
    }
    else if (containerDescriptor === projectContainerDescriptor) {
      val registeredCount = doRegisterExtensions(map, nameToPoint, listenerCallbacks)
      containerDescriptor.distinctExtensionPointCount = registeredCount

      if (registeredCount == map.size) {
        containerDescriptor.extensions = map
        moduleContainerDescriptor.extensions = Java11Shim.INSTANCE.mapOf()
      }
      else if (registeredCount == (map.size - appContainerDescriptor.distinctExtensionPointCount)) {
        moduleContainerDescriptor.extensions = Java11Shim.INSTANCE.mapOf()
      }
    }
    else {
      val registeredCount = doRegisterExtensions(map, nameToPoint, listenerCallbacks)
      if (registeredCount == 0) {
        moduleContainerDescriptor.extensions = Java11Shim.INSTANCE.mapOf()
      }
    }
  }

  private fun doRegisterExtensions(map: Map<String, List<ExtensionDescriptor>>, nameToPoint: Map<String, ExtensionPointImpl<*>>, listenerCallbacks: MutableList<in Runnable>?): Int {
    var registeredCount = 0
    for ((descriptors, point) in intersectMaps(map, nameToPoint)) {
      point.registerExtensions(descriptors, pluginDescriptor = this, listenerCallbacks)
      registeredCount++
    }
    return registeredCount
  }

  private fun fromPluginBundle(key: String, @Nls defaultValue: String?): @Nls String? {
    if (!isEnabled) { // if the plugin is disabled, its classloader is null and the resource bundle cannot be found
      return defaultValue
    }
    val baseName = resourceBundleBaseName
    if (baseName == null) {
      return defaultValue
    }
    return (try {
      AbstractBundle.messageOrDefault(DynamicBundle.getResourceBundle(classLoader, baseName), key, defaultValue ?: "")
    }
    catch (_: MissingResourceException) {
      LOG.info("Cannot find plugin $id resource-bundle: $baseName")
      null
    }) ?: defaultValue
  }

  private fun checkCycle(descriptor: IdeaPluginDescriptorImpl, configFile: String, visitedFiles: List<String>) {
    var i = 0
    val n = visitedFiles.size
    while (i < n) {
      if (configFile == visitedFiles[i]) {
        val cycle = visitedFiles.subList(i, visitedFiles.size)
        throw RuntimeException("Plugin $descriptor optional descriptors form a cycle: ${java.lang.String.join(", ", cycle)}")
      }
      i++
    }
  }

  private fun addCorePluginAliases(pluginAliases: List<PluginId>): List<PluginId> {
    if (id != PluginManagerCore.CORE_ID) {
      return pluginAliases
    }
    return pluginAliases + IdeaPluginOsRequirement.getHostOsModuleIds() + productModeAliasesForCorePlugin()
  }

  private fun warnIfResourceBundleIsDefinedForCorePlugin(resourceBundle: String?) {
    if (resourceBundle != null && id == PluginManagerCore.CORE_ID && moduleName == null) {
      LOG.warn("<resource-bundle>$resourceBundle</resource-bundle> tag is found in an xml descriptor" +
               " included into the platform part of the IDE but the platform part uses predefined bundles " +
               "(e.g. ActionsBundle for actions) anyway; this tag must be replaced by a corresponding attribute in some inner tags " +
               "(e.g. by 'resource-bundle' attribute in 'actions' tag)")
    }
  }

  @ApiStatus.Internal
  companion object {
    private fun convertDepends(depends: List<DependsElement>): MutableList<PluginDependencyImpl> =
      depends.mapTo(ArrayList(depends.size)) {
        PluginDependencyImpl(PluginId.getId(it.pluginId), it.configFile, it.isOptional)
      }

    /** https://youtrack.jetbrains.com/issue/IDEA-206274 */
    private fun fixDepends(depends: List<DependsElement>): List<DependsElement> {
      val unchanged = 0.toByte()
      val removed = 1.toByte()
      val nonOptional = 2.toByte()
      var elemState: ByteArray? = null
      fun getState(index: Int) = elemState?.get(index) ?: unchanged
      fun setState(index: Int, value: Byte) {
        if (elemState == null) {
          elemState = ByteArray(depends.size) { unchanged }
        }
        elemState[index] = value
      }
      fun isOptional(index: Int) = depends[index].isOptional && getState(index) == unchanged
      for ((index, item) in depends.withIndex()) {
        if (isOptional(index)) continue
        for ((candidateIndex, candidate) in depends.withIndex()) {
          if (isOptional(candidateIndex) && candidate.pluginId == item.pluginId) {
            setState(candidateIndex, nonOptional)
            setState(index, removed)
            break
          }
        }
      }
      if (elemState == null) {
        return depends
      }
      return depends.mapIndexedNotNull { index, _ ->
        when (getState(index)) {
          unchanged -> depends[index]
          removed -> null
          nonOptional -> DependsElement(depends[index].pluginId, false, depends[index].configFile)
          else -> throw IllegalStateException("Unknown state ${getState(index)}")
        }
      }
    }

    private fun sortExtensions(rawMap: Map<String, List<ExtensionDescriptor>>): Map<String, List<ExtensionDescriptor>> {
      if (rawMap.size < 2 || !rawMap.containsKey(REGISTRY_EP_NAME)) {
        return rawMap
      }
      /*
       * What's going on: see `com.intellij.ide.plugins.DynamicPluginsTest.registry access of key from same plugin`
       * This is an ad-hoc solution to the problem, it doesn't fix the root cause. This may also break if this map gets copied
       * or transformed into a HashMap somewhere, but it seems it's not the case right now.
       * TODO: one way to make a better fix is to introduce loadingOrder on extension points (as it is made for extensions).
       */
      val result = LinkedHashMap<String, List<ExtensionDescriptor>>(rawMap.size)
      val keys = rawMap.keys.toTypedArray()
      keys.sortWith(extensionPointNameComparator)
      for (key in keys) {
        result.put(key, rawMap[key]!!)
      }
      return result
    }

    private const val REGISTRY_EP_NAME = "com.intellij.registryKey"

    private val extensionPointNameComparator = Comparator<String> { o1, o2 ->
      if (o1 == REGISTRY_EP_NAME) {
        if (o2 == REGISTRY_EP_NAME) 0
        else -1
      }
      else if (o2 == REGISTRY_EP_NAME) 1
      else o1.compareTo(o2)
    }

    private fun convertExtensions(rawMap: Map<String, List<MiscExtensionElement>>): Map<String, List<ExtensionDescriptor>> = rawMap.mapValues { (_, extensions) ->
      extensions.mapNotNull {
        try {
          val order = LoadingOrder.readOrder(it.order) // throws AssertionError
          ExtensionDescriptor(
            implementation = it.implementation,
            os = it.os?.convert(),
            orderId = it.orderId,
            order = order,
            element = it.element,
            hasExtraAttributes = it.hasExtraAttributes
          )
        } catch (e: Throwable) {
          LOG.error(e)
          null
        }
      }
    }

    private fun convertDependencies(dependencies: List<DependenciesElement>): ModuleDependenciesDescriptor {
      if (dependencies.isEmpty()) {
        return ModuleDependenciesDescriptor.EMPTY
      }
      val moduleDeps = ArrayList<ModuleDependenciesDescriptor.ModuleReference>()
      val pluginDeps = ArrayList<ModuleDependenciesDescriptor.PluginReference>()
      for (dep in dependencies) {
        when (dep) {
          is DependenciesElement.PluginDependency -> pluginDeps.add(ModuleDependenciesDescriptor.PluginReference(PluginId.getId(dep.pluginId)))
          is DependenciesElement.ModuleDependency -> moduleDeps.add(ModuleDependenciesDescriptor.ModuleReference(dep.moduleName))
          else -> LOG.error("Unknown dependency type: $dep")
        }
      }
      return ModuleDependenciesDescriptor(moduleDeps, pluginDeps)
    }

    private fun convertContentModules(contentElements: List<ContentElement>): List<PluginContentDescriptor.ModuleItem> {
      return contentElements.mapNotNull { elem ->
        when (elem) {
          is ContentElement.Module -> {
            val index = elem.name.lastIndexOf('/')
            val configFile: String? = if (index != -1) {
              "${elem.name.substring(0, index)}.${elem.name.substring(index + 1)}.xml"
            } else null
            PluginContentDescriptor.ModuleItem(elem.name, configFile, elem.embeddedDescriptorContent, elem.loadingRule.fromElementValue())
          }
          else -> {
            LOG.error("Unknown content element: $elem")
            null
          }
        }
      }
    }

    private fun <K, V1, V2> intersectMaps(first: Map<K, V1>, second: Map<K, V2>): List<Pair<V1, V2>> {
      // Make sure we iterate the smaller map
      return if (first.size < second.size) {
        first.mapNotNull { (key, firstValue) ->
          second[key]?.let { secondValue -> firstValue to secondValue }
        }
      }
      else {
        second.mapNotNull { (key, secondValue) ->
          first[key]?.let { firstValue -> firstValue to secondValue }
        }
      }
    }

    /**
     * This method returns plugin aliases, which are added to the core module.
     * This is done to support running without the module-based loader (from sources and in dev mode),
     * where all modules are available, but only some of them need to be loaded.
     *
     * This method is left for compatibility only.
     * Now dependencies on 'intellij.platform.frontend' and 'intellij.platform.backend' should be used instead.
     * These modules are automatically disabled if they aren't relevant to the product mode, see [PluginSetBuilder.getModuleIncompatibleWithCurrentProductMode].
     */
    @VisibleForTesting
    @ApiStatus.Obsolete
    fun productModeAliasesForCorePlugin(): List<PluginId> = buildList {
      if (!AppMode.isRemoteDevHost()) {
        // This alias is available in monolith and frontend.
        // Modules, which depend on it, will not be loaded in a split backend.
        add(PluginId.getId("com.intellij.platform.experimental.frontend"))
      }
      if (!PlatformUtils.isJetBrainsClient()) {
        // This alias is available in monolith and backend.
        // Modules, which depend on it, will not be loaded in a split frontend.
        add(PluginId.getId("com.intellij.platform.experimental.backend"))
      }
      if (!AppMode.isRemoteDevHost() && !PlatformUtils.isJetBrainsClient()) {
        // This alias is available in monolith only.
        // Modules, which depend on it, will not be loaded in split mode.
        add(PluginId.getId("com.intellij.platform.experimental.monolith"))
      }
    }
  }
}