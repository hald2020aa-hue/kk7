package org.misterdownloadmanager.mobile;

import android.content.Context;

public final class NativeBridge {
    static { System.loadLibrary("mdm_mobile"); }
    private NativeBridge() {}
    public static native String initialize(Context context, String directory);
    public static native String command(String request);
}
