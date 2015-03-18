package org.SecuredText.SecuredText.jobs;

import android.content.Context;
import android.net.Uri;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.util.Pair;

import org.SecuredText.SecuredText.R;
import org.SecuredText.SecuredText.crypto.MasterSecret;
import org.SecuredText.SecuredText.crypto.MmsCipher;
import org.SecuredText.SecuredText.crypto.storage.SecuredTextAxolotlStore;
import org.SecuredText.SecuredText.database.DatabaseFactory;
import org.SecuredText.SecuredText.database.MmsDatabase;
import org.SecuredText.SecuredText.jobs.requirements.MasterSecretRequirement;
import org.SecuredText.SecuredText.mms.ApnUnavailableException;
import org.SecuredText.SecuredText.mms.IncomingMediaMessage;
import org.SecuredText.SecuredText.mms.IncomingMmsConnection;
import org.SecuredText.SecuredText.mms.MmsConnection;
import org.SecuredText.SecuredText.mms.MmsRadio;
import org.SecuredText.SecuredText.mms.MmsRadioException;
import org.SecuredText.SecuredText.mms.OutgoingMmsConnection;
import org.SecuredText.SecuredText.notifications.MessageNotifier;
import org.SecuredText.SecuredText.protocol.WirePrefix;
import org.SecuredText.SecuredText.service.KeyCachingService;
import org.whispersystems.jobqueue.JobParameters;
import org.whispersystems.jobqueue.requirements.NetworkRequirement;
import org.whispersystems.libaxolotl.DuplicateMessageException;
import org.whispersystems.libaxolotl.InvalidMessageException;
import org.whispersystems.libaxolotl.LegacyMessageException;
import org.whispersystems.libaxolotl.NoSessionException;
import org.whispersystems.libaxolotl.util.guava.Optional;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import ws.com.google.android.mms.InvalidHeaderValueException;
import ws.com.google.android.mms.MmsException;
import ws.com.google.android.mms.pdu.MultimediaMessagePdu;
import ws.com.google.android.mms.pdu.NotificationInd;
import ws.com.google.android.mms.pdu.NotifyRespInd;
import ws.com.google.android.mms.pdu.PduComposer;
import ws.com.google.android.mms.pdu.PduHeaders;
import ws.com.google.android.mms.pdu.RetrieveConf;

import static org.SecuredText.SecuredText.mms.MmsConnection.Apn;

public class MmsDownloadJob extends MasterSecretJob {

  private static final String TAG = MmsDownloadJob.class.getSimpleName();

  private final long    messageId;
  private final long    threadId;
  private final boolean automatic;

  public MmsDownloadJob(Context context, long messageId, long threadId, boolean automatic) {
    super(context, JobParameters.newBuilder()
                                .withPersistence()
                                .withRequirement(new MasterSecretRequirement(context))
                                .withRequirement(new NetworkRequirement(context))
                                .withGroupId("mms-operation")
                                .withWakeLock(true, 30, TimeUnit.SECONDS)
                                .create());

    this.messageId = messageId;
    this.threadId  = threadId;
    this.automatic = automatic;
  }

  @Override
  public void onAdded() {
    if (automatic && KeyCachingService.getMasterSecret(context) == null) {
      DatabaseFactory.getMmsDatabase(context).markIncomingNotificationReceived(threadId);
      MessageNotifier.updateNotification(context, null);
    }
  }

