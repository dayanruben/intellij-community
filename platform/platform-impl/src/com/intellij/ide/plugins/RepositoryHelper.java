// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.plugins;

import com.intellij.configurationStore.XmlSerializer;
import com.intellij.ide.IdeBundle;
import com.intellij.ide.plugins.marketplace.MarketplaceRequests;
import com.intellij.ide.plugins.marketplace.utils.MarketplaceCustomizationService;
import com.intellij.ide.plugins.newui.PluginUiModel;
import com.intellij.ide.plugins.newui.PluginUiModelAdapter;
import com.intellij.ide.plugins.newui.PluginUiModelBuilderFactory;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.application.ex.ApplicationInfoEx;
import com.intellij.openapi.application.impl.ApplicationInfoImpl;
import com.intellij.openapi.components.impl.stores.ComponentStorageUtil;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.updateSettings.impl.PluginDownloader;
import com.intellij.openapi.updateSettings.impl.UpdateOptions;
import com.intellij.openapi.updateSettings.impl.UpdateSettings;
import com.intellij.openapi.updateSettings.impl.UpdateSettingsProvider;
import com.intellij.openapi.util.BuildNumber;
import com.intellij.openapi.util.JDOMUtil;
import com.intellij.openapi.util.io.FileUtilRt;
import com.intellij.util.Url;
import com.intellij.util.Urls;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.io.URLUtil;
import com.intellij.util.text.VersionComparatorUtil;
import org.jdom.JDOMException;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static com.intellij.ide.plugins.BrokenPluginFileKt.isBrokenPlugin;

public final class RepositoryHelper {
  private static final Logger LOG = Logger.getInstance(RepositoryHelper.class);

  /** Duplicates VmOptionsGenerator.CUSTOM_BUILT_IN_PLUGIN_REPOSITORY_PROPERTY */
  private static final String CUSTOM_BUILT_IN_PLUGIN_REPOSITORY_PROPERTY = "intellij.plugins.custom.built.in.repository.url";
  @SuppressWarnings("SpellCheckingInspection") private static final String PLUGIN_LIST_FILE = "availables.xml";
  private static final String MARKETPLACE_PLUGIN_ID = "com.intellij.marketplace";
  private static final String ULTIMATE_MODULE = "com.intellij.modules.ultimate";

  /**
   * Returns a list of configured custom plugin repository hosts.
   */
  public static @NotNull List<@NotNull String> getCustomPluginRepositoryHosts() {
    var hosts = new ArrayList<>(UpdateSettings.getInstance().getStoredPluginHosts());

    var pluginHosts = System.getProperty("idea.plugin.hosts");
    if (pluginHosts != null) {
      ContainerUtil.addAll(hosts, pluginHosts.split(";"));
    }

    hosts.addAll(UpdateSettingsProvider.getRepositoriesFromProviders());

    @SuppressWarnings("deprecation") var pluginsUrl = ApplicationInfoEx.getInstanceEx().getBuiltinPluginsUrl();
    if (pluginsUrl != null && !"__BUILTIN_PLUGINS_URL__".equals(pluginsUrl)) {
      hosts.add(pluginsUrl);
    }

    pluginsUrl = System.getProperty(CUSTOM_BUILT_IN_PLUGIN_REPOSITORY_PROPERTY);
    if (pluginsUrl != null) {
      hosts.add(pluginsUrl);
    }

    ContainerUtil.removeDuplicates(hosts);
    return hosts;
  }

  /**
   * Returns a list of configured plugin hosts.
   * Note that the list always ends with {@code null} element denoting the main plugin repository (Marketplace).
   */
  public static @NotNull List<@Nullable String> getPluginHosts() {
    List<@Nullable String> hosts = getCustomPluginRepositoryHosts();
    hosts.add(null); // main plugin repository
    return hosts;
  }

  /**
   * Use method only for getting plugins from custom repositories
   *
   * @deprecated Please use {@link #loadPlugins(String, BuildNumber, ProgressIndicator)} to get a list of {@link PluginNode}s.
   */
  @Deprecated(forRemoval = true)
  public static @NotNull List<IdeaPluginDescriptor> loadPlugins(@Nullable String repositoryUrl, @Nullable ProgressIndicator indicator) throws IOException {
    return new ArrayList<>(loadPlugins(repositoryUrl, null, indicator));
  }

  /**
   * Use method only for getting plugins from custom repositories
   * deprecated, use {@link #loadPluginModels}
   */
  @Deprecated(forRemoval = true)
  public static @NotNull List<PluginNode> loadPlugins(
    @Nullable String repositoryUrl,
    @Nullable BuildNumber build,
    @Nullable ProgressIndicator indicator
  ) throws IOException {
    return ContainerUtil.map(loadPluginModels(repositoryUrl, build, indicator), it -> (PluginNode)it.getDescriptor());
  }

