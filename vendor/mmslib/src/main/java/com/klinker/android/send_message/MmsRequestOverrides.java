package com.klinker.android.send_message;

import android.content.Context;
import android.os.Bundle;

/** Host-app hook for per-request MMS HTTP and transaction settings. */
public final class MmsRequestOverrides {
    public interface Provider {
        void apply(Context context, int subId, Bundle configOverrides);

        boolean appendTransactionId(Context context, int subId);
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
}
