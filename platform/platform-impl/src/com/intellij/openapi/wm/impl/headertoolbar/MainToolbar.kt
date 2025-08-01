// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("ReplacePutWithAssignment")

package com.intellij.openapi.wm.impl.headertoolbar

import com.intellij.accessibility.AccessibilityUtils
import com.intellij.ide.ProjectWindowCustomizerService
import com.intellij.ide.repaintWhenProjectGradientOffsetChanged
import com.intellij.ide.ui.LafManagerListener
import com.intellij.ide.ui.MainMenuDisplayMode
import com.intellij.ide.ui.UISettings
import com.intellij.ide.ui.customization.*
import com.intellij.ide.ui.laf.darcula.ui.MainToolbarComboBoxButtonUI
import com.intellij.idea.AppMode
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.actionSystem.ex.ComboBoxAction.ComboBoxButton
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.actionSystem.impl.ActionToolbarImpl
import com.intellij.openapi.actionSystem.impl.ActionToolbarPresentationFactory
import com.intellij.openapi.actionSystem.impl.PresentationFactory
import com.intellij.openapi.actionSystem.toolbarLayout.CompressingLayoutStrategy
import com.intellij.openapi.actionSystem.toolbarLayout.ToolbarLayoutStrategy
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.UiWithModelAccess
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.keymap.impl.ui.ActionsTreeUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.wm.impl.IdeBackgroundUtil
import com.intellij.openapi.wm.impl.ToolbarComboButton
import com.intellij.openapi.wm.impl.customFrameDecorations.header.CustomWindowHeaderUtil
import com.intellij.openapi.wm.impl.customFrameDecorations.header.CustomWindowHeaderUtil.hideNativeLinuxTitle
import com.intellij.openapi.wm.impl.customFrameDecorations.header.CustomWindowHeaderUtil.isMenuButtonInToolbar
import com.intellij.openapi.wm.impl.customFrameDecorations.header.toolbar.ExpandableMenu
import com.intellij.openapi.wm.impl.customFrameDecorations.header.toolbar.HeaderToolbarButtonLook
import com.intellij.platform.diagnostic.telemetry.impl.span
import com.intellij.ui.*
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.mac.touchbar.TouchbarSupport
import com.intellij.util.containers.ContainerUtil
import com.intellij.util.ui.JBInsets
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.JBUI.CurrentTheme.Toolbar.mainToolbarButtonInsets
import com.intellij.util.ui.showingScope
import com.jetbrains.WindowDecorations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.ApiStatus.Internal
import java.awt.*
import java.awt.event.MouseEvent
import java.lang.ref.WeakReference
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.accessibility.AccessibleContext
import javax.accessibility.AccessibleRole
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JPanel
import kotlin.math.min

private const val MAIN_TOOLBAR_ID = IdeActions.GROUP_MAIN_TOOLBAR_NEW_UI

private sealed interface MainToolbarFlavor {
  fun addWidget() {
  }
}

private class MenuButtonInToolbarMainToolbarFlavor(
  coroutineScope: CoroutineScope,
  private val headerContent: JComponent,
  frame: JFrame, toolbar: MainToolbar,
) : MainToolbarFlavor {
  private val mainMenuWithButton = MainMenuWithButton(coroutineScope, frame)
  private val mainMenuButton = mainMenuWithButton.mainMenuButton

  init {
    val expandableMenu = ExpandableMenu(headerContent = headerContent, coroutineScope = coroutineScope, frame)
    mainMenuButton.expandableMenu = expandableMenu
    mainMenuButton.rootPane = frame.rootPane
    toolbar.addWidthCalculationListener(object : ToolbarWidthCalculationListener {
      override fun onToolbarCompressed(event: ToolbarWidthCalculationEvent) {
        mainMenuWithButton.recalculateWidth(toolbar)
      }
    })
  }

  override fun addWidget() {
    addWidget(widget = mainMenuWithButton, parent = headerContent, position = HorizontalLayout.Group.LEFT)
  }

}

private data object DefaultMainToolbarFlavor : MainToolbarFlavor

private const val layoutGap = 10