  @Deprecated(forRemoval = true)
  @ApiStatus.Internal
  public static @NotNull List<PluginNode> loadPlugins(
    @Nullable String repositoryUrl,
    @Nullable BuildNumber build,
    @Nullable ProgressIndicator indicator,
    @NotNull PluginUiModelBuilderFactory factory
  ) throws IOException {
    return ContainerUtil.map(loadPluginModels(repositoryUrl, build, indicator, factory), it -> (PluginNode)it.getDescriptor());
  }

  public static @NotNull List<PluginUiModel> loadPluginModels(
    @Nullable String repositoryUrl,
    @Nullable BuildNumber build,
    @Nullable ProgressIndicator indicator
  ) throws IOException {
    return loadPluginModels(repositoryUrl, build, indicator, PluginUiModelBuilderFactory.getInstance());
  }

  @ApiStatus.Internal
  public static @NotNull List<PluginUiModel> loadPluginModels(
    @Nullable String repositoryUrl,
    @Nullable BuildNumber build,
    @Nullable ProgressIndicator indicator,
    @NotNull PluginUiModelBuilderFactory factory
  ) throws IOException {
    Path pluginListFile;
    Url url;
    if (repositoryUrl == null) {
      if (ApplicationInfoImpl.getShadowInstance().usesJetBrainsPluginRepository()) {
        LOG.error("Using deprecated API for getting plugins from Marketplace");
      }
      var base = MarketplaceCustomizationService.getInstance().getPluginsListUrl();
      url = Urls.newFromEncoded(base).addParameters(Map.of("uuid", PluginDownloader.getMarketplaceDownloadsUUID()));
      pluginListFile = Paths.get(PathManager.getPluginsPath(), PLUGIN_LIST_FILE);
    }
    else {
      url = Urls.newFromEncoded(repositoryUrl);
      pluginListFile = null;
    }

    if (!URLUtil.FILE_PROTOCOL.equals(url.getScheme())) {
      url = url.addParameters(Map.of("build", ApplicationInfoImpl.orFromPluginCompatibleBuild(build)));
    }

    if (indicator != null) {
      indicator.setText2(IdeBundle.message("progress.connecting.to.plugin.manager", url.getAuthority()));
    }

    var message = IdeBundle.message("progress.downloading.list.of.plugins", url.getAuthority());
    var descriptors = MarketplaceRequests.readOrUpdateFile(pluginListFile, url.toExternalForm(), indicator, message,
                                                           input -> MarketplaceRequests.parsePluginList(input, factory));
    return process(descriptors, build != null ? build : PluginManagerCore.getBuildNumber(), repositoryUrl);
  }

  private static List<PluginUiModel> process(List<PluginUiModel> uiModels, BuildNumber build, @Nullable String repositoryUrl) {
    var result = new LinkedHashMap<PluginId, PluginUiModel>(uiModels.size());

    var isPaidPluginsRequireMarketplacePlugin = isPaidPluginsRequireMarketplacePlugin();

    for (var model : uiModels) {
      var pluginId = model.getPluginId();

      if (repositoryUrl != null && model.getDownloadUrl() == null) {
        LOG.debug("Malformed plugin record (id:" + pluginId + " repository:" + repositoryUrl + ")");
        continue;
      }

      if (isBrokenPlugin(model.getPluginId(), model.getVersion()) || PluginManagerCore.isIncompatible(model.getDescriptor(), build)) {
        LOG.debug("An incompatible plugin (id:" + pluginId + " repository:" + repositoryUrl + ")");
        continue;
      }

      if (repositoryUrl != null) {
        model.setRepositoryName(repositoryUrl);
      }

      if (model.getName() == null) {
        var url = model.getDownloadUrl();
        model.setName(FileUtilRt.getNameWithoutExtension(url.substring(url.lastIndexOf('/') + 1)));
      }

      var previous = result.get(pluginId);
      if (previous == null || VersionComparatorUtil.compare(model.getVersion(), previous.getVersion()) > 0) {
        result.put(pluginId, model);
      }

      addMarketplacePluginDependencyIfRequired(model, isPaidPluginsRequireMarketplacePlugin);
    }

    return List.copyOf(result.values());
  }

  /**
   * If a plugin is paid (has `productCode`) and the IDE is not JetBrains "ultimate", then MARKETPLACE_PLUGIN_ID is required.
   */
  @ApiStatus.Internal
  public static void addMarketplacePluginDependencyIfRequired(@NotNull PluginUiModel model) {
    var isPaidPluginsRequireMarketplacePlugin = isPaidPluginsRequireMarketplacePlugin();
    addMarketplacePluginDependencyIfRequired(model, isPaidPluginsRequireMarketplacePlugin);
  }

  private static boolean isPaidPluginsRequireMarketplacePlugin() {
    var core = PluginManagerCore.findPlugin(PluginManagerCore.CORE_ID);
    return core == null ||
           !core.getPluginAliases().contains(PluginId.getId(ULTIMATE_MODULE)) ||
           !ApplicationInfoImpl.getShadowInstance().isVendorJetBrains();
  }

  private static void addMarketplacePluginDependencyIfRequired(PluginUiModel node, boolean isPaidPluginsRequireMarketplacePlugin) {
    if (isPaidPluginsRequireMarketplacePlugin && node.getProductCode() != null) {
      node.addDependency(PluginId.getId(MARKETPLACE_PLUGIN_ID), false);
    }
  }

