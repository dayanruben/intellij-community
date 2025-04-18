// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package com.intellij.ide.fileTemplates.ui;

import com.intellij.ide.IdeBundle;
import com.intellij.ide.fileTemplates.FileTemplate;
import com.intellij.ide.fileTemplates.actions.AttributesDefaults;
import com.intellij.openapi.ui.DialogWrapperPeer;
import com.intellij.openapi.util.NlsSafe;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.TextRange;
import com.intellij.ui.ScreenUtil;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.util.ui.JBInsets;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;

/*
 * @author MYakovlev
 */
@ApiStatus.Internal
public final class CreateFromTemplatePanel {

  private JPanel myMainPanel;
  private JPanel myAttrPanel;
  private JTextField myFilenameField;
  private final String[] myUnsetAttributes;
  private final ArrayList<Pair<String, JTextField>> myAttributes = new ArrayList<>();

  private int myHorizontalMargin = -1;
  private int myVerticalMargin = -1;
  private final boolean myMustEnterName;
  private final AttributesDefaults myAttributesDefaults;

  public CreateFromTemplatePanel(final String[] unsetAttributes, final boolean mustEnterName,
                                 final @Nullable AttributesDefaults attributesDefaults){
    myMustEnterName = mustEnterName;
    myUnsetAttributes = unsetAttributes;
    myAttributesDefaults = attributesDefaults;
  }

  public boolean hasSomethingToAsk() {
    return myMustEnterName || myUnsetAttributes.length != 0;
  }

  public JComponent getComponent() {
    if (myMainPanel == null){
      myMainPanel = new JPanel(new GridBagLayout()){
        @Override
        public Dimension getPreferredSize(){
          return getMainPanelPreferredSize(super.getPreferredSize());
        }
      };
      myAttrPanel = new JPanel(new GridBagLayout());
      JPanel myScrollPanel = new JPanel(new GridBagLayout());
      updateShown();

      myScrollPanel.setBorder(null);
      int attrCount = myUnsetAttributes.length;
      if (myMustEnterName && !Arrays.asList(myUnsetAttributes).contains(FileTemplate.ATTRIBUTE_NAME)) {
        attrCount++;
      }
      Insets insets = (attrCount > 1) ? JBUI.insets(2) : JBInsets.emptyInsets();
      myScrollPanel.add(myAttrPanel,  new GridBagConstraints(0, 0, 1, 1, 1.0, 0.0, GridBagConstraints.CENTER, GridBagConstraints.HORIZONTAL, insets, 0, 0));
      if (attrCount > 1) {
        myScrollPanel.add(new JPanel(), new GridBagConstraints(0, 1, 1, 1, 0.0, 1.0, GridBagConstraints.CENTER, GridBagConstraints.BOTH, JBUI.insets(2), 0, 0));
        JScrollPane attrScroll = ScrollPaneFactory.createScrollPane(myScrollPanel, true);
        attrScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        attrScroll.setViewportBorder(null);
        myMainPanel.add(attrScroll, new GridBagConstraints(0, 0, 1, 1, 1.0, 1.0, GridBagConstraints.CENTER, GridBagConstraints.BOTH, JBUI.insets(2), 0, 0));
      }
      else {
        myMainPanel.add(myScrollPanel, new GridBagConstraints(0, 0, 1, 1, 1.0, 1.0, GridBagConstraints.CENTER, GridBagConstraints.BOTH,
                                                              JBInsets.emptyInsets(), 0, 0));
      }
    }
    return myMainPanel;
  }

  public void ensureFitToScreen(int horizontalMargin, int verticalMargin){
    myHorizontalMargin = horizontalMargin;
    myVerticalMargin = verticalMargin;
  }

  private Dimension getMainPanelPreferredSize(Dimension superPreferredSize){
    if((myHorizontalMargin > 0) && (myVerticalMargin > 0)){
      Dimension screenSize = ScreenUtil.getMainScreenBounds().getSize();
      Dimension preferredSize = superPreferredSize;
      Dimension maxSize = new Dimension(screenSize.width - myHorizontalMargin, screenSize.height - myVerticalMargin);
      int width = Math.min(preferredSize.width, maxSize.width);
      int height = Math.min(preferredSize.height, maxSize.height);
      if(height < preferredSize.height){
        width = Math.min(width + 50, maxSize.width); // to disable horizontal scroller
      }
      preferredSize = new Dimension(width, height);
      return preferredSize;
    }
    else{
      return superPreferredSize;
    }
  }

