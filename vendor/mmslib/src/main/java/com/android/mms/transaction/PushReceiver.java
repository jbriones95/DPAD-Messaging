/*
 * Copyright 2014 Jacob Klinker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.mms.transaction;

import static android.provider.Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION;
import static android.provider.Telephony.Sms.Intents.WAP_PUSH_RECEIVED_ACTION;
import static com.google.android.mms.pdu_alt.PduHeaders.MESSAGE_TYPE_DELIVERY_IND;
import static com.google.android.mms.pdu_alt.PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND;
import static com.google.android.mms.pdu_alt.PduHeaders.MESSAGE_TYPE_READ_ORIG_IND;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SqliteWrapper;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.Telephony.Mms;
import android.provider.Telephony.Mms.Inbox;
import android.telephony.SmsManager;
import android.text.TextUtils;

import com.android.mms.MmsConfig;
import com.android.mms.logs.LogTag;
import com.android.mms.service_alt.DownloadRequest;
import com.android.mms.service_alt.MmsNetworkManager;
import com.android.mms.service_alt.MmsRequestManager;
import com.google.android.mms.ContentType;
import com.google.android.mms.MmsException;
import com.google.android.mms.pdu_alt.DeliveryInd;
import com.google.android.mms.pdu_alt.GenericPdu;
import com.google.android.mms.pdu_alt.NotificationInd;
import com.google.android.mms.pdu_alt.PduHeaders;
import com.google.android.mms.pdu_alt.PduParser;
import com.google.android.mms.pdu_alt.PduPersister;
import com.google.android.mms.pdu_alt.ReadOrigInd;
import com.klinker.android.logger.Log;
import com.klinker.android.send_message.MmsRequestOverrides;
import com.klinker.android.send_message.Settings;
import com.klinker.android.send_message.SmsManagerFactory;
import com.klinker.android.send_message.Transaction;
import com.klinker.android.send_message.Utils;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Receives Intent.WAP_PUSH_RECEIVED_ACTION intents and starts the
 * TransactionService by passing the push-data to it.
 */
public class PushReceiver extends BroadcastReceiver {
    private static final String TAG = LogTag.TAG;
    private static final boolean DEBUG = false;
    private static final boolean LOCAL_LOGV = true;

    static final String[] PROJECTION = new String[]{
            Mms.CONTENT_LOCATION,
            Mms.LOCKED
    };

    static final String[] TRANSACTION_ID_PROJECTION = new String[]{
            Mms.CONTENT_LOCATION,
            Mms.LOCKED
    };

    static final int COLUMN_CONTENT_LOCATION = 0;

    private static Set<String> downloadedUrls = new HashSet<String>();
    private static final ExecutorService PUSH_RECEIVER_EXECUTOR = Executors.newSingleThreadExecutor();

    private class ReceivePushTask extends AsyncTask<Intent, Void, Void> {
        private Context mContext;
        private PendingResult pendingResult;

        ReceivePushTask(Context context, PendingResult pendingResult) {
            mContext = context;
            this.pendingResult = pendingResult;
        }

