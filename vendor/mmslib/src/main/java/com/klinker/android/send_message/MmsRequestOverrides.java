package com.klinker.android.send_message;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

/** Host-app hook for per-request MMS HTTP and transaction settings. */
public final class MmsRequestOverrides {
    public interface Provider {
        void apply(Context context, int subId, Bundle configOverrides);

        boolean appendTransactionId(Context context, int subId);

        default boolean shouldAutoDownload(Context context, int subId) {
            return true;
        }

        default void onDownloadDeferred(Context context, Uri messageUri, int subId) {
        }
    }

    private static volatile Provider provider;

    private MmsRequestOverrides() {
    }

    public static void setProvider(Provider value) {
        provider = value;
    }

    public static void apply(Context context, int subId, Bundle configOverrides) {
        Provider current = provider;
        if (current == null) return;
        try {
            current.apply(context, subId, configOverrides);
        } catch (RuntimeException error) {
            android.util.Log.e("MmsRequestOverrides", "provider failed", error);
        }
    }

    public static boolean appendTransactionId(Context context, int subId) {
        Provider current = provider;
        if (current == null) return false;
        try {
            return current.appendTransactionId(context, subId);
        } catch (RuntimeException error) {
            android.util.Log.e("MmsRequestOverrides", "provider failed", error);
            return false;
        }
    }

    public static boolean shouldAutoDownload(Context context, int subId) {
        Provider current = provider;
        if (current == null) return true;
        try {
            return current.shouldAutoDownload(context, subId);
        } catch (RuntimeException error) {
            android.util.Log.e("MmsRequestOverrides", "provider failed", error);
            return true;
        }
    }

    public static void onDownloadDeferred(Context context, Uri messageUri, int subId) {
        Provider current = provider;
        if (current == null) return;
        try {
            current.onDownloadDeferred(context, messageUri, subId);
        } catch (RuntimeException error) {
            android.util.Log.e("MmsRequestOverrides", "provider failed", error);
        }
    }
}
