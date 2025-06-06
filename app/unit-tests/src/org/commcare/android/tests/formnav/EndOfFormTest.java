package org.commcare.android.tests.formnav;

import android.content.Intent;
import android.util.Log;
import android.view.View;

import org.commcare.CommCareApplication;
import org.commcare.CommCareTestApplication;
import org.commcare.activities.FormEntryActivity;
import org.commcare.android.database.user.models.FormRecord;
import org.commcare.android.util.ActivityLaunchUtils;
import org.commcare.android.util.TestAppInstaller;
import org.commcare.dalvik.R;
import org.commcare.models.database.SqlStorage;
import org.commcare.utils.RobolectricUtil;
import org.commcare.views.QuestionsView;
import org.commcare.views.widgets.IntegerWidget;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowLooper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;

/**
 * @author Phillip Mates (pmates@dimagi.com).
 */
@Config(application = CommCareTestApplication.class)
@RunWith(AndroidJUnit4.class)
public class EndOfFormTest {

    private static final String TAG = EndOfFormTest.class.getSimpleName();

    @Before
    public void setup() {
        TestAppInstaller.installAppAndLogin(
                "jr://resource/commcare-apps/form_nav_tests/profile.ccpr",
                "test", "123");
        ((CommCareTestApplication)CommCareTestApplication.instance()).initWorkManager();
    }

    /**
     * Test filling out and saving a form ending in a hidden repeat group. This
     * type of form exercises uncommon end-of-form code paths
     */
    @Test
    public void testHiddenRepeatAtEndOfForm() {
        ShadowActivity shadowActivity =
                ActivityLaunchUtils.buildHomeActivityForFormEntryLaunch("m0-f0");

        Intent formEntryIntent = shadowActivity.getNextStartedActivity();

        // make sure the form entry activity should be launched
        String intentActivityName = formEntryIntent.getComponent().getClassName();
        assertTrue(intentActivityName.equals(FormEntryActivity.class.getName()));

        ShadowActivity shadowFormEntryActivity = navigateFormEntry(formEntryIntent);

        // trigger CommCareHomeActivity.onActivityResult for the completion of
        // FormEntryActivity
        shadowActivity.receiveResult(formEntryIntent,
                shadowFormEntryActivity.getResultCode(),
                shadowFormEntryActivity.getResultIntent());
        assertStoredForms();
    }

    private static ShadowActivity navigateFormEntry(Intent formEntryIntent) {
        // launch form entry
        FormEntryActivity formEntryActivity =
                Robolectric.buildActivity(FormEntryActivity.class, formEntryIntent)
                        .create().start().resume().get();
        ShadowLooper.idleMainLooper();
        // enter an answer for the question
        QuestionsView questionsView = formEntryActivity.getODKView();
        IntegerWidget favoriteNumber = (IntegerWidget)questionsView.getWidgets().get(0);
        favoriteNumber.setAnswer("2");
        View finishButton = formEntryActivity.findViewById(R.id.nav_btn_finish);
        finishButton.performClick();
        RobolectricUtil.flushBackgroundThread(formEntryActivity);
        ShadowActivity shadowFormEntryActivity = Shadows.shadowOf(formEntryActivity);
        while (!formEntryActivity.isFinishing()) {
            Log.d(TAG, "Waiting for the form to save and the form entry activity to finish");
        }

        return shadowFormEntryActivity;
    }

    private void assertStoredForms() {
        SqlStorage<FormRecord> formsStorage =
                CommCareApplication.instance().getUserStorage(FormRecord.class);

        int unsentForms = formsStorage.getIDsForValue(FormRecord.META_STATUS,
                FormRecord.STATUS_UNSENT).size();
        int incompleteForms = formsStorage.getIDsForValue(FormRecord.META_STATUS,
                FormRecord.STATUS_INCOMPLETE).size();
        assertEquals("There should be a single form waiting to be sent", 1, unsentForms);
        assertEquals("There shouldn't be any forms saved as incomplete", 0, incompleteForms);
    }
}