@Internal
class MainToolbar(
  private val coroutineScope: CoroutineScope,
  private val frame: JFrame,
  isOpaque: Boolean = false,
  background: Color? = null,
  private val isFullScreen: () -> Boolean,
) : JPanel(HorizontalLayout(layoutGap)) {
  private val flavor: MainToolbarFlavor
  private val widthCalculationListeners = mutableSetOf<ToolbarWidthCalculationListener>()
  private val cachedWidths by lazy { ConcurrentHashMap<String, Int>() }

  init {
    this.background = background
    this.isOpaque = isOpaque
    flavor = if (isMenuButtonInToolbar(UISettings.shadowInstance)) {
      MenuButtonInToolbarMainToolbarFlavor(headerContent = this, coroutineScope = coroutineScope, frame = frame, toolbar = this)
    }
    else {
      DefaultMainToolbarFlavor
    }
    ClientProperty.put(this, IdeBackgroundUtil.NO_BACKGROUND, true)
    showingScope("Main toolbar update") {
      ApplicationManager.getApplication().messageBus.connect(this).subscribe(LafManagerListener.TOPIC, LafManagerListener {
        updateToolbarActions()
      })
    }
    (layout as HorizontalLayout).apply {
      preferredSizeFunction = { component ->
        when (component) {
          is ActionToolbar -> {
            val mainToolbarWidth = this@MainToolbar.width
            val availableSize = Dimension(mainToolbarWidth - 4 * JBUI.scale(layoutGap), this@MainToolbar.height)
            val sizeMap = CompressingLayoutStrategy.distributeSize(availableSize, components.filterIsInstance<ActionToolbar>())
            val dimension = sizeMap.getValue(component)

            if (widthCalculationListeners.isNotEmpty()) {
              val componentText = (component as ActionToolbar).actionGroup.templatePresentation.text
              val cachedWidth = cachedWidths[componentText]
              if (dimension.width != cachedWidth || cachedWidths[MAIN_TOOLBAR_ID] != mainToolbarWidth) {
                notifyToolbarWidthCalculation(ToolbarWidthCalculationEvent(this@MainToolbar))
                cachedWidths.put(componentText, dimension.width)
                cachedWidths.put(MAIN_TOOLBAR_ID, mainToolbarWidth)
              }
            }
            dimension
          }
          else -> {
            component.preferredSize
          }
        }
      }
    }
    repaintWhenProjectGradientOffsetChanged(this)
  }

  fun calculatePreferredWidth(): Int {
    return components.filterIsInstance<ActionToolbar>().sumOf { it.component.preferredSize.width} + 4 * JBUI.scale(layoutGap)
  }

  @Internal
  fun addToolbarListeners(listener: ActionToolbarListener, disposable: Disposable) {
    components.filterIsInstance<ActionToolbar>().forEach { it.addListener(listener, disposable) }
  }

  private fun updateToolbarActions() {
    for (component in components) {
      if (component is ActionToolbarImpl) {
        component.updateActionsAsync()
      }
    }
  }

  override fun getComponentGraphics(g: Graphics): Graphics = super.getComponentGraphics(IdeBackgroundUtil.getOriginalGraphics(g))

  suspend fun init(customTitleBar: WindowDecorations.CustomTitleBar? = null) {
    val schema = CustomActionsSchema.getInstanceAsync()
    val actionGroups = computeMainActionGroups(schema)
    val customizationGroup = schema.getCorrectedActionAsync(MAIN_TOOLBAR_ID)
    val customizationGroupPopupHandler = customizationGroup?.let {
      CustomizationUtil.createToolbarCustomizationHandler(it, MAIN_TOOLBAR_ID, this, ActionPlaces.MAIN_TOOLBAR)
    }

    val widgets = withContext(Dispatchers.UiWithModelAccess) {
      removeAll()

      flavor.addWidget()

      val widgets = actionGroups.map { (actionGroup, position) ->
        createActionBar(group = actionGroup, customizationGroup = customizationGroup) to position
      }
      for ((widget, position) in widgets) {
        addWidget(widget = widget.component, parent = this@MainToolbar, position = position)
      }

      customizationGroupPopupHandler?.let { installClickListener(popupHandler = it, customTitleBar = customTitleBar) }
      widgets
    }

    for (widget in widgets) {
      // separate EDT action - avoid long-running update
      withContext(Dispatchers.UiWithModelAccess) {
        widget.first.updateActions()
      }
    }

    migratePreviousCustomizations(schema)
    migrateVcsActions(schema)
  }

  private fun migrateVcsActions(schema: CustomActionsSchema) {
    if (AppMode.isRemoteDevHost()) return
    val allActions = schema.getActions().toMutableList()
    val actionsToRemove = setOf("main.toolbar.git.update", "main.toolbar.git.push")
    val wereRemoved = allActions.removeIf { it.groupPath.contains("Main Toolbar") && it.component in actionsToRemove }
    if (wereRemoved) {
      schema.setActions(allActions)
      schemaChanged()
    }
  }

  /*
   * this is temporary solutions for migration customizations from 2023.1 version
   * todo please remove it when users are not migrate any more from 2023.1
   */
  private fun migratePreviousCustomizations(schema: CustomActionsSchema) {
    val mainToolbarName = schema.getDisplayName(MAIN_TOOLBAR_ID) ?: return
    val mainToolbarPath = listOf("root", mainToolbarName)
    if (!schema.getChildActions(mainToolbarPath).isEmpty()) {
      return
    }

    val backup = CustomActionsSchema(null)
    backup.copyFrom(schema)

    var tmpSchema = migrateToolbar(currentSchema = schema,
                                   newSchema = null,
                                   fromPath = listOf("root", "Main Toolbar Left"),
                                   toPath = mainToolbarPath + "Left")
    tmpSchema = migrateToolbar(currentSchema = schema,
                               newSchema = tmpSchema,
                               fromPath = listOf("root", "Main Toolbar Center"),
                               toPath = mainToolbarPath + "Center")
    tmpSchema = migrateToolbar(currentSchema = schema,
                               newSchema = tmpSchema,
                               fromPath = listOf("root", "Main Toolbar Right"),
                               toPath = mainToolbarPath + "Right")

    if (tmpSchema != null) {
      schema.copyFrom(tmpSchema)
      schemaChanged()
    }
  }

  private fun migrateToolbar(currentSchema: CustomActionsSchema,
                             newSchema: CustomActionsSchema?,
                             fromPath: List<String>,
                             toPath: List<String>): CustomActionsSchema? {
    val childActions = currentSchema.getChildActions(groupPath = fromPath)
    if (childActions.isEmpty()) {
      return newSchema
    }

    var copied = newSchema
    if (copied == null) {
      copied = CustomActionsSchema(null)
      copied.copyFrom(currentSchema)
    }
    doMigrateToolbar(schema = copied, childActions = childActions, toPath = toPath, fromPath = fromPath)
    return copied
  }

  private fun doMigrateToolbar(schema: CustomActionsSchema,
                               childActions: List<ActionUrl>,
                               toPath: List<String>,
                               fromPath: List<String>) {
    val newUrls = childActions.map { ActionUrl(ArrayList(toPath), it.component, it.actionType, it.absolutePosition) }
    val actions = schema.getActions().toMutableList()
    actions.addAll(newUrls)
    actions.removeIf { fromPath == it.groupPath }
    schema.setActions(actions)
  }

  override fun paintComponent(g: Graphics?) {
    super.paintComponent(g)
    if (!CustomWindowHeaderUtil.isToolbarInHeader(UISettings.getInstance(), isFullScreen())) {
      ProjectWindowCustomizerService.getInstance().paint(frame, this, g as Graphics2D)
    }
  }

  private fun installClickListener(popupHandler: PopupHandler, customTitleBar: WindowDecorations.CustomTitleBar?) {
    if (hideNativeLinuxTitle(UISettings.shadowInstance) && UISettings.shadowInstance.mainMenuDisplayMode != MainMenuDisplayMode.SEPARATE_TOOLBAR) {
      WindowMoveListener(this).apply {
        setLeftMouseButtonOnly(true)
        installTo(this@MainToolbar)
      }
    }

    if (customTitleBar == null) {
      addMouseListener(popupHandler)
      return
    }

    val listener = object : HeaderClickTransparentListener(customTitleBar) {
      private fun handlePopup(e: MouseEvent) {
        if (e.isPopupTrigger) {
          popupHandler.invokePopup(e.component, e.x, e.y)
          e.consume()
        }
        else {
          hit()
        }
      }

      override fun mouseClicked(e: MouseEvent) = handlePopup(e)
      override fun mousePressed(e: MouseEvent) = handlePopup(e)
      override fun mouseReleased(e: MouseEvent) = handlePopup(e)
    }
    addMouseListener(listener)
    addMouseMotionListener(listener)
  }

  internal fun addWidthCalculationListener(listener: ToolbarWidthCalculationListener) {
    widthCalculationListeners.add(listener)
  }

  internal fun removeWidthCalculationListener(listener: ToolbarWidthCalculationListener) {
    widthCalculationListeners.remove(listener)
  }

  private fun notifyToolbarWidthCalculation(event: ToolbarWidthCalculationEvent) {
    widthCalculationListeners.forEach { it.onToolbarCompressed(event) }
  }

  override fun removeNotify() {
    super.removeNotify()
    if (ScreenUtil.isStandardAddRemoveNotify(this)) {
      coroutineScope.cancel()
    }
  }

  override fun getAccessibleContext(): AccessibleContext {
    if (accessibleContext == null) {
      accessibleContext = AccessibleMainToolbar()
    }
    accessibleContext.accessibleName = if (ExperimentalUI.isNewUI() && UISettings.getInstance().mainMenuDisplayMode == MainMenuDisplayMode.SEPARATE_TOOLBAR) {
      UIBundle.message("main.toolbar.accessible.group.name")
    }
    else {
      ""
    }
    return accessibleContext
  }

  @Suppress("RedundantInnerClassModifier")
  private inner class AccessibleMainToolbar : AccessibleJPanel() {
    override fun getAccessibleRole(): AccessibleRole = AccessibilityUtils.GROUPED_ELEMENTS
  }
}

