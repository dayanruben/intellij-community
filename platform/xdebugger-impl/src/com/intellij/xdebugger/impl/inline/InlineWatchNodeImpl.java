// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.xdebugger.impl.inline;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.util.Disposer;
import com.intellij.util.ThreeState;
import com.intellij.util.ui.UIUtil;
import com.intellij.xdebugger.Obsolescent;
import com.intellij.xdebugger.XExpression;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator;
import com.intellij.xdebugger.frame.*;
import com.intellij.xdebugger.frame.presentation.XErrorValuePresentation;
import com.intellij.xdebugger.frame.presentation.XValuePresentation;
import com.intellij.xdebugger.impl.frame.XDebugSessionProxy;
import com.intellij.xdebugger.impl.frame.XDebugView;
import com.intellij.xdebugger.impl.ui.XDebuggerUIConstants;
import com.intellij.xdebugger.impl.ui.XValueTextProvider;
import com.intellij.xdebugger.impl.ui.tree.XDebuggerTree;
import com.intellij.xdebugger.impl.ui.tree.nodes.*;
import com.intellij.xdebugger.settings.XDebuggerSettingsManager;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

@ApiStatus.Internal
public class InlineWatchNodeImpl extends WatchNodeImpl implements InlineWatchNode {
  private final InlineWatch myWatch;
  private final List<Inlay<InlineDebugRenderer>> myInlays = new ArrayList<>();

  public InlineWatchNodeImpl(@NotNull XDebuggerTree tree,
                             @NotNull XDebuggerTreeNode parent,
                             @NotNull InlineWatch watch,
                             @Nullable XStackFrame stackFrame) {
    super(tree, parent, watch.getExpression(), new XInlineWatchValue(watch.getExpression(), tree, stackFrame, watch.getPosition()));
    myWatch = watch;
  }

  @Override
  public @NotNull XValue getValueContainer() {
    return myValueContainer;
  }

  @Override
  public @NotNull XSourcePosition getPosition() {
    return myWatch.getPosition();
  }

  @Override
  public @NotNull InlineWatch getWatch() {
    return myWatch;
  }

  void inlayCreated(Inlay<InlineDebugRenderer> inlay) {
    myInlays.add(inlay);
  }

  public void nodeRemoved() {
    UIUtil.invokeLaterIfNeeded(() -> {
      myInlays.forEach(Disposer::dispose);
      myInlays.clear();
    });
  }

  @Override
  public @Nullable XDebuggerTreeNodeHyperlink getLink() {
    return new XDebuggerTreeNodeHyperlink(" " + myWatch.getPosition().getFile().getName() + ":" + (myWatch.getPosition().getLine() + 1)) {
      @Override
      public boolean alwaysOnScreen() {
        return true;
      }

      @Override
      public void onClick(MouseEvent event) {
        myWatch.getPosition().createNavigatable(myTree.getProject()).navigate(true);
        event.consume();
      }
    };
  }

  @Override
  public @NotNull XEvaluationOrigin getEvaluationOrigin() {
    return XEvaluationOrigin.INLINE_WATCH;
  }

  @Override
  protected boolean shouldUpdateInlineDebuggerData() {
    return XDebuggerSettingsManager.getInstance().getDataViewSettings().isShowValuesInline();
  }

  private static class XInlineWatchValue extends XNamedValue implements XValueTextProvider {
    private final XExpression myExpression;
    private final XDebuggerTree myTree;
    private final XStackFrame myStackFrame;
    private final XSourcePosition myPosition;
    private volatile XValue myValue;

    XInlineWatchValue(XExpression expression, XDebuggerTree tree, XStackFrame frame, XSourcePosition position) {
      super(expression.getExpression());
      myExpression = expression;
      myTree = tree;
      myStackFrame = frame;
      myPosition = position;
    }

    @Override
    public void computeChildren(@NotNull XCompositeNode node) {
      if (myValue != null) {
        myValue.computeChildren(node);
      }
    }

    @Override
    public void computePresentation(@NotNull XValueNode node, @NotNull XValuePlace place) {
      if (myStackFrame != null) {
        if (sessionIsInOtherFileThanNode()) {
          node.setPresentation(AllIcons.Debugger.Db_watch, EMPTY_PRESENTATION, false);
          return;
        }

        XDebuggerEvaluator evaluator = myStackFrame.getEvaluator();
        if (evaluator != null) {
          evaluator.evaluate(myExpression, new MyEvaluationCallback(node, place), myStackFrame.getSourcePosition());
          return;
        }
      }

      node.setPresentation(AllIcons.Debugger.Db_watch, EMPTY_PRESENTATION, false);
    }

    private boolean sessionIsInOtherFileThanNode() {
      XDebugSessionProxy session = XDebugView.getSessionProxy(myTree);
      if (session != null) {
        XSourcePosition sessionCurrentPosition = session.getCurrentPosition();
        if (sessionCurrentPosition != null && !sessionCurrentPosition.getFile().equals(myPosition.getFile())) {
          return true;
        }
      }
      return false;
    }

    @Override
    public @Nullable String getValueText() {
      if (shouldShowTextValue()) {
        return ((XValueTextProvider)myValue).getValueText();
      }
      return null;
    }

    @Override
    public boolean shouldShowTextValue() {
      return myValue != null &&
             myValue instanceof XValueTextProvider &&
             ((XValueTextProvider)myValue).shouldShowTextValue();
    }

    private class MyEvaluationCallback extends XEvaluationCallbackBase implements XEvaluationCallbackWithOrigin, Obsolescent {
      private final @NotNull XValueNode myNode;
      private final @NotNull XValuePlace myPlace;

      MyEvaluationCallback(@NotNull XValueNode node, @NotNull XValuePlace place) {
        myNode = node;
        myPlace = place;
      }

      @Override
      public boolean isObsolete() {
        return myNode.isObsolete();
      }

      @Override
      public void evaluated(@NotNull XValue result) {
        myValue = result;
        result.computePresentation(myNode, myPlace);
      }

      @Override
      public void errorOccurred(@NotNull String errorMessage) {
        myNode.setPresentation(XDebuggerUIConstants.ERROR_MESSAGE_ICON, new XErrorValuePresentation(errorMessage), false);
      }

      @Override
      public XEvaluationOrigin getOrigin() {
        if (myNode instanceof WatchNodeImpl watchNode) {
          return watchNode.getEvaluationOrigin();
        }
        return XEvaluationOrigin.UNSPECIFIED_WATCH;
      }
    }

    private static final XValuePresentation EMPTY_PRESENTATION = new XValuePresentation() {
      @Override
      public @NotNull String getSeparator() {
        return "";
      }

      @Override
      public void renderValue(@NotNull XValueTextRenderer renderer) {
      }
    };

    @Override
    public void computeSourcePosition(@NotNull XNavigatable navigatable) {
      navigatable.setSourcePosition(myPosition);
    }

    @Override
    public @NotNull ThreeState computeInlineDebuggerData(@NotNull XInlineDebuggerDataCallback callback) {
      callback.computed(myPosition);
      return ThreeState.YES;
    }
  }
}