  private void updateShown() {
    final Insets insets = JBUI.insets(2, 4, 4, 2);
    if(myMustEnterName || Arrays.asList(myUnsetAttributes).contains(FileTemplate.ATTRIBUTE_NAME)){
      final JLabel filenameLabel = new JLabel(IdeBundle.message("label.file.name"));
      myAttrPanel.add(filenameLabel, new GridBagConstraints(0, 1, 1, 1, 0.0, 0.0, GridBagConstraints.WEST, GridBagConstraints.NONE, insets, 0, 0));
      myFilenameField = new JTextField(20);

      // if default settings specified
      if (myAttributesDefaults != null) {
        final String fileName = myAttributesDefaults.getDefaultFileName();
        // if default file name specified
        if (fileName != null) {
          // set predefined file name value
          myFilenameField.setText(fileName);
          final TextRange selectionRange;
          // select range from default attributes or select file name without extension
          if (myAttributesDefaults.getDefaultFileNameSelection() != null) {
            selectionRange = myAttributesDefaults.getDefaultFileNameSelection();
          } else {
            final int dot = fileName.indexOf('.');
            if (dot > 0) {
              selectionRange = new TextRange(0, dot);
            } else {
              selectionRange = null;
            }
          }
          // set selection in editor
          if (selectionRange != null) {
            setPredefinedSelectionFor(myFilenameField, selectionRange);
          }
        }
      }
      myAttrPanel.add(myFilenameField, new GridBagConstraints(1, 1, 1, 1, 1.0, 0.0, GridBagConstraints.WEST, GridBagConstraints.HORIZONTAL, insets, 0, 0));
    }

    Map<String, @Nls String> attributeVisibleNames = null;
    if (myAttributesDefaults != null) {
      attributeVisibleNames = myAttributesDefaults.getAttributeVisibleNames();
    }
    int lastRow = 2;
    for (@NlsSafe String attribute : myUnsetAttributes) {
      if (attribute.equals(FileTemplate.ATTRIBUTE_NAME)) { // already asked above
        continue;
      }
      String visibleName = attribute.replace('_', ' ');
      if (attributeVisibleNames != null && attributeVisibleNames.containsKey(attribute)) {
        visibleName = attributeVisibleNames.get(attribute);
      }
      final JLabel label = new JLabel(visibleName + ":");
      final JTextField field = new JTextField();
      field.setColumns(30);
      if (myAttributesDefaults != null) {
        final String defaultValue = myAttributesDefaults.getDefaultValueFor(attribute);
        final TextRange selectionRange = myAttributesDefaults.getRangeFor(attribute);
        if (defaultValue != null) {
          field.setText(defaultValue);
          // set default selection
          if (selectionRange != null) {
            setPredefinedSelectionFor(field, selectionRange);
          }
        }
      }
      myAttributes.add(Pair.create(attribute, field));
      myAttrPanel.add(label, new GridBagConstraints(0, lastRow, 1, 1, 0.0, 0.0, GridBagConstraints.WEST, GridBagConstraints.NONE,
                                                    insets, 0, 0));
      myAttrPanel.add(field, new GridBagConstraints(1, lastRow, 1, 1, 1.0, 0.0, GridBagConstraints.WEST,
                                                    GridBagConstraints.HORIZONTAL, insets, 0, 0));
      lastRow++;
    }

    myAttrPanel.repaint();
    myAttrPanel.revalidate();
    myMainPanel.revalidate();
  }

  public @Nullable String getFileName(){
    if (myFilenameField!=null) {
      String fileName = myFilenameField.getText();
      return fileName == null ? "" : fileName;
    } else {
      return null;
    }
  }

  public Properties getProperties(Properties predefinedProperties) {
    Properties result = (Properties) predefinedProperties.clone();
    for (Pair<String, JTextField> pair : myAttributes) {
      result.setProperty(pair.first, pair.second.getText());
    }
    String fileName = getFileName();
    if (fileName != null) {
      result.setProperty(FileTemplate.ATTRIBUTE_NAME, fileName);
    }
    return result;
  }

  private static void setPredefinedSelectionFor(final JTextField field, final TextRange selectionRange) {
    field.select(selectionRange.getStartOffset(), selectionRange.getEndOffset());
    field.putClientProperty(DialogWrapperPeer.HAVE_INITIAL_SELECTION, true);
  }
}

