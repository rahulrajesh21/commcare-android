package org.commcare.activities;

import android.Manifest;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.WifiP2pManager.ActionListener;
import android.net.wifi.p2p.WifiP2pManager.Channel;
import android.net.wifi.p2p.WifiP2pManager.PeerListListener;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.MutableLiveData;


import org.commcare.CommCareApplication;
import org.commcare.android.adapters.WiFiDirectUIController;
import org.commcare.android.database.user.models.FormRecord;
import org.commcare.dalvik.R;
import org.commcare.fragments.DeviceDetailFragment;
import org.commcare.fragments.DeviceListFragment;
import org.commcare.fragments.DeviceListFragment.DeviceActionListener;
import org.commcare.fragments.FileServerFragment;
import org.commcare.fragments.FileServerFragment.FileServerListener;
import org.commcare.fragments.WiFiDirectManagementFragment;
import org.commcare.fragments.WiFiDirectManagementFragment.WifiDirectManagerListener;
import org.commcare.interfaces.CommCareActivityUIController;
import org.commcare.interfaces.WithUIController;
import org.commcare.models.database.SqlStorage;
import org.commcare.preferences.AdvancedActionsPreferences;
import org.commcare.preferences.ServerUrls;
import org.commcare.services.WiFiDirectBroadcastReceiver;
import org.commcare.tasks.FormRecordToFileTask;
import org.commcare.tasks.FormTransferTask;
import org.commcare.tasks.SendTask;
import org.commcare.tasks.UnZipTaskListener;
import org.commcare.tasks.UnzipTask;
import org.commcare.tasks.WipeTask;
import org.commcare.tasks.ZipTask;
import org.commcare.tasks.templates.CommCareTaskConnector;
import org.commcare.util.LogTypes;
import org.commcare.utils.FileUtil;
import org.commcare.utils.FormUploadResult;
import org.commcare.utils.StorageUtils;
import org.commcare.views.dialogs.CustomProgressDialog;
import org.commcare.views.dialogs.StandardAlertDialog;
import org.javarosa.core.services.Logger;
import org.javarosa.core.services.locale.Localization;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Vector;

import static org.commcare.activities.CommCareWiFiDirectActivity.PermissionAction.INFORM_FEATURE_DEGRADATION;
import static org.commcare.activities.CommCareWiFiDirectActivity.PermissionAction.REQUEST_PERMISSION;
import static org.commcare.activities.CommCareWiFiDirectActivity.PermissionAction.SHOW_PERMISSION_RATIONALE;

/**
 * An activity that uses WiFi Direct APIs to discover and connect with available
 * devices. WiFi Direct APIs are asynchronous and rely on callback mechanism
 * using interfaces to notify the application of operation success or failure.
 * The application should also register a BroadcastReceiver for notification of
 * WiFi state related events.
 */
