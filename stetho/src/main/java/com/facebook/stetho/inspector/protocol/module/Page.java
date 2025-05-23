/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.stetho.inspector.protocol.module;

import android.content.Context;
import android.os.Process;

import androidx.annotation.Nullable;

import com.facebook.stetho.common.ProcessUtil;
import com.facebook.stetho.inspector.domstorage.SharedPreferencesHelper;
import com.facebook.stetho.inspector.jsonrpc.JsonRpcPeer;
import com.facebook.stetho.inspector.jsonrpc.JsonRpcResult;
import com.facebook.stetho.inspector.protocol.ChromeDevtoolsDomain;
import com.facebook.stetho.inspector.protocol.ChromeDevtoolsMethod;
import com.facebook.stetho.inspector.screencast.ScreenDispatcher;
import com.facebook.stetho.json.ObjectMapper;
import com.facebook.stetho.json.annotation.JsonProperty;
import com.facebook.stetho.json.annotation.JsonValue;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public class Page implements ChromeDevtoolsDomain {
  public static final String BANNER = // Note: not using Android resources so we can maintain .jar distribution for now.
  "_____/\\\\\\\\\\\\\\\\\\\\\\_______________________________________________/\\\\\\_______________________\n" +
  " ___/\\\\\\/////////\\\\\\____________________________________________\\/\\\\\\_______________________\n" +
  "  __\\//\\\\\\______\\///______/\\\\\\_________________________/\\\\\\______\\/\\\\\\_______________________\n" +
  "   ___\\////\\\\\\__________/\\\\\\\\\\\\\\\\\\\\\\_____/\\\\\\\\\\\\\\\\___/\\\\\\\\\\\\\\\\\\\\\\_\\/\\\\\\_____________/\\\\\\\\\\____\n" +
  "    ______\\////\\\\\\______\\////\\\\\\////____/\\\\\\/////\\\\\\_\\////\\\\\\////__\\/\\\\\\\\\\\\\\\\\\\\____/\\\\\\///\\\\\\__\n" +
  "     _________\\////\\\\\\______\\/\\\\\\_______/\\\\\\\\\\\\\\\\\\\\\\_____\\/\\\\\\______\\/\\\\\\/////\\\\\\__/\\\\\\__\\//\\\\\\_\n" +
  "      __/\\\\\\______\\//\\\\\\_____\\/\\\\\\_/\\\\__\\//\\\\///////______\\/\\\\\\_/\\\\__\\/\\\\\\___\\/\\\\\\_\\//\\\\\\__/\\\\\\__\n" +
  "       _\\///\\\\\\\\\\\\\\\\\\\\\\/______\\//\\\\\\\\\\____\\//\\\\\\\\\\\\\\\\\\\\____\\//\\\\\\\\\\___\\/\\\\\\___\\/\\\\\\__\\///\\\\\\\\\\/___\n" +
  "        ___\\///////////_________\\/////______\\//////////______\\/////____\\///____\\///_____\\/////_____\n" +
  "         Welcome to Stetho";


  private final Context mContext;
  private final String mMessage;
  private final ObjectMapper mObjectMapper = new ObjectMapper();
  @Nullable
  private ScreenDispatcher mScreencastDispatcher;

  public Page(Context context) {
    this(context, BANNER);
  }

  public Page(Context context, String message) {
    mContext = context;
    mMessage = message;
  }

  @ChromeDevtoolsMethod
  public void enable(JsonRpcPeer peer, JSONObject params) {
  }

  @ChromeDevtoolsMethod
  public void disable(JsonRpcPeer peer, JSONObject params) {
  }

  // Dog science...
  // @ChromeDevtoolsMethod
  public JsonRpcResult getResourceTree(JsonRpcPeer peer, JSONObject params) {
    // The DOMStorage module expects one key/value store per "security origin" which has a 1:1
    // relationship with resource tree frames.
    List<String> prefsTags = SharedPreferencesHelper.getSharedPreferenceTags(mContext);
    Iterator<String> prefsTagsIter = prefsTags.iterator();

    FrameResourceTree tree = createSimpleFrameResourceTree(
        "1",
        null /* parentId */,
        "Stetho",
        prefsTagsIter.hasNext() ? prefsTagsIter.next() : "");
    if (tree.childFrames == null) {
      tree.childFrames = new ArrayList<FrameResourceTree>();
    }

    int nextChildFrameId = 1;
    while (prefsTagsIter.hasNext()) {
      String frameId = "1." + (nextChildFrameId++);
      String prefsTag = prefsTagsIter.next();
      FrameResourceTree child = createSimpleFrameResourceTree(
          frameId,
          "1",
          "Child #" + frameId,
          prefsTag);
      tree.childFrames.add(child);
    }

    GetResourceTreeParams resultParams = new GetResourceTreeParams();
    resultParams.frameTree = tree;
    return resultParams;
  }

  private static FrameResourceTree createSimpleFrameResourceTree(
      String id,
      String parentId,
      String name,
      String securityOrigin) {
    Frame frame = new Frame();
    frame.id = id;
    frame.parentId = parentId;
    frame.loaderId = "1";
    frame.name = name;
    frame.url = "";
    frame.securityOrigin = securityOrigin;
    frame.mimeType = "text/plain";
    FrameResourceTree tree = new FrameResourceTree();
    tree.frame = frame;
    tree.resources = Collections.emptyList();
    tree.childFrames = null;
    return tree;
  }

  @ChromeDevtoolsMethod
  public JsonRpcResult canScreencast(JsonRpcPeer peer, JSONObject params) {
    return new SimpleBooleanResult(true);
  }

  @ChromeDevtoolsMethod
  public JsonRpcResult hasTouchInputs(JsonRpcPeer peer, JSONObject params) {
    return new SimpleBooleanResult(false);
  }

  @ChromeDevtoolsMethod
  public void setDeviceMetricsOverride(JsonRpcPeer peer, JSONObject params) {
  }

  @ChromeDevtoolsMethod
  public void clearDeviceOrientationOverride(JsonRpcPeer peer, JSONObject params) {
  }

  @ChromeDevtoolsMethod
  public void startScreencast(final JsonRpcPeer peer, JSONObject params) {
    final StartScreencastRequest request = mObjectMapper.convertValue(
            params, StartScreencastRequest.class);
    peer.getService(ScreenDispatcher.class).startScreencast(request);
  }

  @ChromeDevtoolsMethod
  public void stopScreencast(JsonRpcPeer peer, JSONObject params) {
    ScreenDispatcher p = peer.peekService(ScreenDispatcher.class);
    if (p != null) p.stopScreencast();
  }

  @ChromeDevtoolsMethod
  public void screencastFrameAck(JsonRpcPeer peer, JSONObject params) {
    // Nothing to do here, just need to make sure Chrome doesn't get an error that this method
    // isn't implemented
  }

  @ChromeDevtoolsMethod
  public void clearGeolocationOverride(JsonRpcPeer peer, JSONObject params) {
  }

  @ChromeDevtoolsMethod
  public void setTouchEmulationEnabled(JsonRpcPeer peer, JSONObject params) {
  }

  @ChromeDevtoolsMethod
  public void setEmulatedMedia(JsonRpcPeer peer, JSONObject params) {
  }

  @ChromeDevtoolsMethod
  public void setShowViewportSizeOnResize(JsonRpcPeer peer, JSONObject params) {
  }

  @ChromeDevtoolsMethod
  public JsonRpcResult getNavigationHistory(JsonRpcPeer peer, JSONObject params) {
    GetNavigationHistoryResponse response = new GetNavigationHistoryResponse();
    response.currentIndex = 0;
    NavigationEntry entry = new NavigationEntry();
    entry.id = 0;
    entry.title = "Stetho";
    entry.url = "stetho://" + Process.myPid() + "/" + ProcessUtil.getProcessName();
    entry.userTypedUrl = entry.url;
    entry.transitionType = "reload";
    response.entries = new ArrayList<>();
    response.entries.add(entry);
    return response;
  }

  private static class NavigationEntry {
    @JsonProperty
    public int id;
    @JsonProperty
    public String url;
    @JsonProperty
    public String userTypedUrl;
    @JsonProperty
    public String title;
    @JsonProperty
    public String transitionType;
  }

  private static class GetNavigationHistoryResponse implements JsonRpcResult {
    @JsonProperty
    public int currentIndex;

    @JsonProperty
    public List<NavigationEntry> entries;
  }

  private static class GetResourceTreeParams implements JsonRpcResult {
    @JsonProperty(required = true)
    public FrameResourceTree frameTree;
  }

  private static class FrameResourceTree {
    @JsonProperty(required = true)
    public Frame frame;

    @JsonProperty
    public List<FrameResourceTree> childFrames;

    @JsonProperty(required = true)
    public List<Resource> resources;
  }

  private static class Frame {
    @JsonProperty(required = true)
    public String id;

    @JsonProperty
    public String parentId;

    @JsonProperty(required = true)
    public String loaderId;

    @JsonProperty
    public String name;

    @JsonProperty(required = true)
    public String url;

    @JsonProperty(required = true)
    public String securityOrigin;

    @JsonProperty(required = true)
    public String mimeType;
  }

  private static class Resource {
    // Incomplete...
  }

  public enum ResourceType {
    DOCUMENT("Document"),
    STYLESHEET("Stylesheet"),
    IMAGE("Image"),
    FONT("Font"),
    SCRIPT("Script"),
    XHR("XHR"),
    WEBSOCKET("WebSocket"),
    OTHER("Other");

    private final String mProtocolValue;

    private ResourceType(String protocolValue) {
      mProtocolValue = protocolValue;
    }

    @JsonValue
    public String getProtocolValue() {
      return mProtocolValue;
    }
  }

  public static class ScreencastFrameEvent {
    @JsonProperty(required = true)
    public String data;

    @JsonProperty(required = true)
    public ScreencastFrameEventMetadata metadata;
  }

  public static class ScreencastFrameEventMetadata {
    @JsonProperty(required = true)
    public int pageScaleFactor;
    @JsonProperty(required = true)
    public int offsetTop;
    @JsonProperty(required = true)
    public int deviceWidth;
    @JsonProperty(required = true)
    public int deviceHeight;
    @JsonProperty(required = true)
    public int scrollOffsetX;
    @JsonProperty(required = true)
    public int scrollOffsetY;

    @JsonProperty
    public double timestamp;
  }

  public static class StartScreencastRequest {
    @JsonProperty
    public String format;
    @JsonProperty
    public int quality;
    @JsonProperty
    public int maxWidth;
    @JsonProperty
    public int maxHeight;
  }


}
