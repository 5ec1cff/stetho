/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.stetho.inspector.network;

import android.content.Context;

import com.facebook.stetho.common.Util;
import com.facebook.stetho.inspector.helper.ChromePeerManager;
import com.facebook.stetho.inspector.helper.PeersRegisteredListener;

import javax.annotation.Nullable;

public class NetworkPeerManager extends ChromePeerManager {
  private static NetworkPeerManager sInstance;

  private final ResponseBodyFileManager mResponseBodyFileManager;
  private AsyncPrettyPrinterInitializer mPrettyPrinterInitializer;
  private AsyncPrettyPrinterRegistry mAsyncPrettyPrinterRegistry;

  @Nullable
  public static synchronized NetworkPeerManager getInstanceOrNull() {
    return sInstance;
  }

  public static synchronized NetworkPeerManager getOrCreateInstance(Context context) {
    if (sInstance == null) {
      sInstance = new NetworkPeerManager(
          new ResponseBodyFileManager(
              Util.getApplication(context)));
    }
    return sInstance;
  }

  public NetworkPeerManager(
      ResponseBodyFileManager responseBodyFileManager) {
    mResponseBodyFileManager = responseBodyFileManager;
    setListener(mTempFileCleanup);
  }

  public ResponseBodyFileManager getResponseBodyFileManager() {
    return mResponseBodyFileManager;
  }

  @Nullable
  public AsyncPrettyPrinterRegistry getAsyncPrettyPrinterRegistry() {
    return mAsyncPrettyPrinterRegistry;
  }

  public void setPrettyPrinterInitializer(AsyncPrettyPrinterInitializer initializer) {
    Util.throwIfNotNull(mPrettyPrinterInitializer);
    mPrettyPrinterInitializer = Util.throwIfNull(initializer);
  }

  private final PeersRegisteredListener mTempFileCleanup = new PeersRegisteredListener() {
    @Override
    protected void onFirstPeerRegistered() {
      AsyncPrettyPrinterExecutorHolder.ensureInitialized();
      if (mAsyncPrettyPrinterRegistry == null && mPrettyPrinterInitializer != null) {
        mAsyncPrettyPrinterRegistry = new AsyncPrettyPrinterRegistry();
        mPrettyPrinterInitializer.populatePrettyPrinters(mAsyncPrettyPrinterRegistry);
      }
      mResponseBodyFileManager.cleanupFiles();
    }

    @Override
    protected void onLastPeerUnregistered() {
      mResponseBodyFileManager.cleanupFiles();
      AsyncPrettyPrinterExecutorHolder.shutdown();
    }
  };
}
