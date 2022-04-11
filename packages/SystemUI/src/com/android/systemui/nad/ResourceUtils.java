package com.android.systemui.nad;

import android.content.Context;
import android.view.View;
import android.view.ViewStub;

public class ResourceUtils {
    private static Context CONTEXT = null;
    private static String PACKAGE_NAME = "";

    public static ViewStub findViewStubById(View view, int i) {
        return (ViewStub) view.findViewById(i);
    }

    public static int getAndroidAnimResId(String name) {
        return CONTEXT.getResources().getIdentifier(name, "anim", "android");
    }

    public static int getAndroidArrayResId(String name) {
        return CONTEXT.getResources().getIdentifier(name, "array", "android");
    }

    public static int getAndroidAttrPrivateResId(String name) {
        return CONTEXT.getResources().getIdentifier(name, "^attr-private", "android");
    }

    public static int getAndroidAttrResId(String name) {
        return CONTEXT.getResources().getIdentifier(name, "attr", "android");
    }

    public static int getAndroidBoolResId(String name) {
        return CONTEXT.getResources().getIdentifier(name, "bool", "android");
    }

    public static int getAndroidColorResId(String name) {
        return CONTEXT.getResources().getIdentifier(name, "color", "android");
    }

    public static int getAndroidDimenResId(String name) {
        return CONTEXT.getResources().getIdentifier(name, "dimen", "android");
    }

    public static int getAndroidDrawableResId(String name) {
        return CONTEXT.getResources().getIdentifier(name, "drawable", "android");
    }

    public static int getAndroidIdResId(String name) {
        return CONTEXT.getResources().getIdentifier(name, "id", "android");
    }

    public static int getAndroidIntegerResId(String name) {
        return CONTEXT.getResources().getIdentifier(name, "integer", "android");
    }

    public static int getAndroidLayoutResId(String name) {
        return CONTEXT.getResources().getIdentifier(name, "layout", "android");
    }

    public static int getAndroidStringResId(String name) {
        return CONTEXT.getResources().getIdentifier(name, "string", "android");
    }

    public static int getAndroidStyleResId(String name) {
        return CONTEXT.getResources().getIdentifier(name, "style", "android");
    }

    public static int getAndroidStyleableResId(String name) {
        return CONTEXT.getResources().getIdentifier(name, "styleable", "android");
    }

    public static int getAnimResId(String name) {
        return CONTEXT.getResources().getIdentifier(name, "anim", PACKAGE_NAME);
    }

    public static int getArrayResId(String name) {
        return CONTEXT.getResources().getIdentifier(name, "array", PACKAGE_NAME);
    }

    public static int getBoolResId(String name) {
        return CONTEXT.getResources().getIdentifier(name, "bool", PACKAGE_NAME);
    }

    public static int getColorResId(String name) {
        return CONTEXT.getResources().getIdentifier(name, "color", PACKAGE_NAME);
    }

    public static Context getContext() {
        return CONTEXT;
    }

    public static int getDimenResId(String name) {
        return CONTEXT.getResources().getIdentifier(name, "dimen", PACKAGE_NAME);
    }

    public static int getDrawableResId(String name) {
        return CONTEXT.getResources().getIdentifier(name, "drawable", PACKAGE_NAME);
    }

    public static int getIdResId(String name) {
        return CONTEXT.getResources().getIdentifier(name, "id", PACKAGE_NAME);
    }

    public static int getIntegerResId(String name) {
        return CONTEXT.getResources().getIdentifier(name, "integer", PACKAGE_NAME);
    }

    public static int getLayoutResId(String name) {
        return CONTEXT.getResources().getIdentifier(name, "layout", PACKAGE_NAME);
    }

    public static int getMenuResId(String name) {
        return CONTEXT.getResources().getIdentifier(name, "menu", PACKAGE_NAME);
    }

    public static String getPackageName() {
        return PACKAGE_NAME;
    }

    public static int getPluralsResId(String name) {
        return CONTEXT.getResources().getIdentifier(name, "plurals", PACKAGE_NAME);
    }

    public static int getStringResId(String name) {
        return CONTEXT.getResources().getIdentifier(name, "string", PACKAGE_NAME);
    }

    public static int[] getStyleableArrayResId(String name) {
        return new int[]{CONTEXT.getResources().getIdentifier(name, "styleable", PACKAGE_NAME)};
    }

    public static int getStyleableResId(String name) {
        return CONTEXT.getResources().getIdentifier(name, "styleable", PACKAGE_NAME);
    }

    public static int getXmlResId(String name) {
        return CONTEXT.getResources().getIdentifier(name, "xml", PACKAGE_NAME);
    }

    public static void init(String name, Context context) {
        PACKAGE_NAME = name;
        CONTEXT = context;
        DynamicUtils.init(context);
    }
}
