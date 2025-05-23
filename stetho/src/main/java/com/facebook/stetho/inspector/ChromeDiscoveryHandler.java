/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.stetho.inspector;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Process;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Log;

import com.facebook.stetho.common.ProcessUtil;
import com.facebook.stetho.server.SocketLike;
import com.facebook.stetho.server.http.ExactPathMatcher;
import com.facebook.stetho.server.http.HandlerRegistry;
import com.facebook.stetho.server.http.HttpHandler;
import com.facebook.stetho.server.http.HttpStatus;
import com.facebook.stetho.server.http.LightHttpBody;
import com.facebook.stetho.server.http.LightHttpRequest;
import com.facebook.stetho.server.http.LightHttpResponse;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import javax.annotation.Nullable;

/**
 * Provides sufficient responses to convince Chrome's {@code chrome://inspect/devices} that we're
 * "one of them".  Note that we are being discovered automatically by the name of our socket
 * as defined in {@link LocalSocketHttpServer}.  After discovery, we're required to provide
 * some context on how exactly to display and inspect what we have.
 */
public class ChromeDiscoveryHandler implements HttpHandler {
  private static final String PAGE_ID = "1";

  // support new page list path
  // see https://source.chromium.org/chromium/chromium/src/+/edc0c1e2f214915bd612e0982fb3c72ab33729c4:chrome/browser/devtools/device/devtools_device_discovery.cc;dlc=134d0a1df343117c386f2d6699c159041689d3b4
  private static final String PATH_PAGE_LIST = "/json";
  private static final String PATH_PAGE_LIST_NEW = "/json/list";
  private static final String PATH_VERSION = "/json/version";
  private static final String PATH_ACTIVATE = "/json/activate/" + PAGE_ID;

  /**
   * Latest version of the WebKit Inspector UI that we've tested again (ideally).
   */
  private static final String WEBKIT_REV = "@040e18a49d4851edd8ac6643352a4045245b368f";
  private static final String WEBKIT_VERSION = "537.36 (" + WEBKIT_REV + ")";

  private static final String USER_AGENT = "Stetho";

  /**
   * Structured version of the WebKit Inspector protocol that we understand.
   */
  private static final String PROTOCOL_VERSION = "1.3";

  private final Context mContext;
  private final String mInspectorPath;

  @Nullable private LightHttpBody mVersionResponse;
  @Nullable private LightHttpBody mPageListResponse;

  public static String getWebViewRev() {
    try {
      String rev = SystemProperties.get("debug.stetho.webkit.rev");
      if (TextUtils.isEmpty(rev)) {
        return WEBKIT_REV;
      }
      return rev;
    } catch (Throwable t) {
      Log.e("Stetho", "failed to get property", t);
      return WEBKIT_REV;
    }
  }

  public ChromeDiscoveryHandler(Context context, String inspectorPath) {
    mContext = context;
    mInspectorPath = inspectorPath;
  }

  public void register(HandlerRegistry registry) {
    registry.register(new ExactPathMatcher(PATH_PAGE_LIST), this);
    registry.register(new ExactPathMatcher(PATH_PAGE_LIST_NEW), this);
    registry.register(new ExactPathMatcher(PATH_VERSION), this);
    registry.register(new ExactPathMatcher(PATH_ACTIVATE), this);
  }

  @Override
  public boolean handleRequest(SocketLike socket, LightHttpRequest request, LightHttpResponse response) {
    String path = request.uri.getPath();
    try {
      if (PATH_VERSION.equals(path)) {
        handleVersion(response);
      } else if (PATH_PAGE_LIST.equals(path) || PATH_PAGE_LIST_NEW.equals(path)) {
        handlePageList(response);
      } else if (PATH_ACTIVATE.equals(path)) {
        handleActivate(response);
      } else {
        response.code = HttpStatus.HTTP_NOT_IMPLEMENTED;
        response.reasonPhrase = "Not implemented";
        response.body = LightHttpBody.create("No support for " + path + "\n", "text/plain");
      }
    } catch (JSONException e) {
      response.code = HttpStatus.HTTP_INTERNAL_SERVER_ERROR;
      response.reasonPhrase = "Internal server error";
      response.body = LightHttpBody.create(e.toString() + "\n", "text/plain");
    }
    return true;
  }

  private void handleVersion(LightHttpResponse response)
      throws JSONException {
    if (mVersionResponse == null) {
      JSONObject reply = new JSONObject();
      reply.put("WebKit-Version", WEBKIT_VERSION);
      reply.put("User-Agent", USER_AGENT);
      reply.put("Protocol-Version", PROTOCOL_VERSION);
      reply.put("Browser", getAppLabelAndVersion());
      reply.put("Android-Package", mContext.getPackageName());
      mVersionResponse = LightHttpBody.create(reply.toString(), "application/json");
    }
    setSuccessfulResponse(response, mVersionResponse);
  }

  private void handlePageList(LightHttpResponse response)
      throws JSONException {
    if (mPageListResponse == null) {
      JSONArray reply = new JSONArray();
      JSONObject page = new JSONObject();
      page.put("type", "app");
      page.put("title", makeTitle());
      page.put("id", PAGE_ID);
      page.put("description", "");
      page.put("url", "stetho://" + Process.myPid() + "/" + ProcessUtil.getProcessName());

      page.put("webSocketDebuggerUrl", "ws://" + mInspectorPath);
      Uri chromeFrontendUrl = new Uri.Builder()
          .scheme("http")
          .authority("chrome-devtools-frontend.appspot.com")
          .appendEncodedPath("serve_rev")
          .appendEncodedPath(getWebViewRev())
          .appendEncodedPath("inspector.html")
          .appendQueryParameter("ws", mInspectorPath)
          .build();
      page.put("devtoolsFrontendUrl", chromeFrontendUrl.toString());

      reply.put(page);
      mPageListResponse = LightHttpBody.create(reply.toString(), "application/json");
    }
    setSuccessfulResponse(response, mPageListResponse);
  }

  private String makeTitle() {
    StringBuilder b = new StringBuilder();
    b.append(getAppLabel());

    b.append(" (powered by Stetho)");

    String processName = ProcessUtil.getProcessName();
    int colonIndex = processName.indexOf(':');
    if (colonIndex >= 0) {
      String nonDefaultProcessName = processName.substring(colonIndex);
      b.append(nonDefaultProcessName);
    }

    return b.toString();
  }

  private void handleActivate(LightHttpResponse response) {
    // Arbitrary response seem acceptable :)
    setSuccessfulResponse(
        response,
        LightHttpBody.create("Target activation ignored\n", "text/plain"));
  }

  private static void setSuccessfulResponse(
      LightHttpResponse response,
      LightHttpBody body) {
    response.code = HttpStatus.HTTP_OK;
    response.reasonPhrase = "OK";
    response.body = body;
  }

  private String getAppLabelAndVersion() {
    StringBuilder b = new StringBuilder();
    PackageManager pm = mContext.getPackageManager();
    b.append(getAppLabel());
    b.append('/');
    try {
      PackageInfo info = pm.getPackageInfo(mContext.getPackageName(), 0 /* flags */);
      b.append(info.versionName);
    } catch (PackageManager.NameNotFoundException e) {
      throw new RuntimeException(e);
    }
    return b.toString();
  }

  private CharSequence getAppLabel() {
    PackageManager pm = mContext.getPackageManager();
    return pm.getApplicationLabel(mContext.getApplicationInfo());
  }
}