private fun createActionBar(group: ActionGroup, customizationGroup: ActionGroup?): MyActionToolbarImpl {
  val toolbar = MyActionToolbarImpl(group = group, customizationGroup = customizationGroup)
  toolbar.setActionButtonBorder(JBUI.Borders.empty(mainToolbarButtonInsets()))
  toolbar.setCustomButtonLook(HeaderToolbarButtonLook())

  toolbar.setMinimumButtonSize { ActionToolbar.experimentalToolbarMinimumButtonSize() }
  toolbar.targetComponent = null
  toolbar.layoutStrategy = ToolbarLayoutStrategy.COMPRESSING_STRATEGY
  val component = toolbar.component
  component.border = JBUI.Borders.empty()
  component.isOpaque = false
  return toolbar
}

/**
 * Method is added for Demo-action only
 * Do not use it in your code
 */
@Internal
fun createDemoToolbar(group: ActionGroup): MyActionToolbarImpl = createActionBar(group, null)

private fun addWidget(widget: JComponent, parent: JComponent, position: HorizontalLayout.Group) {
  parent.add(widget, position)
  if (widget is Disposable) {
    logger<MainToolbar>().error("Do not implement Disposable: ${widget.javaClass.name}")
  }
}

@Internal
class MyActionToolbarImpl(group: ActionGroup, customizationGroup: ActionGroup?)
  : ActionToolbarImpl(ActionPlaces.MAIN_TOOLBAR, group, true, false, false) {
  private val iconUpdater = HeaderIconUpdater()

  init {
    updateFont()
    ClientProperty.put(this, IdeBackgroundUtil.NO_BACKGROUND, true)
    installPopupHandler(true, customizationGroup, MAIN_TOOLBAR_ID)
  }

  override fun createPresentationFactory(): PresentationFactory {
    return object : ActionToolbarPresentationFactory(this) {
      override fun postProcessPresentation(action: AnAction, presentation: Presentation) {
        super.postProcessPresentation(action, presentation)

        presentation.icon = presentation.icon?.let { iconUpdater.updateIcon(it) }
        presentation.selectedIcon = presentation.selectedIcon?.let { iconUpdater.updateIcon(it) }
        presentation.hoveredIcon = presentation.hoveredIcon?.let { iconUpdater.updateIcon(it) }
        presentation.disabledIcon = presentation.disabledIcon?.let { iconUpdater.updateIcon(it) }
        presentation.getClientProperty(ActionUtil.SECONDARY_ICON)?.let {
          presentation.putClientProperty(ActionUtil.SECONDARY_ICON, iconUpdater.updateIcon(it))
        }
      }
    }
  }

  fun updateActions() {
    updateActionsWithoutLoadingIcon(includeInvisible = false)
  }

  override fun getChildPreferredSize(index: Int): Dimension {
    val pref = super.getChildPreferredSize(index)

    val cmp = getComponent(index)
    val max = cmp.getMaximumSize()
    return Dimension(min(pref.width, max.width), min(pref.height, max.height))
  }


  override fun createCustomComponent(action: CustomComponentAction, presentation: Presentation): JComponent {
    val component = super.createCustomComponent(action, presentation)

    if (component.foreground != null) {
      @Suppress("UnregisteredNamedColor")
      component.foreground = JBColor.namedColor("MainToolbar.foreground", component.foreground)
    }
    (component as? ActionButton)?.setMinimumButtonSize(ActionToolbar.experimentalToolbarMinimumButtonSize())
    if (component is ToolbarComboButton) {
      component.preferredHeightSupplier = { ActionToolbar.experimentalToolbarMinimumButtonSize().height }
      val insets = mainToolbarButtonInsets().apply {
        left = 0
        right = 0
      }
      component.border = JBUI.Borders.empty(insets)
    }

    if (action is ComboBoxAction) {
      findComboButton(component)?.apply {
        margin = JBInsets.emptyInsets()
        setUI(MainToolbarComboBoxButtonUI())
        addPropertyChangeListener("UI") { event ->
          if (event.newValue !is MainToolbarComboBoxButtonUI) {
            setUI(MainToolbarComboBoxButtonUI())
          }
        }
      }
    }
    return component
  }

  override fun getSeparatorColor(): Color {
    return JBColor.namedColor("MainToolbar.separatorColor", super.getSeparatorColor())
  }

  private fun findComboButton(c: Container): ComboBoxButton? {
    if (c is ComboBoxButton) {
      return c
    }

    for (child in c.components) {
      if (child is ComboBoxButton) {
        return child
      }
      val childCombo = (child as? Container)?.let { findComboButton(it) }
      if (childCombo != null) {
        return childCombo
      }
    }
    return null
  }

  override fun updateUI() {
    @Suppress("UNNECESSARY_SAFE_CALL") // you know, the old "this thing is called from a superclass constructor" pitfall
    iconUpdater?.clearCache()
    super.updateUI()
    updateFont()
  }

  override fun addImpl(comp: Component, constraints: Any?, index: Int) {
    super.addImpl(comp, constraints, index)
    comp.font = font
    if (comp is JComponent) {
      ClientProperty.put(comp, IdeBackgroundUtil.NO_BACKGROUND, true)
    }
    if (comp is ActionToolbarImpl) {
      comp.layoutStrategy = ToolbarLayoutStrategy.COMPRESSING_STRATEGY
    }
  }

  private fun updateFont() {
    font = JBUI.CurrentTheme.Toolbar.experimentalToolbarFont()
    for (component in components) {
      component.font = font
    }
  }
}

