/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.stetho.inspector.elements.android;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.TextView;

import com.facebook.stetho.common.LogUtil;
import com.facebook.stetho.common.Util;
import com.facebook.stetho.common.android.ResourcesUtil;
import com.facebook.stetho.inspector.elements.AbstractChainedDescriptor;
import com.facebook.stetho.inspector.elements.AttributeAccumulator;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

final class TextViewDescriptor extends AbstractChainedDescriptor<TextView> {
  private static final String TEXT_ATTRIBUTE_NAME = "text";
  private static final String TEXT_ID_ATTR_NAME = "text_id";
  private static final String TEXT_ID_NAME_ATTR_NAME = "text_id_name";
  private static final String HINT_ID_ATTR_NAME = "hint_id";
  private static final String HINT_ID_NAME_ATTR_NAME = "hint_id_name";
  private static final Field sTextIdField;
  private static final Field sHintIdField;

  private final Map<TextView, ElementContext> mElementToContextMap =
      Collections.synchronizedMap(new IdentityHashMap<TextView, ElementContext>());

  static {
    Field textIdField = null;
    try {
      textIdField = TextView.class.getDeclaredField("mTextId");
      textIdField.setAccessible(true);
    } catch (NoSuchFieldException e) {
      // ignore
      LogUtil.e(e, "failed to get mTextId");
    }
    sTextIdField = textIdField;
    Field hintIdField = null;
    try {
      hintIdField = TextView.class.getDeclaredField("mHintId");
      hintIdField.setAccessible(true);
    } catch (NoSuchFieldException e) {
      // ignore
      LogUtil.e(e, "failed to get mHintId");
    }
    sHintIdField = hintIdField;
  }

  private static int getTextId(View view) {
    if (sTextIdField != null) {
      try {
        return sTextIdField.getInt(view);
      } catch (IllegalAccessException e) {
        LogUtil.e(e, "get text id");
      }
    }
    return 0;
  }

  private static int getHintId(View view) {
    if (sHintIdField != null) {
      try {
        return sHintIdField.getInt(view);
      } catch (IllegalAccessException e) {
        LogUtil.e(e, "get text id");
      }
    }
    return 0;
  }

  @Override
  protected void onHook(final TextView element) {
    ElementContext context = new ElementContext();
    context.hook(element);
    mElementToContextMap.put(element, context);
  }

  protected void onUnhook(TextView element) {
    ElementContext context = mElementToContextMap.remove(element);
    context.unhook();
  }

  @Override
  protected void onGetAttributes(TextView element, AttributeAccumulator attributes) {
    CharSequence text = element.getText();
    if (text != null && text.length() != 0) {
      attributes.store(TEXT_ATTRIBUTE_NAME, text.toString());
    }
    int text_id = getTextId(element);
    if (text_id != 0) {
      attributes.store(TEXT_ID_ATTR_NAME, String.format("0x%08x", text_id));
      attributes.store(TEXT_ID_NAME_ATTR_NAME,
              ResourcesUtil.getIdStringQuietly(element, element.getResources(), text_id));
    }
    int hint_id = getHintId(element);
    if (hint_id != 0) {
      attributes.store(HINT_ID_ATTR_NAME, String.format("0x%08x", hint_id));
      attributes.store(HINT_ID_NAME_ATTR_NAME,
              ResourcesUtil.getIdStringQuietly(element, element.getResources(), hint_id));
    }
  }

  private final class ElementContext implements TextWatcher {
    private TextView mElement;

    public void hook(TextView element) {
      mElement = Util.throwIfNull(element);
      mElement.addTextChangedListener(this);
    }

    public void unhook() {
      if (mElement != null) {
        mElement.removeTextChangedListener(this);
        mElement = null;
      }
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
    }

    @Override
    public void afterTextChanged(Editable s) {
      if (s.length() == 0) {
        getHost().onAttributeRemoved(mElement, TEXT_ATTRIBUTE_NAME);
      } else {
        getHost().onAttributeModified(mElement, TEXT_ATTRIBUTE_NAME, s.toString());
      }
    }
  }
}