public class CommCareWiFiDirectActivity
        extends SessionAwareCommCareActivity<CommCareWiFiDirectActivity>
        implements DeviceActionListener, FileServerListener, WifiDirectManagerListener, WithUIController, UnZipTaskListener {

    private static final String TAG = LogTypes.TYPE_WIFI_DIRECT;

    private WifiP2pManager mManager;
    private Channel mChannel;
    private WiFiDirectBroadcastReceiver mReceiver;

    private IntentFilter mIntentFilter;

    public enum wdState {send, receive, submit}

    private WiFiDirectUIController uiController;

    private static String baseDirectory;
    private static String toBeTransferredDirectory;
    private static String zipFilePath;
    private static String receiveDirectory;
    private static String receiveZipDirectory;
    private static String toBeSubmittedDirectory;

    private TextView myStatusText;
    private TextView formCountText;
    private TextView stateHeaderText;
    private TextView stateStatusText;

    private static final int FILE_SERVER_TASK_ID = 129123;

    private wdState mState = wdState.send;

    private FormRecord[] cachedRecords;

    private final int NEARBY_WIFI_PERM_REQUEST = 2;
    private final String SHOW_PERMISSION_DIALOG = "show-permission-dialog";
    /**
     * Responsible for showing a dialog to the user related with permissions acquisition and its
     * restoration in case of configuration changes, it can take:
     * - NO_ACTION - No action
     * - REJECT_FEATURE - Inform the user about feature unavailability when the permission was not
     * granted
     * - SHOW_PERMISSION_RATIONALE - Show the reason for needing the permission
     * - REQUEST_PERMISSION - Request permission to the user
     */
    private MutableLiveData<PermissionAction> showingPermissionDialog = new MutableLiveData<>(PermissionAction.NO_ACTION);

    public enum PermissionAction {
        NO_ACTION,
        INFORM_FEATURE_DEGRADATION,
        SHOW_PERMISSION_RATIONALE,
        REQUEST_PERMISSION;
    }

    {
        showingPermissionDialog.observe(this, value -> {
            if (value == INFORM_FEATURE_DEGRADATION) {
                informUserAboutFeatureUnavailability();
            } else if (value == SHOW_PERMISSION_RATIONALE) {
                showNearbyWiFiPermissionRationale();
            } else if (value == REQUEST_PERMISSION) {
                requestNearbyWifiPermission();
            }
        });
    }

    @Override
    public void onCreateSessionSafe(Bundle savedInstanceState) {
        super.onCreateSessionSafe(savedInstanceState);

        setContentView(R.layout.wifi_direct_main);

        myStatusText = this.findViewById(R.id.my_status_text);
        formCountText = this.findViewById(R.id.form_count_text);
        stateStatusText = this.findViewById(R.id.wifi_state_status);
        stateHeaderText = this.findViewById(R.id.wifi_state_header);

        String baseDir = this.getFilesDir().getAbsolutePath();

        baseDirectory = baseDir + "/" + Localization.get("wifi.direct.base.folder");
        // these are the two folders where form record folders can live.
        toBeTransferredDirectory = baseDirectory + "/transfer";
        toBeSubmittedDirectory = baseDirectory + "/submit";
        // these are temporary paths for storing received and unzipped forms
        zipFilePath = baseDirectory + "/formRecordZip.zip";
        receiveDirectory = baseDirectory + "/receive";
        receiveZipDirectory = receiveDirectory + "/zipDest";

        mManager = (WifiP2pManager)getSystemService(Context.WIFI_P2P_SERVICE);
        mChannel = mManager.initialize(this, getMainLooper(), null);
        mIntentFilter = new IntentFilter();
        mIntentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        mIntentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        mIntentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        mIntentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        if (savedInstanceState == null) {
            showChangeStateDialog();
        }
        uiController.setupUI();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkMissingNearbyWifiPermission();
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(SHOW_PERMISSION_DIALOG, showingPermissionDialog.getValue().ordinal());
    }

    @Override
    protected void onRestoreInstanceState(@NonNull Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        if (savedInstanceState != null) {
            showingPermissionDialog.setValue(
                    PermissionAction.values()[savedInstanceState.getInt(SHOW_PERMISSION_DIALOG)]);
        }
    }

    @Override
    public void initUIController() {
        uiController = new WiFiDirectUIController(this);
    }

    @Override
    public CommCareActivityUIController getUIController() {
        return this.uiController;
    }

    public void showChangeStateDialog() {
        showDialog(localize("wifi.direct.change.state.title").toString(),
                localize("wifi.direct.change.state.text").toString());
    }

    /**
     * register the broadcast receiver
     */
    @Override
    public void onResumeSessionSafe() {
        final WiFiDirectManagementFragment fragment = (WiFiDirectManagementFragment)getSupportFragmentManager()
                .findFragmentById(R.id.wifi_manager_fragment);

        mReceiver = new WiFiDirectBroadcastReceiver(mManager, fragment);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mReceiver, mIntentFilter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(mReceiver, mIntentFilter);
        }

        fragment.startReceiver(mManager, mChannel);

        changeState();
    }

    /**
     * unregister the broadcast receiver
     */
    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(mReceiver);
    }

    private void hostGroup() {
        Logger.log(TAG, "Hosting Wi-fi direct group");

        final FileServerFragment fsFragment = (FileServerFragment)getSupportFragmentManager()
                .findFragmentById(R.id.file_server_fragment);
        fsFragment.startServer(receiveZipDirectory);

        WiFiDirectManagementFragment fragment = (WiFiDirectManagementFragment)getSupportFragmentManager()
                .findFragmentById(R.id.wifi_manager_fragment);

        if (!fragment.isWifiP2pEnabled()) {
            Logger.log(TAG, "returning because Wi-fi direct is not available");
            Toast.makeText(CommCareWiFiDirectActivity.this, localize("wifi.direct.wifi.direct.off"),
                    Toast.LENGTH_SHORT).show();
            return;
        }

        mManager.createGroup(mChannel, fragment);
    }

    private void changeState() {
        updateStatusText();
        uiController.refreshView();
    }

    private void showDialog(String title, String message) {
        StandardAlertDialog d = new StandardAlertDialog(title, message);
        DialogInterface.OnClickListener listener = (dialog, which) -> {
            switch (which) {
                case AlertDialog.BUTTON_POSITIVE:
                    beSubmitter();
                    break;
                case AlertDialog.BUTTON_NEUTRAL:
                    beReceiver();
                    break;
                case AlertDialog.BUTTON_NEGATIVE:
                    beSender();
                    break;
            }
            dialog.dismiss();
        };
        d.setNeutralButton(localize("wifi.direct.receive.forms"), listener);
        d.setNegativeButton(localize("wifi.direct.transfer.forms"), listener, true);
        d.setPositiveButton(localize("wifi.direct.submit.forms"), listener);
        showAlertDialog(d);
    }

    private void beSender() {

        myStatusText.setText(localize("wifi.direct.enter.send.mode"));

        WiFiDirectManagementFragment wifiFragment = (WiFiDirectManagementFragment)getSupportFragmentManager()
                .findFragmentById(R.id.wifi_manager_fragment);

        DeviceListFragment fragmentList = (DeviceListFragment)getSupportFragmentManager()
                .findFragmentById(R.id.frag_list);

        DeviceDetailFragment fragmentDetails = (DeviceDetailFragment)getSupportFragmentManager()
                .findFragmentById(R.id.frag_detail);

        FileServerFragment fsFragment = (FileServerFragment)getSupportFragmentManager()
                .findFragmentById(R.id.file_server_fragment);

        FragmentTransaction tr = getSupportFragmentManager().beginTransaction();

        tr.show(wifiFragment);
        tr.show(fragmentList);
        tr.show(fragmentDetails);
        tr.hide(fsFragment);
        tr.commit();

        wifiFragment.setIsHost(false);
        wifiFragment.resetConnectionGroup();


        Logger.log(TAG, "Device designated as sender");
        resetData();
        mState = wdState.send;
        changeState();
    }

    private void beReceiver() {
        myStatusText.setText(localize("wifi.direct.enter.receive.mode"));

        WiFiDirectManagementFragment wifiFragment = (WiFiDirectManagementFragment)getSupportFragmentManager()
                .findFragmentById(R.id.wifi_manager_fragment);

        DeviceListFragment fragmentList = (DeviceListFragment)getSupportFragmentManager()
                .findFragmentById(R.id.frag_list);

        DeviceDetailFragment fragmentDetails = (DeviceDetailFragment)getSupportFragmentManager()
                .findFragmentById(R.id.frag_detail);

        FileServerFragment fsFragment = (FileServerFragment)getSupportFragmentManager()
                .findFragmentById(R.id.file_server_fragment);

        FragmentTransaction tr = getSupportFragmentManager().beginTransaction();

        tr.show(wifiFragment);
        tr.show(fragmentList);
        tr.show(fragmentDetails);
        tr.show(fsFragment);
        tr.commit();

        wifiFragment.setIsHost(true);
        wifiFragment.resetConnectionGroup();

        Logger.log(TAG, "Device designated as receiver");
        resetData();
        hostGroup();

        mState = wdState.receive;
        changeState();
    }

    private void beSubmitter() {
        unzipFilesHelper();
        myStatusText.setText(localize("wifi.direct.enter.submit.mode"));

        WiFiDirectManagementFragment wifiFragment = (WiFiDirectManagementFragment)getSupportFragmentManager()
                .findFragmentById(R.id.wifi_manager_fragment);

        DeviceListFragment fragmentList = (DeviceListFragment)getSupportFragmentManager()
                .findFragmentById(R.id.frag_list);

        DeviceDetailFragment fragmentDetails = (DeviceDetailFragment)getSupportFragmentManager()
                .findFragmentById(R.id.frag_detail);

        FileServerFragment fsFragment = (FileServerFragment)getSupportFragmentManager()
                .findFragmentById(R.id.file_server_fragment);

        FragmentTransaction tr = getSupportFragmentManager().beginTransaction();

        tr.hide(fsFragment);
        tr.hide(wifiFragment);
        tr.hide(fragmentList);
        tr.hide(fragmentDetails);
        tr.commit();

        wifiFragment.setIsHost(false);
        wifiFragment.resetConnectionGroup();

        Logger.log(TAG, "Device designated as submitter");
        mState = wdState.submit;
        changeState();
    }

    private void cleanPostSend() {

        Logger.log(TAG, "cleaning forms after successful Wi-fi direct transfer");

        // remove Forms from CC

        WipeTask mWipeTask = new WipeTask(getApplicationContext(), this.cachedRecords) {
            @Override
            protected void deliverResult(CommCareWiFiDirectActivity receiver,
                                         Boolean result) {
                receiver.onCleanSuccessful();
            }

            @Override
            protected void deliverUpdate(CommCareWiFiDirectActivity receiver, String... update) {
                receiver.updateProgress(update[0], WipeTask.WIPE_TASK_ID);
                receiver.myStatusText.setText(update[0]);
            }

            @Override
            protected void deliverError(CommCareWiFiDirectActivity receiver, Exception e) {
                receiver.myStatusText.setText(localize("wifi.direct.error.wiping.forms", e.getMessage()));
                receiver.transplantStyle(myStatusText, R.layout.template_text_notification_problem);
            }
        };

        mWipeTask.connect(CommCareWiFiDirectActivity.this);
        mWipeTask.execute();

        FileUtil.deleteFileOrDir(new File(toBeTransferredDirectory));
        FileUtil.deleteFileOrDir(new File(zipFilePath));

        Logger.log(TAG, "Deleting dirs " + toBeTransferredDirectory + " and " + zipFilePath);

        this.cachedRecords = null;

    }

    private void onCleanSuccessful() {
        Logger.log(TAG, "clean successful");
        updateStatusText();
    }

    public void submitFiles() {

        Logger.log(TAG, "submitting forms in Wi-fi direct activity");

        unzipFilesHelper();

        final String url = this.getString(R.string.PostURL);

        File receiveFolder = new File(toBeSubmittedDirectory);

        if (!receiveFolder.isDirectory() || !receiveFolder.exists()) {
            myStatusText.setText(Localization.get("wifi.direct.submit.missing", new String[]{receiveFolder.getPath()}));
        }

        File[] files = receiveFolder.listFiles();

        if (files == null) {
            myStatusText.setText(localize("wifi.direct.error.no.forms"));
            transplantStyle(myStatusText, R.layout.template_text_notification_problem);
            return;
        }

        final int formsOnSD = files.length;

        //if there're no forms to submit, just return
        if (formsOnSD == 0) {
            myStatusText.setText(Localization.get("bulk.form.no.unsynced"));
            transplantStyle(myStatusText, R.layout.template_text_notification_problem);
            return;
        }

        SharedPreferences settings = CommCareApplication.instance().getCurrentApp().getAppPreferences();
        SendTask<CommCareWiFiDirectActivity> mSendTask = new SendTask<CommCareWiFiDirectActivity>(
                settings.getString(ServerUrls.PREFS_SUBMISSION_URL_KEY, url),
                receiveFolder) {

            @Override
            protected void deliverResult(CommCareWiFiDirectActivity receiver, Boolean result) {
                if (result == Boolean.TRUE) {
                    Intent i = new Intent(getIntent());
                    i.putExtra(AdvancedActionsPreferences.KEY_NUMBER_DUMPED, formsOnSD);
                    receiver.setResult(BULK_SEND_ID, i);
                    Logger.log(TAG, "Sucessfully dumped " + formsOnSD);
                    receiver.finish();
                } else {
                    //assume that we've already set the error message, but make it look scary
                    receiver.transplantStyle(myStatusText, R.layout.template_text_notification_problem);
                }
            }

            @Override
            protected void deliverUpdate(CommCareWiFiDirectActivity receiver, String... update) {
                receiver.updateProgress(update[0], BULK_SEND_ID);
                receiver.myStatusText.setText(update[0]);
            }

            @Override
            protected void deliverError(CommCareWiFiDirectActivity receiver, Exception e) {
                Logger.log(TAG, "Error submitting forms in wi-fi direct with exception" + e.getMessage());
                receiver.myStatusText.setText(Localization.get("bulk.form.error", new String[]{e.getMessage()}));
                receiver.transplantStyle(myStatusText, R.layout.template_text_notification_problem);
            }
        };
        mSendTask.connect(CommCareWiFiDirectActivity.this);
        mSendTask.execute();
    }

    private boolean unzipFilesHelper() {
        File receiveZipDir = new File(receiveDirectory);
        if (!receiveZipDir.exists() || !(receiveZipDir.isDirectory())) {
            return false;
        }

        File[] zipDirContents = receiveZipDir.listFiles();
        if (zipDirContents.length < 1) {
            return false;
        }

        myStatusText.setText(localize("wifi.direct.zip.unzipping"));

        for (File zipDirContent : zipDirContents) {
            unzipFiles(zipDirContent.getAbsolutePath());
        }

        return true;
    }

    private void unzipFiles(String fn) {
        Logger.log(TAG, "Unzipping files in Wi-fi direct");
        UnzipTask mUnzipTask = new UnzipTask();
        mUnzipTask.connect(((CommCareTaskConnector)this));
        Logger.log(TAG, "executing task with: " + fn + " , " + toBeSubmittedDirectory);
        mUnzipTask.execute(fn, toBeSubmittedDirectory);
    }

    @Override
    public void OnUnzipSuccessful(Integer result) {
        if (result > 0) {
            Logger.log(TAG, "Successfully unzipped " + result.toString() + " files.");
            myStatusText.setText(localize("wifi.direct.receive.successful", result.toString()));
            if (!FileUtil.deleteFileOrDir(new File(receiveDirectory))) {
                Log.d(TAG, "source zip not succesfully deleted");
            }
            updateStatusText();
        } else {
            //assume that we've already set the error message, but make it look scary
            transplantStyle(myStatusText, R.layout.template_text_notification_problem);
        }
    }

    @Override
    public void OnUnzipFailure(String cause) {
        if (!TextUtils.isEmpty(cause)) {
            myStatusText.setText(Localization.get("mult.install.error", new String[]{cause}));
        }
        transplantStyle(myStatusText, R.layout.template_text_notification_problem);
    }

    @Override
    public void updateUnzipProgress(String update, int taskId) {
        updateProgress(update, taskId);
        myStatusText.setText(update);
    }

    /* if successful, broadcasts WIFI_P2P_Peers_CHANGED_ACTION intent with list of peers
     * received in WiFiDirectBroadcastReceiver class
     */
    public void discoverPeers() {
        Logger.log(TAG, "Discovering Wi-fi direct peers");

        WiFiDirectManagementFragment fragment = (WiFiDirectManagementFragment)getSupportFragmentManager()
                .findFragmentById(R.id.wifi_manager_fragment);

        if (!fragment.isWifiP2pEnabled()) {
            Toast.makeText(CommCareWiFiDirectActivity.this, localize("wifi.direct.wifi.direct.off"),
                    Toast.LENGTH_SHORT).show();
            return;
        }

        final DeviceListFragment dlFragment = (DeviceListFragment)getSupportFragmentManager()
                .findFragmentById(R.id.frag_list);
        dlFragment.onInitiateDiscovery();

        mManager.discoverPeers(mChannel, new WifiP2pManager.ActionListener() {

            @Override
            public void onSuccess() {
                Toast.makeText(CommCareWiFiDirectActivity.this,
                        localize("wifi.direct.discovery.start"),
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onFailure(int reasonCode) {

                Logger.log(TAG, "Discovery of Wi-fi peers failed");

                if (reasonCode == 0 || reasonCode == 2) {
                    Toast.makeText(CommCareWiFiDirectActivity.this,
                            localize("wifi.direct.discovery.failed.generic"),
                            Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(CommCareWiFiDirectActivity.this,
                            localize("wifi.direct.discovery.failed.specific", "" + reasonCode),
                            Toast.LENGTH_SHORT).show();
                }

            }
        });
    }

    @Override
    public void resetData() {
        DeviceListFragment fragmentList = (DeviceListFragment)getSupportFragmentManager()
                .findFragmentById(R.id.frag_list);
        DeviceDetailFragment fragmentDetails = (DeviceDetailFragment)getSupportFragmentManager()
                .findFragmentById(R.id.frag_detail);
        if (fragmentList != null) {
            fragmentList.clearPeers();
        }
        if (fragmentDetails != null) {
            fragmentDetails.resetViews();
        }
    }

    @Override
    public void connect(WifiP2pConfig config) {
        Logger.log(TAG, "connecting to wi-fi peer");

        mManager.connect(mChannel, config, new ActionListener() {

            @Override
            public void onSuccess() {
                myStatusText.setText(localize("wifi.direct.connect.success"));
            }

            @Override
            public void onFailure(int reason) {
                Logger.log(TAG, "Connection to peer failed");
                Toast.makeText(CommCareWiFiDirectActivity.this,
                        localize("wifi.direct.connect.failed"),
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static void deleteIfExists(String filePath) {
        File toDelete = new File(filePath);
        if (toDelete.exists()) {
            toDelete.delete();
        }
    }

    public void prepareFileTransfer() {
        Logger.log(TAG, "Preparing File Transfer");

        CommCareWiFiDirectActivity.deleteIfExists(zipFilePath);

        final WiFiDirectManagementFragment fragment = (WiFiDirectManagementFragment)getSupportFragmentManager()
                .findFragmentById(R.id.wifi_manager_fragment);

        if (!fragment.getDeviceConnected()) {
            myStatusText.setText(localize("wifi.direct.no.group"));
            return;
        }
        moveFormRecordsToFiles();
    }

    private void moveReceivedFiles() {
        File receiveFolder = new File(toBeSubmittedDirectory);
        if (!receiveFolder.isDirectory() || !receiveFolder.exists()) {
            // we haven't received any transfers, just return.
            return;
        }
        // We've received transferred forms. Move them into the to be zipped directory.
        File[] originFiles = receiveFolder.listFiles();
        File[] destinationFiles = new File[originFiles.length];
        for (int i = 0; i < originFiles.length; i++) {
            destinationFiles[i] = new File(toBeTransferredDirectory, originFiles[i].getName());
        }
        try {
            Log.d(TAG, "We have " + originFiles.length + " toBeSubmitted files to transfer");
            for (int i = 0; i < originFiles.length; i++) {
                FileUtil.copyFileDeep(originFiles[i], destinationFiles[i]);
                Log.d(TAG, "Transferred " + originFiles[i] + " to " + destinationFiles[i]);
            }
        } catch (IOException e) {
            // if we catch an error, delete all the new files we've copied over
            for (File destinationFile : destinationFiles) {
                if (destinationFile.exists()) {
                    FileUtil.deleteFileOrDir(destinationFile);
                }
            }
        }
        // Delete all the to be submitted files, we have them copied for transfer.
        for (File originFile : originFiles) {
            if (originFile.exists()) {
                FileUtil.deleteFileOrDir(originFile);
            }
        }
    }

    private void onZipSuccesful() {
        myStatusText.setText(localize("wifi.direct.zip.successful"));
        updateStatusText();
        sendFiles();
    }

    private void onZipError() {
        FileUtil.deleteFileOrDir(new File(toBeTransferredDirectory));
        Log.d(CommCareWiFiDirectActivity.TAG, "Zip unsuccessful");
    }


    private void onRecordPullCompleted(Pair<FormUploadResult, FormRecord[]> result, CommCareWiFiDirectActivity receiver) {
        myStatusText.setText(localize("wifi.direct.pull.successful"));
        if (result != null) {
            if (result.first != FormUploadResult.FULL_SUCCESS) {
                // if we had files but they failed, we should error and block
                receiver.myStatusText.setText(localize("wifi.direct.pull.unsuccessful",
                        "Problem transferring forms to file system"));
                receiver.transplantStyle(receiver.myStatusText,
                        R.layout.template_text_notification_problem);
                return;
            }
            this.cachedRecords = result.second;
        }
        updateStatusText();
        moveReceivedFiles();
        zipFiles();
    }

    private void moveFormRecordsToFiles() {
        Logger.log(TAG, "Getting records from storage");
        FormRecordToFileTask formRecordToFileTask = new FormRecordToFileTask(this, toBeTransferredDirectory) {
            @Override
            protected void deliverResult(CommCareWiFiDirectActivity receiver, Pair<FormUploadResult, FormRecord[]> result) {
                receiver.onRecordPullCompleted(result, receiver);
            }

            @Override
            protected void deliverUpdate(CommCareWiFiDirectActivity receiver, String... update) {
                receiver.updateProgress(update[0], taskId);
                receiver.myStatusText.setText(update[0]);
            }

            @Override
            protected void deliverError(CommCareWiFiDirectActivity receiver, Exception e) {
                receiver.myStatusText.setText(localize("wifi.direct.pull.unsuccessful", e.getMessage()));
                receiver.transplantStyle(receiver.myStatusText, R.layout.template_text_notification_problem);
            }
        };
        formRecordToFileTask.connect(this);
        formRecordToFileTask.execute();
    }

    private void zipFiles() {
        Logger.log(TAG, "Zipping Files");
        ZipTask mZipTask = new ZipTask(toBeTransferredDirectory, zipFilePath) {
            @Override
            protected void deliverUpdate(CommCareWiFiDirectActivity receiver, String... update) {
                receiver.updateProgress(update[0], taskId);
                receiver.myStatusText.setText(update[0]);
            }

            @Override
            protected void deliverError(CommCareWiFiDirectActivity receiver, Exception e) {
                receiver.myStatusText.setText(localize("wifi.direct.zip.unsuccessful", e.getMessage()));
                receiver.transplantStyle(receiver.myStatusText, R.layout.template_text_notification_problem);
            }

            @Override
            protected void deliverResult(CommCareWiFiDirectActivity receiver, ZipTaskResult result) {
                if (result == ZipTaskResult.Success) {
                    receiver.onZipSuccesful();
                } else {
                    receiver.onZipError();
                    receiver.transplantStyle(receiver.myStatusText, R.layout.template_text_notification_problem);
                }
            }
        };
        mZipTask.connect(CommCareWiFiDirectActivity.this);
        mZipTask.execute();
    }

    private void sendFiles() {
        Logger.log(TAG, "Sending Files via Wi-fi Direct");
        TextView statusText = myStatusText;
        statusText.setText(localize("wifi.direct.send.forms"));
        Log.d(CommCareWiFiDirectActivity.TAG, "Starting form transfer task");

        final WiFiDirectManagementFragment fragment = (WiFiDirectManagementFragment)getSupportFragmentManager()
                .findFragmentById(R.id.wifi_manager_fragment);

        String address = fragment.getHostAddress();

        FormTransferTask mTransferTask = new FormTransferTask(address, zipFilePath, 8988) {

            @Override
            protected void deliverResult(CommCareWiFiDirectActivity receiver,
                                         Boolean result) {
                if (result == Boolean.TRUE) {
                    receiver.onSendSuccessful();
                } else {
                    receiver.onSendFail();
                    receiver.transplantStyle(receiver.myStatusText, R.layout.template_text_notification_problem);
                }
            }

            @Override
            protected void deliverUpdate(CommCareWiFiDirectActivity receiver,
                                         String... update) {
                receiver.updateProgress(update[0], taskId);
                receiver.myStatusText.setText(update[0]);
            }

            @Override
            protected void deliverError(CommCareWiFiDirectActivity receiver,
                                        Exception e) {
                receiver.myStatusText.setText(localize("wifi.direct.send.unsuccessful"));
                receiver.transplantStyle(receiver.myStatusText, R.layout.template_text_notification_problem);

            }

        };

        mTransferTask.connect(CommCareWiFiDirectActivity.this);
        mTransferTask.execute();

        Log.d(CommCareWiFiDirectActivity.TAG, "Task started");
    }

    private void onSendSuccessful() {
        Logger.log(TAG, "File Send Successful");
        Toast.makeText(CommCareWiFiDirectActivity.this, localize("wifi.direct.send.successful"),
                Toast.LENGTH_SHORT).show();

        updateStatusText();
        myStatusText.setText(localize("wifi.direct.send.successful"));
        this.cleanPostSend();
    }

    private void onSendFail() {
        Logger.log(TAG, "Error Sending Files");
    }

    private void updateStatusText() {
        SqlStorage<FormRecord> storage = CommCareApplication.instance().getUserStorage(FormRecord.class);
        Vector<Integer> ids = StorageUtils.getUnsentOrUnprocessedFormIdsForCurrentApp(storage);

        int numUnsyncedForms = ids.size();
        int numUnsubmittedForms;

        File wDirectory = new File(toBeSubmittedDirectory);

        if (!wDirectory.exists() || !wDirectory.isDirectory()) {
            numUnsubmittedForms = 0;
        } else {
            numUnsubmittedForms = wDirectory.listFiles().length;
        }

        if (mState.equals(wdState.send)) {
            stateHeaderText.setText(localize("wifi.direct.status.transfer.header"));
            formCountText.setText(localize("wifi.direct.status.transfer.count",
                    new String[]{"" + numUnsyncedForms, "" + numUnsubmittedForms}));
            stateStatusText.setText(localize("wifi.direct.status.transfer.message"));
        } else if (mState.equals(wdState.receive)) {
            stateHeaderText.setText(localize("wifi.direct.status.receive.header"));
            formCountText.setText(localize("wifi.direct.status.receive.count", "" + numUnsubmittedForms));
            stateStatusText.setText(localize("wifi.direct.status.receive.message"));
        } else {
            stateHeaderText.setText(localize("wifi.direct.status.submit.header"));
            formCountText.setText(localize("wifi.direct.status.submit.count", "" + numUnsubmittedForms));
            stateStatusText.setText(localize("wifi.direct.status.submit.message"));
        }
    }

    public static void copyFile(InputStream inputStream, OutputStream out) throws IOException {
        Logger.log(TAG, "File server copying file");
        if (inputStream == null) {
            Logger.log(TAG, "Input stream null");
            throw new IOException("Got null input stream");
        }
        byte buf[] = new byte[1024];
        int len;
        try {
            while ((len = inputStream.read(buf)) != -1) {
                out.write(buf, 0, len);
            }
            out.close();
            inputStream.close();
        } catch (IOException e) {
            Logger.exception("Copy in File Server failed with exception " + e, e);
            throw e;
        }
        Logger.log(TAG, "Copy in File Server successful");
    }

    @Override
    public void onFormsCopied(String result) {
        Logger.log(TAG, "Copied files successfully to path " + result);
        this.unzipFiles(result);
    }

    @Override
    public void updatePeers() {
        Logger.log(TAG, "Wi-Fi direct peers updating");
        mManager.requestPeers(mChannel, (PeerListListener)this.getSupportFragmentManager()
                .findFragmentById(R.id.frag_list));

    }

    @Override
    public void updateDeviceStatus(WifiP2pDevice mDevice) {
        Logger.log(TAG, "Wi-fi direct status updating");
        DeviceListFragment fragment = (DeviceListFragment)this.getSupportFragmentManager()
                .findFragmentById(R.id.frag_list);

        fragment.updateThisDevice(mDevice);

    }

    /**
     * Implementation of generateProgressDialog() for DialogController -- other methods
     * handled entirely in CommCareActivity
     */
    @Override
    public CustomProgressDialog generateProgressDialog(int taskId) {
        String title, message;
        switch (taskId) {
            case ZipTask.ZIP_TASK_ID:
                title = localize("wifi.direct.zip.task.title").toString();
                message = localize("wifi.direct.zip.task.message").toString();
                break;
            case UnzipTask.UNZIP_TASK_ID:
                title = localize("wifi.direct.unzip.task.title").toString();
                message = localize("wifi.direct.unzip.task.message").toString();
                break;
            case SendTask.BULK_SEND_ID:
                title = localize("wifi.direct.submit.task.title").toString();
                message = localize("wifi.direct.submit.task.message").toString();
                break;
            case FormTransferTask.BULK_TRANSFER_ID:
                title = localize("wifi.direct.transfer.task.title").toString();
                message = localize("wifi.direct.transfer.task.message").toString();
                break;
            case FILE_SERVER_TASK_ID:
                title = localize("wifi.direct.receive.task.title").toString();
                message = localize("wifi.direct.receive.task.message").toString();
                break;
            case WipeTask.WIPE_TASK_ID:
                title = localize("wifi.direct.wipe.task.title").toString();
                message = localize("wifi.direct.wipe.task.message").toString();
                break;
            case FormRecordToFileTask.PULL_TASK_ID:
                title = localize("wifi.direct.pull.task.title").toString();
                message = localize("wifi.direct.pull.task.message").toString();
                break;
            default:
                Log.w(TAG, "taskId passed to generateProgressDialog does not match "
                        + "any valid possibilities in CommCareWifiDirectActivity");
                return null;
        }
        return CustomProgressDialog.newInstance(title, message, taskId);
    }

    public wdState getWifiDirectState() {
        return mState;
    }

    private void checkMissingNearbyWifiPermission() {
        if (isMissingNearbyWifiPermission()) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                    Manifest.permission.NEARBY_WIFI_DEVICES)) {
                showingPermissionDialog.setValue(SHOW_PERMISSION_RATIONALE);
            } else {
                showingPermissionDialog.setValue(REQUEST_PERMISSION);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode) {
            case NEARBY_WIFI_PERM_REQUEST:
                for (int i = 0; i < permissions.length; i++) {
                    if (permissions[i].equals(Manifest.permission.NEARBY_WIFI_DEVICES) &&
                            grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                        // inform user and end activity
                        showingPermissionDialog.setValue(INFORM_FEATURE_DEGRADATION);
                    }
                }
        }
    }

    private boolean isMissingNearbyWifiPermission() {
        return ContextCompat.checkSelfPermission(this,
                Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED;
    }

    private void showNearbyWiFiPermissionRationale() {
        StandardAlertDialog d = new StandardAlertDialog(
                Localization.get("wifi.direct.permission.nearby.wifi.title"),
                Localization.get("wifi.direct.permission.nearby.wifi.message"));
        DialogInterface.OnClickListener listener = (dialog, which) -> {
            showingPermissionDialog.setValue(REQUEST_PERMISSION);
            dialog.dismiss();
        };
        d.setPositiveButton(Localization.get("dialog.ok"), listener);
        showAlertDialog(d);
    }

    private void requestNearbyWifiPermission() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.NEARBY_WIFI_DEVICES},
                NEARBY_WIFI_PERM_REQUEST);
    }

    private void informUserAboutFeatureUnavailability() {
        StandardAlertDialog d = StandardAlertDialog.getBasicAlertDialog(
                Localization.get("wifi.direct.permission.nearby.wifi.title"),
                Localization.get("wifi.direct.permission.nearby.wifi.denial"),
                (dialog, which) -> {
                    showingPermissionDialog.setValue(PermissionAction.NO_ACTION);

                    // terminate activity
                    this.setResult(RESULT_CANCELED);
                    this.finish();
                });
        showAlertDialog(d);
    }
}
