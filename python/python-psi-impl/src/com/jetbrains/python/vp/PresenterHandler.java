// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.vp;

import org.jetbrains.annotations.NotNull;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

/**
 * Wrapper for presenter.
 * @author Ilya.Kazakevich
 * @param <C> presenter class
 */
class PresenterHandler<C> implements InvocationHandler {
  /**
   * Presenter, created by user with {@link Creator#createPresenter(Object)}
   */
  private C realPresenter;

  void setRealPresenter(@NotNull C realPresenter) {
    this.realPresenter = realPresenter;
  }

  @Override
  public Object invoke(Object proxy, final Method method, final Object[] args) throws Throwable {
    /*
     * TODO: Implement async call.
     * The idea is void methods marked with @Async should be called in background thread.
     * That will allow presenter to be agnostic about EDT
     */
    return method.invoke(realPresenter, args);
  }
}
