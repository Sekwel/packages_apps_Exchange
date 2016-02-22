// Copyright 2013 Google Inc. All Rights Reserved.

package com.android.exchange;

import android.accounts.AccountManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.CalendarContract;
import android.provider.ContactsContract;

import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.Mailbox;
import com.android.exchange.R;

import com.android.exchange.utility.CalendarUtilities;
import com.android.mail.utils.LogUtils;


public class ExchangeBroadcastReceiver extends BroadcastReceiver {

    private static final String TAG = "ExchangeReceiver";
    public static final String ACTION_SEND_CALENDAR_RESPONSE = "com.android.exchange.ACTION_SEND_CALENDAR_RESPONSE";

    @Override
    public void onReceive(final Context context, final Intent intent) {


        if (intent.getAction().equals(ACTION_SEND_CALENDAR_RESPONSE)) {
            LogUtils.d(TAG, "Got action: " + intent.getAction());

            long eventId = (long) intent.getIntExtra("calendar_event_id", 0);
            int eventStatus = intent.getIntExtra("calendar_event_status", 0);
            String emailAddress = intent.getStringExtra("calendar_email_address");
            int messageFlag;

            switch (eventStatus) {
                case 1:
                    messageFlag = EmailContent.Message.FLAG_OUTGOING_MEETING_ACCEPT;
                    break;
                case 2:
                    messageFlag = EmailContent.Message.FLAG_OUTGOING_MEETING_DECLINE;
                    break;
                case 3:
                    messageFlag = EmailContent.Message.FLAG_OUTGOING_MEETING_TENTATIVE;
                    break;
                default:
                    messageFlag = EmailContent.Message.FLAG_OUTGOING_MEETING_ACCEPT;
            }

            LogUtils.i(TAG, "eventId: " + eventId + " | eventStatus: " + eventStatus + " | emailAddress: " + emailAddress + " | messageFlag: " + messageFlag);

            com.android.emailcommon.provider.Account account = com.android.emailcommon.provider.Account.restoreAccountWithAddress(context, emailAddress);

            EmailContent.Message message = CalendarUtilities.createMessageForEventId(context, eventId, messageFlag, null, account, emailAddress);

            if (message != null) {
                long mailboxId = Mailbox.findMailboxOfType(context, account.mId, Mailbox.TYPE_OUTBOX);
                if (mailboxId == Mailbox.NO_MAILBOX) {
                    LogUtils.w(TAG, "No outbox for account %d, creating it", account.mId);
                    final Mailbox outbox =
                            Mailbox.newSystemMailbox(context, account.mId, Mailbox.TYPE_OUTBOX);
                    outbox.save(context);
                    mailboxId = outbox.mId;
                }

                message.mMailboxKey = mailboxId;
                message.mAccountKey = account.mId;
                message.save(context);

                final android.accounts.Account[] androidAccounts = AccountManager.get(context).getAccounts(); //.getAccountsByType(context.getString(R.string.account_manager_type_exchange));
                LogUtils.i(Eas.LOG_TAG, "Requesting FolderSync for unsynced accounts");
                for (final android.accounts.Account androidAccount : androidAccounts) {
                    if (androidAccount.name.equals(emailAddress)) {
                        final Bundle extras = Mailbox.createSyncBundle(mailboxId);
                        ContentResolver.requestSync(androidAccount, EmailContent.AUTHORITY, extras);
                        LogUtils.i(TAG, "requestSync EasServerConnection requestSyncForMailbox %s, %s",
                                androidAccount.toString(), extras.toString());
                        break;
                    }
                }
            }
        } else {
            final android.accounts.Account[] accounts = AccountManager.get(context).getAccountsByType(context.getString(R.string.account_manager_type_exchange));
            LogUtils.i(Eas.LOG_TAG, "Accounts changed - requesting FolderSync for unsynced accounts");
            for (final android.accounts.Account account : accounts) {
                // Only do a sync for accounts that are not configured to sync any types, since the
                // initial sync will do the right thing if at least one of those is enabled.
                if (!ContentResolver.getSyncAutomatically(account, EmailContent.AUTHORITY) &&
                        !ContentResolver.getSyncAutomatically(account, CalendarContract.AUTHORITY) &&
                        !ContentResolver.getSyncAutomatically(account, ContactsContract.AUTHORITY)) {
                    final Bundle bundle = new Bundle(3);
                    bundle.putBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_SETTINGS, true);
                    bundle.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
                    bundle.putBoolean(Mailbox.SYNC_EXTRA_ACCOUNT_ONLY, true);
                    ContentResolver.requestSync(account, EmailContent.AUTHORITY, bundle);
                }
            }
        }
    }
}