internal suspend fun computeMainActionGroups(): List<Pair<ActionGroup, HorizontalLayout.Group>> {
  return span("toolbar action groups computing") {
    computeMainActionGroups(CustomActionsSchema.getInstanceAsync())
  }
}

private suspend fun computeMainActionGroups(customActionSchema: CustomActionsSchema): List<Pair<ActionGroup, HorizontalLayout.Group>> {
  val result = ArrayList<Pair<ActionGroup, HorizontalLayout.Group>>(3)
  for (info in getMainToolbarGroups()) {
    customActionSchema.getCorrectedActionAsync(info.id, info.name)?.let {
      result.add(it to info.align)
    }
  }
  return result
}

internal fun blockingComputeMainActionGroups(): List<Pair<ActionGroup, HorizontalLayout.Group>> {
  return blockingComputeMainActionGroups(CustomActionsSchema.getInstance())
}

internal fun blockingComputeMainActionGroups(customActionSchema: CustomActionsSchema): List<Pair<ActionGroup, HorizontalLayout.Group>> {
  return getMainToolbarGroups()
    .mapNotNull { info ->
      customActionSchema.getCorrectedAction(info.id, info.name)?.let {
        it to info.align
      }
    }
    .toList()
}

private fun getMainToolbarGroups(): Sequence<GroupInfo> {
  return sequenceOf(
    GroupInfo("MainToolbarLeft", ActionsTreeUtil.getMainToolbarLeft(), HorizontalLayout.Group.LEFT),
    GroupInfo("MainToolbarCenter", ActionsTreeUtil.getMainToolbarCenter(), HorizontalLayout.Group.CENTER),
    GroupInfo("MainToolbarRight", ActionsTreeUtil.getMainToolbarRight(), HorizontalLayout.Group.RIGHT)
  )
}