        @Override
        protected Void doInBackground(Intent... intents) {
            Log.v(TAG, "receiving a new mms message");
            Intent intent = intents[0];

            // Get raw PDU push-data from the message and parse it
            byte[] pushData = intent.getByteArrayExtra("data");
            PduParser parser = new PduParser(pushData);
            GenericPdu pdu = parser.parse();

            if (null == pdu) {
                Log.e(TAG, "Invalid PUSH data");
                return null;
            }

            PduPersister p = PduPersister.getPduPersister(mContext);
            ContentResolver cr = mContext.getContentResolver();
            int type = pdu.getMessageType();
            long threadId = -1;
            int subId = intent.getIntExtra("subscription", Settings.DEFAULT_SUBSCRIPTION_ID);

            try {
                switch (type) {
                    case MESSAGE_TYPE_DELIVERY_IND:
                    case MESSAGE_TYPE_READ_ORIG_IND: {
                        threadId = findThreadId(mContext, pdu, type);
                        if (threadId == -1) {
                            // The associated SendReq isn't found, therefore skip
                            // processing this PDU.
                            break;
                        }

                        boolean group;

                        try {
                            group = com.klinker.android.send_message.Transaction.settings.getGroup();
                        } catch (Exception e) {
                            group = PreferenceManager.getDefaultSharedPreferences(mContext).getBoolean("group_message", true);
                        }

                        Uri uri = p.persist(pdu, Inbox.CONTENT_URI, true,
                                group, null, subId);
                        // Update thread ID for ReadOrigInd & DeliveryInd.
                        ContentValues values = new ContentValues(1);
                        values.put(Mms.THREAD_ID, threadId);
                        SqliteWrapper.update(mContext, cr, uri, values, null, null);
                        break;
                    }
                    case MESSAGE_TYPE_NOTIFICATION_IND: {
                        NotificationInd nInd = (NotificationInd) pdu;

                        boolean appendTransactionId = false;
                        Bundle configOverrides = SmsManagerFactory.createSmsManager(subId).getCarrierConfigValues();
                        appendTransactionId = configOverrides.getBoolean(SmsManager.MMS_CONFIG_APPEND_TRANSACTION_ID);
                        appendTransactionId = appendTransactionId
                                || MmsRequestOverrides.appendTransactionId(mContext, subId);

                        if (appendTransactionId) {
                            Log.v(TAG, "appending the transaction ID, based on the SMS manager overrides");
                        }

                        if (MmsConfig.getTransIdEnabled() || appendTransactionId) {
                            byte[] contentLocation = nInd.getContentLocation();
                            if ('=' == contentLocation[contentLocation.length - 1]) {
                                byte[] transactionId = nInd.getTransactionId();
                                byte[] contentLocationWithId = new byte[contentLocation.length
                                        + transactionId.length];
                                System.arraycopy(contentLocation, 0, contentLocationWithId,
                                        0, contentLocation.length);
                                System.arraycopy(transactionId, 0, contentLocationWithId,
                                        contentLocation.length, transactionId.length);
                                nInd.setContentLocation(contentLocationWithId);
                            }
                        }

                        if (!isDuplicateNotification(mContext, nInd)) {
                             // Persist the notification row when the app policy defers the
                             // download so it can be retrieved later by the user.
                            boolean group;

                            try {
                                group = com.klinker.android.send_message.Transaction.settings.getGroup();
                            } catch (Exception e) {
                                group = PreferenceManager.getDefaultSharedPreferences(mContext).getBoolean("group_message", true);
                            }

                             boolean allowAutoDownload = MmsRequestOverrides.shouldAutoDownload(mContext, subId);
                             Uri uri = p.persist(pdu, Inbox.CONTENT_URI,
                                     !allowAutoDownload,
                                     group,
                                     null,
                                     subId);

                            String location;
                            try {
                                location = getContentLocation(mContext, uri);
                            } catch (MmsException ex) {
                                location = p.getContentLocationFromPduHeader(pdu);
                                if (TextUtils.isEmpty(location)) {
                                    throw ex;
                                }
                            }

                            if (downloadedUrls.contains(location)) {
                                Log.v(TAG, "already added this download, don't download again");
                                return null;
                            } else {
                                downloadedUrls.add(location);
                            }

                            String transactionId;
                            try {
                                transactionId = getTransactionId(mContext, uri);
                            } catch (MmsException ex) {
                                transactionId = p.getTransactionIdFromPduHeader(pdu);
                                if (TextUtils.isEmpty(transactionId)) {
                                    throw ex;
                                }
                            }

                            Log.v(TAG, "receiving on a lollipop+ device");
                            boolean useSystem = true;

                            if (com.klinker.android.send_message.Transaction.settings != null) {
                                useSystem = com.klinker.android.send_message.Transaction.settings
                                        .getUseSystemSending();
                            } else {
                                useSystem = PreferenceManager.getDefaultSharedPreferences(mContext)
                                        .getBoolean("system_mms_sending", useSystem);
                            }

                             if (!allowAutoDownload) {
                                 MmsRequestOverrides.onDownloadDeferred(mContext, uri, subId);
                             } else if (useSystem) {
                                 DownloadManager.getInstance().downloadMultimediaMessage(mContext, location, transactionId, uri, true, subId);
                             } else {
                                Log.v(TAG, "receiving with lollipop method");
                                MmsRequestManager requestManager = new MmsRequestManager(mContext);
                                DownloadRequest request = new DownloadRequest(requestManager,
                                        Utils.getDefaultSubscriptionId(),
                                        location, transactionId, uri, null, null,
                                        null, mContext);
                                MmsNetworkManager manager = new MmsNetworkManager(mContext, Utils.getDefaultSubscriptionId());
                                request.execute(mContext, manager);
                            }
                        } else if (LOCAL_LOGV) {
                            Log.v(TAG, "Skip downloading duplicate message: "
                                    + new String(nInd.getTransactionId()));
                        }
                        break;
                    }
                    default:
                        Log.e(TAG, "Received unrecognized PDU.");
                }
            } catch (MmsException e) {
                Log.e(TAG, "Failed to save the data from PUSH: type=" + type, e);
            } catch (RuntimeException e) {
                Log.e(TAG, "Unexpected RuntimeException.", e);
            }

            if (LOCAL_LOGV) {
                Log.v(TAG, "PUSH Intent processed.");
            }

            return null;
        }

