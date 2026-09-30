package com.audacious_software.phone_dashboard;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.DisplayCutoutCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.Collections;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 34}, application = MonitoringHealthLayoutTest.TestApplication.class)
public class MonitoringHealthLayoutTest {
    public static class TestApplication extends AppApplication {
        @Override public void onCreate() { }
    }

    @Test public void statusAndSideNavigationBarsDoNotOverlapContent() {
        try (ActivityController<MonitoringHealthActivity> controller = Robolectric.buildActivity(MonitoringHealthActivity.class).create()) {
            ViewGroup frame = controller.get().findViewById(android.R.id.content);
            LinearLayout root = (LinearLayout) frame.getChildAt(0);
            Toolbar toolbar = (Toolbar) root.getChildAt(0);
            ScrollView scroll = (ScrollView) root.getChildAt(1);
            WindowInsetsCompat insets = new WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(20, 48, 0, 36))
                    .build();
            ViewCompat.dispatchApplyWindowInsets(root, insets);
            ViewCompat.dispatchApplyWindowInsets(root, insets);
            root.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 1000, 1600);

            assertEquals(48, toolbar.getTop());
            assertEquals(20, toolbar.getLeft());
            assertTrue(toolbar.getHeight() > 0);
            assertEquals(toolbar.getBottom(), scroll.getTop());
            assertEquals(1600 - 36, scroll.getBottom());
            assertEquals(48, root.getPaddingTop()); // Repeated inset delivery must not accumulate padding.
        }
    }

    @Test
    @Config(sdk = 34)
    public void actualDisplayCutoutKeepsToolbarAndContentOutsideUnsafeArea() {
        try (ActivityController<MonitoringHealthActivity> controller = Robolectric.buildActivity(MonitoringHealthActivity.class).create()) {
            ViewGroup frame = controller.get().findViewById(android.R.id.content);
            LinearLayout root = (LinearLayout) frame.getChildAt(0);
            WindowInsetsCompat insets = new WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 48, 0, 36))
                    .setDisplayCutout(new DisplayCutoutCompat(new Rect(20, 0, 0, 0),
                            Collections.singletonList(new Rect(0, 60, 20, 100))))
                    .build();
            ViewCompat.dispatchApplyWindowInsets(root, insets);
            root.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 1000, 1600);
            assertEquals(20, root.getPaddingLeft());
            assertEquals(20, root.getChildAt(0).getLeft());
            assertEquals(20, root.getChildAt(1).getLeft());
            assertEquals(root.getChildAt(0).getBottom(), root.getChildAt(1).getTop());
        }
    }
}