internal fun isDarkHeader(): Boolean = ColorUtil.isDark(JBColor.namedColor("MainToolbar.background"))

private fun adjustIconForHeader(icon: Icon): Icon = IconLoader.getDarkIcon(icon = icon, dark = isDarkHeader())

private class HeaderIconUpdater {
  val iconCache = WeakHashMap<Icon, WeakReference<Icon>>()
  val alreadyUpdated = ContainerUtil.createWeakSet<Icon>()

  fun updateIcon(sourceIcon: Icon): Icon {
    if (sourceIcon in alreadyUpdated) return sourceIcon

    val cached = iconCache[sourceIcon]?.get()
    if (cached != null) return cached

    val replaceIcon = adjustIconForHeader(sourceIcon)
    iconCache[sourceIcon] = WeakReference(replaceIcon)
    alreadyUpdated.add(replaceIcon)
    return replaceIcon
  }

  fun clearCache() {
    iconCache.clear()
    alreadyUpdated.clear()
  }
}

private data class GroupInfo(@JvmField val id: String, @JvmField val name: String, @JvmField val align: HorizontalLayout.Group)

@Internal
@Suppress("HardCodedStringLiteral", "ActionPresentationInstantiatedInCtor")
class RemoveMainToolbarActionsAction private constructor() : DumbAwareAction("Remove Actions From Main Toolbar") {
  override fun actionPerformed(e: AnActionEvent) {
    val schema = CustomActionsSchema.getInstance()
    val groups = blockingComputeMainActionGroups(schema)

    val mainToolbarName = schema.getDisplayName(MAIN_TOOLBAR_ID)!!
    val mainToolbarPath = listOf("root", mainToolbarName)

    for (group in groups) {
      val actionsToRemove = group.first.getChildren(null)
      val fromPath = ArrayList(mainToolbarPath + group.first.templatePresentation.text)
      for (action in actionsToRemove) {
        val actionId = ActionManager.getInstance().getId(action)
        schema.addAction(ActionUrl(fromPath, actionId, ActionUrl.DELETED, 0))
      }
    }

    schemaChanged()
  }

  override fun getActionUpdateThread(): ActionUpdateThread {
    return ActionUpdateThread.EDT
  }
}

private fun schemaChanged() {
  val customActionsSchema = CustomActionsSchema.getInstance()
  customActionsSchema.initActionIcons()
  customActionsSchema.setCustomizationSchemaForCurrentProjects()
  if (SystemInfoRt.isMac) {
    TouchbarSupport.reloadAllActions()
  }
  CustomActionsListener.fireSchemaChanged()
}

internal class MainToolbarActionGroupCustomization : ActionGroupCustomizationExtension {
  override fun getReadOnlyActionGroupIds(): Set<String> = setOf(MAIN_TOOLBAR_ID)
}
