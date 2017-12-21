/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package android.telephony.ims.internal;

import android.annotation.IntDef;
import android.annotation.SystemApi;
import android.os.RemoteException;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.telephony.ims.internal.aidl.IImsSmsListener;
import android.telephony.ims.internal.feature.MmTelFeature;
import android.util.Log;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Base implementation for SMS over IMS.
 *
 * Any service wishing to provide SMS over IMS should extend this class and implement all methods
 * that the service supports.
 * @hide
 */
public class SmsImplBase {
  private static final String LOG_TAG = "SmsImplBase";

  @IntDef({
          SEND_STATUS_OK,
          SEND_STATUS_ERROR,
          SEND_STATUS_ERROR_RETRY,
          SEND_STATUS_ERROR_FALLBACK
      })
  @Retention(RetentionPolicy.SOURCE)
  public @interface SendStatusResult {}
  /**
   * Message was sent successfully.
   */
  public static final int SEND_STATUS_OK = 1;

  /**
   * IMS provider failed to send the message and platform should not retry falling back to sending
   * the message using the radio.
   */
  public static final int SEND_STATUS_ERROR = 2;

  /**
   * IMS provider failed to send the message and platform should retry again after setting TP-RD bit
   * to high.
   */
  public static final int SEND_STATUS_ERROR_RETRY = 3;

  /**
   * IMS provider failed to send the message and platform should retry falling back to sending
   * the message using the radio.
   */
  public static final int SEND_STATUS_ERROR_FALLBACK = 4;

  @IntDef({
          DELIVER_STATUS_OK,
          DELIVER_STATUS_ERROR
      })
  @Retention(RetentionPolicy.SOURCE)
  public @interface DeliverStatusResult {}
  /**
   * Message was delivered successfully.
   */
  public static final int DELIVER_STATUS_OK = 1;

  /**
   * Message was not delivered.
   */
  public static final int DELIVER_STATUS_ERROR = 2;

  @IntDef({
          STATUS_REPORT_STATUS_OK,
          STATUS_REPORT_STATUS_ERROR
      })
  @Retention(RetentionPolicy.SOURCE)
  public @interface StatusReportResult {}

  /**
   * Status Report was set successfully.
   */
  public static final int STATUS_REPORT_STATUS_OK = 1;

  /**
   * Error while setting status report.
   */
  public static final int STATUS_REPORT_STATUS_ERROR = 2;


  // Lock for feature synchronization
  private final Object mLock = new Object();
  private IImsSmsListener mListener;

  /**
   * Registers a listener responsible for handling tasks like delivering messages.
   *
   * @param listener listener to register.
   *
   * @hide
   */
  public final void registerSmsListener(IImsSmsListener listener) {
    synchronized (mLock) {
      mListener = listener;
    }
  }

  /**
   * This method will be triggered by the platform when the user attempts to send an SMS. This
   * method should be implemented by the IMS providers to provide implementation of sending an SMS
   * over IMS.
   *
   * @param smsc the Short Message Service Center address.
   * @param format the format of the message. Valid values are {@link SmsMessage#FORMAT_3GPP} and
   * {@link SmsMessage#FORMAT_3GPP2}.
   * @param messageRef the message reference.
   * @param isRetry whether it is a retry of an already attempted message or not.
   * @param pdu PDUs representing the contents of the message.
   */
  public void sendSms(int messageRef, String format, String smsc, boolean isRetry, byte[] pdu) {
    // Base implementation returns error. Should be overridden.
    try {
      onSendSmsResult(messageRef, SEND_STATUS_ERROR, SmsManager.RESULT_ERROR_GENERIC_FAILURE);
    } catch (RemoteException e) {
      Log.e(LOG_TAG, "Can not send sms: " + e.getMessage());
    }
  }

  /**
   * This method will be triggered by the platform after {@link #onSmsReceived(String, byte[])} has
   * been called to deliver the result to the IMS provider.
   *
   * @param result result of delivering the message. Valid values are defined in
   * {@link DeliverStatusResult}
   * @param messageRef the message reference or -1 of unavailable.
   */
  public void acknowledgeSms(int messageRef, @DeliverStatusResult int result) {

  }

