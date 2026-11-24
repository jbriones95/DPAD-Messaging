
package com.android.mms.transaction;

import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.telephony.SmsManager;
import android.text.TextUtils;

import com.android.mms.MmsConfig;
import com.klinker.android.logger.Log;
import com.klinker.android.send_message.MmsReceivedReceiver;
import com.klinker.android.send_message.MmsRequestOverrides;
import com.klinker.android.send_message.SmsManagerFactory;

import java.io.File;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In order to avoid downloading duplicate MMS.
 * We should manage to call SMSManager.downloadMultimediaMessage().
 */
public class DownloadManager {
    private static final String TAG = "DownloadManager";
    private static DownloadManager ourInstance = new DownloadManager();
    private static final ConcurrentHashMap<String, Object> mMap = new ConcurrentHashMap<>();

    public static DownloadManager getInstance() {
        return ourInstance;
    }

    private DownloadManager() {

    }

    public static final String ACTION_MMS_DOWNLOADED = "com.dpad.messaging.action.MMS_DOWNLOADED";

    public void downloadMultimediaMessage(final Context context, final String location, final String transactionId, Uri uri, boolean byPush, int subscriptionId) {
        if (location == null || mMap.get(location) != null) {
            return;
        }

        // Keep only duplicate-tracking state in memory. The completion callback
        // targets a manifest receiver so process death cannot lose it.
        mMap.put(location, null);

        Log.v(TAG, "receiving with system method");
        final String fileName = "download." + Math.abs(new Random().nextLong()) + ".dat";
        File mDownloadFile = new File(context.getCacheDir(), fileName);
        Uri contentUri = (new Uri.Builder())
                .authority(context.getPackageName() + ".MmsFileProvider")
                .path(fileName)
                .scheme(ContentResolver.SCHEME_CONTENT)
                .build();

        Intent download = new Intent(ACTION_MMS_DOWNLOADED)
                .setPackage(context.getPackageName())
                .setClass(context, com.klinker.android.send_message.MmsReceivedReceiver.class);
        download.putExtra(MmsReceivedReceiver.EXTRA_FILE_PATH, mDownloadFile.getPath());
        download.putExtra(MmsReceivedReceiver.EXTRA_LOCATION_URL, location);
        download.putExtra(MmsReceivedReceiver.EXTRA_TRANSACTION_ID, transactionId);
        download.putExtra(MmsReceivedReceiver.EXTRA_TRIGGER_PUSH, byPush);
        download.putExtra(MmsReceivedReceiver.EXTRA_URI, uri);
        download.putExtra(MmsReceivedReceiver.SUBSCRIPTION_ID, subscriptionId);
        int flags = PendingIntent.FLAG_CANCEL_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags | PendingIntent.FLAG_IMMUTABLE;
        }
        final PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context, location.hashCode(), download, flags);

        final SmsManager smsManager = SmsManagerFactory.createSmsManager(subscriptionId);

        Bundle configOverrides = new Bundle();
        String httpParams = MmsConfig.getHttpParams();
        if (!TextUtils.isEmpty(httpParams)) {
            configOverrides.putString(SmsManager.MMS_CONFIG_HTTP_PARAMS, httpParams);
        } else {
            // this doesn't seem to always work...
            // configOverrides = smsManager.getCarrierConfigValues();
        }
        MmsRequestOverrides.apply(context, subscriptionId, configOverrides);

        grantUriPermission(context, contentUri);
        smsManager.downloadMultimediaMessage(context, location, contentUri, configOverrides, pendingIntent);
    }

    private void grantUriPermission(Context context, Uri contentUri) {
        context.grantUriPermission(context.getPackageName() + ".MmsFileProvider",
                contentUri,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    }

    public static void finishDownload(String location) {
        if (location != null) {
            mMap.remove(location);
        }
    }
}