        @Override
        public void onPostExecute(Void result) {
            if (pendingResult != null) {
                pendingResult.finish();
            }
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.v(TAG, intent.getAction() + " " + intent.getType());
        if ((intent.getAction().equals(WAP_PUSH_DELIVER_ACTION) || intent.getAction().equals(WAP_PUSH_RECEIVED_ACTION))
                && ContentType.MMS_MESSAGE.equals(intent.getType())) {
            if (LOCAL_LOGV) {
                Log.v(TAG, "Received PUSH Intent: " + intent);
            }

            MmsConfig.init(context);
            new ReceivePushTask(context, null).executeOnExecutor(PUSH_RECEIVER_EXECUTOR, intent);

            Log.v("mms_receiver", context.getPackageName() + " received and aborted");
        }
    }

    public static String getContentLocation(Context context, Uri uri)
            throws MmsException {
        Cursor cursor = SqliteWrapper.query(context, context.getContentResolver(),
                uri, PROJECTION, null, null, null);

        if (cursor != null) {
            try {
                if ((cursor.getCount() == 1) && cursor.moveToFirst()) {
                    String location = cursor.getString(COLUMN_CONTENT_LOCATION);
                    cursor.close();
                    return location;
                }
            } finally {
                cursor.close();
            }
        }

        throw new MmsException("Cannot get X-Mms-Content-Location from: " + uri);
    }

    public static String getTransactionId(Context context, Uri uri)
            throws MmsException {
        Cursor cursor = SqliteWrapper.query(context, context.getContentResolver(),
                uri, TRANSACTION_ID_PROJECTION, null, null, null);

        if (cursor != null) {
            try {
                if ((cursor.getCount() == 1) && cursor.moveToFirst()) {
                    String transactionId = cursor.getString(COLUMN_CONTENT_LOCATION);
                    cursor.close();
                    return transactionId;
                }
            } finally {
                cursor.close();
            }
        }

        throw new MmsException("Cannot get Transaction-id from: " + uri);
    }

    private static long findThreadId(Context context, GenericPdu pdu, int type) {
        String messageId;

        if (type == MESSAGE_TYPE_DELIVERY_IND) {
            messageId = new String(((DeliveryInd) pdu).getMessageId());
        } else {
            messageId = new String(((ReadOrigInd) pdu).getMessageId());
        }

        StringBuilder sb = new StringBuilder('(');
        sb.append(Mms.MESSAGE_ID);
        sb.append('=');
        sb.append(DatabaseUtils.sqlEscapeString(messageId));
        sb.append(" AND ");
        sb.append(Mms.MESSAGE_TYPE);
        sb.append('=');
        sb.append(PduHeaders.MESSAGE_TYPE_SEND_REQ);
        // TODO ContentResolver.query() appends closing ')' to the selection argument
        // sb.append(')');

        Cursor cursor = SqliteWrapper.query(context, context.getContentResolver(),
                Mms.CONTENT_URI, new String[]{Mms.THREAD_ID},
                sb.toString(), null, null);
        if (cursor != null) {
            try {
                if ((cursor.getCount() == 1) && cursor.moveToFirst()) {
                    long id = cursor.getLong(0);
                    cursor.close();
                    return id;
                }
            } finally {
                cursor.close();
            }
        }

        return -1;
    }

    private static boolean isDuplicateNotification(
            Context context, NotificationInd nInd) {
        byte[] rawTransactionId = nInd.getTransactionId();
        if (rawTransactionId != null) {
            String location = new String(rawTransactionId);
            String selection = Mms.TRANSACTION_ID + " = ?";
            String[] selectionArgs = new String[]{location};
            Cursor cursor = SqliteWrapper.query(
                    context, context.getContentResolver(),
                    Mms.CONTENT_URI, new String[]{Mms._ID},
                    selection, selectionArgs, null);
            if (cursor != null) {
                try {
                    if (cursor.getCount() > 0) {
                        // We already received the same notification before.
                        cursor.close();
                        return true;
                    }
                } finally {
                    cursor.close();
                }
            }
        }
        return false;
    }
}