  /**
   * This method will be triggered by the platform after
   * {@link #onSmsStatusReportReceived(int, int, byte[])} has been called to provide the result to
   * the IMS provider.
   *
   * @param result result of delivering the message. Valid values are defined in
   * {@link StatusReportResult}
   * @param messageRef the message reference or -1 of unavailable.
   */
  public void acknowledgeSmsReport(int messageRef, @StatusReportResult int result) {

  }

  /**
   * This method should be triggered by the IMS providers when there is an incoming message. The
   * platform will deliver the message to the messages database and notify the IMS provider of the
   * result by calling {@link #acknowledgeSms(int, int)}.
   *
   * This method must not be called before {@link MmTelFeature#onFeatureReady()} is called.
   *
   * @param format the format of the message. Valid values are {@link SmsMessage#FORMAT_3GPP} and
   * {@link SmsMessage#FORMAT_3GPP2}.
   * @param pdu PDUs representing the contents of the message.
   * @throws IllegalStateException if called before {@link MmTelFeature#onFeatureReady()}
   */
  public final void onSmsReceived(String format, byte[] pdu) throws IllegalStateException {
    synchronized (mLock) {
      if (mListener == null) {
        throw new IllegalStateException("Feature not ready.");
      }
      try {
        mListener.onSmsReceived(format, pdu);
        acknowledgeSms(-1, DELIVER_STATUS_OK);
      } catch (RemoteException e) {
        Log.e(LOG_TAG, "Can not deliver sms: " + e.getMessage());
        acknowledgeSms(-1, DELIVER_STATUS_ERROR);
      }
    }
  }

  /**
   * This method should be triggered by the IMS providers to pass the result of the sent message
   * to the platform.
   *
   * This method must not be called before {@link MmTelFeature#onFeatureReady()} is called.
   *
   * @param messageRef the message reference. Should be between 0 and 255 per TS.123.040
   * @param status result of sending the SMS. Valid values are defined in {@link SendStatusResult}
   * @param reason reason in case status is failure. Valid values are:
   *  {@link SmsManager#RESULT_ERROR_NONE},
   *  {@link SmsManager#RESULT_ERROR_GENERIC_FAILURE},
   *  {@link SmsManager#RESULT_ERROR_RADIO_OFF},
   *  {@link SmsManager#RESULT_ERROR_NULL_PDU},
   *  {@link SmsManager#RESULT_ERROR_NO_SERVICE},
   *  {@link SmsManager#RESULT_ERROR_LIMIT_EXCEEDED},
   *  {@link SmsManager#RESULT_ERROR_SHORT_CODE_NOT_ALLOWED},
   *  {@link SmsManager#RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED}
   * @throws IllegalStateException if called before {@link MmTelFeature#onFeatureReady()}
   * @throws RemoteException if the connection to the framework is not available. If this happens
   *  attempting to send the SMS should be aborted.
   */
  public final void onSendSmsResult(int messageRef, @SendStatusResult int status, int reason)
      throws IllegalStateException, RemoteException {
    synchronized (mLock) {
      if (mListener == null) {
        throw new IllegalStateException("Feature not ready.");
      }
      mListener.onSendSmsResult(messageRef, status, reason);
    }
  }

  /**
   * Sets the status report of the sent message.
   *
   * @param messageRef the message reference.
   * @param format the format of the message. Valid values are {@link SmsMessage#FORMAT_3GPP} and
   * {@link SmsMessage#FORMAT_3GPP2}.
   * @param pdu PDUs representing the content of the status report.
   * @throws IllegalStateException if called before {@link MmTelFeature#onFeatureReady()}
   */
  public final void onSmsStatusReportReceived(int messageRef, String format, byte[] pdu) {
    synchronized (mLock) {
      if (mListener == null) {
        throw new IllegalStateException("Feature not ready.");
      }
      try {
        mListener.onSmsStatusReportReceived(messageRef, format, pdu);
      } catch (RemoteException e) {
        Log.e(LOG_TAG, "Can not process sms status report: " + e.getMessage());
        acknowledgeSmsReport(messageRef, STATUS_REPORT_STATUS_ERROR);
      }
    }
  }

  /**
   * Returns the SMS format. Default is {@link SmsMessage#FORMAT_3GPP} unless overridden by IMS
   * Provider.
   *
   * @return  the format of the message. Valid values are {@link SmsMessage#FORMAT_3GPP} and
   * {@link SmsMessage#FORMAT_3GPP2}.
   */
  public String getSmsFormat() {
    return SmsMessage.FORMAT_3GPP;
  }

}
