package org.commcare.logic;

import android.widget.Toast;

import org.commcare.CommCareApplication;
import org.commcare.activities.FormRecordListActivity;
import org.commcare.network.DataPullRequester;
import org.commcare.network.mocks.LocalFilePullResponseFactory;
import org.commcare.preferences.DeveloperPreferences;
import org.commcare.tasks.DataPullTask;
import org.commcare.tasks.FormRecordCleanupTask;
import org.commcare.tasks.ResultAndError;
import org.commcare.util.CommCarePlatform;
import org.javarosa.core.model.User;
import org.javarosa.core.services.locale.Localization;

import java.io.File;

/**
 * Only used for developer debugging.
 *
 * Load saved form instances manually from a xml payload.
 *
 * @author Phillip Mates (pmates@dimagi.com).
 */
public class ArchivedFormRemoteRestore {
    public static final int CLEANUP_ID = 1;

    public static void pullArchivedFormsFromServer(String remoteUrl,
                                                   final FormRecordListActivity activity,
                                                   final CommCarePlatform platform) {

        requestForms(activity, platform, remoteUrl, CommCareApplication.instance().getDataPullRequester(), false);
    }

    public static void pullArchivedFormsFromFile(String filePath,
                                                 final FormRecordListActivity activity,
                                                 final CommCarePlatform platform) {
        if (filePath != null && !filePath.isEmpty()) {
            File file = new File(filePath);
            if (file.exists()) {
                LocalFilePullResponseFactory.setRequestPayloads(new File[]{file});
                requestForms(activity, platform, "fake-server-that-is-never-used", LocalFilePullResponseFactory.INSTANCE, true);
            } else {
                Toast.makeText(activity, Localization.get("payload.file.not.exist"), Toast.LENGTH_LONG).show();
            }
        } else {
            Toast.makeText(activity, Localization.get("payload.file.not.set"), Toast.LENGTH_LONG).show();
        }
    }