  @Override
  public void onRun(MasterSecret masterSecret)  {
    Log.w(TAG, "MmsDownloadJob:onRun()");

    MmsDatabase               database     = DatabaseFactory.getMmsDatabase(context);
    Optional<NotificationInd> notification = database.getNotification(messageId);

    if (!notification.isPresent()) {
      Log.w(TAG, "No notification for ID: " + messageId);
      return;
    }

    database.markDownloadState(messageId, MmsDatabase.Status.DOWNLOAD_CONNECTING);

    String   contentLocation = new String(notification.get().getContentLocation());
    byte[]   transactionId   = notification.get().getTransactionId();
    MmsRadio radio           = MmsRadio.getInstance(context);

    Log.w(TAG, "About to parse URL...");

    Log.w(TAG, "Downloading mms at " +  Uri.parse(contentLocation).getHost());

    try {
      if (isCdmaNetwork()) {
        Log.w(TAG, "Connecting directly...");
        try {
          retrieveAndStore(masterSecret, radio, messageId, threadId, contentLocation,
                           transactionId, false, false);
          return;
        } catch (IOException e) {
          Log.w(TAG, e);
        }
      }

      Log.w(TAG, "Changing radio to MMS mode..");
      radio.connect();

      try {
        Log.w(TAG, "Downloading in MMS mode with proxy...");

        try {
          retrieveAndStore(masterSecret, radio, messageId, threadId, contentLocation,
                           transactionId, true, true);
          return;
        } catch (IOException e) {
          Log.w(TAG, e);
        }

        Log.w(TAG, "Downloading in MMS mode without proxy...");

        try {
          retrieveAndStore(masterSecret, radio, messageId, threadId,
                           contentLocation, transactionId, true, false);
        } catch (IOException e) {
          Log.w(TAG, e);
          handleDownloadError(masterSecret, messageId, threadId,
                              MmsDatabase.Status.DOWNLOAD_SOFT_FAILURE,
                              context.getString(R.string.MmsDownloader_error_connecting_to_mms_provider),
                              automatic);
        }
      } finally {
        radio.disconnect();
      }

    } catch (ApnUnavailableException e) {
      Log.w(TAG, e);
      handleDownloadError(masterSecret, messageId, threadId, MmsDatabase.Status.DOWNLOAD_APN_UNAVAILABLE,
                          context.getString(R.string.MmsDownloader_error_reading_mms_settings), automatic);
    } catch (MmsException e) {
      Log.w(TAG, e);
      handleDownloadError(masterSecret, messageId, threadId,
                          MmsDatabase.Status.DOWNLOAD_HARD_FAILURE,
                          context.getString(R.string.MmsDownloader_error_storing_mms),
                          automatic);
    } catch (MmsRadioException e) {
      Log.w(TAG, e);
      handleDownloadError(masterSecret, messageId, threadId,
                          MmsDatabase.Status.DOWNLOAD_SOFT_FAILURE,
                          context.getString(R.string.MmsDownloader_error_connecting_to_mms_provider),
                          automatic);
    } catch (DuplicateMessageException e) {
      Log.w(TAG, e);
      database.markAsDecryptDuplicate(messageId, threadId);
    } catch (LegacyMessageException e) {
      Log.w(TAG, e);
      database.markAsLegacyVersion(messageId, threadId);
    } catch (NoSessionException e) {
      Log.w(TAG, e);
      database.markAsNoSession(messageId, threadId);
    } catch (InvalidMessageException e) {
      Log.w(TAG, e);
      database.markAsDecryptFailed(messageId, threadId);
    }
  }

  @Override
  public void onCanceled() {
    MmsDatabase database = DatabaseFactory.getMmsDatabase(context);
    database.markDownloadState(messageId, MmsDatabase.Status.DOWNLOAD_SOFT_FAILURE);

    if (automatic) {
      database.markIncomingNotificationReceived(threadId);
      MessageNotifier.updateNotification(context, null, threadId);
    }
  }

  @Override
  public boolean onShouldRetryThrowable(Exception exception) {
    return false;
  }

