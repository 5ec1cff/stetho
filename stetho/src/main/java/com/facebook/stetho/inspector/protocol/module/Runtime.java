/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.stetho.inspector.protocol.module;

import android.content.Context;
import android.os.Bundle;
import android.os.Process;

import com.facebook.stetho.common.LogRedirector;
import com.facebook.stetho.common.LogUtil;
import com.facebook.stetho.common.ProcessUtil;
import com.facebook.stetho.common.ReflectionUtil;
import com.facebook.stetho.inspector.console.IConsole;
import com.facebook.stetho.inspector.console.JsRuntimeException;
import com.facebook.stetho.inspector.console.RuntimeRepl;
import com.facebook.stetho.inspector.console.RuntimeRepl2;
import com.facebook.stetho.inspector.console.RuntimeReplFactory;
import com.facebook.stetho.inspector.helper.ObjectIdMapper;
import com.facebook.stetho.inspector.jsonrpc.DisconnectReceiver;
import com.facebook.stetho.inspector.jsonrpc.JsonRpcException;
import com.facebook.stetho.inspector.jsonrpc.JsonRpcPeer;
import com.facebook.stetho.inspector.jsonrpc.JsonRpcResult;
import com.facebook.stetho.inspector.jsonrpc.protocol.JsonRpcError;
import com.facebook.stetho.inspector.protocol.ChromeDevtoolsDomain;
import com.facebook.stetho.inspector.protocol.ChromeDevtoolsMethod;
import com.facebook.stetho.inspector.runtime.RhinoDetectingRuntimeReplFactory;
import com.facebook.stetho.inspector.screencast.InspectingObject;
import com.facebook.stetho.json.ObjectMapper;
import com.facebook.stetho.json.annotation.JsonProperty;
import com.facebook.stetho.json.annotation.JsonValue;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class Runtime implements ChromeDevtoolsDomain {
  private final ObjectMapper mObjectMapper = new ObjectMapper();

  private static final Map<JsonRpcPeer, Session> sSessions =
      Collections.synchronizedMap(new HashMap<>());

  private final RuntimeReplFactory mReplFactory;

  /**
   * @deprecated This was a transitionary API that was replaced by
   *     {@link com.facebook.stetho.Stetho.DefaultInspectorModulesBuilder#runtimeRepl}
   */
  public Runtime(Context context) {
    this(new RhinoDetectingRuntimeReplFactory(context));
  }

  public Runtime(RuntimeReplFactory replFactory) {
    mReplFactory = replFactory;
  }

  public static int mapObject(JsonRpcPeer peer, Object object) {
    return getSession(peer).getObjects().putObject(object);
  }

  @Nonnull
  private static synchronized Session getSession(final JsonRpcPeer peer) {
    Session session = sSessions.get(peer);
    if (session == null) {
      session = new Session(peer);
      sSessions.put(peer, session);
      peer.registerDisconnectReceiver(new DisconnectReceiver() {
        @Override
        public void onDisconnect() {
          sSessions.remove(peer);
        }
      });
    }
    return session;
  }

  /**
   * Removes objects from peer's session previously added by {@link #mapObject}
   */
  public static void releaseObject(JsonRpcPeer peer, Integer id) throws JSONException {
    getSession(peer).getObjects().removeObjectById(id);
  }

  @ChromeDevtoolsMethod
  public void releaseObject(JsonRpcPeer peer, JSONObject params) throws JSONException {
    String objectId = params.getString("objectId");
    getSession(peer).getObjects().removeObjectById(Integer.parseInt(objectId));
  }

  @ChromeDevtoolsMethod
  public void releaseObjectGroup(JsonRpcPeer peer, JSONObject params) {
    LogUtil.w("Ignoring request to releaseObjectGroup: " + params);
  }

  @ChromeDevtoolsMethod
  public JsonRpcResult callFunctionOn(JsonRpcPeer peer, JSONObject params)
      throws JsonRpcException {
    return getSession(peer).callFunctionOn(mReplFactory, params);
  }

  @ChromeDevtoolsMethod
  public JsonRpcResult evaluate(JsonRpcPeer peer, JSONObject params) {
    return getSession(peer).evaluate(mReplFactory, params, peer.getService(InspectingObject.class).getInspectedObject());
  }

  @ChromeDevtoolsMethod
  public JsonRpcResult getProperties(JsonRpcPeer peer, JSONObject params) throws JsonRpcException {
    return getSession(peer).getProperties(params);
  }

  @ChromeDevtoolsMethod
  public void enable(JsonRpcPeer peer, JSONObject params) {
    notifyExecutionContexts(peer);
    sendWelcomeMessage(peer);
  }

  private void sendWelcomeMessage(JsonRpcPeer peer) {
    Log.ConsoleMessage message = new Log.ConsoleMessage();
    message.source = Log.MessageSource.JAVASCRIPT;
    message.level = Log.MessageLevel.INFO;
    message.text = "Attached to " + ProcessUtil.getProcessName() + "\n";
    Log.MessageAddedRequest messageAddedRequest = new Log.MessageAddedRequest();
    messageAddedRequest.entry = message;
    peer.invokeMethod(Log.CMD_LOG_ADDED, messageAddedRequest, null /* callback */);
  }

  private void notifyExecutionContexts(JsonRpcPeer peer) {
    ExecutionContextDescription context = new ExecutionContextDescription();
    context.frameId = "1";
    context.id = 1;
    context.origin = "stetho://" + Process.myPid() + "/" + ProcessUtil.getProcessName();
    ExecutionContextCreatedParams params = new ExecutionContextCreatedParams();
    params.context = context;
    peer.invokeMethod("Runtime.executionContextCreated", params, null /* callback */);
  }

  private static class ExecutionContextCreatedParams {
    @JsonProperty(required = true)
    public ExecutionContextDescription context;
  }

  private static class ExecutionContextDescription {
    @JsonProperty(required = true)
    public String frameId;

    @JsonProperty(required = true)
    public int id;

    @JsonProperty
    public String origin;
  }

  private static boolean needToStringDescription(Object o) {
    return o instanceof Class || o instanceof Member;
  }

  private static String getPropertyClassName(Object o) {
    String name = o.getClass().getSimpleName();
    if (name == null || name.length() == 0) {
      // Looks better for anonymous classes.
      name = o.getClass().getName();
    }
    return name;
  }

  private static class ObjectProtoContainer {
    public final Object object;

    public ObjectProtoContainer(Object object) {
      this.object = object;
    }
  }

  /**
   * Object representing a session with a single client.
   *
   * <p>Clients inherently leak object references because they can expand any object in the UI
   * at any time.  Grouping references by client allows us to drop them when the client
   * disconnects.
   */
  private static class Session implements IConsole, DisconnectReceiver {
    private final JsonRpcPeer mPeer;
    private final ObjectIdMapper mObjects = new ObjectIdMapper();
    private final ObjectMapper mObjectMapper = new ObjectMapper();

    Session(JsonRpcPeer peer) {
      mPeer = peer;
      mPeer.registerDisconnectReceiver(this);
    }

    @Nullable
    private RuntimeRepl mRepl;

    public ObjectIdMapper getObjects() {
      return mObjects;
    }

    public Object getObjectOrThrow(String objectId) throws JsonRpcException {
      Object object = getObjects().getObjectForId(Integer.parseInt(objectId));
      if (object == null) {
        throw new JsonRpcException(new JsonRpcError(
            JsonRpcError.ErrorCode.INVALID_REQUEST,
            "No object found for " + objectId,
            null /* data */));
      }
      return object;
    }

    public RemoteObject objectForRemote(Object value) {
      if (mRepl != null && mRepl instanceof RuntimeRepl2) {
        return ((RuntimeRepl2) mRepl).objectForRemote(value, mObjects);
      }
      return Runtime.objectForRemote(value, mObjects);
    }

    @Override
    public void log(Log.MessageLevel logLevel, Log.MessageSource messageSource, String messageText) {
      LogRedirector.d("Runtime", messageText);

      Log.ConsoleMessage message = new Log.ConsoleMessage();
      message.source = messageSource;
      message.level = logLevel;
      message.text = messageText;
      Log.MessageAddedRequest messageAddedRequest = new Log.MessageAddedRequest();
      messageAddedRequest.entry = message;
      mPeer.invokeMethod(Log.CMD_LOG_ADDED, messageAddedRequest, null);
    }

    @Override
    public void callConsoleAPI(Runtime.ConsoleAPI api, Object... args) {
      LogRedirector.d("Runtime", Arrays.toString(args));

      ConsoleAPICalled message = new ConsoleAPICalled();
      message.type = api;
      message.args = new ArrayList<>();
      for (Object arg: args) {
        message.args.add(objectForRemote(arg));
      }
      mPeer.invokeMethod("Runtime.consoleAPICalled", message, null);
    }

    @Override
    public void onDisconnect() {
      if (mRepl != null && mRepl instanceof RuntimeRepl2) {
        ((RuntimeRepl2) mRepl).onFinalize();
      }
    }

    private static class SideEffectCheckException extends JsRuntimeException {
      private final Runtime.ExceptionDetails details;

      SideEffectCheckException() {
        details = new Runtime.ExceptionDetails();
        details.text = "Uncaught";
        Runtime.RemoteObject exceptionObject = new Runtime.RemoteObject();
        exceptionObject.type = Runtime.ObjectType.OBJECT;
        exceptionObject.subtype = Runtime.ObjectSubType.ERROR;
        exceptionObject.className = "Error";
        exceptionObject.description = "EvalError: Possible side-effect in debug-evaluate";
        details.exception = exceptionObject;
      }

      @Override
      public Runtime.ExceptionDetails getExceptionDetails() {
        return details;
      }
    }

    public EvaluateResponse evaluate(RuntimeReplFactory replFactory, JSONObject params, Object inspected) {
      EvaluateRequest request = mObjectMapper.convertValue(params, EvaluateRequest.class);

      try {
        RuntimeRepl repl = getRepl(replFactory);
        Object result;
        if (repl instanceof RuntimeRepl2) {
          RuntimeRepl2 repl2 = (RuntimeRepl2) repl;
          // if (request.throwOnSideEffect) // && "(async function(){ await 1; })()".equals(request.expression))
          //   throw new SideEffectCheckException();
          // else
          result = repl2.evaluateJs(request.expression, mObjects, inspected);
        } else {
          result = repl.evaluate(request.expression);
        }
        return buildNormalResponse(result);
      } catch (Throwable t) {
        return buildExceptionResponse(t);
      }
    }

    public CallFunctionOnResponse callFunctionOn(RuntimeReplFactory replFactory, JSONObject params) {
      CallFunctionOnRequest args = mObjectMapper.convertValue(params, CallFunctionOnRequest.class);

      try {
        RuntimeRepl repl = getRepl(replFactory);
        RemoteObject result;
        if (repl instanceof RuntimeRepl2) {
          result = ((RuntimeRepl2) repl).callFunctionOn(args.objectId, args.arguments, args.functionDeclaration, mObjects);
        } else {
          throw new UnsupportedOperationException("");
        }
        CallFunctionOnResponse response = new CallFunctionOnResponse();
        response.result = result;
        return response;
      } catch (Throwable t) {
        CallFunctionOnResponse response = new CallFunctionOnResponse();
        response.exceptionDetails = buildExceptionDetails(t);
        return response;
      }
    }

    @Nonnull
    private synchronized RuntimeRepl getRepl(RuntimeReplFactory replFactory) {
      if (mRepl == null) {
        mRepl = replFactory.newInstance();
        if (mRepl instanceof RuntimeRepl2) {
          ((RuntimeRepl2) mRepl).onAttachConsole(this);
        }
      }
      return mRepl;
    }

    private EvaluateResponse buildNormalResponse(Object retval) {
      EvaluateResponse response = new EvaluateResponse();
      response.wasThrown = false;
      if (retval instanceof RemoteObject)
        response.result = (RemoteObject) retval;
      else
        response.result = objectForRemote(retval);
      return response;
    }

    private EvaluateResponse buildExceptionResponse(Throwable t) {
      EvaluateResponse response = new EvaluateResponse();
      response.wasThrown = true;
      response.result = objectForRemote(t);
      response.exceptionDetails = buildExceptionDetails(t);
      return response;
    }

    private ExceptionDetails buildExceptionDetails(Throwable t) {
      ExceptionDetails ed;
      if (t instanceof JsRuntimeException) {
        ed = ((JsRuntimeException) t).getExceptionDetails();
      } else {
        ed = new ExceptionDetails();
        StackTrace stackTrace = new StackTrace();
        stackTrace.description = t.getMessage();
        stackTrace.callFrames = new ArrayList<>();
        stackTrace.fillJavaStack(t);
        ed.stackTrace = stackTrace;
        ed.text = "Java Exception " + t.getMessage();
      }
      return ed;
    }

    public GetPropertiesResponse getProperties(JSONObject params) throws JsonRpcException {
      GetPropertiesRequest request = mObjectMapper.convertValue(params, GetPropertiesRequest.class);

      Object object = getObjectOrThrow(request.objectId);

      if (request.ownProperties) {
        GetPropertiesResponse response = new GetPropertiesResponse();
        response.result = new ArrayList<>();
        PropertyDescriptor propertyDescriptor = new PropertyDescriptor();
        propertyDescriptor.name = "[[class]]";
        propertyDescriptor.isOwn = true;
        propertyDescriptor.value = objectForRemote(object.getClass().getName());
        response.result.add(propertyDescriptor);
        if (object instanceof List) {
          response.result.addAll(getPropertiesForIterable((List) object, /* enumerate */ true));
        } else if (object instanceof Set) {
          response.result.addAll(getPropertiesForIterable((Set) object, /* enumerate */ false));
        } else if (object instanceof Map) {
          response.result.addAll(getPropertiesForMap(object));
        } else if (object instanceof Bundle) {
          response.result.addAll(getPropertiesForBundle((Bundle) object));
        } else if (object instanceof Class<?>) {
          response.result.addAll(getPropertiesForClass((Class<?>) object));
        }
        return response;
      }

      if (object.getClass().isArray()) {
        object = arrayToList(object);
      }

      if (object instanceof ObjectProtoContainer) {
        return getPropertiesForProtoContainer((ObjectProtoContainer) object);
      }
      GetPropertiesResponse response = new GetPropertiesResponse();
      response.result = getPropertiesForObject(object);
      return response;
    }

    private List<?> arrayToList(Object object) {
      Class<?> type = object.getClass();
      if (!type.isArray()) {
        throw new IllegalArgumentException("Argument must be an array.  Was " + type);
      }
      Class<?> component = type.getComponentType();

      if (!component.isPrimitive()) {
        return Arrays.asList((Object[]) object);
      }

      // Loop manually for primitives.
      int length = Array.getLength(object);
      List<Object> ret = new ArrayList<>(length);
      for (int i = 0; i < length; i++) {
        ret.add(Array.get(object, i));
      }
      return ret;
    }

    // Normally JavaScript will return the full class hierarchy as a list.  That seems less
    // useful for Java since it's more natural (IMO) to see all available member variables in one
    // big list.
    private GetPropertiesResponse getPropertiesForProtoContainer(ObjectProtoContainer proto) {
      Object target = proto.object;
      RemoteObject protoRemote = new RemoteObject();
      protoRemote.type = ObjectType.OBJECT;
      protoRemote.subtype = ObjectSubType.NODE;
      protoRemote.className = target.getClass().getName();
      protoRemote.description = getPropertyClassName(target);
      protoRemote.objectId = String.valueOf(mObjects.putObject(target));
      PropertyDescriptor descriptor = new PropertyDescriptor();
      descriptor.name = "1";
      descriptor.value = protoRemote;
      GetPropertiesResponse response = new GetPropertiesResponse();
      response.result = new ArrayList<>(1);
      response.result.add(descriptor);
      return response;
    }

    private List<PropertyDescriptor> getPropertiesForIterable(Iterable<?> object, boolean enumerate) {
      List<PropertyDescriptor> properties = new ArrayList<>();

      int index = 0;
      for (Object value : object) {
        PropertyDescriptor property = new PropertyDescriptor();
        property.name = enumerate ? String.valueOf(index++) : null;
        property.value = objectForRemote(value);
        property.isOwn = true;
        properties.add(property);
      }

      return properties;
    }

    private List<PropertyDescriptor> getPropertiesForMap(Object object) {
      List<PropertyDescriptor> properties = new ArrayList<>();

      for (Map.Entry<?, ?> entry : ((Map<?, ?>) object).entrySet()) {
        PropertyDescriptor property = new PropertyDescriptor();
        property.name = "[map] " + entry.getKey();
        property.value = objectForRemote(entry.getValue());
        property.isOwn = true;
        properties.add(property);
      }

      return properties;
    }

    private List<PropertyDescriptor> getPropertiesForBundle(Bundle object) {
      List<PropertyDescriptor> properties = new ArrayList<>();

      for (String key : object.keySet()) {
        PropertyDescriptor property = new PropertyDescriptor();
        property.name = "[bundle] " + key;
        property.value = objectForRemote(object.get(key));
        property.isOwn = true;
        properties.add(property);
      }

      return properties;
    }
    private List<PropertyDescriptor> getPropertiesForClass(Class<?> clazz) {
      List<PropertyDescriptor> properties = new ArrayList<>();
      Field[] fields = ReflectionUtil.getDeclaredFields(clazz);
      for (Field field : fields) {
        if (!Modifier.isStatic(field.getModifiers())) {
          continue;
        }
        field.setAccessible(true);
        try {
          PropertyDescriptor property = new PropertyDescriptor();
          property.name = "[static]" + field.getName();
          if (field.getType() == null) {
            property.value = objectForRemote(Runtime.UNDEFINED);
          } else {
            Object fieldValue = field.get(null);
            property.value = objectForRemote(fieldValue);
          }
          property.isOwn = true;
          properties.add(property);
        } catch (IllegalAccessException e) {
          throw new RuntimeException(e);
        }
      }
      return properties;
    }

    private List<PropertyDescriptor> getPropertiesForObject(Object object) {
      List<PropertyDescriptor> properties = new ArrayList<>();
      int level = 0;
      for (
          Class<?> declaringClass = object.getClass();
          declaringClass != null;
          declaringClass = declaringClass.getSuperclass()
          ) {
        Field[] fields = ReflectionUtil.getDeclaredFields(declaringClass);
        String prefix = declaringClass == object.getClass()
            ? ""
            : declaringClass.getSimpleName() + ".";
        for (Field field : fields) {
          if (Modifier.isStatic(field.getModifiers())) {
            continue;
          }
          field.setAccessible(true);
          try {
            PropertyDescriptor property = new PropertyDescriptor();
            property.name = "[" + level + "]" + prefix + field.getName();
            if (field.getType() == null) {
              property.value = objectForRemote(Runtime.UNDEFINED);
            } else {
              Object fieldValue = field.get(object);
              property.value = objectForRemote(fieldValue);
            }
            properties.add(property);
          } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
          }
        }
        level++;
      }
      return properties;
    }
  }

  public static RemoteObject objectForRemote(Object value, ObjectIdMapper mapper) {
    RemoteObject result = new RemoteObject();
    if (value == null) {
      result.type = ObjectType.OBJECT;
      result.subtype = ObjectSubType.NULL;
      result.value = JSONObject.NULL;
    } else if (Runtime.UNDEFINED.equals(value)) {
      result.type = ObjectType.UNDEFINED;
    } else if (value instanceof Boolean) {
      result.type = ObjectType.BOOLEAN;
      result.value = value;
    } else if (value instanceof Long) {
      result.type = Runtime.ObjectType.BIGINT;
      result.description = result.unserializableValue = value.toString();
    } else if (value instanceof BigInteger) {
      result.type = ObjectType.BIGINT;
      result.description = result.unserializableValue = value + "n";
    } else if (value instanceof Number) {
      result.type = ObjectType.NUMBER;
      result.value = value;
    } else if (value instanceof Character) {
      // Unclear whether we should expose these as strings, numbers, or something else.
      result.type = ObjectType.NUMBER;
      result.value = Integer.valueOf(((Character)value).charValue());
    } else if (value instanceof String) {
      result.type = ObjectType.STRING;
      result.value = String.valueOf(value);
    } else {
      result.type = ObjectType.OBJECT;
      result.className = value.getClass().getName();
      result.objectId = String.valueOf(mapper.putObject(value));

      if (value.getClass().isArray()) {
        result.description = "array";
      } else if (value instanceof List) {
        result.description = "List";
      } else if (value instanceof Set) {
        result.description = "Set";
      } else if (value instanceof Map) {
        result.description = "Map";
      } else {
        if (needToStringDescription(value))
          result.description = value.toString();
        else result.description = getPropertyClassName(value);
      }

    }
    return result;
  }

  private static class CallFunctionOnRequest {
    @JsonProperty
    public String objectId;

    @JsonProperty
    public String functionDeclaration;

    @JsonProperty
    public List<CallArgument> arguments;

    @JsonProperty(required = false)
    public Boolean doNotPauseOnExceptionsAndMuteConsole;

    @JsonProperty(required = false)
    public Boolean returnByValue;

    @JsonProperty(required = false)
    public Boolean generatePreview;
  }

  private static class CallFunctionOnResponse implements JsonRpcResult {
    @JsonProperty
    public RemoteObject result;

    @JsonProperty(required = false)
    public ExceptionDetails exceptionDetails;
  }

  public static class CallArgument {
    public Object value;
    public String objectId;
    public String unserializableValue;
  }

  private static class GetPropertiesRequest implements JsonRpcResult {
    @JsonProperty(required = true)
    public boolean ownProperties;

    @JsonProperty(required = true)
    public String objectId;
  }

  private static class GetPropertiesResponse implements JsonRpcResult {
    @JsonProperty(required = true)
    public List<PropertyDescriptor> result;
  }

  private static class EvaluateRequest implements JsonRpcResult {
    @JsonProperty
    public String objectGroup;

    @JsonProperty(required = true)
    public String expression;

    @JsonProperty
    public boolean throwOnSideEffect;
  }

  private static class EvaluateResponse implements JsonRpcResult {
    @JsonProperty(required = true)
    public RemoteObject result;

    @JsonProperty(required = true)
    public boolean wasThrown;

    @JsonProperty
    public ExceptionDetails exceptionDetails;
  }

  public static class ExceptionDetails {
    @JsonProperty(required = true)
    public String text;

    @JsonProperty
    public RemoteObject exception;

    @JsonProperty
    public StackTrace stackTrace;
  }

  public static class StackTrace {
    @JsonProperty
    public String description;

    @JsonProperty(required = true)
    public List<CallFrame> callFrames;

    public void fillJavaStack(Throwable t) {
      if (t == null) return;
      for (StackTraceElement e: t.getStackTrace()) {
        CallFrame cf = new CallFrame();
        cf.functionName = e.getClassName() + "." + e.getMethodName();
        cf.url = (e.isNativeMethod() ? "native:" : "java:") + e.getFileName();
        cf.lineNumber = e.getLineNumber();
        cf.scriptId = "";
        cf.columnNumber = 0;
        callFrames.add(cf);
      }
    }
  }

  public static class CallFrame {
    @JsonProperty(required = true)
    public String functionName;

    @JsonProperty(required = true)
    public String scriptId;

    @JsonProperty(required = true)
    public String url;

    @JsonProperty(required = true)
    public int lineNumber;

    @JsonProperty(required = true)
    public int columnNumber;
  }

  public static class RemoteObject {
    @JsonProperty(required = true)
    public ObjectType type;

    @JsonProperty
    public ObjectSubType subtype;

    @JsonProperty
    public Object value;

    @JsonProperty
    public String className;

    @JsonProperty
    public String description;

    @JsonProperty
    public String objectId;

    @JsonProperty
    public String unserializableValue;
  }

  private static class PropertyDescriptor {
    @JsonProperty(required = true)
    public String name;

    @JsonProperty(required = true)
    public RemoteObject value;

    @JsonProperty(required = true)
    public boolean isOwn = false;

    @JsonProperty(required = true)
    public final boolean configurable = false;

    @JsonProperty(required = true)
    public final boolean enumerable = true;

    @JsonProperty(required = true)
    public final boolean writable = false;
  }

  public static enum ObjectType {
    OBJECT("object"),
    FUNCTION("function"),
    UNDEFINED("undefined"),
    STRING("string"),
    NUMBER("number"),
    BOOLEAN("boolean"),
    SYMBOL("symbol"),

    BIGINT("bigint");

    private final String mProtocolValue;

    private ObjectType(String protocolValue) {
      mProtocolValue = protocolValue;
    }

    @JsonValue
    public String getProtocolValue() {
      return mProtocolValue;
    }
  }

  public static enum ObjectSubType {
    ARRAY("array"),
    NULL("null"),
    NODE("node"),
    REGEXP("regexp"),
    DATE("date"),
    MAP("map"),
    SET("set"),
    ITERATOR("iterator"),
    GENERATOR("generator"),
    ERROR("error");

    private final String mProtocolValue;

    private ObjectSubType(String protocolValue) {
      mProtocolValue = protocolValue;
    }

    @JsonValue
    public String getProtocolValue() {
      return mProtocolValue;
    }
  }

  public enum ConsoleAPI {
    LOG("log"),
    DEBUG("debug"),
    INFO("info"),
    ERROR("error"),
    WARNING("warning"),
    CLEAR("clear");

    private final String mProtocolValue;

    private ConsoleAPI(String protocolValue) {
      mProtocolValue = protocolValue;
    }

    @JsonValue
    public String getProtocolValue() {
      return mProtocolValue;
    }
  }

  public static class ConsoleAPICalled {
    @JsonProperty
    public ConsoleAPI type;

    @JsonProperty
    public List<RemoteObject> args;

    @JsonProperty
    public StackTrace stackTrace;
  }

  public static final Object UNDEFINED = new Object();
}
