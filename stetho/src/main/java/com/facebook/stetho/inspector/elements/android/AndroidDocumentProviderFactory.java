/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.stetho.inspector.elements.android;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import com.facebook.stetho.Stetho;
import com.facebook.stetho.common.ThreadBound;
import com.facebook.stetho.common.UncheckedCallable;
import com.facebook.stetho.common.Util;
import com.facebook.stetho.common.android.HandlerUtil;
import com.facebook.stetho.inspector.elements.DescriptorProvider;
import com.facebook.stetho.inspector.elements.DocumentProvider;
import com.facebook.stetho.inspector.elements.DocumentProviderFactory;

import java.util.List;

public final class AndroidDocumentProviderFactory
    implements DocumentProviderFactory, ThreadBound {
  private final Application mApplication;
  private final List<DescriptorProvider> mDescriptorProviders;
  private final Handler mHandler;

  public AndroidDocumentProviderFactory(
      Application application,
      List<DescriptorProvider> descriptorProviders) {
    mApplication = Util.throwIfNull(application);
    mDescriptorProviders = Util.throwIfNull(descriptorProviders);
    mHandler = new Handler(Looper.getMainLooper());
  }

  @Override
  public DocumentProvider create() {
    return new AndroidDocumentProvider(mApplication, mDescriptorProviders, this);
  }

  // ThreadBound implementation
  @Override
  public boolean checkThreadAccess() {
    if (Stetho.isSuspend()) return true;
    return HandlerUtil.checkThreadAccess(mHandler);
  }

  @Override
  public void verifyThreadAccess() {
    if (Stetho.isSuspend()) return;
    HandlerUtil.verifyThreadAccess(mHandler);
  }

  @Override
  public <V> V postAndWait(UncheckedCallable<V> c) {
    if (Stetho.isSuspend()) return c.call();
    return HandlerUtil.postAndWait(mHandler, c);
  }

  @Override
  public void postAndWait(Runnable r) {
    if (Stetho.isSuspend()) r.run();
    else HandlerUtil.postAndWait(mHandler, r);
  }

  @Override
  public void postDelayed(Runnable r, long delayMillis) {
    if (!mHandler.postDelayed(r, delayMillis)) {
      throw new RuntimeException("Handler.postDelayed() returned false");
    }
  }

  @Override
  public void removeCallbacks(Runnable r) {
    mHandler.removeCallbacks(r);
  }
}