  @ApiStatus.Internal
  @Deprecated(forRemoval = true)
  public static @NotNull Collection<PluginNode> mergePluginsFromRepositories(
    @NotNull List<PluginNode> marketplacePlugins,
    @NotNull List<PluginNode> customPlugins,
    boolean addMissing) {
    List<PluginUiModel> marketplaceModels = ContainerUtil.map(marketplacePlugins, PluginUiModelAdapter::new);
    List<PluginUiModel> customPluginsModels = ContainerUtil.map(customPlugins, PluginUiModelAdapter::new);
    Collection<PluginUiModel> mergedPluginModels = mergePluginModelsFromRepositories(marketplaceModels, customPluginsModels, addMissing);
    return ContainerUtil.map(mergedPluginModels, model-> (PluginNode)model.getDescriptor());
  }

  @ApiStatus.Internal
  public static @NotNull Collection<PluginUiModel> mergePluginModelsFromRepositories(
    @NotNull List<PluginUiModel> marketplacePlugins,
    @NotNull List<PluginUiModel> customPlugins,
    boolean addMissing
  ) {
    var compatiblePluginMap = new LinkedHashMap<PluginId, PluginUiModel>(marketplacePlugins.size());

    for (var marketplacePlugin : marketplacePlugins) {
      compatiblePluginMap.put(marketplacePlugin.getPluginId(), marketplacePlugin);
    }

    for (var customPlugin : customPlugins) {
      var pluginId = customPlugin.getPluginId();
      var plugin = compatiblePluginMap.get(pluginId);
      if (plugin == null && addMissing ||
          plugin != null && PluginDownloader.compareVersionsSkipBrokenAndIncompatible(customPlugin.getVersion(), plugin.getDescriptor()) > 0) {
        compatiblePluginMap.put(pluginId, customPlugin);
      }
    }

    return compatiblePluginMap.values();
  }

  /**
   * Returns a list of plugins compatible with the current build, loaded from all configured custom repositories.
   */
  @ApiStatus.Internal
  public static @NotNull List<PluginNode> loadPluginsFromCustomRepositories(@Nullable ProgressIndicator indicator) {
    return loadPluginsFromCustomRepositories(indicator, PluginUiModelBuilderFactory.getInstance());
  }


  @ApiStatus.Internal
  public static @NotNull List<PluginNode> loadPluginsFromCustomRepositories(@Nullable ProgressIndicator indicator, @NotNull PluginUiModelBuilderFactory factory) {
    var ids = new HashSet<PluginId>();
    var result = new ArrayList<PluginNode>();

    for (var host : getCustomPluginRepositoryHosts()) {
      try {
        var plugins = loadPlugins(host, null, indicator, factory);
        for (var plugin : plugins) {
          if (ids.add(plugin.getPluginId())) {
            result.add(plugin);
          }
        }
      }
      catch (IOException e) {
        LOG.info("Couldn't load plugins from " + host + ": " + e);
        LOG.debug(e);
      }
    }

    return result;
  }

  /**
   * Looks for the given plugins in the Marketplace and custom repositories. Only compatible plugins are returned.
   */
  public static @NotNull Collection<PluginNode> loadPlugins(@NotNull Set<PluginId> pluginIds) {
    var mpPlugins = MarketplaceRequests.loadLastCompatiblePluginDescriptors(pluginIds);
    var customPlugins = loadPluginsFromCustomRepositories(null).stream().filter(p -> pluginIds.contains(p.getPluginId())).toList();
    return mergePluginsFromRepositories(mpPlugins, customPlugins, true);
  }

  @ApiStatus.Internal
  public static void updatePluginHostsFromConfigDir(@NotNull Path oldConfigDir, @NotNull Logger logger) {
    logger.info("reading plugin repositories from " + oldConfigDir);
    try {
      var text = ComponentStorageUtil.loadTextContent(oldConfigDir.resolve("options/updates.xml"));
      var components = ComponentStorageUtil.loadComponents(JDOMUtil.load(text), null);
      var element = components.get("UpdatesConfigurable");
      if (element != null) {
        var hosts = XmlSerializer.deserialize(element, UpdateOptions.class).getPluginHosts();
        if (!hosts.isEmpty()) {
          amendPluginHostsProperty(hosts);
          logger.info("plugin hosts: " + System.getProperty("idea.plugin.hosts"));
        }
      }
    }
    catch (InvalidPathException | IOException | JDOMException e) {
      logger.error("... failed: " + e.getMessage());
    }
  }

  @ApiStatus.Internal
  public static void amendPluginHostsProperty(@NotNull Collection<String> repositoryUrls) {
    var hosts = System.getProperty("idea.plugin.hosts");
    var newHosts = String.join(";", repositoryUrls);
    if (hosts != null && !hosts.isBlank()) {
      newHosts = hosts + ";" + newHosts;
    }
    System.setProperty("idea.plugin.hosts", newHosts);
  }
}