    private static void requestForms(FormRecordListActivity activity, CommCarePlatform platform,
                                     String remoteUrl, DataPullRequester dataPullRequester, boolean blockRemoteKeyManagement) {
        User u = CommCareApplication.instance().getSession().getLoggedInUser();

        // We should go digest auth this user on the server and see whether to pull them down.
        DataPullTask<FormRecordListActivity> pull = new DataPullTask<FormRecordListActivity>(
                u.getUsername(), u.getCachedPwd(), u.getUniqueId(), remoteUrl, activity,
                dataPullRequester, blockRemoteKeyManagement, false) {
            @Override
            protected void deliverResult(FormRecordListActivity receiver, ResultAndError<PullTaskResult> statusAndErrorMessage) {
                PullTaskResult status = statusAndErrorMessage.data;
                switch (status) {
                    case EMPTY_URL:
                        Toast.makeText(receiver, "Server url is not set", Toast.LENGTH_LONG).show();
                        break;
                    case DOWNLOAD_SUCCESS:
                        downloadForms(activity, platform);
                        break;
                    case UNKNOWN_FAILURE:
                        Toast.makeText(receiver, "Failure retrieving or processing data, please try again later...", Toast.LENGTH_LONG).show();
                        break;
                    case CANCELLED:
                        Toast.makeText(receiver, Localization.get("sync.fail.cancelled"), Toast.LENGTH_LONG).show();
                        break;
                    case ENCRYPTION_FAILURE:
                        Toast.makeText(receiver, Localization.get("sync.fail.encryption.failure"), Toast.LENGTH_LONG).show();
                        break;
                    case SESSION_EXPIRE:
                        Toast.makeText(receiver, Localization.get("sync.fail.session.expire"), Toast.LENGTH_LONG).show();
                        break;
                    case RECOVERY_FAILURE:
                        Toast.makeText(receiver, Localization.get("sync.fail.recovery.failure"), Toast.LENGTH_LONG).show();
                    case ACTIONABLE_FAILURE:
                        Toast.makeText(receiver, statusAndErrorMessage.errorMessage, Toast.LENGTH_LONG).show();
                        break;
                    case TOKEN_UNAVAILABLE:
                        Toast.makeText(receiver, Localization.get("sync.fail.token.unavailable"), Toast.LENGTH_LONG).show();
                        break;
                    case TOKEN_DENIED:
                        Toast.makeText(receiver, Localization.get("sync.fail.token.denied"), Toast.LENGTH_LONG).show();
                        break;
                    case AUTH_FAILED:
                        Toast.makeText(receiver, "Authentication failure. Please logout and resync with the server and try again.", Toast.LENGTH_LONG).show();
                        break;
                    case BAD_DATA:
                    case BAD_DATA_REQUIRES_INTERVENTION:
                        Toast.makeText(receiver, "Bad data from server. Please talk with your supervisor.", Toast.LENGTH_LONG).show();
                        break;
                    case CONNECTION_TIMEOUT:
                        Toast.makeText(receiver, "The server took too long to generate a response. Please try again later, and ask your supervisor if the problem persists.", Toast.LENGTH_LONG).show();
                        break;
                    case SERVER_ERROR:
                        Toast.makeText(receiver, "The server had an error processing your data. Please try again later, and contact CommCare Support if the problem persists.", Toast.LENGTH_LONG).show();
                        break;
                    case RATE_LIMITED_SERVER_ERROR:
                        Toast.makeText(receiver, "Our servers are unavailable at this time. Please try again later, and contact CommCare Support if the problem persists.", Toast.LENGTH_LONG).show();
                        break;
                    case UNREACHABLE_HOST:
                        Toast.makeText(receiver, "Couldn't contact server, please check your network connection and try again.", Toast.LENGTH_LONG).show();
                        break;
                    case CAPTIVE_PORTAL:
                        Toast.makeText(receiver, Localization.get("connection.captive_portal.action"), Toast.LENGTH_LONG).show();
                        break;
                }
            }

            @Override
            protected void deliverUpdate(FormRecordListActivity receiver, Integer... update) {
                switch (update[0]) {
                    case DataPullTask.PROGRESS_AUTHED:
                        receiver.updateProgress("Authed with server, downloading forms" +
                                        (update[1] == 0 ? "" : " (" + update[1] + ")"),
                                DataPullTask.DATA_PULL_TASK_ID);
                        break;
                }
            }

            @Override
            protected void deliverError(FormRecordListActivity receiver, Exception e) {
                Toast.makeText(receiver, Localization.get("activity.task.error.generic", new String[]{e.getMessage()}), Toast.LENGTH_LONG).show();
            }
        };
        pull.connect(activity);
        pull.executeParallel();
    }

    private static void downloadForms(FormRecordListActivity activity, CommCarePlatform platform) {
        FormRecordCleanupTask<FormRecordListActivity> task =
                new FormRecordCleanupTask<FormRecordListActivity>(activity, platform, CLEANUP_ID,
                        DeveloperPreferences.formLoadPayloadStatus()) {

                    @Override
                    protected void deliverResult(FormRecordListActivity receiver, Integer result) {
                        receiver.refreshView();
                    }

                    @Override
                    protected void deliverUpdate(FormRecordListActivity receiver, Integer... values) {
                        if (values[0] < 0) {
                            if (values[0] == FormRecordCleanupTask.STATUS_CLEANUP) {
                                receiver.updateProgress("Forms Processed. "
                                        + "Cleaning up form records...", CLEANUP_ID);
                            }
                        } else {
                            receiver.updateProgress("Forms downloaded. Processing "
                                    + values[0] + " of " + values[1] + "...", CLEANUP_ID);
                        }

                    }

                    @Override
                    protected void deliverError(FormRecordListActivity receiver, Exception e) {
                        Toast.makeText(receiver, Localization.get("activity.task.error.generic", new String[]{e.getMessage()}), Toast.LENGTH_LONG).show();
                    }
                };
        task.connect(activity);
        task.executeParallel();
    }
}
