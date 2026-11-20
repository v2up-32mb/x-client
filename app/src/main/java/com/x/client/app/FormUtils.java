/*
 ============================================================================
 Name        : FormUtils.java
 Description : Helper utilities for TextInputLayout and form fields
 ============================================================================
 */

package com.x.client.app;

import android.view.View;
import android.view.ViewParent;
import android.widget.EditText;
import com.google.android.material.textfield.TextInputLayout;

public final class FormUtils {

    private FormUtils() {}

    /**
     * 查找包裹目标输入框的外层 TextInputLayout。
     */
    public static TextInputLayout tilOf(View view) {
        if (view == null) return null;
        ViewParent parent = view.getParent();
        while (parent != null) {
            if (parent instanceof TextInputLayout) {
                return (TextInputLayout) parent;
            }
            parent = parent.getParent();
        }
        return null;
    }

    /**
     * 在输入框所属的 TextInputLayout 上显示内联错误提示，并使输入框请求焦点。
     */
    public static void showFieldError(EditText edit, String message) {
        if (edit == null) return;
        TextInputLayout til = tilOf(edit);
        if (til != null) {
            til.setError(message);
        } else {
            edit.setError(message);
        }
        edit.requestFocus();
    }

    /**
     * 同步启用或禁用输入框及其所属的 TextInputLayout（置灰视觉反馈）。
     */
    public static void setFieldEnabled(EditText edit, boolean enabled) {
        if (edit == null) return;
        edit.setEnabled(enabled);
        TextInputLayout til = tilOf(edit);
        if (til != null) {
            til.setEnabled(enabled);
        }
    }

    /**
     * 清除指定输入框上的内联错误。
     */
    public static void clearFieldError(EditText edit) {
        if (edit == null) return;
        TextInputLayout til = tilOf(edit);
        if (til != null) {
            til.setError(null);
        }
        edit.setError(null);
    }
}
