/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.stetho.inspector.protocol.module;

import android.annotation.SuppressLint;

import com.facebook.stetho.inspector.console.ConsolePeerManager;
import com.facebook.stetho.inspector.jsonrpc.JsonRpcPeer;
import com.facebook.stetho.inspector.protocol.ChromeDevtoolsDomain;
import com.facebook.stetho.inspector.protocol.ChromeDevtoolsMethod;
import com.facebook.stetho.json.annotation.JsonProperty;
import com.facebook.stetho.json.annotation.JsonValue;

import org.json.JSONObject;

import java.util.List;

public class Log implements ChromeDevtoolsDomain {
  public static final String CMD_LOG_ADDED = "Log.entryAdded";
  public Log() {
  }

  @ChromeDevtoolsMethod
  public void enable(JsonRpcPeer peer, JSONObject params) {
    ConsolePeerManager.getOrCreateInstance().addPeer(peer);
  }

  @ChromeDevtoolsMethod
  public void disable(JsonRpcPeer peer, JSONObject params) {
    ConsolePeerManager.getOrCreateInstance().removePeer(peer);
  }

  @SuppressLint({ "UsingDefaultJsonDeserializer", "EmptyJsonPropertyUse" })
  public static class MessageAddedRequest {
    @JsonProperty(required = true)
    public ConsoleMessage entry;
  }

  @SuppressLint({ "UsingDefaultJsonDeserializer", "EmptyJsonPropertyUse" })
  public static class ConsoleMessage {
    @JsonProperty(required = true)
    public MessageSource source;

    @JsonProperty(required = true)
    public MessageLevel level;

    @JsonProperty(required = true)
    public String text;

    @JsonProperty
    public Runtime.StackTrace stackTrace;

    @JsonProperty
    public List<Runtime.RemoteObject> args;
  }

  public enum MessageSource {
    XML("xml"),
    JAVASCRIPT("javascript"),
    NETWORK("network"),
    CONSOLE_API("console-api"),
    STORAGE("storage"),
    APPCACHE("appcache"),
    RENDERING("rendering"),
    CSS("css"),
    SECURITY("security"),
    OTHER("other");

    private final String mProtocolValue;

    private MessageSource(String protocolValue) {
      mProtocolValue = protocolValue;
    }

    @JsonValue
    public String getProtocolValue() {
      return mProtocolValue;
    }
  }

  public enum MessageLevel {
    INFO("info"),
    WARNING("warning"),
    ERROR("error"),
    VERBOSE("verbose");

    private final String mProtocolValue;

    private MessageLevel(String protocolValue) {
      mProtocolValue = protocolValue;
    }

    @JsonValue
    public String getProtocolValue() {
      return mProtocolValue;
    }
  }

  @SuppressLint({ "UsingDefaultJsonDeserializer", "EmptyJsonPropertyUse" })
  public static class CallFrame {
    @JsonProperty(required = true)
    public String functionName;

    @JsonProperty(required = true)
    public String url;

    @JsonProperty(required = true)
    public int lineNumber;

    @JsonProperty(required = true)
    public int columnNumber;

    public CallFrame() {
    }

    public CallFrame(String functionName, String url, int lineNumber, int columnNumber) {
      this.functionName = functionName;
      this.url = url;
      this.lineNumber = lineNumber;
      this.columnNumber = columnNumber;
    }
  }
}