  private void retrieveAndStore(MasterSecret masterSecret, MmsRadio radio,
                                long messageId, long threadId,
                                String contentLocation, byte[] transactionId,
                                boolean radioEnabled, boolean useProxy)
      throws IOException, MmsException, ApnUnavailableException,
             DuplicateMessageException, NoSessionException,
             InvalidMessageException, LegacyMessageException
  {
    Apn                   dbApn      = MmsConnection.getApn(context, radio.getApnInformation());
    Apn                   contentApn = new Apn(contentLocation, dbApn.getProxy(), Integer.toString(dbApn.getPort()), dbApn.getUsername(), dbApn.getPassword());
    IncomingMmsConnection connection = new IncomingMmsConnection(context, contentApn);
    RetrieveConf          retrieved  = connection.retrieve(radioEnabled, useProxy);

    storeRetrievedMms(masterSecret, contentLocation, messageId, threadId, retrieved);
    sendRetrievedAcknowledgement(radio, transactionId, radioEnabled, useProxy);
  }

  private void storeRetrievedMms(MasterSecret masterSecret, String contentLocation,
                                 long messageId, long threadId, RetrieveConf retrieved)
      throws MmsException, NoSessionException, DuplicateMessageException, InvalidMessageException,
             LegacyMessageException
  {
    MmsDatabase          database = DatabaseFactory.getMmsDatabase(context);
    IncomingMediaMessage message  = new IncomingMediaMessage(retrieved);

    Pair<Long, Long> messageAndThreadId;

    if (retrieved.getSubject() != null && WirePrefix.isEncryptedMmsSubject(retrieved.getSubject().getString())) {
      MmsCipher            mmsCipher          = new MmsCipher(new SecuredTextAxolotlStore(context, masterSecret));
      MultimediaMessagePdu plaintextPdu       = mmsCipher.decrypt(context, retrieved);
      RetrieveConf         plaintextRetrieved = new RetrieveConf(plaintextPdu.getPduHeaders(), plaintextPdu.getBody());
      IncomingMediaMessage plaintextMessage   = new IncomingMediaMessage(plaintextRetrieved);

      messageAndThreadId = database.insertSecureDecryptedMessageInbox(masterSecret, plaintextMessage,
                                                                      threadId);

//      if (masterSecret != null)
//        DecryptingQueue.scheduleDecryption(context, masterSecret, messageAndThreadId.first,
//                                           messageAndThreadId.second, retrieved);

    } else {
      messageAndThreadId = database.insertMessageInbox(masterSecret, message,
                                                       contentLocation, threadId);
    }

    database.delete(messageId);
    MessageNotifier.updateNotification(context, masterSecret, messageAndThreadId.second);
  }

  private void sendRetrievedAcknowledgement(MmsRadio radio,
                                            byte[] transactionId,
                                            boolean usingRadio,
                                            boolean useProxy)
      throws ApnUnavailableException
  {
    try {
      NotifyRespInd notifyResponse = new NotifyRespInd(PduHeaders.CURRENT_MMS_VERSION,
                                                       transactionId,
                                                       PduHeaders.STATUS_RETRIEVED);

      OutgoingMmsConnection connection = new OutgoingMmsConnection(context, radio.getApnInformation(), new PduComposer(context, notifyResponse).make());
      connection.sendNotificationReceived(usingRadio, useProxy);
    } catch (InvalidHeaderValueException | IOException e) {
      Log.w(TAG, e);
    }
  }

  private void handleDownloadError(MasterSecret masterSecret, long messageId, long threadId,
                                   int downloadStatus, String error, boolean automatic)
  {
    MmsDatabase db = DatabaseFactory.getMmsDatabase(context);

    db.markDownloadState(messageId, downloadStatus);

    if (automatic) {
      db.markIncomingNotificationReceived(threadId);
      MessageNotifier.updateNotification(context, masterSecret, threadId);
    }
//
//    toastHandler.makeToast(error);
  }

  private boolean isCdmaNetwork() {
    return ((TelephonyManager)context
        .getSystemService(Context.TELEPHONY_SERVICE))
        .getPhoneType() == TelephonyManager.PHONE_TYPE_CDMA;
  }

}
