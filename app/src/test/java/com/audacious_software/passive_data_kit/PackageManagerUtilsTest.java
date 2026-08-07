package com.audacious_software.passive_data_kit;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = android.app.Application.class)
public class PackageManagerUtilsTest {
    private static final String PACKAGE_NAME = "com.example.app";

    private PackageManager mPackageManager;

    @Before
    public void setUp() {
        this.mPackageManager = mock(PackageManager.class);
    }

    @Test
    public void returnsNullWithoutLookupForMissingPackageNames() {
        assertNull(PackageManagerUtils.safeGetLaunchIntentForPackage(this.mPackageManager, null));
        assertNull(PackageManagerUtils.safeGetLaunchIntentForPackage(this.mPackageManager, ""));
        assertNull(PackageManagerUtils.safeGetLaunchIntentForPackage(this.mPackageManager, "   "));

        verifyNoInteractions(this.mPackageManager);
    }

    @Test
    public void returnsNullWhenPackageManagerIsMissing() {
        assertNull(PackageManagerUtils.safeGetLaunchIntentForPackage(null, PACKAGE_NAME));
    }

    @Test
    public void usesValidInfoActivityWithoutQueryingLauncherActivities() {
        this.stubCategory(Intent.CATEGORY_INFO, Collections.singletonList(this.resolveInfo(PACKAGE_NAME, "com.example.app.InfoActivity")));

        Intent actual = PackageManagerUtils.safeGetLaunchIntentForPackage(this.mPackageManager, PACKAGE_NAME);

        this.assertLaunchIntent(actual, Intent.CATEGORY_INFO, PACKAGE_NAME, "com.example.app.InfoActivity");
        verify(this.mPackageManager, never()).queryIntentActivities(
                argThat(intent -> intent != null && intent.hasCategory(Intent.CATEGORY_LAUNCHER)),
                eq(0));
    }

    @Test
    public void fallsBackToValidLauncherActivity() {
        this.stubCategory(Intent.CATEGORY_INFO, Collections.emptyList());
        this.stubCategory(Intent.CATEGORY_LAUNCHER, Collections.singletonList(this.resolveInfo(PACKAGE_NAME, "com.example.app.MainActivity")));

        Intent actual = PackageManagerUtils.safeGetLaunchIntentForPackage(this.mPackageManager, PACKAGE_NAME);

        this.assertLaunchIntent(actual, Intent.CATEGORY_LAUNCHER, PACKAGE_NAME, "com.example.app.MainActivity");
    }

    @Test
    public void skipsMalformedAndroid11ResultAndUsesLaterValidResult() {
        ResolveInfo malformed = this.resolveInfo(PACKAGE_NAME, null);
        ResolveInfo valid = this.resolveInfo(PACKAGE_NAME, "com.example.app.MainActivity");
        this.stubCategory(Intent.CATEGORY_INFO, Arrays.asList(malformed, valid));

        Intent actual = PackageManagerUtils.safeGetLaunchIntentForPackage(this.mPackageManager, PACKAGE_NAME);

        this.assertLaunchIntent(actual, Intent.CATEGORY_INFO, PACKAGE_NAME, "com.example.app.MainActivity");
    }

    @Test
    public void fallsBackWhenAllInfoResultsAreMalformed() {
        this.stubCategory(Intent.CATEGORY_INFO, Arrays.asList(
                null,
                new ResolveInfo(),
                this.resolveInfo(null, "com.example.app.InfoActivity"),
                this.resolveInfo(PACKAGE_NAME, null),
                this.resolveInfo(PACKAGE_NAME, "")));
        this.stubCategory(Intent.CATEGORY_LAUNCHER, Collections.singletonList(
                this.resolveInfo(PACKAGE_NAME, "com.example.app.MainActivity")));

        Intent actual = PackageManagerUtils.safeGetLaunchIntentForPackage(this.mPackageManager, PACKAGE_NAME);

        this.assertLaunchIntent(actual, Intent.CATEGORY_LAUNCHER, PACKAGE_NAME, "com.example.app.MainActivity");
    }

    @Test
    public void returnsNullWhenNoValidActivityExists() {
        this.stubCategory(Intent.CATEGORY_INFO, null);
        this.stubCategory(Intent.CATEGORY_LAUNCHER, Arrays.asList(
                null,
                new ResolveInfo(),
                this.resolveInfo(PACKAGE_NAME, null)));

        assertNull(PackageManagerUtils.safeGetLaunchIntentForPackage(this.mPackageManager, PACKAGE_NAME));
    }

    @Test(expected = SecurityException.class)
    public void doesNotHidePackageVisibilityFailures() {
        when(this.mPackageManager.queryIntentActivities(
                argThat(intent -> intent != null && intent.hasCategory(Intent.CATEGORY_INFO)),
                eq(0)))
                .thenThrow(new SecurityException("package visibility denied"));

        PackageManagerUtils.safeGetLaunchIntentForPackage(this.mPackageManager, PACKAGE_NAME);
    }

    @Test(expected = IllegalStateException.class)
    public void doesNotHidePackageServiceFailures() {
        when(this.mPackageManager.queryIntentActivities(
                argThat(intent -> intent != null && intent.hasCategory(Intent.CATEGORY_INFO)),
                eq(0)))
                .thenThrow(new IllegalStateException("package service unavailable"));

        PackageManagerUtils.safeGetLaunchIntentForPackage(this.mPackageManager, PACKAGE_NAME);
    }

    private void stubCategory(String category, List<ResolveInfo> results) {
        when(this.mPackageManager.queryIntentActivities(
                argThat(intent -> intent != null
                        && PACKAGE_NAME.equals(intent.getPackage())
                        && intent.hasCategory(category)),
                eq(0)))
                .thenReturn(results);
    }

    private ResolveInfo resolveInfo(String packageName, String className) {
        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.activityInfo = new ActivityInfo();
        resolveInfo.activityInfo.packageName = packageName;
        resolveInfo.activityInfo.name = className;
        return resolveInfo;
    }

    private void assertLaunchIntent(Intent intent, String category, String packageName, String className) {
        assertNotNull(intent);
        assertEquals(Intent.ACTION_MAIN, intent.getAction());
        assertTrue(intent.hasCategory(category));
        assertEquals(packageName, intent.getPackage());
        assertNotNull(intent.getComponent());
        assertEquals(packageName, intent.getComponent().getPackageName());
        assertEquals(className, intent.getComponent().getClassName());
        assertTrue((intent.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
    }
}
