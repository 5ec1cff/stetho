/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.stetho.inspector;

import com.facebook.stetho.common.ExceptionUtil;
import com.facebook.stetho.common.Util;
import com.facebook.stetho.inspector.console.CLog;
import com.facebook.stetho.inspector.jsonrpc.JsonRpcException;
import com.facebook.stetho.inspector.jsonrpc.JsonRpcPeer;
import com.facebook.stetho.inspector.jsonrpc.JsonRpcResult;
import com.facebook.stetho.inspector.jsonrpc.protocol.EmptyResult;
import com.facebook.stetho.inspector.jsonrpc.protocol.JsonRpcError;
import com.facebook.stetho.inspector.protocol.ChromeDevtoolsDomain;
import com.facebook.stetho.inspector.protocol.ChromeDevtoolsMethod;
import com.facebook.stetho.inspector.protocol.module.Log;
import com.facebook.stetho.json.ObjectMapper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
public class MethodDispatcher {
  @GuardedBy("this")
  private Map<String, MethodDispatchHelper> mMethods;

  private final ObjectMapper mObjectMapper;
  private final Iterable<ChromeDevtoolsDomain> mDomainHandlers;

  public MethodDispatcher(
      ObjectMapper objectMapper,
      Iterable<ChromeDevtoolsDomain> domainHandlers) {
    mObjectMapper = objectMapper;
    mDomainHandlers = domainHandlers;
  }

  private synchronized MethodDispatchHelper findMethodDispatcher(String methodName) {
    if (mMethods == null) {
      mMethods = buildDispatchTable(mObjectMapper, mDomainHandlers);
    }
    return mMethods.get(methodName);
  }

  public JSONObject dispatch(JsonRpcPeer peer, String methodName, @Nullable JSONObject params)
      throws JsonRpcException {
    MethodDispatchHelper dispatchHelper = findMethodDispatcher(methodName);
    if (dispatchHelper == null) {
      throw new JsonRpcException(new JsonRpcError(JsonRpcError.ErrorCode.METHOD_NOT_FOUND,
          "Not implemented: " + methodName,
          null /* data */));
    }
    try {
      return dispatchHelper.invoke(peer, params);
    } catch (InvocationTargetException e) {
      android.util.Log.e("stetho", "dispatch failed", e);
      StringWriter w = new StringWriter();
      PrintWriter pw = new PrintWriter(w);
      Throwable t = e.getTargetException();
      if (t != null) t.printStackTrace(pw);
      else e.printStackTrace(pw);
      CLog.writeToConsole(Log.MessageLevel.ERROR, Log.MessageSource.JAVASCRIPT,
              "fatal error in stetho:" + w);
      Throwable cause = e.getCause();
      ExceptionUtil.propagateIfInstanceOf(cause, JsonRpcException.class);
      throw ExceptionUtil.propagate(cause);
    } catch (IllegalAccessException e) {
      throw new RuntimeException(e);
    } catch (JSONException e) {
      throw new JsonRpcException(new JsonRpcError(JsonRpcError.ErrorCode.INTERNAL_ERROR,
          e.toString(),
          null /* data */));
    }
  }

  private static class MethodDispatchHelper {
    private final ObjectMapper mObjectMapper;
    private final ChromeDevtoolsDomain mInstance;
    private final Method mMethod;

    public MethodDispatchHelper(ObjectMapper objectMapper,
        ChromeDevtoolsDomain instance,
        Method method) {
      mObjectMapper = objectMapper;
      mInstance = instance;
      mMethod = method;
    }

    public JSONObject invoke(JsonRpcPeer peer, @Nullable JSONObject params)
        throws InvocationTargetException, IllegalAccessException, JSONException, JsonRpcException {
      Object internalResult = mMethod.invoke(mInstance, peer, params);
      if (internalResult == null || internalResult instanceof EmptyResult) {
        return new JSONObject();
      } else {
        JsonRpcResult convertableResult = (JsonRpcResult)internalResult;
        return mObjectMapper.convertValue(convertableResult, JSONObject.class);
      }
    }
  }

  private static Map<String, MethodDispatchHelper> buildDispatchTable(
      ObjectMapper objectMapper,
      Iterable<ChromeDevtoolsDomain> domainHandlers) {
    Util.throwIfNull(objectMapper);
    HashMap<String, MethodDispatchHelper> methods = new HashMap<String, MethodDispatchHelper>();
    for (ChromeDevtoolsDomain domainHandler : Util.throwIfNull(domainHandlers)) {
      Class<?> handlerClass = domainHandler.getClass();
      String domainName = handlerClass.getSimpleName();

      for (Method method : handlerClass.getMethods()) {
        if (isDevtoolsMethod(method)) {
          MethodDispatchHelper dispatchHelper = new MethodDispatchHelper(
              objectMapper,
              domainHandler,
              method);
          methods.put(domainName + "." + method.getName(), dispatchHelper);
        }
      }
    }
    return Collections.unmodifiableMap(methods);
  }

  /**
   * Determines if the method is a {@link ChromeDevtoolsMethod}, and validates accordingly
   * if it is.
   *
   * @throws IllegalArgumentException Thrown if it is a {@link ChromeDevtoolsMethod} but
   *     it otherwise fails to satisfy requirements.
   */
  private static boolean isDevtoolsMethod(Method method) throws IllegalArgumentException {
    if (!method.isAnnotationPresent(ChromeDevtoolsMethod.class)) {
      return false;
    } else {
      Class<?> args[] = method.getParameterTypes();
      String methodName = method.getDeclaringClass().getSimpleName() + "." + method.getName();
      Util.throwIfNot(args.length == 2,
          "%s: expected 2 args, got %s",
          methodName,
          args.length);
      Util.throwIfNot(args[0].equals(JsonRpcPeer.class),
          "%s: expected 1st arg of JsonRpcPeer, got %s",
          methodName,
          args[0].getName());
      Util.throwIfNot(args[1].equals(JSONObject.class),
          "%s: expected 2nd arg of JSONObject, got %s",
          methodName,
          args[1].getName());

      Class<?> returnType = method.getReturnType();
      if (!returnType.equals(void.class)) {
        Util.throwIfNot(JsonRpcResult.class.isAssignableFrom(returnType),
            "%s: expected JsonRpcResult return type, got %s",
            methodName,
            returnType.getName());
      }
      return true;
    }
  }
}
